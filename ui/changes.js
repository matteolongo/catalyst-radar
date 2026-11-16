(function () {
  'use strict';

  var STATES = ['NORMAL', 'WATCH', 'BUILDING', 'CATALYZED', 'HIGH'];
  var RANGES = { '24h': 24 * 3600000, '7d': 7 * 24 * 3600000 };
  var PAGE_SIZE = 50;

  function esc(value) {
    return String(value == null ? 'Unavailable' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  function time(value) {
    if (!value || !Number.isFinite(Date.parse(value))) return 'Unavailable';
    return '<time datetime="' + esc(value) + '">' + new Date(value).toISOString().replace('T', ' ').slice(0, 16) + ' UTC</time>';
  }

  function range(value) { return Object.hasOwn(RANGES, value) ? value : '24h'; }

  /** Query for the transitions feed covering the chosen period up to [now]. */
  function query(options) {
    options = options || {};
    var now = options.now === undefined ? Date.now() : options.now;
    var result = new URLSearchParams();
    result.set('since', new Date(now - RANGES[range(options.range)]).toISOString());
    (options.tickers || []).forEach(function (ticker) { result.append('ticker', ticker); });
    result.set('limit', String(PAGE_SIZE));
    return result;
  }

  function pill(state) {
    var cls = STATES.includes(state) ? state.toLowerCase() : 'unknown';
    return '<span class="pill catalyst-state ' + cls + '">' + esc(state) + '</span>';
  }

  function isNew(entry, lastVisit) {
    var at = Date.parse(entry.transitionedAt);
    var seen = Date.parse(lastVisit);
    return Number.isFinite(at) && Number.isFinite(seen) && at > seen;
  }

  function list(title, entries, lastVisit, emptyText) {
    var items = entries.map(function (entry) {
      return '<li><a href="?view=company&amp;ticker=' + encodeURIComponent(entry.ticker) + '" data-change-ticker="' + esc(entry.ticker) + '">' +
        esc(entry.ticker) + '</a> <span class="cell-secondary">' + esc(entry.name) + '</span>' +
        '<span class="change-states">' + pill(entry.fromState) + ' <span aria-label="to">→</span> ' + pill(entry.toState) + '</span>' +
        '<span class="muted small">' + time(entry.transitionedAt) + (isNew(entry, lastVisit) ? ' <span class="pill info change-new">New</span>' : '') + '</span></li>';
    }).join('');
    return '<div class="change-list"><h3>' + esc(title) + ' <span class="muted">(' + entries.length + ')</span></h3>' +
      (items ? '<ul>' + items + '</ul>' : '<p class="muted small">' + esc(emptyText) + '</p>') + '</div>';
  }

  /** Server order is preserved inside each list; direction comes from the server. */
  function render(page, options) {
    options = options || {};
    var entries = (page && page.transitions) || [];
    var up = entries.filter(function (entry) { return entry.direction === 'UPGRADE'; });
    var down = entries.filter(function (entry) { return entry.direction === 'DOWNGRADE'; });
    var newCount = entries.filter(function (entry) { return isNew(entry, options.lastVisit); }).length;
    return (options.lastVisit && newCount ? '<p class="small change-summary">' + newCount + ' new since your last visit.</p>' : '') +
      '<div class="change-columns">' +
      list('Moved up', up, options.lastVisit, 'No company moved to a higher state in this period.') +
      list('Moved down', down, options.lastVisit, 'No company moved to a lower state in this period.') + '</div>' +
      (page && page.nextCursor ? '<p class="muted small">Showing the latest ' + PAGE_SIZE + ' changes in this period.</p>' : '');
  }

  window.CatalystChanges = Object.freeze({ query: query, render: render, range: range });
})();
