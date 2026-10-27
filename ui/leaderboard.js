(function () {
  'use strict';

  var STATES = ['NORMAL', 'WATCH', 'BUILDING', 'CATALYZED', 'HIGH'];

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
    [['minScore', 'Minimum score'], ['minVelocity7d', 'Minimum 7-day change']].forEach(function (entry) {
      var value = filters[entry[0]];
      if (value === undefined || value === null || String(value).trim() === '') return;
      if (!Number.isFinite(Number(value))) invalid(entry[0], entry[1] + ' must be a finite number.');
      if (entry[0] === 'minScore' && (Number(value) < 0 || Number(value) > 100)) invalid(entry[0], 'Minimum score must be between 0 and 100.');
      result.set(entry[0], String(Number(value)));
    });
    if (filters.sector && filters.sector.trim()) result.set('sector', filters.sector.trim());
    var sort = filters.sort === undefined ? 'SCORE' : filters.sort;
    if (!['SCORE', 'VELOCITY'].includes(sort)) invalid('discoverySort', 'Choose Highest score or Highest 7-day change.');
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

  function render(page, options) {
    options = options || {};
    var compact = !!options.compact;
    var sort = options.sort || 'SCORE';
    var columns = '<th scope="col">Company</th>' + (compact ? '' : '<th scope="col">Sector</th>') +
      '<th scope="col">State</th><th scope="col" class="num"' + (sort === 'SCORE' ? ' aria-sort="descending"' : '') + '>Score</th>' +
      '<th scope="col" class="num"' + (sort === 'VELOCITY' ? ' aria-sort="descending"' : '') + '>7-day change <span class="muted">(points)</span></th>' +
      (compact ? '' : '<th scope="col" class="num">Events discovered · 7d</th>') + '<th scope="col">Saved at</th>' + (compact ? '' : '<th scope="col">Versions</th>');
    var rows = (page.results || []).map(function (entry) {
      var stateClass = STATES.includes(entry.state) ? entry.state.toLowerCase() : 'unknown';
      return '<tr><td><a href="?view=company&amp;ticker=' + encodeURIComponent(entry.ticker) + '" data-ticker="' + esc(entry.ticker) + '">' + esc(entry.ticker) + '</a>' +
        '<span class="cell-secondary">' + esc(entry.name) + '</span></td>' +
        (compact ? '' : '<td>' + esc(entry.sector || 'Unavailable') + '</td>') +
        '<td><span class="pill catalyst-state ' + stateClass + '">' + esc(entry.state) + '</span></td>' +
        '<td class="num">' + number(entry.score) + '</td><td class="num">' + number(entry.velocity7d, true) + '</td>' +
        (compact ? '' : '<td class="num">' + esc(entry.events7d) + '</td>') +
        '<td class="saved-time">' + time(entry.asOf) + '</td>' +
        (compact ? '' : '<td class="small">' + esc(entry.scoreVersion) + '<span class="cell-secondary">' + esc(entry.taxonomyVersion) + '</span></td>') + '</tr>';
    }).join('');
    return '<div class="table-scroll" tabindex="0" role="region" aria-label="Company leaderboard"><table class="leaderboard-table' + (compact ? ' compact' : '') + '">' +
      '<caption class="sr-only">Latest stored company snapshots. Company as of times appear in Saved at.</caption><thead><tr>' + columns + '</tr></thead><tbody>' + rows + '</tbody></table></div>';
  }

  window.CatalystLeaderboard = Object.freeze({ query: query, render: render });
})();
