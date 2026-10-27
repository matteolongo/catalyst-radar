/* Explanatory pages only: no network calls, scoring, or stored example data. */
(function () {
  'use strict';
  var chapters = [
    {
      id: 'companies', title: 'Companies', subtitle: 'Decide which companies the pipeline can recognise.',
      input: 'Company reference records: ticker, name, exchange, sector and active status.',
      process: 'Ingestion uses the configured active company universe. Captured documents are linked to recognised companies. A company can exist before it has news, events or a catalyst score.',
      output: 'A known company that can be associated with source documents. This is the starting point, not a scored catalyst.',
      example: 'ExampleCo is a fictional US-listed company in the active universe. No news has been collected yet, so there is no score to compare.',
      before: 'Company reference', after: 'Company ready for news association',
      missing: 'An empty Companies leaderboard means no matching saved snapshots, not necessarily an empty company universe. Use Open company with a known ticker. A document with no recognised company appears as Unresolved in Documents.',
      inspect: 'Open Companies to search a known ticker or rank saved snapshots. The optional ticker above only changes inspection links; it does not add or activate a company.',
      links: [['discover', 'Open Companies'], ['documents', 'Inspect company documents']],
      next: 'With company references available, the pipeline can collect and associate news.',
    },
    {
      id: 'collect', title: 'Collect news', subtitle: 'Capture the source material before interpreting it.',
      input: 'Active company tickers, configured news providers and the ingestion lookback window.',
      process: 'The pipeline asks the configured primary provider for news, with a fallback on supported failures. It stores new documents, associates companies and queues eligible documents for processing. Existing documents are deduplicated.',
      output: 'Stored source documents and a provider run recording fetched, new and duplicate counts. A collected article is not yet an extracted event.',
      example: 'A provider returns an article saying ExampleCo raised its revenue outlook from 700 to 760 million. The headline, available source text and publication time are captured; the document now waits for processing.',
      before: 'Provider article', after: 'Stored document → processing queue',
      missing: 'No provider runs in the selected period means no recorded runs in that window. Check a longer period and provider configuration. Waiting documents need processing; a failed fetch is different from failed extraction.',
      inspect: 'Inspect the provider run in Pipeline, then its source documents. Publication time is when the source published it; discovery time is when CatalystRadar captured it.',
      note: 'Two-week imports: the current ingestion loop reads only the first provider page per ticker batch. A longer lookback alone does not guarantee complete coverage. There is no backfill control in these notebooks.',
      links: [['pipeline', 'Inspect provider runs'], ['documents', 'Inspect collected documents'], ['settings', 'Check provider configuration']],
      next: 'The processing queue supplies stored documents to event extraction.',
    },
    {
      id: 'extract', title: 'Extract events', subtitle: 'Turn source text into evidence-linked facts.',
      input: 'One stored document, its associated companies, and the configured extraction model and prompt version.',
      process: 'The model identifies structured catalyst events. Application code validates and normalises the response against the controlled taxonomy. One document can produce several events, or none if it contains no relevant catalyst.',
      output: 'Validated event reports with company, family, type, direction, evidence and available attributes. A model does not choose the final company score.',
      example: 'From the ExampleCo article, the model proposes GUIDANCE_RAISE in the GUIDANCE family, with POSITIVE direction. The statement about the outlook increasing from 700 to 760 million supplies the evidence.',
      before: 'Stored ExampleCo document', after: 'Guidance-raise event report + evidence',
      missing: 'Open the document’s Attempts and Model calls to distinguish a failed response, invalid output and a valid result with no relevant events. Earlier attempts can fail even when the document later completes.',
      inspect: 'Select a document, then inspect Timeline, Attempts, Model calls and Events. Model calls show recorded token usage and estimated cost; unknown cost is not zero.',
      links: [['documents', 'Inspect extraction on a document'], ['models-extract', 'Inspect extraction model calls']],
      next: 'Reports that describe the same underlying event must be grouped before scoring.',
    },
    {
      id: 'group', title: 'Group reports', subtitle: 'Count the catalyst, not the number of articles.',
      input: 'Validated event reports for a company, including type, event time and supporting facts.',
      process: 'The application compares compatible reports using its clustering rules and semantic similarity. Reports about one underlying event can share a stored cluster. Different catalysts must remain distinct.',
      output: 'Event reports linked to a canonical cluster where applicable. Duplicate reports do not multiply the catalyst contribution.',
      example: 'A second publisher covers the same ExampleCo guidance raise. Two source documents and two reports support one underlying event, rather than two independent guidance raises.',
      before: 'Two articles → two related reports', after: 'One underlying guidance catalyst',
      missing: 'A missing cluster reference is not proof that an event was ignored. The Events view groups the loaded reports only; its report count is not a complete source-coverage count. Missing recorded stages cannot be reconstructed from a count.',
      inspect: 'Expand evidence in Events or open a company’s contextual events. Compare cluster references, source links and facts. Embedding model calls may have recorded usage and estimated cost.',
      links: [['events', 'Inspect grouped event evidence'], ['company', 'Inspect a company’s events'], ['models-embed', 'Inspect embedding model calls']],
      next: 'The scoring rules select canonical contributions so repeated coverage is not counted again.',
    },
    {
      id: 'score', title: 'Calculate scores', subtitle: 'Apply repeatable rules to the company’s catalysts.',
      input: 'Eligible canonical events, their structured attributes, the scoring time and the scoring version.',
      process: 'Deterministic application rules weight event contributions using confidence, materiality, surprise, source quality, directness and time decay. They aggregate contributions, apply the configured family convergence and normalisation, then derive the catalyst state.',
      output: 'A saved company score and state, with a timestamp and version. Captured runs can also retain the detailed calculation inputs and before/after values.',
      example: 'ExampleCo’s guidance raise contributes positively under the rules. Its final score also depends on other eligible catalysts and their age. The headline alone cannot tell us the score or whether the state changes.',
      before: 'Canonical event contributions', after: 'Saved score + state + calculation when recorded',
      missing: 'Document completion does not guarantee company scoring succeeded. Inspect the cycle’s issues. A current reconstructed explanation is not proof of the exact drivers used for an older score; use Recorded calculations when available.',
      inspect: 'Open a company and compare Current snapshot with Recorded calculations. Contributions are raw calculation inputs, not final score points. The application provides research evidence, not buy/sell recommendations.',
      links: [['company', 'Inspect company scores and calculations'], ['pipeline', 'Inspect cycle scoring outcomes']],
      next: 'Repeated saved snapshots allow comparison over time, including changes from decay.',
    },
    {
      id: 'history', title: 'Track changes', subtitle: 'Compare what was actually saved at different times.',
      input: 'Stored company snapshots, state transitions and any recorded calculation history.',
      process: 'Company history displays saved points and available state transitions. Score changes compare available stored values. Daily snapshots, when enabled, recalculate scores as events age even when no new news arrives.',
      output: 'A timeline of saved scores and states, plus available 1-day, 3-day and 7-day changes. Gaps remain gaps; a displayed change alone does not establish complete lookback coverage.',
      example: 'Later, ExampleCo has another saved score. You can compare the two recorded points and inspect any captured calculations. An interval with no saved point remains empty; the UI does not invent a daily score.',
      before: 'Saved snapshot A + saved snapshot B', after: 'Observed score change and any recorded state transition',
      missing: 'A fresh installation may have no snapshot history. Daily snapshots can be disabled. An old article imported now is discovered today: importing two weeks of news does not create two weeks of historical scores. Replay uses only records available by its cutoff and does not populate saved history.',
      inspect: 'Open a company’s Score history and Recorded calculations. Daily snapshot runs appear separately in Pipeline. Missing trace data means the calculation details were not retained, not that the score was zero.',
      links: [['company', 'Inspect company history'], ['snapshots', 'Inspect daily snapshot runs'], ['overview', 'Return to Overview']],
      next: 'You have followed the complete process. Use Overview to monitor it and Companies to investigate its results.',
    },
  ];

  function esc(value) {
    return String(value).replace(/[&<>"']/g, function (character) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[character];
    });
  }
  function notebookUrl(id, ticker) {
    return '?view=notebooks&module=' + id + (ticker ? '&ticker=' + encodeURIComponent(ticker) : '');
  }
  function recordUrl(view, ticker) {
    if (view === 'company') return ticker ? '?view=company&ticker=' + encodeURIComponent(ticker) : '?view=discover';
    if (view === 'documents') return '?view=documents' + (ticker ? '&ticker=' + encodeURIComponent(ticker) : '');
    if (view === 'snapshots') return '?view=pipeline&kind=DAILY_SNAPSHOTS';
    if (view === 'models-extract') return '?view=models&operation=extract';
    if (view === 'models-embed') return '?view=models&operation=embed';
    return '?view=' + view;
  }
  function link(url, label, attributes) {
    return '<a data-notebook-link href="' + esc(url) + '"' + (attributes || '') + '>' + esc(label) + '</a>';
  }
  function render(module, ticker) {
    ticker = ticker || '';
    var index = chapters.findIndex(function (chapter) { return chapter.id === module; });
    var unknown = !!module && index < 0;
    if (index < 0) index = 0;
    var chapter = chapters[index];
    var contents = '<nav class="notebook-contents" aria-label="Process notebooks"><p class="eyebrow">The full process</p><ol>' +
      chapters.map(function (item, position) {
        return '<li>' + link(notebookUrl(item.id, ticker), (position + 1) + '. ' + item.title,
          position === index ? ' aria-current="step"' : '') + '</li>';
      }).join('') + '</ol><p class="muted small">News → facts → grouped catalysts → scores → history</p></nav>';
    var body = '<article class="notebook-page" aria-label="' + esc(chapter.title) + ' notebook">' +
      (unknown ? '<p role="status">Notebook not found. Showing the first module; choose a step from the contents.</p>' : '') +
      '<header><p class="eyebrow">Notebook ' + (index + 1) + ' / 6</p><h2>' + esc(chapter.title) + '</h2><p class="notebook-purpose">' + esc(chapter.subtitle) + '</p></header>' +
      '<section class="notebook-cell"><h3><span>01</span> What goes in</h3><p>' + esc(chapter.input) + '</p></section>' +
      '<section class="notebook-cell"><h3><span>02</span> What happens</h3><p>' + esc(chapter.process) + '</p></section>' +
      '<section class="notebook-cell"><h3><span>03</span> What comes out</h3><p>' + esc(chapter.output) + '</p></section>' +
      '<section class="notebook-example"><h3>Illustrative example · ExampleCo</h3><p class="muted small">Fictional walkthrough, not stored data. The same company is followed in all six notebooks.</p>' +
      '<p>' + esc(chapter.example) + '</p><div class="notebook-flow"><span>' + esc(chapter.before) + '</span><span aria-hidden="true">→</span><strong>' + esc(chapter.after) + '</strong></div></section>' +
      '<section class="notebook-cell"><h3>If something is missing</h3><p>' + esc(chapter.missing) + '</p>' +
      (chapter.note ? '<p class="notebook-note">' + esc(chapter.note) + '</p>' : '') + '</section>' +
      '<section class="notebook-inspect"><h3>Inspect your records</h3><p>' + esc(chapter.inspect) + '</p><p class="muted small">' +
      (ticker ? 'Company inspection links use ' + esc(ticker) + '. Other lists keep their stated scope.' : 'Choose a known ticker above to scope company and document links, or browse all records.') + '</p>' +
      '<div class="notebook-links">' + chapter.links.map(function (item) { return link(recordUrl(item[0], ticker), item[1]); }).join('') + '</div>' +
      '<p class="muted small">Links open stored-record views; access keys may be required. This notebook does not run this stage. The full pipeline is started from Pipeline and may incur provider and model charges.</p></section>' +
      '<footer class="notebook-next"><p>' + esc(chapter.next) + '</p><div class="notebook-links">' +
      (index > 0 ? link(notebookUrl(chapters[index - 1].id, ticker), '← ' + chapters[index - 1].title) : '') +
      (index < chapters.length - 1 ? link(notebookUrl(chapters[index + 1].id, ticker), 'Next: ' + chapters[index + 1].title + ' →') : link('?view=overview', 'Open Overview →')) + '</div></footer></article>';
    return { title: (index + 1) + '. ' + chapter.title, html: contents + body };
  }
  window.CatalystNotebooks = Object.freeze({ render: render });
})();
