(function () {
  'use strict';

  var STATES = ['NORMAL', 'WATCH', 'BUILDING', 'CATALYZED', 'HIGH'];
  // Scores are not decayed between snapshots, so rows saved this long before
  // the ranking are not directly comparable with fresh ones.
  var STALE_AFTER_HOURS = 36;

  function invalid(field, message) {
    var error = new Error(message);
    error.field = field;
    throw error;
  }

  function query(filters) {
    filters = filters || {};
    var result = new URLSearchParams();
    (filters.states || []).forEach(function (state) {
      if (!STATES.includes(state)) invalid('discoveryStates', 'Choose a supported catalyst state.');
      result.append('state', state);
    });
    [['minScore', 'Minimum score'], ['minVelocity7d', 'Minimum 7-day change'], ['minDownside', 'Minimum downside']].forEach(function (entry) {
      var value = filters[entry[0]];
      if (value === undefined || value === null || String(value).trim() === '') return;
      if (!Number.isFinite(Number(value))) invalid(entry[0], entry[1] + ' must be a finite number.');
      if (entry[0] !== 'minVelocity7d' && (Number(value) < 0 || Number(value) > 100)) invalid(entry[0], entry[1] + ' must be between 0 and 100.');
      result.set(entry[0], String(Number(value)));
    });
    if (filters.sector && filters.sector.trim()) result.set('sector', filters.sector.trim());
    var sort = filters.sort === undefined ? 'SCORE' : filters.sort;
    if (!['SCORE', 'VELOCITY', 'DOWNSIDE'].includes(sort)) invalid('discoverySort', 'Choose Highest score, Highest 7-day change or Highest downside.');
    var limit = filters.limit === undefined ? 20 : Number(filters.limit);
    var offset = filters.offset === undefined ? 0 : Number(filters.offset);
    if (!Number.isInteger(limit) || limit < 1 || limit > 100) invalid('pageSize', 'Results per page must be between 1 and 100.');
    if (!Number.isSafeInteger(offset) || offset < 0) invalid('pageSize', 'Result offset must be a non-negative integer.');
    result.set('sort', sort);
    result.set('limit', String(limit));
    result.set('offset', String(offset));
    return result;
  }

  function esc(value) {
    return String(value == null ? 'Unavailable' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  function number(value, signed) {
    if (value === null || value === undefined || !Number.isFinite(Number(value))) return 'Unavailable';
    var numeric = Number(value);
    return (signed && numeric > 0 ? '+' : numeric < 0 ? '−' : '') + Math.abs(numeric).toFixed(1);
  }

  function time(value) {
    if (!value || !Number.isFinite(Date.parse(value))) return 'Unavailable';
    return '<time datetime="' + esc(value) + '">' + new Date(value).toISOString().replace('T', ' ').slice(0, 19) + ' UTC</time>';
  }

  function sevenDayChange(entry) {
    if (entry.velocity7dBaselineAvailable === false) return { text: 'No 7-day baseline', className: '' };
    var className = entry.velocity7d > 0 ? ' change-positive' : entry.velocity7d < 0 ? ' change-negative' : '';
    return { text: number(entry.velocity7d, true), className: className };
  }

  function isStale(entry, rankedAt) {
    var saved = Date.parse(entry.asOf);
    var ranked = Date.parse(rankedAt);
    return Number.isFinite(saved) && Number.isFinite(ranked) && ranked - saved > STALE_AFTER_HOURS * 3600000;
  }

  function staleMarker() {
    return ' <span class="pill warn stale-marker" title="Score and 7-day change were computed when this snapshot was saved and have not decayed since.">Stale</span>';
  }

  function render(page, options) {
    options = options || {};
    var compact = !!options.compact;
    var sort = options.sort || 'SCORE';
    var columns = '<th scope="col">Company</th>' + (compact ? '' : '<th scope="col">Sector</th>') +
      '<th scope="col" class="num"' + (sort === 'SCORE' ? ' aria-sort="descending"' : '') + '>Score</th><th scope="col">State</th>' +
      '<th scope="col" class="num"' + (sort === 'VELOCITY' ? ' aria-sort="descending"' : '') + '>7-day change <span class="muted">(points)</span></th>' +
      '<th scope="col" class="num"' + (sort === 'DOWNSIDE' ? ' aria-sort="descending"' : '') + ' title="Strength of negative catalysts alone, 0–100. The headline score nets them against positive ones.">Downside</th>' +
      (compact ? '' : '<th scope="col" class="num">Events discovered · 7d</th>') + '<th scope="col" class="saved-heading">Saved at</th>' + (compact ? '' : '<th scope="col">Versions</th>');
    var anyStale = false;
    var rows = (page.results || []).map(function (entry) {
      var stateClass = STATES.includes(entry.state) ? entry.state.toLowerCase() : 'unknown';
      var change = sevenDayChange(entry);
      var stale = isStale(entry, page.asOf);
      if (stale) anyStale = true;
      var savedAt = time(entry.asOf) + (stale ? staleMarker() : '');
      return '<tr><td><a href="?view=company&amp;ticker=' + encodeURIComponent(entry.ticker) + '" data-ticker="' + esc(entry.ticker) + '">' + esc(entry.ticker) + '</a>' +
        '<span class="cell-secondary">' + esc(entry.name) + '</span>' +
        (compact && entry.sector ? '<span class="cell-secondary ranking-sector">' + esc(entry.sector) + '</span>' : '') +
        (compact ? '<span class="cell-secondary ranking-saved-inline">Saved ' + savedAt + '</span>' : '') + '</td>' +
        (compact ? '' : '<td>' + esc(entry.sector || 'Unavailable') + '</td>') +
        '<td class="num score-value">' + number(entry.score) + '</td>' +
        '<td><span class="pill catalyst-state ' + stateClass + '">' + esc(entry.state) + '</span></td>' +
        '<td class="num' + change.className + '">' + esc(change.text) + '</td>' +
        '<td class="num downside-value">' + number(entry.downsideScore) + '</td>' +
        (compact ? '' : '<td class="num">' + esc(entry.events7d) + '</td>') +
        '<td class="saved-time">' + savedAt + '</td>' +
        (compact ? '' : '<td class="small">' + esc(entry.scoreVersion) + '<span class="cell-secondary">' + esc(entry.taxonomyVersion) + '</span></td>') + '</tr>';
    }).join('');
    return '<div class="table-scroll" tabindex="0" role="region" aria-label="Company leaderboard"><table class="leaderboard-table' + (compact ? ' compact' : '') + '">' +
      '<caption class="sr-only">Latest stored company snapshots. Company as of times appear in Saved at.</caption><thead><tr>' + columns + '</tr></thead><tbody>' + rows + '</tbody></table></div>' +
      (anyStale ? '<p class="muted small ranking-stale-note">Some scores were saved more than ' + STALE_AFTER_HOURS +
        ' hours before this ranking. Their score and 7-day change were computed then and have not decayed since. Enable daily snapshots to keep the ranking comparable.</p>' : '');
  }

  function step(done, title, detail, link) {
    var marker = done === true ? '<span class="pill ok">Done</span>' : done === false ? '<span class="pill warn">To do</span>' : '<span class="pill info">Unknown</span>';
    return '<li class="first-run-step">' + marker + ' <strong>' + esc(title) + '</strong><span class="cell-secondary">' + detail + '</span>' +
      (link && done !== true ? '<a href="' + esc(link.href) + '" data-radar-route="' + esc(link.href) + '">' + esc(link.text) + '</a>' : '') + '</li>';
  }

  function configured(config, name) {
    return !!(config && (config.providers || []).some(function (provider) { return provider.name === name && provider.configured; }));
  }

  /**
   * Steps between a fresh install and a populated Radar. Only what a read
   * actually reported is marked done or to do; anything unread stays Unknown.
   */
  function firstRun(status) {
    status = status || {};
    var config = status.config;
    var overview = status.overview;
    var checked = status.hasAdmin && config;
    var news = checked ? configured(config, 'polygon') || configured(config, 'finnhub') : null;
    var models = checked ? configured(config, 'openai') : null;
    var ran = status.hasAdmin && overview ? !!(overview.ingestionFreshness && overview.ingestionFreshness.lastSuccessAt) : null;
    var snapshots = checked ? !!(config.snapshots && config.snapshots.enabled) : null;
    var unread = status.hasAdmin && (!config || !overview) && status.checked ?
      '<p class="muted small">Some setup checks could not be read. Unknown steps may already be done.</p>' : '';
    return '<section class="first-run" aria-labelledby="firstRunTitle"><h3 id="firstRunTitle">Get Radar started</h3>' +
      '<p class="muted small">Scores appear after news has been collected, turned into events and scored.</p><ol>' +
      step(!!status.hasAdmin, 'Save the admin key', 'Setup checks and the pipeline need it.', { href: '?view=settings', text: 'Open Access' }) +
      step(news === null || models === null ? null : news && models, 'Configure news and model providers',
        'A news key (Polygon or Finnhub) and an OpenAI key, set as environment variables before start-up.', { href: '?view=settings', text: 'Review configuration' }) +
      step(ran, 'Run the pipeline', 'Collects news, extracts events and saves the first scores. Provider and model calls may cost money.', { href: '?view=pipeline', text: 'Open Pipeline runs' }) +
      step(snapshots, 'Enable daily snapshots', 'Set <code>CATALYST_SNAPSHOTS_ENABLED=true</code> so scores decay daily and rankings stay comparable. It calls no provider or model.') +
      '</ol>' + unread + '</section>';
  }

  window.CatalystLeaderboard = Object.freeze({ query: query, render: render, sevenDayChange: sevenDayChange, firstRun: firstRun });
})();
