/* CatalystRadar dashboard — vanilla JavaScript, served with the application. */
(function () {
  'use strict';

  var API_BASE = window.location.origin;
  var ADMIN_KEY_NAME = 'catalyst-admin-key';
  var API_KEY_NAME = 'catalyst-api-key';
  var credentialRevision = 0;
  var operations;
  var currentView = 'overview';
  var discoveryOffset = 0;
  var discoveryTotal = 0;
  var discoveryLimit = 20;
  var appliedDiscoveryFilters;
  var discoveryRequest = 0;
  var discoveryLoading = false;
  var companyRequest = 0;
  var historyRequest = 0;
  var currentCompany = '';
  var companyEvents = [];
  var companyNextCursor = null;
  var companyEventsLoading = false;
  var eventRequest = 0;
  var eventLoaded = false;
  var eventLoading = false;
  var eventItems = [];
  var eventNextCursor = null;
  var appliedEventFilters;

  function $(id) { return document.getElementById(id); }

  function esc(value) {
    return String(value === null || value === undefined ? '' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  function fmtTime(iso) {
    if (!iso) return '–';
    var date = new Date(iso);
    return isNaN(date) ? String(iso) : date.toLocaleString('en-US', { timeZone: 'UTC' });
  }

  function fmtInt(value) {
    return (value === null || value === undefined) ? '–' : Number(value).toLocaleString('en-US');
  }

  function fmtCost(value) {
    if (value === null || value === undefined) return '–';
    return '$' + Number(value).toFixed(6);
  }

  function showError(message) {
    var banner = $('banner');
    banner.textContent = message;
    banner.classList.remove('hidden');
  }

  function clearError() {
    $('banner').classList.add('hidden');
  }

  async function api(path, options) {
    if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//') || path.indexOf('\\') >= 0) {
      throw new TypeError('API paths must be local absolute paths');
    }
    var url = new URL(path, API_BASE);
    if (url.origin !== API_BASE || url.hash) throw new TypeError('API paths must stay on this origin');
    options = options || {};
    var allowed = ['method', 'body', 'signal'];
    Object.keys(options).forEach(function (key) {
      if (!allowed.includes(key)) throw new TypeError('Unsupported API request option: ' + key);
    });
    var method = String(options.method || 'GET').toUpperCase();
    if (!['GET', 'POST'].includes(method)) throw new TypeError('Unsupported API method');
    if (options.body !== undefined && method !== 'POST') throw new TypeError('Only POST requests may have a body');
    var headers = { Accept: 'application/json' };
    var adminKey = window.sessionStorage.getItem(ADMIN_KEY_NAME);
    var apiKey = window.sessionStorage.getItem(API_KEY_NAME);
    if (url.pathname.startsWith('/internal/') && adminKey) headers['X-Admin-Key'] = adminKey;
    if (url.pathname.startsWith('/v1/') && apiKey) headers.Authorization = 'Bearer ' + apiKey;
    var fetchOptions = { method: method, headers: headers };
    if (options.signal) fetchOptions.signal = options.signal;
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
      fetchOptions.body = JSON.stringify(options.body);
    }
    var response = await fetch(url.href, fetchOptions);
    var body = await response.json().catch(function () { return {}; });
    if (!response.ok) {
      var error = new Error(body.detail || body.title || ('HTTP ' + response.status));
      error.status = body.status || response.status;
      error.code = body.code;
      error.requestId = body.requestId;
      throw error;
    }
    return body;
  }

  function statusPill(status) {
    var cls = status === 'SUCCESS' ? 'ok' : (status === 'PARTIAL' ? 'warn' : (status === 'FAILED' ? 'bad' : 'info'));
    return '<span class="pill ' + cls + '">' + esc(status) + '</span>';
  }

  function readDiscoveryFilters() {
    var query = new URLSearchParams();
    Array.from($('discoveryStates').selectedOptions || []).forEach(function (option) {
      query.append('state', option.value);
    });
    if ($('minScore').value !== '') query.set('minScore', $('minScore').value);
    if ($('minVelocity7d').value !== '') query.set('minVelocity7d', $('minVelocity7d').value);
    if ($('sector').value.trim()) query.set('sector', $('sector').value.trim());
    query.set('sort', $('discoverySort').value || 'SCORE');
    query.set('limit', String(Math.min(100, Math.max(1, Number($('pageSize').value) || 20))));
    return query;
  }

  function fmtScore(value) {
    return value === null || value === undefined || !Number.isFinite(Number(value)) ? '–' : Number(value).toFixed(1);
  }

  function fmtFactor(value) {
    return value === null || value === undefined || !Number.isFinite(Number(value)) ? '–' : String(value);
  }

  function timeLabel(value) {
    return '<time datetime="' + esc(value || '') + '">' + esc(fmtTime(value)) + '</time>';
  }

  function sourceLabel(source) {
    if (!source) return '<span class="muted">Source unavailable</span><span class="source-date">Source publication date: ' + timeLabel(null) + '</span>';
    var label = esc(source.title || 'Untitled source') + ' · ' + esc(source.provider || 'Unknown provider');
    var safeUrl = '';
    try {
      var parsed = new URL(source.canonicalUrl);
      if (parsed.protocol === 'http:' || parsed.protocol === 'https:') safeUrl = parsed.href;
    } catch (_) { /* Missing or invalid source URL stays plain text. */ }
    return (safeUrl ? '<a href="' + esc(safeUrl) + '" target="_blank" rel="noopener noreferrer">' + label + '</a>' : label) +
      '<span class="source-date">Source publication date: ' + timeLabel(source.publishedAt) + '</span>';
  }

  function evidenceList(evidence) {
    if (!Array.isArray(evidence) || !evidence.length) return '<p class="muted">No evidence quote available.</p>';
    return '<ul class="evidence-list">' + evidence.map(function (item) {
      return '<li><blockquote>' + esc(item.quoteOrFact) + '</blockquote></li>';
    }).join('') + '</ul>';
  }

  function companyPath(ticker, suffix, query) {
    return '/v1/companies/' + encodeURIComponent(ticker) + suffix + (query ? '?' + query.toString() : '');
  }

  function companyPanelError(id, message) {
    $(id).textContent = 'Unable to load ' + message + '.';
  }

  function renderCompanyOverview(company) {
    $('companyTitle').textContent = company.name + ' (' + company.ticker + ')';
    $('companyOverview').innerHTML = '<p class="muted">' + [company.exchange, company.sector, company.industry, company.country].filter(Boolean).map(esc).join(' · ') +
      ' · ' + (company.active ? 'Active' : 'Inactive') + '</p>';
  }

  function factorDetails(driver) {
    if (!driver.factors) return '';
    var fields = ['sign', 'baseWeight', 'confidence', 'materialityFactor', 'surpriseFactor', 'sourceQualityFactor', 'directnessFactor', 'timeDecayFactor', 'value'];
    return '<dl class="fact-grid">' + fields.map(function (key) {
      return '<div><dt>' + esc(key) + '</dt><dd>' + esc(fmtFactor(driver.factors[key])) + '</dd></div>';
    }).join('') + '</dl>';
  }

  function renderCatalyst(data) {
    var band = data.stateBand || {};
    $('companyScore').innerHTML = '<div class="score-header"><strong class="score-number">' + esc(fmtScore(data.score)) + '</strong>' + statusPill(data.state) +
      '<span>State range: ' + esc(fmtScore(band.minScore)) + '–' + esc(fmtScore(band.maxScore)) + (band.maxInclusive ? ' inclusive' : ' exclusive upper bound') + '</span></div>' +
      '<dl class="fact-grid"><div><dt>1-day velocity</dt><dd>' + esc(fmtScore(data.velocity1d)) + '</dd></div>' +
      '<div><dt>3-day velocity</dt><dd>' + esc(fmtScore(data.velocity3d)) + '</dd></div>' +
      '<div><dt>7-day velocity</dt><dd>' + esc(fmtScore(data.velocity7d)) + '</dd></div>' +
      '<div><dt>Total events</dt><dd>' + esc(fmtInt(data.totalEvents)) + '</dd></div>' +
      '<div><dt>Events 7d</dt><dd>' + esc(fmtInt(data.events7d)) + '</dd></div>' +
      '<div><dt>As of</dt><dd>' + timeLabel(data.asOf) + '</dd></div>' +
      '<div><dt>Score version</dt><dd>' + esc(data.scoreVersion) + '</dd></div>' +
      '<div><dt>Taxonomy version</dt><dd>' + esc(data.taxonomyVersion) + '</dd></div></dl>';
    if (data.explanationStatus !== 'RECONSTRUCTED_SCORE_MATCH') {
      $('companyExplanation').innerHTML = '<p class="panel-error">Explanation unavailable: ' +
        (data.explanationStatus === 'VERSION_MISMATCH' ? 'score or taxonomy versions differ from the saved snapshot.' :
          'the reconstructed score does not match the saved snapshot.') + '</p>';
      return;
    }
    var calculation = data.scoreCalculation || {};
    var summary = '<dl class="fact-grid"><div><dt>Positive summary</dt><dd>' + esc(fmtScore(data.positiveScore)) + '</dd></div>' +
      '<div><dt>Negative summary</dt><dd>' + esc(fmtScore(data.negativeScore)) + '</dd></div>' +
      '<div><dt>Direct summary</dt><dd>' + esc(fmtScore(data.directScore)) + '</dd></div>' +
      '<div><dt>Inferred summary</dt><dd>' + esc(fmtScore(data.inferredScore)) + '</dd></div></dl>';
    var drivers = (data.topDrivers || []).slice(0, 3).map(function (driver) {
      return '<article class="driver"><h4>' + esc(driver.direction) + ' · ' + esc(driver.family || 'Unclassified') + ' / ' + esc(driver.type) + '</h4>' +
        '<p>Raw event contribution: ' + esc(fmtScore(driver.contribution)) + '</p>' +
        '<p>Event date: ' + timeLabel(driver.eventTimestamp) + ' · First captured: ' + timeLabel(driver.discoveredAt) + '</p>' +
        evidenceList(driver.evidence) + '<p>' + sourceLabel(driver.source) + '</p>' +
        '<details><summary>Scoring details</summary><p>Score version: ' + esc(data.scoreVersion) + '</p>' + factorDetails(driver) + '</details></article>';
    }).join('');
    var calcFields = ['contributionSum', 'familyCount', 'convergenceMultiplier', 'rawScore', 'normalizationScale', 'contributionCutoff'];
    $('companyExplanation').innerHTML = '<p class="muted">Reconstructed current explanation: the saved snapshot does not retain its original driver list. Matching score and versions do not prove original driver membership.</p>' +
      summary + (drivers || '<p>No reconstructed drivers available.</p>') +
      '<details><summary>Scoring details</summary><p>Score version: ' + esc(data.scoreVersion) + '</p><dl class="fact-grid">' +
      calcFields.map(function (key) { return '<div><dt>' + esc(key) + '</dt><dd>' + esc(fmtFactor(calculation[key])) + '</dd></div>'; }).join('') + '</dl></details>';
  }

  function historyQuery() {
    var query = new URLSearchParams();
    var range = $('historyRange').value || '30';
    if (range !== 'max') {
      var to = new Date();
      var from = new Date(to.getTime() - Number(range) * 86400000);
      query.set('from', from.toISOString());
      query.set('to', to.toISOString());
    }
    query.set('limit', '200');
    $('historyRangeNote').textContent = range === 'max'
      ? 'Maximum returned view is capped at 200 stored snapshots and 200 transitions.'
      : 'Showing up to 200 stored snapshots and transitions from the last ' + range + ' days.';
    return query;
  }

  function renderHistory(data) {
    var snapshots = (data.snapshots || []).slice().sort(function (a, b) { return new Date(a.asOf) - new Date(b.asOf); });
    var transitions = (data.transitions || []).slice().sort(function (a, b) { return new Date(a.at) - new Date(b.at); });
    var transitionList = transitions.length ? '<p>State transitions at persisted times:</p><ul>' + transitions.map(function (t) {
      return '<li>' + timeLabel(t.at) + ' · ' + esc(t.from) + ' → ' + esc(t.to) + ' · score ' + esc(fmtScore(t.score)) + '</li>';
    }).join('') + '</ul>' : '';
    if (!snapshots.length) {
      $('companyHistory').innerHTML = '<p>No score history in this range.</p>' + transitionList;
      return;
    }
    var times = snapshots.map(function (s) { return new Date(s.asOf).getTime(); })
      .concat(transitions.map(function (t) { return new Date(t.at).getTime(); }));
    var minTime = Math.min.apply(null, times);
    var maxTime = Math.max.apply(null, times);
    var x = function (at) { return maxTime === minTime ? 300 : 30 + 540 * (new Date(at).getTime() - minTime) / (maxTime - minTime); };
    var y = function (score) { return 130 - Math.max(0, Math.min(100, Number(score))) * 1.1; };
    var chart = (snapshots.length === 1 ? '<p>Insufficient history for a trend chart. One stored snapshot is available.</p>' : '') +
      '<p class="muted small">Only stored snapshot points are shown; gaps have no implied values.</p><svg viewBox="0 0 600 150" role="img" aria-label="Stored catalyst score snapshot points and state transitions; data table follows">' +
      '<line x1="30" y1="130" x2="570" y2="130" class="axis"/><line x1="30" y1="20" x2="30" y2="130" class="axis"/>' +
      snapshots.map(function (s) { return '<circle class="score-point" cx="' + x(s.asOf) + '" cy="' + y(s.score) + '" r="4"><title>' + esc(s.asOf) + ' · ' + esc(fmtScore(s.score)) + ' · ' + esc(s.state) + '</title></circle>'; }).join('') +
      transitions.map(function (t) { return '<path class="transition-mark" d="M' + x(t.at) + ' 20V130"><title>' + esc(t.at) + ' · ' + esc(t.from) + ' → ' + esc(t.to) + '</title></path>'; }).join('') + '</svg>';
    $('companyHistory').innerHTML = chart + '<div class="history-table"><table><caption>Stored score snapshots</caption><thead><tr><th>Date</th><th>Score</th><th>State</th><th>As of</th></tr></thead><tbody>' +
      snapshots.map(function (s) { return '<tr><td>' + esc((s.asOf || '').slice(0, 10)) + '</td><td>' + esc(fmtScore(s.score)) + '</td><td>' + esc(s.state) + '</td><td>' + timeLabel(s.asOf) + '</td></tr>'; }).join('') +
      '</tbody></table></div>' + transitionList;
  }

  function renderCompanyEvents() {
    var groups = new Map();
    companyEvents.forEach(function (event) {
      var id = event.clusterId || event.id;
      if (!groups.has(id)) groups.set(id, []);
      groups.get(id).push(event);
    });
    $('companyEvents').innerHTML = companyEvents.length ? Array.from(groups.values()).map(function (group) {
      return '<article class="event-cluster"><h4>' + (group.length > 1 ? 'Reports about one catalyst' : 'Event report') + '</h4>' + group.map(function (event) {
        return '<div class="event-report"><strong>' + esc(event.direction) + ' · ' + esc(event.family) + ' / ' + esc(event.type) + '</strong>' +
          '<p>Event date: ' + timeLabel(event.eventTimestamp) + ' · First captured: ' + timeLabel(event.discoveredAt) + '</p>' +
          evidenceList(event.evidence) + '<p>' + sourceLabel(event.source) + '</p></div>';
      }).join('') + '</article>';
    }).join('') : '<p>No company events found.</p>';
    $('loadCompanyEvents').classList[companyNextCursor ? 'remove' : 'add']('hidden');
    $('loadCompanyEvents').disabled = !companyNextCursor || companyEventsLoading;
  }

  function readEventFilters() {
    var query = new URLSearchParams();
    ['Ticker', 'Family', 'Type', 'Direction'].forEach(function (name) {
      var value = $('event' + name).value.trim();
      if (value) query.set(name.toLowerCase(), value.toUpperCase());
    });
    if ($('eventFrom').value) query.set('from', $('eventFrom').value + 'T00:00:00Z');
    if ($('eventTo').value) query.set('to', $('eventTo').value + 'T23:59:59.999Z');
    query.set('limit', String(Math.min(100, Math.max(1, Number($('eventPageSize').value) || 20))));
    return query;
  }

  function renderEvents() {
    $('eventResults').innerHTML = eventItems.length ? eventItems.map(function (event) {
      var company = event.ticker
        ? '<a href="?view=company&ticker=' + encodeURIComponent(event.ticker) + '" data-ticker="' + esc(event.ticker) + '">' + esc(event.ticker) + '</a>'
        : '<span class="muted">Ticker unavailable</span>';
      return '<article class="event-cluster"><h3>' + company + ' · ' + esc(event.companyName || 'Company name unavailable') + '</h3>' +
        '<strong>' + esc(event.direction) + ' · ' + esc(event.family) + ' / ' + esc(event.type) + '</strong>' +
        '<p>Event date: ' + timeLabel(event.eventTimestamp) + ' · First captured: ' + timeLabel(event.discoveredAt) + '</p>' +
        '<p class="muted small">Cluster: ' + esc(event.clusterId || 'unclustered') + '</p>' +
        evidenceList(event.evidence) + '<p>' + sourceLabel(event.source) + '</p></article>';
    }).join('') : '<p>No events match these filters.</p>';
    $('loadEvents').classList[eventNextCursor ? 'remove' : 'add']('hidden');
    $('loadEvents').disabled = !eventNextCursor || eventLoading;
  }

  async function loadEvents(reset) {
    if (eventLoading && !reset) return;
    if (!reset && !eventNextCursor) return;
    if (reset) {
      appliedEventFilters = readEventFilters();
      eventItems = [];
      eventNextCursor = null;
      $('eventResults').textContent = '';
    }
    var query = new URLSearchParams(appliedEventFilters);
    if (!reset) query.set('cursor', eventNextCursor);
    var request = ++eventRequest;
    eventLoading = true;
    eventLoaded = true;
    $('eventStatus').textContent = 'Loading events…';
    $('loadEvents').disabled = true;
    try {
      var page = await api('/v1/events?' + query.toString());
      if (request !== eventRequest) return;
      eventItems = eventItems.concat(page.events || []);
      eventNextCursor = page.nextCursor || null;
      $('eventStatus').textContent = eventItems.length + ' loaded reports' + (eventNextCursor ? '; more available.' : '.');
      renderEvents();
    } catch (err) {
      if (request !== eventRequest) return;
      $('eventStatus').textContent = reset ? 'Unable to load events. Try searching again.' : 'Unable to load more events. Try again.';
      if (reset) $('eventResults').textContent = '';
      else renderEvents();
      showError('Events: ' + err.message);
    } finally {
      if (request === eventRequest) {
        eventLoading = false;
        $('loadEvents').disabled = !eventNextCursor;
      }
    }
  }

  async function loadCompanyEvents(ticker, request, reset) {
    if (companyEventsLoading || (!reset && !companyNextCursor)) return;
    companyEventsLoading = true;
    $('companyEventsStatus').textContent = '';
    if (reset) { companyEvents = []; companyNextCursor = null; $('companyEvents').textContent = 'Loading events…'; }
    $('loadCompanyEvents').disabled = true;
    var query = new URLSearchParams();
    query.set('limit', '20');
    if (!reset) query.set('cursor', companyNextCursor);
    try {
      var page = await api(companyPath(ticker, '/events', query));
      if (request !== companyRequest) return;
      companyEvents = companyEvents.concat(page.events || []);
      companyNextCursor = page.nextCursor;
      companyEventsLoading = false;
      renderCompanyEvents();
    } catch (_) {
      if (request === companyRequest) {
        companyEventsLoading = false;
        if (reset) companyPanelError('companyEvents', 'company events');
        else {
          $('companyEventsStatus').textContent = 'Unable to load more events. Try again.';
          renderCompanyEvents();
        }
      }
    } finally { if (request === companyRequest) companyEventsLoading = false; }
  }

  async function loadCompanyHistory(ticker, request) {
    var sequence = ++historyRequest;
    $('companyHistory').textContent = 'Loading score history…';
    try {
      var data = await api(companyPath(ticker, '/timeline', historyQuery()));
      if (request === companyRequest && sequence === historyRequest) renderHistory(data);
    } catch (_) { if (request === companyRequest && sequence === historyRequest) companyPanelError('companyHistory', 'score history'); }
  }

  function refreshCompany(ticker) {
    var request = ++companyRequest;
    historyRequest++;
    currentCompany = ticker;
    companyEvents = [];
    companyNextCursor = null;
    companyEventsLoading = false;
    $('companyTitle').textContent = ticker + ' analysis';
    $('companyOverview').textContent = 'Loading company…';
    $('companyScore').textContent = 'Loading current score…';
    $('companyExplanation').textContent = 'Loading explanation…';
    $('companyEvents').textContent = 'Loading events…';
    $('companyEventsStatus').textContent = '';
    $('loadCompanyEvents').classList.add('hidden');
    api(companyPath(ticker, '')).then(function (company) {
      if (request === companyRequest) renderCompanyOverview(company);
    }).catch(function () { if (request === companyRequest) companyPanelError('companyOverview', 'company'); });
    api(companyPath(ticker, '/catalyst')).then(function (data) {
      if (request === companyRequest) renderCatalyst(data);
    }).catch(function () {
      if (request === companyRequest) { companyPanelError('companyScore', 'current score'); companyPanelError('companyExplanation', 'explanation'); }
    });
    loadCompanyHistory(ticker, request);
    loadCompanyEvents(ticker, request, true);
  }

  function discoveryPath() {
    var query = new URLSearchParams(appliedDiscoveryFilters);
    query.set('offset', String(discoveryOffset));
    return '/v1/discovery/catalyzed?' + query.toString();
  }

  async function refreshDiscovery() {
    var page;
    var request = ++discoveryRequest;
    var path = discoveryPath();
    var requestOffset = discoveryOffset;
    var requestLimit = discoveryLimit;
    discoveryLoading = true;
    $('discoveryStatus').textContent = 'Loading results…';
    $('previousPage').disabled = true;
    $('nextPage').disabled = true;
    try {
      page = await api(path);
    } catch (err) {
      if (request !== discoveryRequest) return;
      discoveryLoading = false;
      $('discoveryResults').innerHTML = '';
      $('discoveryStatus').textContent = 'Unable to load discovery results.';
      $('discoveryAsOf').textContent = '';
      $('pageSummary').textContent = '';
      $('previousPage').disabled = true;
      $('nextPage').disabled = true;
      showError('Discovery: ' + err.message);
      return;
    }
    if (request !== discoveryRequest) return;
    discoveryLoading = false;
    discoveryTotal = page.total;
    $('discoveryAsOf').textContent = 'Discovery query as of ' + fmtTime(page.asOf);
    $('pageSummary').textContent = page.total ?
      'Results ' + (requestOffset + 1) + '–' + (requestOffset + page.results.length) + ' of ' + page.total : '0 results';
    $('previousPage').disabled = requestOffset === 0;
    $('nextPage').disabled = requestOffset + requestLimit >= discoveryTotal;
    if (!page.results.length) {
      $('discoveryStatus').textContent = 'No companies match these filters.';
      $('discoveryResults').innerHTML = '';
      return;
    }
    $('discoveryStatus').textContent = page.total + ' matching companies';
    $('discoveryResults').innerHTML = page.results.map(function (entry) {
      return '<article class="discovery-card"><h3><a href="?view=company&ticker=' + encodeURIComponent(entry.ticker) + '" data-ticker="' + esc(entry.ticker) + '">' + esc(entry.ticker) + '</a> <span class="muted">' + esc(entry.name) + '</span></h3>' +
        '<dl><div><dt>Sector</dt><dd>' + esc(entry.sector || '–') + '</dd></div>' +
        '<div><dt>Score</dt><dd>' + esc(Number(entry.score).toFixed(1)) + '</dd></div>' +
        '<div><dt>State</dt><dd>' + statusPill(entry.state) + '</dd></div>' +
        '<div><dt>7-day velocity</dt><dd>' + esc(Number(entry.velocity7d).toFixed(1)) + '</dd></div>' +
        '<div><dt>Events 7d</dt><dd>' + fmtInt(entry.events7d) + '</dd></div>' +
        '<div><dt>Score version</dt><dd>' + esc(entry.scoreVersion) + '</dd></div>' +
        '<div><dt>Company as of</dt><dd>' + esc(fmtTime(entry.asOf)) + '</dd></div></dl></article>';
    }).join('');
  }

  function routeView(route) {
    var requested = route.get('view') || 'overview';
    if (requested === 'operations') return 'pipeline';
    return requested;
  }

  function validTicker(value) { return /^[A-Za-z0-9][A-Za-z0-9.-]{0,14}$/.test(value || ''); }

  function currentCredentials() {
    return { hasAdmin: !!window.sessionStorage.getItem(ADMIN_KEY_NAME), revision: credentialRevision };
  }

  function safeReturnSearch(value) {
    if (typeof value !== 'string' || !value.startsWith('?') || value.startsWith('??') || value.indexOf('#') >= 0) return null;
    var view = new URLSearchParams(value.slice(1)).get('view') || 'overview';
    if (view === 'operations') view = 'pipeline';
    return ['overview', 'pipeline', 'documents', 'models', 'discover', 'company', 'events'].includes(view) ? value : null;
  }

  function localSearch(value) {
    if (typeof value !== 'string' || !value.startsWith('?') || value.startsWith('??') || value.indexOf('#') >= 0 || value.indexOf('\\') >= 0) {
      throw new TypeError('Navigation accepts only a local query string');
    }
    return value;
  }

  function navigate(localSearchValue, state) {
    var search = localSearch(localSearchValue);
    var historyState = {};
    if (state && safeReturnSearch(state.returnSearch)) historyState.returnSearch = state.returnSearch;
    window.history.pushState(historyState, '', search);
    showView(true);
  }

  function refreshPublicView() {
    if (currentView === 'discover') return refreshDiscovery();
    if (currentView === 'events') return loadEvents(true);
    if (currentView === 'company') return refreshCompany(new URLSearchParams(window.location.search).get('ticker'));
  }

  function showView(moveFocus) {
    var route = new URLSearchParams(window.location.search);
    var view = routeView(route);
    var invalidTicker = false;
    if (view === 'company' && !validTicker(route.get('ticker'))) {
      view = 'discover';
      invalidTicker = true;
    }
    if (!['overview', 'pipeline', 'documents', 'models', 'settings', 'discover', 'company', 'events'].includes(view)) view = 'overview';
    currentView = view;

    ['overview', 'pipeline', 'documents', 'models', 'settings', 'discover', 'company', 'events'].forEach(function (name) {
      $(name + 'View').classList.toggle('hidden', name !== view);
    });

    var titles = { overview: 'Overview', pipeline: 'Pipeline', documents: 'Documents', models: 'Models & costs',
      settings: 'Settings', discover: 'Discover', company: 'Company', events: 'Events' };
    $('pageTitle').textContent = titles[view];
    $('activityRangeLabel').classList.toggle('hidden', !['overview', 'pipeline', 'models'].includes(view));
    $('activityRange').value = route.get('range') === '7d' ? '7d' : '24h';

    var intelligence = ['discover', 'company', 'events'].includes(view);
    var topNavigation = { overview: 'navOverview', pipeline: 'navPipeline', documents: 'navDocuments',
      models: 'navModels', settings: 'navSettings', discover: 'navIntelligence', company: 'navIntelligence', events: 'navIntelligence' };
    ['navOverview', 'navPipeline', 'navDocuments', 'navModels', 'navIntelligence', 'navSettings', 'navDiscover', 'navEvents'].forEach(function (id) {
      $(id).removeAttribute('aria-current');
    });
    $(topNavigation[view]).setAttribute('aria-current', 'page');
    if (view === 'discover' || view === 'company') $('navDiscover').setAttribute('aria-current', 'page');
    if (view === 'events') $('navEvents').setAttribute('aria-current', 'page');
    document.querySelectorAll('.intelligence-tabs').forEach(function (tabs) { tabs.classList.toggle('hidden', !intelligence); });

    if (view === 'company') {
      if (route.get('ticker') !== currentCompany) refreshCompany(route.get('ticker').toUpperCase());
    } else {
      companyRequest++;
      historyRequest++;
      currentCompany = '';
    }
    if (view === 'discover') {
      var discoverRead = refreshDiscovery();
      if (invalidTicker) Promise.resolve(discoverRead).then(function () {
        $('discoveryStatus').textContent = 'Enter a valid ticker to open a company.';
      });
    }
    if (view === 'events' && !eventLoaded) loadEvents(true);
    if (operations) operations.show({ view: view, params: route });

    if (moveFocus) {
      if (view === 'company') $('companyTitle').focus();
      else if (view === 'discover') $('discoverTitle').focus();
      else $('pageTitle').focus();
    }
  }

  document.addEventListener('DOMContentLoaded', function () {
    appliedDiscoveryFilters = readDiscoveryFilters();
    discoveryLimit = Number(appliedDiscoveryFilters.get('limit'));
    var saved = window.sessionStorage.getItem(ADMIN_KEY_NAME);
    if (saved) $('adminKey').value = saved;
    var savedApiKey = window.sessionStorage.getItem(API_KEY_NAME);
    if (savedApiKey) $('apiKey').value = savedApiKey;
    $('saveKey').addEventListener('click', function () {
      credentialRevision++;
      var adminKey = $('adminKey').value.trim();
      var apiKey = $('apiKey').value.trim();
      if (adminKey) window.sessionStorage.setItem(ADMIN_KEY_NAME, adminKey);
      else window.sessionStorage.removeItem(ADMIN_KEY_NAME);
      if (apiKey) window.sessionStorage.setItem(API_KEY_NAME, apiKey);
      else window.sessionStorage.removeItem(API_KEY_NAME);
      if (operations) operations.credentialsChanged();
      if (['discover', 'company', 'events'].includes(currentView)) refreshPublicView();
      var returnSearch = safeReturnSearch(window.history.state && window.history.state.returnSearch);
      if (adminKey && returnSearch && currentView === 'settings') navigate(returnSearch);
    });
    $('clearKey').addEventListener('click', function () {
      credentialRevision++;
      window.sessionStorage.removeItem(ADMIN_KEY_NAME);
      window.sessionStorage.removeItem(API_KEY_NAME);
      $('adminKey').value = '';
      $('apiKey').value = '';
      if (operations) operations.credentialsChanged();
      if (['discover', 'company', 'events'].includes(currentView)) refreshPublicView();
    });
    $('refresh').addEventListener('click', function () {
      if (operations) operations.refresh();
      if (['discover', 'company', 'events'].includes(currentView)) refreshPublicView();
    });
    $('activityRange').addEventListener('change', function (event) {
      var route = new URLSearchParams(window.location.search);
      route.set('view', currentView);
      route.set('range', event.target.value === '7d' ? '7d' : '24h');
      navigate('?' + route.toString());
    });
    $('discoveryFilters').addEventListener('submit', function (event) {
      event.preventDefault();
      appliedDiscoveryFilters = readDiscoveryFilters();
      discoveryLimit = Number(appliedDiscoveryFilters.get('limit'));
      discoveryOffset = 0;
      clearError();
      return refreshDiscovery();
    });
    $('previousPage').addEventListener('click', function () {
      if (discoveryLoading || discoveryOffset === 0) return;
      discoveryOffset = Math.max(0, discoveryOffset - discoveryLimit);
      return refreshDiscovery();
    });
    $('nextPage').addEventListener('click', function () {
      if (discoveryLoading || discoveryOffset + discoveryLimit >= discoveryTotal) return;
      discoveryOffset += discoveryLimit;
      return refreshDiscovery();
    });
    $('discoveryResults').addEventListener('click', function (event) {
      var link = event.target.closest('[data-ticker]');
      if (!link) return;
      if (event.preventDefault) event.preventDefault();
      navigate('?view=company&ticker=' + encodeURIComponent(link.dataset.ticker), { returnSearch: window.location.search || '?view=discover' });
    });
    $('backToResults').addEventListener('click', function () {
      navigate(safeReturnSearch(window.history.state && window.history.state.returnSearch) || '?view=discover');
    });
    $('historyRange').addEventListener('change', function () {
      if (currentCompany) return loadCompanyHistory(currentCompany, companyRequest);
    });
    $('loadCompanyEvents').addEventListener('click', function () {
      if (currentCompany) return loadCompanyEvents(currentCompany, companyRequest, false);
    });
    $('eventFilters').addEventListener('submit', function (event) {
      event.preventDefault();
      clearError();
      return loadEvents(true);
    });
    $('loadEvents').addEventListener('click', function () { return loadEvents(false); });
    $('eventResults').addEventListener('click', function (event) {
      var link = event.target.closest('[data-ticker]');
      if (!link) return;
      if (event.preventDefault) event.preventDefault();
      navigate('?view=company&ticker=' + encodeURIComponent(link.dataset.ticker), { returnSearch: window.location.search || '?view=events' });
    });
    [
      ['navOverview', '?view=overview'], ['navPipeline', '?view=pipeline'], ['navDocuments', '?view=documents'],
      ['navModels', '?view=models'], ['navIntelligence', '?view=discover'], ['navDiscover', '?view=discover'],
      ['navEvents', '?view=events'], ['navSettings', '?view=settings'],
    ].forEach(function (item) {
      $(item[0]).addEventListener('click', function (event) {
        event.preventDefault();
        navigate(item[1]);
      });
    });
    window.addEventListener('popstate', function () { showView(true); });
    operations = window.CatalystOperations.create({ api: api, navigate: navigate, credentials: currentCredentials,
      format: { escape: esc, time: fmtTime, integer: fmtInt, cost: fmtCost } });
    operations.init();
    showView();
  });
})();
