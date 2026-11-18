(function () {
  'use strict';

  /** States the extraction step accepts; anything else is already finished or never queued. */
  var EXTRACTABLE = ['PENDING', 'PROCESSING', 'RETRYABLE_ERROR'];
  var MAX_FETCH_TICKERS = 10;
  var MAX_EXTRACTION = 50;

  function esc(value) {
    return String(value == null ? 'Unavailable' : value).replace(/[&<>"']/g, function (ch) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
    });
  }

  function time(value) {
    if (!value || !Number.isFinite(Date.parse(value))) return 'Unavailable';
    return '<time datetime="' + esc(value) + '">' + new Date(value).toISOString().replace('T', ' ').slice(0, 16) + ' UTC</time>';
  }

  function checkbox(attribute, value, checked, label, disabled) {
    return '<input type="checkbox" ' + attribute + '="' + esc(value) + '"' + (checked ? ' checked' : '') + (disabled ? ' disabled' : '') +
      ' aria-label="' + esc(label) + '">';
  }

  function extractable(state) { return EXTRACTABLE.indexOf(state) >= 0; }

  /** Step 1: every symbol in the window's news, tracked or not, each with a selection box. */
  function renderSymbols(page, selected) {
    var rows = ((page && page.symbols) || []).map(function (symbol) {
      return '<tr><td>' + checkbox('data-guided-symbol', symbol.ticker, selected.has(symbol.ticker), 'Select ' + symbol.ticker) + '</td>' +
        '<td><strong>' + esc(symbol.ticker) + '</strong>' + (symbol.companyName ? '<br><span class="muted">' + esc(symbol.companyName) + '</span>' : '') + '</td>' +
        '<td>' + (symbol.tracked ? '<span class="pill ok">Tracked</span>' : '<span class="pill warn" title="Selecting it tracks it before its history is fetched.">Not tracked</span>') + '</td>' +
        '<td class="num">' + esc(symbol.documents) + '</td><td class="num">' + esc(symbol.queuedDocuments) + '</td>' +
        '<td>' + esc(symbol.latestTitle) + '<br><span class="muted">' + time(symbol.latestAt) + '</span></td></tr>';
    }).join('');
    if (!rows) return '<p class="muted">No symbol appeared in stored news published in this window. Fetch the latest news first.</p>';
    return '<div class="table-scroll" tabindex="0" role="region" aria-label="Symbols in the news"><table><caption class="sr-only">Symbols named by news in the window</caption>' +
      '<thead><tr><th scope="col"><span class="sr-only">Select</span></th><th scope="col">Symbol</th><th scope="col">Universe</th>' +
      '<th scope="col" class="num" title="Stored articles naming the symbol. Syndicated copies each count; this is not an event count.">Articles</th>' +
      '<th scope="col" class="num" title="Articles waiting for extraction for this company.">Waiting</th>' +
      '<th scope="col">Latest headline</th></tr></thead><tbody>' + rows + '</tbody></table></div>';
  }

  function runSummary(runs) {
    return (runs || []).map(function (run) {
      return esc(run.provider) + ' ' + esc(run.status) + ': ' + esc(run.fetched) + ' fetched, ' + esc(run.added) + ' new, ' + esc(run.duplicates) + ' already stored' +
        (run.errorCode ? ' (' + esc(run.errorCode) + ')' : '');
    }).join('; ');
  }

  /** Step 2 outcome: what tracking and the history fetch did per symbol. */
  function renderFetch(fetches, refusals) {
    var items = (fetches || []).map(function (fetch) {
      return '<li><strong>' + esc(fetch.ticker) + '</strong>: ' + esc(fetch.newDocuments) + ' new articles. ' + runSummary(fetch.runs) + '</li>';
    }).concat((refusals || []).map(function (refusal) {
      return '<li><strong>' + esc(refusal.ticker) + '</strong>: not fetched. ' + esc(refusal.reason) + '</li>';
    })).join('');
    return items ? '<ul class="guided-list">' + items + '</ul>' : '';
  }

  /**
   * Step 2 result: each symbol's articles in the fetch window. Only queued
   * articles can be chosen; the others show why they are not offered.
   */
  function renderDocuments(groups, selected) {
    var sections = (groups || []).map(function (group) {
      var rows = group.documents.map(function (document) {
        var open = extractable(document.state);
        return '<tr><td>' + checkbox('data-guided-document', document.id, open && selected.has(document.id), 'Extract ' + document.title, !open) + '</td>' +
          '<td>' + esc(document.title) + '</td><td>' + time(document.publishedAt) + '</td>' +
          '<td>' + (open ? '<span class="pill warn">Waiting</span>' : '<span class="pill">' + esc(document.state) + '</span>') + '</td></tr>';
      }).join('');
      return '<h4>' + esc(group.ticker) + ' <span class="muted">· ' + group.documents.length + ' articles</span></h4>' +
        (rows ? '<div class="table-scroll" tabindex="0" role="region" aria-label="' + esc(group.ticker) + ' articles"><table>' +
          '<thead><tr><th scope="col"><span class="sr-only">Extract</span></th><th scope="col">Headline</th><th scope="col">Published</th><th scope="col">State</th></tr></thead>' +
          '<tbody>' + rows + '</tbody></table></div>' : '<p class="muted">No article in this window.</p>');
    }).join('');
    return sections || '<p class="muted">No article to show.</p>';
  }

  function eventLine(event) {
    var evidence = event.evidence && event.evidence[0] ? ' “' + esc(event.evidence[0].quoteOrFact) + '”' : '';
    return '<li><strong>' + esc(event.type) + '</strong> · ' + esc(event.direction) + ' · ' + esc(event.ticker || '') +
      ' · confidence ' + esc(event.confidence) + evidence + '</li>';
  }

  /** Step 3 outcome: per chosen article, what extraction did and the events it produced. */
  function renderExtraction(result, titles, eventsByDocument) {
    var rows = ((result && result.documents) || []).map(function (document) {
      var events = eventsByDocument[document.documentId] || [];
      var outcome = document.refused ? 'Not extracted: ' + esc(document.refused) :
        esc(document.status) + ' · ' + esc(document.eventsInserted) + ' new events, ' + esc(document.eventsReused) + ' already known';
      return '<li><a href="?view=documents&amp;documentId=' + esc(document.documentId) + '&amp;documentTab=source">' + esc(titles[document.documentId] || document.documentId) + '</a>' +
        '<br><span class="muted">' + outcome + '</span>' + (events.length ? '<ul>' + events.map(eventLine).join('') + '</ul>' : '') + '</li>';
    }).join('');
    return rows ? '<ul class="guided-list">' + rows + '</ul>' : '<p class="muted">Nothing was extracted.</p>';
  }

  /** Step 4 choice: companies to rescore. */
  function renderCompanies(tickers, selected) {
    if (!tickers.length) return '<p class="muted">No company to score.</p>';
    return '<ul class="guided-list">' + tickers.map(function (ticker) {
      return '<li><label>' + checkbox('data-guided-company', ticker, selected.has(ticker), 'Score ' + ticker) + ' ' + esc(ticker) + '</label></li>';
    }).join('') + '</ul>';
  }

  /** Step 4 outcome: the score and state each recalculation saved. */
  function renderScores(scores) {
    var rows = (scores || []).map(function (entry) {
      if (entry.error) return '<tr><td><strong>' + esc(entry.ticker) + '</strong></td><td colspan="3">' + esc(entry.error) + '</td></tr>';
      var catalyst = entry.catalyst;
      return '<tr><td><a href="?view=company&amp;ticker=' + encodeURIComponent(catalyst.ticker) + '" data-ticker="' + esc(catalyst.ticker) + '"><strong>' + esc(catalyst.ticker) + '</strong></a></td>' +
        '<td class="num">' + esc(Number(catalyst.score).toFixed(1)) + '</td><td>' + esc(catalyst.state) + '</td><td class="num">' + esc(catalyst.totalEvents) + '</td></tr>';
    }).join('');
    if (!rows) return '';
    return '<div class="table-scroll" tabindex="0" role="region" aria-label="Scores"><table><thead><tr><th scope="col">Company</th><th scope="col" class="num">Score</th>' +
      '<th scope="col">State</th><th scope="col" class="num">Events</th></tr></thead><tbody>' + rows + '</tbody></table></div>';
  }

  /** A document linked to several chosen symbols is offered once, under the first. */
  function groupDocuments(perTicker) {
    var seen = new Set();
    return perTicker.map(function (entry) {
      return { ticker: entry.ticker, documents: entry.documents.filter(function (document) {
        if (seen.has(document.id)) return false;
        seen.add(document.id);
        return true;
      }) };
    });
  }

  function chunks(values, size) {
    var result = [];
    for (var i = 0; i < values.length; i += size) result.push(values.slice(i, i + size));
    return result;
  }

  window.CatalystGuided = Object.freeze({
    EXTRACTABLE: EXTRACTABLE, MAX_FETCH_TICKERS: MAX_FETCH_TICKERS, MAX_EXTRACTION: MAX_EXTRACTION,
    extractable: extractable, renderSymbols: renderSymbols, renderFetch: renderFetch, renderDocuments: renderDocuments,
    renderExtraction: renderExtraction, renderCompanies: renderCompanies, renderScores: renderScores,
    groupDocuments: groupDocuments, chunks: chunks,
  });
})();
