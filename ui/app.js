/* CatalystRadar dashboard — vanilla JS, no dependencies.
 * Reads the versioned API plus the admin run-history endpoints.
 * Keys live in sessionStorage and are sent only to the routes that need them.
 */
(function () {
  'use strict';

  var API_BASE = window.location.origin;
  var ADMIN_KEY_NAME = 'catalyst-admin-key';
  var API_KEY_NAME = 'catalyst-api-key';
  var pipelineRunning = false;
  var discoveryOffset = 0;
  var discoveryTotal = 0;
  var discoveryLimit = 20;
  var appliedDiscoveryFilters;
  var discoveryRequest = 0;
  var discoveryLoading = false;
  var companyRequest = 0;
  var currentCompany = '';
  var companyEvents = [];
  var companyNextCursor = null;
  var companyEventsLoading = false;

  function $(id) { return document.getElementById(id); }

  function esc(value) {
    return String(value === null || value === undefined ? '' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  function fmtTime(iso) {
    if (!iso) return '–';
    var date = new Date(iso);
    return isNaN(date) ? String(iso) : date.toLocaleString();
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
    var headers = { 'Accept': 'application/json' };
    var adminKey = window.sessionStorage.getItem(ADMIN_KEY_NAME);
    var apiKey = window.sessionStorage.getItem(API_KEY_NAME);
    if (path.startsWith('/internal/') && adminKey) headers['X-Admin-Key'] = adminKey;
    if (path.startsWith('/v1/') && apiKey) headers.Authorization = 'Bearer ' + apiKey;
    var response = await fetch(API_BASE + path, Object.assign({ headers: headers }, options));
    var body = await response.json().catch(function () { return {}; });
    if (!response.ok) {
      throw new Error(body.detail || body.title || ('HTTP ' + response.status));
    }
    return body;
  }

  function statusPill(status) {
    var cls = status === 'SUCCESS' ? 'ok' : (status === 'PARTIAL' ? 'warn' : (status === 'FAILED' ? 'bad' : 'info'));
    return '<span class="pill ' + cls + '">' + esc(status) + '</span>';
  }

  async function refreshHealth() {
    var el = $('health');
    try {
      var health = await api('/actuator/health');
      var up = health.status === 'UP';
      el.textContent = up ? 'API up' : 'API ' + health.status;
      el.className = 'pill ' + (up ? 'ok' : 'bad');
    } catch (err) {
      el.textContent = 'API unreachable';
      el.className = 'pill bad';
    }
  }

  async function refreshIngestionRuns() {
    var rows;
    try {
      rows = (await api('/internal/ingestion/runs?limit=20')).runs;
    } catch (err) {
      $('ingestionRows').innerHTML = '<tr><td colspan="7" class="muted">Unable to load runs.</td></tr>';
      showError('Ingestion runs: ' + err.message);
      return;
    }
    if (!rows.length) {
      $('ingestionRows').innerHTML = '<tr><td colspan="7" class="muted">No runs yet.</td></tr>';
      return;
    }
    $('ingestionRows').innerHTML = rows.map(function (run) {
      return '<tr><td>' + esc(fmtTime(run.finishedAt)) + '</td><td>' + esc(run.provider) + '</td>' +
        '<td>' + statusPill(run.status) + '</td>' +
        '<td class="num">' + fmtInt(run.fetched) + '</td>' +
        '<td class="num">' + fmtInt(run.added) + '</td>' +
        '<td class="num">' + fmtInt(run.duplicates) + '</td>' +
        '<td>' + esc(run.error || '') + '</td></tr>';
    }).join('');
  }

  async function refreshModelRuns() {
    var rows;
    try {
      rows = (await api('/internal/model-runs?limit=50')).runs;
    } catch (err) {
      $('modelRows').innerHTML = '<tr><td colspan="9" class="muted">Unable to load model runs.</td></tr>';
      $('totalIn').textContent = '–';
      $('totalOut').textContent = '–';
      $('totalCost').textContent = '–';
      showError('Model runs: ' + err.message);
      return;
    }
    if (!rows.length) {
      $('modelRows').innerHTML = '<tr><td colspan="9" class="muted">No model runs yet.</td></tr>';
      $('totalIn').textContent = '–';
      $('totalOut').textContent = '–';
      $('totalCost').textContent = '–';
      return;
    }
    var totalIn = 0, totalOut = 0, totalCost = 0;
    $('modelRows').innerHTML = rows.map(function (run) {
      totalIn += run.inputTokens || 0;
      totalOut += run.outputTokens || 0;
      totalCost += Number(run.estimatedCost || 0);
      var ok = run.success
        ? '<span class="st-ok">yes</span>'
        : '<span class="st-bad">no</span>';
      return '<tr><td>' + esc(fmtTime(run.createdAt)) + '</td><td>' + esc(run.operation) + '</td>' +
        '<td>' + esc(run.model) + '</td>' +
        '<td class="num">' + fmtInt(run.inputTokens) + '</td>' +
        '<td class="num">' + fmtInt(run.outputTokens) + '</td>' +
        '<td class="num">' + fmtInt(run.latencyMs) + ' ms</td>' +
        '<td class="num">' + fmtCost(run.estimatedCost) + '</td>' +
        '<td>' + ok + '</td><td>' + esc(run.error || '') + '</td></tr>';
    }).join('');
    $('totalIn').textContent = fmtInt(totalIn);
    $('totalOut').textContent = fmtInt(totalOut);
    $('totalCost').textContent = fmtCost(totalCost);
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
    if (!source) return '<span class="muted">Source unavailable</span>';
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
    if (!snapshots.length) {
      $('companyHistory').innerHTML = '<p>No score history in this range.</p>';
      return;
    }
    var chart = '';
    if (snapshots.length > 1) {
      var minTime = new Date(snapshots[0].asOf).getTime();
      var maxTime = new Date(snapshots[snapshots.length - 1].asOf).getTime();
      var x = function (at) { return 30 + 540 * (new Date(at).getTime() - minTime) / (maxTime - minTime || 1); };
      var y = function (score) { return 130 - Math.max(0, Math.min(100, Number(score))) * 1.1; };
      chart = '<svg viewBox="0 0 600 150" role="img" aria-label="Stored catalyst score snapshots and state transitions; data table follows">' +
        '<line x1="30" y1="130" x2="570" y2="130" class="axis"/><line x1="30" y1="20" x2="30" y2="130" class="axis"/>' +
        '<polyline class="score-line" points="' + snapshots.map(function (s) { return x(s.asOf) + ',' + y(s.score); }).join(' ') + '"/>' +
        snapshots.map(function (s) { return '<circle class="score-point" cx="' + x(s.asOf) + '" cy="' + y(s.score) + '" r="4"><title>' + esc(s.asOf) + ' · ' + esc(fmtScore(s.score)) + ' · ' + esc(s.state) + '</title></circle>'; }).join('') +
        transitions.filter(function (t) { return new Date(t.at).getTime() >= minTime && new Date(t.at).getTime() <= maxTime; }).map(function (t) { return '<path class="transition-mark" d="M' + x(t.at) + ' 20V130"><title>' + esc(t.at) + ' · ' + esc(t.from) + ' → ' + esc(t.to) + '</title></path>'; }).join('') + '</svg>';
    } else chart = '<p>Insufficient history for a line chart. One stored snapshot is available.</p>';
    $('companyHistory').innerHTML = chart + '<div class="history-table"><table><caption>Stored score snapshots</caption><thead><tr><th>Date</th><th>Score</th><th>State</th><th>As of</th></tr></thead><tbody>' +
      snapshots.map(function (s) { return '<tr><td>' + esc((s.asOf || '').slice(0, 10)) + '</td><td>' + esc(fmtScore(s.score)) + '</td><td>' + esc(s.state) + '</td><td>' + timeLabel(s.asOf) + '</td></tr>'; }).join('') +
      '</tbody></table></div>' + (transitions.length ? '<p>State transitions at persisted times:</p><ul>' + transitions.map(function (t) {
        return '<li>' + timeLabel(t.at) + ' · ' + esc(t.from) + ' → ' + esc(t.to) + ' · score ' + esc(fmtScore(t.score)) + '</li>';
      }).join('') + '</ul>' : '');
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
    $('companyHistory').textContent = 'Loading score history…';
    try {
      var data = await api(companyPath(ticker, '/timeline', historyQuery()));
      if (request === companyRequest) renderHistory(data);
    } catch (_) { if (request === companyRequest) companyPanelError('companyHistory', 'score history'); }
  }

  function refreshCompany(ticker) {
    var request = ++companyRequest;
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

  function showView(moveFocus) {
    var route = new URLSearchParams(window.location.search);
    var view = route.get('view') || 'discover';
    if (!['discover', 'company', 'events', 'operations'].includes(view) || (view === 'company' && !route.get('ticker'))) view = 'discover';
    ['discover', 'company', 'events', 'operations'].forEach(function (name) {
      var element = $(name + 'View');
      if (name === view) element.classList.remove('hidden');
      else element.classList.add('hidden');
    });
    ['Discover', 'Events', 'Operations'].forEach(function (name) {
      var link = $('nav' + name);
      if (name.toLowerCase() === view) link.setAttribute('aria-current', 'page');
      else link.removeAttribute('aria-current');
    });
    if (view === 'company') {
      if (route.get('ticker') !== currentCompany) refreshCompany(route.get('ticker'));
    } else {
      companyRequest++;
      currentCompany = '';
    }
    if (moveFocus) {
      if (view === 'company') $('companyTitle').focus();
      else if (view === 'discover') $('discoverTitle').focus();
      else $('nav' + view.charAt(0).toUpperCase() + view.slice(1)).focus();
    }
  }

  function navigate(search) {
    window.history.pushState({}, '', search);
    showView(true);
  }

  function requireAdminKey() {
    var hasKey = !!window.sessionStorage.getItem(ADMIN_KEY_NAME);
    $('runNow').disabled = !hasKey || pipelineRunning;
    if (!hasKey) {
      $('ingestionRows').innerHTML = '<tr><td colspan="7" class="muted">Save an admin key to view runs.</td></tr>';
      $('modelRows').innerHTML = '<tr><td colspan="9" class="muted">Save an admin key to view model runs.</td></tr>';
      $('totalIn').textContent = '–';
      $('totalOut').textContent = '–';
      $('totalCost').textContent = '–';
    }
    return hasKey;
  }

  async function refreshAll() {
    clearError();
    var hasAdminKey = requireAdminKey();
    var discovery = refreshDiscovery();
    await refreshHealth();
    if (hasAdminKey) {
      await refreshIngestionRuns();
      await refreshModelRuns();
    }
    await discovery;
  }

  async function runPipelineNow() {
    if (pipelineRunning) return;
    pipelineRunning = true;
    var button = $('runNow');
    button.disabled = true;
    $('pipelineStatus').textContent = 'Pipeline running — this takes minutes with live LLM calls…';
    clearError();
    try {
      var result = await api('/internal/ingestion/runs', { method: 'POST' });
      $('pipelineStatus').textContent =
        'Last run: ' + result.status +
        ' — ' + result.documentsProcessed + ' docs, ' +
        result.eventsExtracted + ' events, ' +
        result.companiesRescored + ' rescored' +
        (result.error ? ' (note: ' + result.error + ')' : '');
      await refreshAll();
    } catch (err) {
      showError('Pipeline run failed: ' + err.message);
      $('pipelineStatus').textContent = 'Last run failed.';
    } finally {
      pipelineRunning = false;
      button.disabled = !window.sessionStorage.getItem(ADMIN_KEY_NAME);
    }
  }

  var pollTimer = null;

  function setPolling(on) {
    if (pollTimer) { window.clearInterval(pollTimer); pollTimer = null; }
    if (on) { pollTimer = window.setInterval(refreshAll, 30000); }
  }

  document.addEventListener('DOMContentLoaded', function () {
    appliedDiscoveryFilters = readDiscoveryFilters();
    discoveryLimit = Number(appliedDiscoveryFilters.get('limit'));
    $('apiDocs').href = API_BASE + '/swagger-ui.html';
    var saved = window.sessionStorage.getItem(ADMIN_KEY_NAME);
    if (saved) $('adminKey').value = saved;
    var savedApiKey = window.sessionStorage.getItem(API_KEY_NAME);
    if (savedApiKey) $('apiKey').value = savedApiKey;
    $('saveKey').addEventListener('click', function () {
      window.sessionStorage.setItem(ADMIN_KEY_NAME, $('adminKey').value.trim());
      window.sessionStorage.setItem(API_KEY_NAME, $('apiKey').value.trim());
      refreshAll();
    });
    $('clearKey').addEventListener('click', function () {
      window.sessionStorage.removeItem(ADMIN_KEY_NAME);
      window.sessionStorage.removeItem(API_KEY_NAME);
      $('adminKey').value = '';
      $('apiKey').value = '';
      requireAdminKey();
      window.location.reload();
    });
    $('refresh').addEventListener('click', refreshAll);
    $('runNow').addEventListener('click', runPipelineNow);
    $('autoRefresh').addEventListener('change', function (event) {
      setPolling(event.target.checked);
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
      navigate('?view=company&ticker=' + encodeURIComponent(link.dataset.ticker));
    });
    $('backToResults').addEventListener('click', function () { navigate('?view=discover'); });
    $('historyRange').addEventListener('change', function () {
      if (currentCompany) return loadCompanyHistory(currentCompany, companyRequest);
    });
    $('loadCompanyEvents').addEventListener('click', function () {
      if (currentCompany) return loadCompanyEvents(currentCompany, companyRequest, false);
    });
    ['Discover', 'Events', 'Operations'].forEach(function (name) {
      $('nav' + name).addEventListener('click', function (event) {
        event.preventDefault();
        navigate('?view=' + name.toLowerCase());
      });
    });
    window.addEventListener('popstate', function () { showView(true); });
    showView();
    setPolling($('autoRefresh').checked);
    refreshAll();
  });
})();
