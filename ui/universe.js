(function () {
  'use strict';

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

  function offset(value) {
    var number = Number(value || 0);
    return Number.isSafeInteger(number) && number >= 0 && number <= 10000 ? number : 0;
  }

  function query(options) {
    options = options || {};
    var result = new URLSearchParams();
    result.set('limit', String(PAGE_SIZE));
    result.set('offset', String(offset(options.offset)));
    return result;
  }

  /** Candidate rows; [options.promote] adds a promotion button per row. */
  function render(page, options) {
    options = options || {};
    var rows = ((page && page.candidates) || []).map(function (candidate) {
      return '<tr><td><strong>' + esc(candidate.ticker) + '</strong></td>' +
        '<td class="num">' + esc(candidate.documentsStored) + '</td>' +
        '<td class="num">' + esc(candidate.documentsNeverQueued) + '</td>' +
        '<td>' + time(candidate.firstDiscoveredAt) + '</td><td>' + time(candidate.lastDiscoveredAt) + '</td>' +
        (options.promote ? '<td><button type="button" class="ghost" data-promote-ticker="' + esc(candidate.ticker) + '">Track</button></td>' : '') + '</tr>';
    }).join('');
    if (!rows) return '<p class="muted">No untracked ticker appeared in stored news in this period.</p>';
    return '<div class="table-scroll" tabindex="0" role="region" aria-label="Untracked tickers"><table><caption class="sr-only">Tickers named by stored news that no tracked company explains</caption>' +
      '<thead><tr><th scope="col">Ticker</th><th scope="col" class="num" title="Stored documents naming the ticker. Syndicated copies each count; this is not an event count.">Documents stored</th>' +
      '<th scope="col" class="num" title="Documents naming no tracked company. Tracking the ticker queues these for extraction.">Never processed</th>' +
      '<th scope="col">First seen</th><th scope="col">Last seen</th>' + (options.promote ? '<th scope="col"><span class="sr-only">Action</span></th>' : '') +
      '</tr></thead><tbody>' + rows + '</tbody></table></div>';
  }

  window.CatalystUniverse = Object.freeze({ query: query, render: render, offset: offset, PAGE_SIZE: PAGE_SIZE });
})();
