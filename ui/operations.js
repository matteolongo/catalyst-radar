(function () {
  'use strict';

  var POLL_MS = 30000;
  var POLLED = ['overview', 'pipeline', 'documents', 'models'];
  var OPERATIONAL = ['overview', 'pipeline', 'documents', 'models', 'settings'];
  var ACCESS_STATUS_IDS = ['overviewStatus', 'pipelineStatus', 'documentStatus', 'modelStatus'];
  var DOCUMENT_STATES = ['PENDING', 'PROCESSING', 'COMPLETED', 'SKIPPED', 'RETRYABLE_ERROR', 'TERMINAL_ERROR', 'UNRESOLVED', 'NOT_TRACKED'];
  var DOCUMENT_TABS = ['overview', 'attempts', 'models', 'events', 'source'];
  var MODEL_PROVIDERS = ['openai'];
  var MODEL_OPERATIONS = ['extract', 'embed'];
  var PIPELINE_KINDS = ['PIPELINE', 'DAILY_SNAPSHOTS'];
  var OPERATION_STATUSES = ['RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED', 'CANCELLED', 'INTERRUPTED'];
  var INGESTION_PROVIDERS = ['polygon', 'finnhub'];
  var INGESTION_STATUSES = ['RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED'];
  var SIGNAL_LABELS = {
    DUE_DOCUMENTS: 'Documents due',
    TERMINAL_DOCUMENTS: 'Documents with terminal failures',
    PROCESSING_STALLED: 'Processing appears stalled',
    AUTHENTICATION_FAILURE: 'Provider authentication failed',
    INGESTION_OVERDUE: 'Ingestion is overdue',
    SNAPSHOTS_OVERDUE: 'Daily snapshots are overdue'
  };

  function create(options) {
    var api = options.api;
    var credentials = options.credentials;
    var format = options.format || {};
    var screen = 'overview';
    var params = new URLSearchParams();
    var sequence = 0;
    var shown = false;
    var pending = null;
    var timer = null;
    var disposed = false;
    var requests = new Set();
    var overviewLoaded = false;
    var lastOverviewAt = null;
    var configLoaded = false;
    var lastConfigAt = null;
    var healthLoaded = false;
    var documentFilters = { q: '', provider: '', ticker: '', statuses: [], from: '', to: '', dueOnly: false, runId: '', ingestionRunId: '' };
    var documentItems = [];
    var documentCursor = null;
    var documentGeneratedAt = null;
    var documentPageLoaded = false;
    var documentPageLoading = false;
    var documentPageError = null;
    var documentRouteValid = true;
    var selectedDocumentId = null;
    var selectedDocumentDetail = null;
    var selectedDocumentLoaded = false;
    var selectedDocumentLoading = false;
    var selectedDocumentTab = 'overview';
    var selectedDocumentOpener = null;
    var selectedAttemptId = null;
    var documentTabCache = {};
    var modelRange = '24h';
    var modelFilters = { provider: '', operation: '', model: '', success: '', documentId: '', attemptId: '', runId: '' };
    var modelSummary = null;
    var modelWindow = null;
    var modelItems = [];
    var modelCursor = null;
    var modelSummaryLoaded = false;
    var modelSummaryError = null;
    var lastModelSummaryAt = null;
    var modelPageLoaded = false;
    var modelSummaryLoading = false;
    var modelPageLoading = false;
    var modelPageError = null;
    var modelRouteValid = true;
    var modelGeneration = 0;
    var selectedModelCallId = null;
    var selectedModelCall = null;
    var selectedModelLoaded = false;
    var selectedModelLoading = false;
    var modelDetailSequence = 0;
    var modelDetailOpener = null;
    var pipelineRange = '24h';
    var pipelineKind = 'PIPELINE';
    var pipelineRunStatus = '';
    var ingestionProvider = '';
    var ingestionStatus = '';
    var pipelineRouteValid = true;
    var pipelineOutcomeText = null;
    var lastPipelineAt = null;
    var pipelineGeneration = 0;
    var pipelineRuns = [];
    var knownActivePipelineIds = new Set();
    var pipelineWindow = null;
    var pipelineRunCursor = null;
    var pipelineRunsLoaded = false;
    var pipelineRunsLoading = false;
    var pipelineRunError = null;
    var pipelineGeneratedAt = null;
    var ingestionItems = [];
    var ingestionCursor = null;
    var ingestionLoaded = false;
    var ingestionLoading = false;
    var ingestionError = null;
    var ingestionGeneratedAt = null;
    var selectedRunId = null;
    var selectedRunDetail = null;
    var selectedRunLoaded = false;
    var selectedRunLoading = false;
    var selectedRunError = null;
    var runIngestionItems = [];
    var runIngestionCursor = null;
    var runIngestionLoaded = false;
    var runIngestionLoading = false;
    var runIngestionError = null;
    var runIngestionSequence = 0;
    var runIssues = [];
    var runIssueCursor = null;
    var runIssuesLoaded = false;
    var runIssuesLoading = false;
    var runIssuesError = null;
    var pipelineDetailSequence = 0;
    var runIssueSequence = 0;
    var selectedIngestionRunId = null;
    var selectedIngestionRun = null;
    var selectedIngestionLoaded = false;
    var selectedIngestionLoading = false;
    var selectedIngestionError = null;
    var ingestionDetailSequence = 0;
    var snapshotScheduleEnabled = null;
    var snapshotScheduleLoaded = false;
    var snapshotScheduleLoading = false;
    var pendingPost = false;

    function el(id) { return document.getElementById(id); }
    function esc(value) {
      if (format.escape) return format.escape(value);
      return String(value == null ? '' : value).replace(/[&<>"']/g, function (ch) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
      });
    }
    function time(value) { return value ? (format.time ? format.time(value) : String(value)) : 'Unknown'; }
    function integer(value) { return value == null ? 'Unknown' : (format.integer ? format.integer(value) : String(value)); }
    function cost(value, known, total) {
      return window.CatalystOperationsModel.formatKnownCost(value, known, total);
    }
    function current(revision, requestSequence, expectedScreen) {
      return !disposed && credentials().revision === revision && sequence === requestSequence &&
        (!expectedScreen || screen === expectedScreen);
    }
    function invalidate() {
      sequence++;
      requests.forEach(function (controller) { controller.abort(); });
      requests.clear();
      pending = null;
      documentPageLoading = false;
      selectedDocumentLoading = false;
      Object.keys(documentTabCache).forEach(function (tab) { documentTabCache[tab].loading = false; });
      modelSummaryLoading = false;
      modelPageLoading = false;
      selectedModelLoading = false;
      pipelineRunsLoading = false;
      ingestionLoading = false;
      selectedRunLoading = false;
      runIngestionLoading = false;
      runIssuesLoading = false;
      selectedIngestionLoading = false;
      snapshotScheduleLoading = false;
    }
    function read(path, revision, requestSequence, expectedScreen) {
      var controller = new AbortController();
      requests.add(controller);
      return api(path, { signal: controller.signal }).then(function (data) {
        return current(revision, requestSequence, expectedScreen) ? { current: true, data: data } : { current: false };
      }).finally(function () { requests.delete(controller); });
    }
    function healthStatus(available) {
      el('health').textContent = available ? 'API available' : 'API unavailable';
      el('health').className = 'pill ' + (available ? 'ok' : 'bad');
    }
    function loadHealth(revision, requestSequence, expectedScreen) {
      return read('/actuator/health', revision, requestSequence, expectedScreen).then(function (result) {
        if (result.current) {
          healthStatus(result.data && result.data.status === 'UP');
          healthLoaded = true;
        }
      }).catch(function (error) {
        if (current(revision, requestSequence, expectedScreen) && error.name !== 'AbortError') {
          healthStatus(false);
          healthLoaded = true;
        }
      });
    }
    function clearOverview() {
      overviewLoaded = false;
      lastOverviewAt = null;
      el('overviewMetrics').innerHTML =
        '<article class="metric"><h2>Last successful ingestion</h2><p class="metric-value">—</p></article>' +
        '<article class="metric"><h2>Waiting documents</h2><p class="metric-value">—</p></article>' +
        '<article class="metric"><h2>Terminal documents</h2><p class="metric-value">—</p></article>' +
        '<article class="metric"><h2>Estimated recorded model cost</h2><p class="metric-value">—</p></article>';
      el('overviewActivity').textContent = 'Waiting for an authorized operational read.';
      el('overviewSignals').textContent = 'Waiting for an authorized operational read.';
      el('overviewDependencies').textContent = 'Waiting for an authorized operational read.';
    }
    function clearConfig() {
      configLoaded = false;
      lastConfigAt = null;
      el('settingsConfig').textContent = 'Unknown / unavailable';
    }
    function accessRequired() {
      var link = 'Admin access required. <a href="?view=settings" data-settings-access>Open Settings to add an admin key</a>.';
      if (credentials().hasAdmin) invalidate();
      if (screen === 'overview') {
        clearOverview();
        el('overviewStatus').innerHTML = link;
      } else if (screen === 'settings') {
        clearConfig();
        el('settingsStatus').textContent = 'Admin access required to read server configuration.';
        el('accessStatus').textContent = 'Admin access required. Keys stay in this tab’s session storage.';
      } else {
        var statusId = { pipeline: 'pipelineStatus', documents: 'documentStatus', models: 'modelStatus' }[screen];
        if (statusId) el(statusId).innerHTML = link;
        if (screen === 'pipeline') {
          clearPipelineData();
          toggleHidden('runRows', true);
          toggleHidden('loadRuns', true);
          toggleHidden('runDetail', true);
          toggleHidden('ingestionRows', true);
          toggleHidden('loadIngestion', true);
          toggleHidden('ingestionDetail', true);
          el('runNow').disabled = true;
        } else if (screen === 'documents') {
          clearDocumentData();
          toggleHidden('documentFilters', true);
          toggleHidden('documentRows', true);
          toggleHidden('loadDocuments', true);
          toggleHidden('documentDetail', true);
        } else if (screen === 'models') {
          modelGeneration++;
          modelDetailSequence++;
          clearModelData();
          toggleHidden('modelFilters', true);
          toggleHidden('modelRows', true);
          toggleHidden('loadModels', true);
          toggleHidden('modelDetail', true);
        }
      }
      el('lastRefresh').textContent = 'No successful refresh yet';
      return false;
    }
    function metric(title, value, context, link) {
      var linkHtml = link ? ' · <a href="' + esc(link.route) + '" data-local-route="' + esc(link.route) + '">' + esc(link.label) + '</a>' : '';
      return '<article class="metric"><h2>' + esc(title) + '</h2><p class="metric-value">' + esc(value) +
        '</p><p class="muted small">' + esc(context) + linkHtml + '</p></article>';
    }
    function freshness(value) {
      if (!value) return 'Unknown';
      if (value.status === 'NEVER') return 'Never recorded';
      if (value.status === 'OFF') return 'Disabled';
      if (value.status === 'UNKNOWN') return 'Unknown';
      return value.status;
    }
    function renderOverview(data) {
      var q = data.queue || {};
      var a = data.activity || {};
      var m = data.models || {};
      var i = data.ingestionFreshness || {};
      var s = data.snapshotFreshness || {};
      var waiting = q.waiting == null ? Number(q.pending || 0) + Number(q.retrying || 0) : q.waiting;
      var costCoverage = integer(m.costKnownCalls) + '/' + integer(m.calls) + ' calls with recorded cost';
      var captured = data.historyStartedAt ? time(data.historyStartedAt) + ' UTC' : 'start unavailable';
      el('overviewMetrics').innerHTML = [
        metric('Last successful ingestion', i.lastSuccessAt ? time(i.lastSuccessAt) : freshness(i),
          'Expected cadence: ' + integer(i.expectedIntervalSeconds) + ' seconds · freshness: ' + freshness(i)),
        metric('Waiting documents', integer(waiting), integer(q.pending) + ' pending · ' + integer(q.retrying) +
          ' retrying · ' + integer(q.due) + ' due across the current queue', {
          label: 'Inspect waiting documents', route: '?view=documents&status=PENDING&status=RETRYABLE_ERROR'
        }),
        metric('Terminal documents', integer(q.terminal), 'Current queue scope', {
          label: 'Unresolved: ' + integer(q.unresolved), route: '?view=documents&status=UNRESOLVED'
        }),
        metric('Estimated recorded model cost', cost(m.estimatedCostUsd, m.costKnownCalls, m.calls), costCoverage)
      ].join('');
      var recalculation = a.recalculationHistoryAvailable
        ? integer(a.successfulRecalculations) + ' successful recalculations · ' + integer(a.recalculationFailures) + ' failed'
        : 'History capture not available yet';
      el('overviewActivity').innerHTML = '<dl class="activity-list">' +
        '<div><dt>New source documents</dt><dd>' + esc(integer(a.documentsNew)) + ' documents</dd></div>' +
        '<div><dt>Inserted event reports</dt><dd>' + esc(integer(a.eventsInserted)) + ' reports</dd></div>' +
        '<div><dt>New canonical clusters</dt><dd>' + esc(integer(a.clustersCreated)) + ' clusters</dd></div>' +
        '<div><dt>Recalculations</dt><dd>' + esc(recalculation) + '</dd></div></dl>' +
        '<p class="muted small">Recorded complete cycles only · captured since ' + esc(captured) + '.</p>' +
        '<p class="muted small">Daily snapshot freshness: ' + esc(freshness(s)) + '.</p>';
      var signals = Array.isArray(data.signals) ? data.signals : [];
      el('overviewSignals').innerHTML = signals.length ? '<ul class="signal-list">' + signals.map(function (signal) {
        var label = SIGNAL_LABELS[signal.code] || 'Operational signal';
        var route = window.CatalystOperationsModel.signalRoute(signal);
        var title = esc(label) + (signal.count == null ? '' : ' · ' + esc(integer(signal.count)));
        var link = route ? '<a href="' + esc(route) + '" data-local-route="' + esc(route) + '">' + title + '</a>' : title;
        var severityClass = signal.severity === 'ERROR' ? 'bad' : (signal.severity === 'WARNING' ? 'warn' : 'info');
        return '<li><span class="pill ' + severityClass + '">' +
          esc(signal.severity || 'INFO') + '</span> ' + link +
          (signal.provider ? ' · ' + esc(signal.provider) : '') + '</li>';
      }).join('') + '</ul>' : '<p class="muted">No active signals.</p>';
      var dependencies = Array.isArray(data.dependencies) ? data.dependencies : [];
      el('overviewDependencies').innerHTML = dependencies.length
        ? '<ul class="dependency-list">' + dependencies.map(function (item) {
          return '<li><strong>' + esc(item.provider) + '</strong> · ' + esc(item.status) +
            ' · ' + (item.configured ? 'configured' : 'not configured') +
            ' · observed since ' + esc(item.observedSince ? time(item.observedSince) : 'Unknown') +
            ' · last observed ' + esc(item.lastObservedAt ? time(item.lastObservedAt) : 'Unknown') +
            (item.lastSuccessAt ? ' · last success ' + esc(time(item.lastSuccessAt)) : '') +
            (item.lastErrorCode ? ' · ' + esc(item.lastErrorCode) : '') + '</li>';
        }).join('') + '</ul>' : '<p class="muted">No dependency observations recorded.</p>';
    }
    function renderConfig(config) {
      var v = config.versions || {};
      var providers = (config.providers || []).map(function (item) {
        return '<li><strong>' + esc(item.name) + '</strong> · ' + (item.configured ? 'configured' : 'not configured') + '</li>';
      }).join('');
      el('settingsConfig').innerHTML = '<dl class="config-grid">' +
        '<div><dt>Ingestion</dt><dd>' + (config.ingestion && config.ingestion.enabled ? 'Enabled' : 'Disabled') + ' · every ' +
          esc(integer(config.ingestion && config.ingestion.intervalSeconds)) + ' seconds</dd></div>' +
        '<div><dt>Daily snapshots</dt><dd>' + (config.snapshots && config.snapshots.enabled ? 'Enabled' : 'Disabled') + ' · every ' +
          esc(integer(config.snapshots && config.snapshots.intervalSeconds)) + ' seconds</dd></div>' +
        '<div><dt>News providers</dt><dd>Primary ' + esc(config.primaryNewsProvider) + ' · fallback ' + esc(config.fallbackNewsProvider) + '</dd></div>' +
        '<div><dt>Pipeline limits</dt><dd>Batch ' + esc(integer(config.pipelineBatchSize)) + ' · ' +
          esc(integer(config.pipelineMaxAttempts)) + ' attempts · retry delay ' + esc(integer(config.pipelineRetryDelaySeconds)) + ' seconds</dd></div>' +
        '<div><dt>Public API authentication</dt><dd>' + (config.publicApiAuthEnabled ? 'Required' : 'Not required') + '</dd></div>' +
        '<div><dt>Deployment</dt><dd>' + (config.singleInstance ? 'Single application instance' : 'Multiple instances') + '</dd></div>' +
        '<div><dt>Artifact versions</dt><dd>Score ' + esc(v.score) + ' · taxonomy ' + esc(v.taxonomy) + ' · prompt ' +
          esc(v.prompt) + ' · extractor ' + esc(v.extractor) + '</dd></div>' +
        '<div><dt>Models</dt><dd>Extraction ' + esc(v.extractionModel) + ' · embedding ' + esc(v.embeddingModel) + '</dd></div>' +
        '</dl><h4>Provider configuration</h4><ul class="provider-list">' + providers + '</ul>';
    }
    function toggleHidden(id, hidden) {
      el(id).classList.toggle('hidden', hidden);
      if (hidden) el(id).setAttribute('hidden', '');
      else el(id).removeAttribute('hidden');
    }
    function validInstant(value) {
      if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)) return false;
      var parsed = new Date(value);
      return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 19) === value.slice(0, 19);
    }
    function validUuid(value) {
      return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
    }
    function normalizeDocumentFilters(filters) {
      var normalized = Object.assign({}, filters, {
        q: String(filters.q || '').trim(),
        provider: String(filters.provider || '').trim().toLowerCase(),
        ticker: String(filters.ticker || '').trim().toUpperCase(),
        statuses: Array.from(new Set(filters.statuses || [])),
      });
      if (normalized.q.length > 120 || /[\u0000-\u001f\u007f]/.test(normalized.q)) return { error: 'Title search must contain 1–120 printable characters.' };
      if (normalized.provider && !['polygon', 'finnhub'].includes(normalized.provider)) return { error: 'Choose Polygon or Finnhub as the provider.' };
      if (normalized.ticker && !/^[A-Z0-9][A-Z0-9.-]{0,14}$/.test(normalized.ticker)) return { error: 'Enter a valid ticker.' };
      if (normalized.statuses.some(function (state) { return !DOCUMENT_STATES.includes(state); })) return { error: 'Choose valid document states.' };
      if (normalized.from && !validInstant(normalized.from)) return { error: 'Captured-from must be an ISO UTC timestamp.' };
      if (normalized.to && !validInstant(normalized.to)) return { error: 'Captured-through must be an ISO UTC timestamp.' };
      if (normalized.from && normalized.to && Date.parse(normalized.from) >= Date.parse(normalized.to)) return { error: 'Captured-from must be earlier than the exclusive captured-through boundary.' };
      if (normalized.runId && !validUuid(normalized.runId)) return { error: 'The operation run ID is invalid.' };
      if (normalized.ingestionRunId && !validUuid(normalized.ingestionRunId)) return { error: 'The ingestion run ID is invalid.' };
      return { filters: normalized };
    }
    function documentFilterKey(filters) {
      return JSON.stringify({ q: filters.q, provider: filters.provider, ticker: filters.ticker,
        statuses: filters.statuses.slice().sort(), from: filters.from, to: filters.to, dueOnly: filters.dueOnly,
        runId: filters.runId, ingestionRunId: filters.ingestionRunId });
    }
    function documentFiltersFromRoute(route, preserveWhenOmitted) {
      var filterKeys = ['q', 'provider', 'ticker', 'status', 'from', 'to', 'dueOnly', 'runId', 'ingestionRunId'];
      var hasRouteFilters = filterKeys.some(function (key) { return route.has(key); });
      var next = preserveWhenOmitted && !hasRouteFilters
        ? Object.assign({}, documentFilters, { statuses: documentFilters.statuses.slice() })
        : { q: '', provider: '', ticker: '', statuses: [], from: '', to: '', dueOnly: false, runId: '', ingestionRunId: '' };
      if (route.has('q')) next.q = route.get('q');
      if (route.has('provider')) next.provider = route.get('provider');
      if (route.has('ticker')) next.ticker = route.get('ticker');
      if (route.has('status')) next.statuses = route.getAll('status');
      if (route.has('from')) next.from = route.get('from');
      if (route.has('to')) next.to = route.get('to');
      if (route.has('dueOnly')) {
        var dueOnly = route.get('dueOnly');
        if (dueOnly !== 'true' && dueOnly !== 'false') return { error: 'Due-only must be true or false.' };
        next.dueOnly = dueOnly === 'true';
      }
      if (route.has('runId')) next.runId = route.get('runId');
      if (route.has('ingestionRunId')) next.ingestionRunId = route.get('ingestionRunId');
      return normalizeDocumentFilters(next);
    }
    function documentDateInput(value, through) {
      if (!value || !validInstant(value)) return '';
      var date = new Date(value);
      if (through) date.setUTCDate(date.getUTCDate() - 1);
      return date.toISOString().slice(0, 10);
    }
    function renderDocumentFilters() {
      el('documentTitleFilter').value = documentFilters.q;
      el('documentProvider').value = documentFilters.provider;
      el('documentTicker').value = documentFilters.ticker;
      el('documentFrom').value = documentDateInput(documentFilters.from, false);
      el('documentTo').value = documentDateInput(documentFilters.to, true);
      el('documentDueOnly').checked = documentFilters.dueOnly;
      Array.from(el('documentStates').options || []).forEach(function (option) {
        option.selected = documentFilters.statuses.includes(option.value);
      });
    }
    function documentSearch(filters, selection) {
      var route = new URLSearchParams();
      route.set('view', 'documents');
      if (filters.q) route.set('q', filters.q);
      if (filters.provider) route.set('provider', filters.provider);
      if (filters.ticker) route.set('ticker', filters.ticker);
      filters.statuses.forEach(function (state) { route.append('status', state); });
      if (filters.from) route.set('from', filters.from);
      if (filters.to) route.set('to', filters.to);
      if (filters.dueOnly) route.set('dueOnly', 'true');
      if (filters.runId) route.set('runId', filters.runId);
      if (filters.ingestionRunId) route.set('ingestionRunId', filters.ingestionRunId);
      if (selection && selection.id) route.set('documentId', selection.id);
      if (selection && selection.tab && selection.tab !== 'overview') route.set('documentTab', selection.tab);
      if (selection && selection.attemptId) route.set('attemptId', selection.attemptId);
      return '?' + route.toString();
    }
    function currentLocalSearch() {
      var route = new URLSearchParams(params);
      route.set('view', screen);
      return '?' + route.toString();
    }
    function documentRequestPath(cursor) {
      var query = window.CatalystOperationsModel.documentQuery(Object.assign({}, documentFilters, { limit: 25, cursor: cursor || null }));
      return '/internal/operations/documents?' + query.toString();
    }
    function documentStateLabel(state) {
      return ({ PENDING: 'Pending', PROCESSING: 'Processing', COMPLETED: 'Completed', SKIPPED: 'Skipped',
        RETRYABLE_ERROR: 'Retryable error', TERMINAL_ERROR: 'Terminal error', UNRESOLVED: 'Unresolved', NOT_TRACKED: 'Not tracked' })[state] || 'Unknown';
    }
    function documentStateClass(state) {
      if (state === 'COMPLETED') return 'ok';
      if (state === 'TERMINAL_ERROR') return 'bad';
      if (state === 'RETRYABLE_ERROR' || state === 'PENDING' || state === 'UNRESOLVED') return 'warn';
      return 'info';
    }
    function clearDocumentData() {
      documentItems = [];
      documentCursor = null;
      documentGeneratedAt = null;
      documentPageLoaded = false;
      documentPageLoading = false;
      documentPageError = null;
      selectedDocumentDetail = null;
      selectedDocumentLoaded = false;
      selectedDocumentLoading = false;
      documentTabCache = {};
      el('documentRows').innerHTML = '';
      clearDocumentDetailViews();
    }
    function renderDocumentRows() {
      var initialLoading = documentPageLoading && !documentPageLoaded && !documentItems.length;
      el('documentRows').innerHTML = initialLoading ? '<p class="muted">Loading captured documents…</p>' : (documentItems.length ? documentItems.map(function (item) {
        var id = esc(item.id);
        var tickers = Array.isArray(item.tickers) && item.tickers.length ? item.tickers.join(', ') : 'No supported company linked';
        if (item.tickersTruncated) tickers += ' · first 100 tickers shown';
        var attempts = item.attemptCount == null ? 'Attempt count unknown' : 'Attempt ' + item.attemptCount;
        return '<button type="button" class="document-row" data-document-id="' + id + '"' +
          (selectedDocumentId === item.id ? ' aria-current="true"' : '') + '>' +
          '<span class="document-row-title">' + esc(item.title || 'Untitled document') + '</span>' +
          '<span class="document-row-meta"><span>' + esc(item.provider) + '</span><span>' + esc(tickers) + '</span>' +
          '<span>' + esc(time(item.discoveredAt)) + ' UTC</span><span class="pill ' + documentStateClass(item.state) + '">' + esc(documentStateLabel(item.state)) + '</span>' +
          '<span>' + esc(attempts) + '</span>' + (item.nextAttemptAt ? '<span>Next retry ' + esc(time(item.nextAttemptAt)) + ' UTC</span>' : '') +
          '</span></button>';
      }).join('') : '<p class="muted">No captured documents match these filters.</p>');
      var loaded = documentItems.length + ' loaded document' + (documentItems.length === 1 ? '' : 's');
      var status = documentPageError
        ? (documentItems.length ? loaded + ' · ' + documentPageError : documentPageError)
        : (initialLoading ? 'Loading captured documents…' : (loaded + (documentGeneratedAt ? ' · updated ' + time(documentGeneratedAt) + ' UTC' : '')));
      el('documentStatus').textContent = status;
      var showLoad = !documentPageLoading && (!!documentCursor || !documentPageLoaded);
      toggleHidden('loadDocuments', !showLoad);
      el('loadDocuments').disabled = documentPageLoading || (!documentCursor && documentPageLoaded);
      el('loadDocuments').textContent = documentPageLoaded ? (documentPageError && !documentCursor ? 'Retry documents' : 'Load more') : 'Load documents';
    }
    function documentTime(label, value) {
      return '<div><dt>' + esc(label) + '</dt><dd>' + (value ? '<time datetime="' + esc(value) + '">' + esc(time(value)) + ' UTC</time>' : 'Unknown') + '</dd></div>';
    }
    function timeMark(value) {
      return value ? '<time datetime="' + esc(value) + '">' + esc(time(value)) + ' UTC</time>' : 'Unknown';
    }
    function safeSourceUrl(value) {
      if (!value) return null;
      try {
        var url = new URL(value);
        return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password ? url.href : null;
      } catch (error) { return null; }
    }
    function externalSourceLink(value, label) {
      var url = safeSourceUrl(value);
      return url ? '<a href="' + esc(url) + '" target="_blank" rel="noopener noreferrer">' + esc(label) + '</a>' : '<span class="muted">Source link unavailable</span>';
    }
    function renderDocumentOverview(detail) {
      var item = detail.document || {};
      var companies = (detail.companies || []).map(function (company) {
        var route = '?view=company&ticker=' + encodeURIComponent(company.ticker);
        return '<div class="document-record"><strong><a href="' + esc(route) + '" data-document-company="' + esc(company.ticker) + '">' +
          esc(company.ticker) + ' · ' + esc(company.name) + '</a></strong><p>Latest saved snapshot: ' +
          esc(company.latestSnapshotAsOf ? time(company.latestSnapshotAsOf) + ' UTC' : 'Unknown') +
          ' · recorded ' + esc(company.latestSnapshotCreatedAt ? time(company.latestSnapshotCreatedAt) + ' UTC' : 'Unknown') + '</p></div>';
      }).join('');
      var ingestion = item.firstIngestionRunId
        ? '<a href="?view=pipeline&ingestionRunId=' + encodeURIComponent(item.firstIngestionRunId) + '" data-ingestion-run-id="' + esc(item.firstIngestionRunId) + '">Open origin ingestion run</a>'
        : '<span class="muted">No origin ingestion run recorded</span>';
      var error = item.lastErrorMessage ? '<h4>Latest safe error reason</h4><p class="panel-error">' + esc(item.lastErrorMessage) + '</p>' : '';
      var unrecorded = Number(detail.unrecordedAttemptCount || 0);
      el('documentOverview').innerHTML = '<dl>' +
        '<div><dt>Current state</dt><dd><span class="pill ' + documentStateClass(item.state) + '">' + esc(documentStateLabel(item.state)) + '</span></dd></div>' +
        '<div><dt>Latest processing attempt count</dt><dd>' + esc(integer(item.attemptCount)) + ' / ' + esc(integer(detail.maxAttempts)) + '</dd></div>' +
        '<div><dt>Next retry</dt><dd>' + esc(item.nextAttemptAt ? time(item.nextAttemptAt) + ' UTC' : 'Not scheduled') + '</dd></div>' +
        documentTime('Published', item.publishedAt) + documentTime('Discovered', item.discoveredAt) +
        documentTime('Stored', item.createdAt) + documentTime('Last processing update', item.updatedAt) +
        documentTime('Document processing completed', detail.completedAt) +
        '<div><dt>Recorded model calls</dt><dd>' + esc(integer(detail.modelCallsRecorded)) + '</dd></div>' +
        '<div><dt>Event reports / canonical clusters</dt><dd>' + esc(integer(detail.eventReports)) + ' / ' + esc(integer(detail.canonicalClusters)) + '</dd></div>' +
        '</dl><h4>Source</h4><p>' + externalSourceLink(detail.canonicalUrl, 'Open original source') +
        (detail.providerDocumentId ? ' · Provider document ID: ' + esc(detail.providerDocumentId) : '') + '</p>' +
        '<h4>Origin</h4><p>' + ingestion + '</p>' + error +
        '<h4>Supported companies (' + esc(integer(detail.companiesTotal)) + (detail.companiesTruncated ? '+)' : ')') + '</h4>' +
        (companies || '<p class="muted">No supported company association is recorded.</p>') +
        (detail.companiesTruncated ? '<p class="muted small">Showing the first 100 supported companies.</p>' : '') +
        (unrecorded > 0 ? '<p class="muted small">' + esc(String(unrecorded)) + ' processing attempt(s) have no individual captured ledger row.</p>' : '');
    }
    function setDocumentPane() {
      var hasSelection = !!selectedDocumentId;
      toggleHidden('documentDetail', !hasSelection);
      DOCUMENT_TABS.forEach(function (tab) {
        el('documentTab' + tab[0].toUpperCase() + tab.slice(1)).setAttribute('aria-selected', String(tab === selectedDocumentTab));
        toggleHidden('document' + tab[0].toUpperCase() + tab.slice(1), !hasSelection || tab !== selectedDocumentTab);
      });
      if (!hasSelection) {
        el('documentDetailTitle').textContent = '';
        el('documentDetailStatus').textContent = '';
      }
    }
    function renderDocumentDetail() {
      if (!selectedDocumentId || !selectedDocumentDetail) return;
      var detail = selectedDocumentDetail;
      var item = detail.document || {};
      el('documentDetailTitle').textContent = item.title || 'Untitled document';
      el('documentDetailStatus').textContent = 'Captured ' + time(item.discoveredAt) + ' UTC · ' + documentStateLabel(item.state);
      renderDocumentOverview(detail);
      renderSelectedDocumentTab();
    }
    function tabState(tab) {
      if (!documentTabCache[tab]) documentTabCache[tab] = { loaded: false, loading: false, items: [], cursor: null, error: null, body: null };
      return documentTabCache[tab];
    }
    function modelCallLink(id) {
      var route = '?view=models&modelRunId=' + encodeURIComponent(id);
      return '<a href="' + esc(route) + '" data-model-run-id="' + esc(id) + '">' + esc(id) + '</a>';
    }
    function attemptStatusLabel(status) {
      return ({ RUNNING: 'Running', COMPLETED: 'Completed', SKIPPED: 'Skipped', RETRYABLE_ERROR: 'Retryable error',
        TERMINAL_ERROR: 'Terminal error', INTERRUPTED: 'Interrupted' })[status] || 'Unknown';
    }
    function renderAttempts(state) {
      var detail = selectedDocumentDetail || {};
      var attemptCount = detail.document && detail.document.attemptCount;
      var html = '<p class="muted small">Latest processing attempt count: ' + esc(integer(attemptCount)) + '. ' +
        'Captured ledger rows: ' + esc(integer(detail.capturedAttemptCount)) + '.</p>';
      if (Number(detail.unrecordedAttemptCount || 0) > 0) {
        html += '<p class="muted small">' + esc(integer(detail.unrecordedAttemptCount)) + ' earlier processing attempt(s) have no captured individual record.</p>';
      }
      if (state.error) return html + '<p class="panel-error">' + esc(state.error) + '</p>';
      if (!state.loaded) return html + (state.loading ? '<p class="muted">Loading attempts…</p>' : '<p class="muted">Attempts have not been loaded.</p>');
      if (!state.items.length) {
        html += Number(detail.capturedAttemptCount || 0) === 0
          ? '<p class="muted">No individual attempts recorded.</p>'
          : '<p class="muted">No attempt rows are available on this page.</p>';
      }
      html += state.items.map(function (item) {
        var selected = selectedAttemptId === item.id;
        return '<article class="document-record" data-attempt-id="' + esc(item.id) + '" tabindex="-1"' + (selected ? ' aria-current="true"' : '') + '>' +
          '<strong>Attempt ' + esc(item.number) + ' · ' + esc(attemptStatusLabel(item.status)) + '</strong>' +
          '<p>Started ' + esc(time(item.startedAt)) + ' UTC · duration ' + esc(item.durationMs == null ? 'Unknown' : integer(item.durationMs) + ' ms') +
          (item.nextAttemptAt ? ' · next retry ' + esc(time(item.nextAttemptAt)) + ' UTC' : '') + '</p>' +
          '<p>Events inserted/reused: ' + esc(integer(item.eventsInserted)) + ' / ' + esc(integer(item.eventsReused)) +
          ' · <a href="?view=pipeline&runId=' + encodeURIComponent(item.runId) + '" data-operation-run-id="' + esc(item.runId) + '">Open operation run</a></p>' +
          (item.errorMessage ? '<p class="panel-error">' + esc(item.errorMessage) + '</p>' : '') +
          (item.modelCallIds && item.modelCallIds.length ? '<p>Recorded model calls: ' + item.modelCallIds.map(modelCallLink).join(', ') +
            (item.modelCallsTruncated ? ' · more calls recorded' : '') + '</p>' : '<p>No model call IDs recorded. This is missing provenance; it does not establish whether the provider was invoked.</p>') + '</article>';
      }).join('');
      if (selectedAttemptId && !state.items.some(function (item) { return item.id === selectedAttemptId; })) {
        html += state.cursor
          ? '<p class="muted">Attempt ' + esc(selectedAttemptId) + ': Load older attempts to locate this record.</p>'
          : '<p class="muted">Selected attempt ' + esc(selectedAttemptId) + ' is not in the loaded records.</p>';
      }
      toggleHidden('loadAttempts', !state.cursor);
      el('loadAttempts').disabled = state.loading;
      return html;
    }
    function renderDocumentModels(state) {
      if (state.error) return '<p class="panel-error">' + esc(state.error) + '</p>';
      if (!state.loaded) return '<p class="muted">' + (state.loading ? 'Loading model calls…' : 'Model calls have not been loaded.') + '</p>';
      if (!state.items.length) return '<p class="muted">No explicitly source-linked model calls are recorded. This is missing provenance; it does not establish whether the provider was invoked.</p>';
      return state.items.map(function (item) {
        var usage = (item.inputTokens == null ? 'Unknown' : integer(item.inputTokens)) + ' input · ' +
          (item.outputTokens == null ? 'Unknown' : integer(item.outputTokens)) + ' output tokens';
        var price = item.estimatedCost == null ? 'Unknown cost' : cost(item.estimatedCost, 1, 1);
        return '<article class="document-record"><strong>' + modelCallLink(item.id) + ' · ' + esc(item.provider) + ' ' + esc(item.operation) + '</strong>' +
          '<p>' + esc(item.model) + ' · ' + esc(time(item.createdAt)) + ' UTC · ' + esc(usage) + ' · ' + esc(price) + '</p>' +
          '<p>' + (item.success ? '<span class="pill ok">Recorded call succeeded</span>' : '<span class="pill bad">Recorded call failed</span>') +
          (item.errorMessage ? ' · ' + esc(item.errorMessage) : '') + '</p></article>';
      }).join('');
    }
    function renderDocumentEvents(state) {
      if (state.error) return '<p class="panel-error">' + esc(state.error) + '</p>';
      if (!state.loaded) return '<p class="muted">' + (state.loading ? 'Loading event reports…' : 'Event reports have not been loaded.') + '</p>';
      if (!state.items.length) return '<p class="muted">No stored event reports are linked to this document.</p>';
      var groups = {};
      state.items.forEach(function (item) {
        var key = item.clusterId || 'Unclustered';
        if (!groups[key]) groups[key] = [];
        groups[key].push(item);
      });
      var keys = Object.keys(groups);
      return '<p class="muted small">Showing ' + esc(integer(state.items.length)) + ' loaded reports in ' + esc(integer(keys.length)) +
        ' loaded cluster group(s); a cluster may contain additional reports.</p>' + keys.map(function (clusterId) {
        return '<section class="event-cluster"><h4>Cluster ' + esc(clusterId) + '</h4>' + groups[clusterId].map(function (item) {
          var company = item.ticker ? '<a href="?view=company&ticker=' + encodeURIComponent(item.ticker) + '" data-document-company="' + esc(item.ticker) + '">' +
            esc(item.ticker) + (item.companyName ? ' · ' + esc(item.companyName) : '') + '</a>' : 'No linked company';
          var evidence = (item.evidence || []).map(function (entry) { return '<blockquote>' + esc(entry.quoteOrFact) + '</blockquote>'; }).join('');
          return '<article class="event-report"><strong>' + esc(item.type) + ' · ' + esc(item.family) + ' · ' + esc(item.direction) + '</strong>' +
            '<p>Event ' + timeMark(item.eventTimestamp) + ' · source published ' + timeMark(item.source && item.source.publishedAt) +
            ' · captured ' + timeMark(item.discoveredAt) + '</p><p>' + company + '</p>' + evidence + '</article>';
        }).join('') + '</section>';
      }).join('');
    }
    function renderDocumentSource(state) {
      if (state.error) return '<p class="panel-error">' + esc(state.error) + '</p>';
      if (!state.body) return '<p class="muted">' + (state.loading ? 'Loading source text…' : 'Source text has not been loaded.') + '</p>';
      var body = state.body;
      var item = selectedDocumentDetail && selectedDocumentDetail.document || {};
      var html = '<h4>' + esc(item.title || 'Untitled document') + '</h4><p>' + esc(item.provider || 'Unknown provider') +
        (item.publishedAt ? ' · published ' + esc(time(item.publishedAt)) + ' UTC' : '') + '</p><p>' +
        externalSourceLink(selectedDocumentDetail.canonicalUrl, 'Open original source') + '</p>';
      if (body.truncated) html += '<p class="muted small">Source text truncated to ' + esc(integer(body.text.length)) + ' of ' + esc(integer(body.originalCharacters)) + ' characters.</p>';
      return html + '<pre class="document-source-body">' + esc(body.text) + '</pre>';
    }
    function renderSelectedDocumentTab() {
      if (!selectedDocumentId) return;
      if (selectedDocumentTab === 'overview') {
        if (selectedDocumentDetail) renderDocumentOverview(selectedDocumentDetail);
        return;
      }
      var state = tabState(selectedDocumentTab);
      var id = 'document' + selectedDocumentTab[0].toUpperCase() + selectedDocumentTab.slice(1);
      var html = selectedDocumentTab === 'attempts' ? renderAttempts(state)
        : (selectedDocumentTab === 'models' ? renderDocumentModels(state)
          : (selectedDocumentTab === 'events' ? renderDocumentEvents(state) : renderDocumentSource(state)));
      el(id).innerHTML = html;
      if (selectedDocumentTab === 'attempts') {
        toggleHidden('loadAttempts', !state.cursor);
        el('loadAttempts').disabled = state.loading;
      } else if (selectedDocumentTab === 'models') {
        toggleHidden('loadDocumentModels', !state.cursor);
        el('loadDocumentModels').disabled = state.loading;
      } else if (selectedDocumentTab === 'events') {
        toggleHidden('loadDocumentEvents', !state.cursor);
        el('loadDocumentEvents').disabled = state.loading;
      }
    }
    function loadDocumentPage(appendPage) {
      if (documentPageLoading || (appendPage && !documentCursor) || !credentials().hasAdmin) return Promise.resolve(false);
      var revision = credentials().revision;
      var requestSequence = sequence;
      var cursor = appendPage ? documentCursor : null;
      documentPageLoading = true;
      documentPageError = null;
      renderDocumentRows();
      var path = documentRequestPath(cursor);
      return read(path, revision, requestSequence, 'documents').then(function (result) {
        if (!result.current) return false;
        var incoming = Array.isArray(result.data.items) ? result.data.items : [];
        documentItems = appendPage
          ? window.CatalystOperationsModel.mergePage({ items: documentItems }, { items: incoming }).items
          : incoming.slice();
        documentCursor = result.data.nextCursor || null;
        documentGeneratedAt = result.data.generatedAt || null;
        documentPageLoaded = true;
        documentPageError = null;
        if (documentGeneratedAt) el('lastRefresh').textContent = 'Last loaded ' + time(documentGeneratedAt);
        return true;
      }).catch(function (error) {
        if (!current(revision, requestSequence, 'documents') || error.name === 'AbortError') return false;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        documentPageError = 'Unable to load documents. Retry the read.';
        return false;
      }).finally(function () {
        if (current(revision, requestSequence, 'documents')) {
          documentPageLoading = false;
          renderDocumentRows();
        }
      });
    }
    function loadSelectedDocument(force) {
      if (!selectedDocumentId || !credentials().hasAdmin || selectedDocumentLoading) return Promise.resolve(false);
      if (selectedDocumentLoaded && !force) return Promise.resolve(true);
      var id = selectedDocumentId;
      var revision = credentials().revision;
      var requestSequence = sequence;
      selectedDocumentLoading = true;
      toggleHidden('documentDetail', false);
      if (!selectedDocumentLoaded) {
        el('documentDetailTitle').textContent = 'Loading document…';
        el('documentDetailStatus').textContent = 'Loading document details…';
      } else el('documentDetailStatus').textContent = 'Refreshing document details…';
      return read('/internal/operations/documents/' + encodeURIComponent(id), revision, requestSequence, 'documents').then(function (result) {
        if (!result.current || selectedDocumentId !== id) return false;
        selectedDocumentDetail = result.data;
        selectedDocumentLoaded = true;
        el('documentDetailStatus').textContent = '';
        renderDocumentDetail();
        return true;
      }).catch(function (error) {
        if (!current(revision, requestSequence, 'documents') || error.name === 'AbortError') return false;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        el('documentDetailTitle').textContent = 'Document unavailable';
        el('documentDetailStatus').textContent = error.code === 'DOCUMENT_NOT_FOUND'
          ? 'This captured document was not found.' : 'Unable to load document details. Retry the read.';
        return false;
      }).finally(function () {
        if (current(revision, requestSequence, 'documents') && selectedDocumentId === id) selectedDocumentLoading = false;
      });
    }
    function documentTabPath(tab, cursor) {
      var base = '/internal/operations/documents/' + encodeURIComponent(selectedDocumentId);
      if (tab === 'source') return base + '/body';
      var suffix = tab === 'attempts' ? 'attempts' : (tab === 'models' ? 'model-runs' : 'events');
      var query = new URLSearchParams();
      query.set('limit', '25');
      if (cursor) query.set('cursor', cursor);
      return base + '/' + suffix + '?' + query.toString();
    }
    function loadDocumentTab(force, appendPage) {
      var tab = selectedDocumentTab;
      if (!selectedDocumentId || tab === 'overview' || !credentials().hasAdmin) return Promise.resolve(false);
      var state = tabState(tab);
      if (state.loading || (appendPage && !state.cursor) || (state.loaded && !force && !appendPage)) return Promise.resolve(state.loaded);
      var id = selectedDocumentId;
      var revision = credentials().revision;
      var requestSequence = sequence;
      var cursor = appendPage ? state.cursor : null;
      state.loading = true;
      state.error = null;
      renderSelectedDocumentTab();
      return read(documentTabPath(tab, cursor), revision, requestSequence, 'documents').then(function (result) {
        if (!result.current || selectedDocumentId !== id || selectedDocumentTab !== tab) return false;
        if (tab === 'source') state.body = result.data;
        else {
          var incoming = tab === 'events' ? (result.data.events || []) : (result.data.items || []);
          state.items = appendPage ? window.CatalystOperationsModel.mergePage({ items: state.items }, { items: incoming }).items : incoming.slice();
          state.cursor = result.data.nextCursor || null;
        }
        state.loaded = true;
        state.error = null;
        renderSelectedDocumentTab();
        if (tab === 'attempts' && selectedAttemptId) focusSelectedAttempt(state);
        return true;
      }).catch(function (error) {
        if (!current(revision, requestSequence, 'documents') || selectedDocumentId !== id || error.name === 'AbortError') return false;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        state.error = 'Unable to load this tab. Retry the read.';
        renderSelectedDocumentTab();
        return false;
      }).finally(function () {
        if (current(revision, requestSequence, 'documents') && selectedDocumentId === id) {
          state.loading = false;
          renderSelectedDocumentTab();
        }
      });
    }
    function focusSelectedAttempt(state) {
      var row = Array.from(document.querySelectorAll('[data-attempt-id]')).find(function (item) {
        return item.dataset.attemptId === selectedAttemptId;
      });
      if (row) row.focus();
      else if (state.cursor) el('documentDetailStatus').textContent = 'Attempt ' + selectedAttemptId + ': Load older attempts to locate this record.';
    }
    function clearDocumentDetailViews() {
      ['documentOverview', 'documentAttempts', 'documentModels', 'documentEvents', 'documentSource'].forEach(function (panel) {
        el(panel).innerHTML = '';
      });
      el('documentDetailTitle').textContent = '';
      el('documentDetailStatus').textContent = '';
    }
    function resetSelectedDocument(id) {
      selectedDocumentId = id;
      selectedDocumentDetail = null;
      selectedDocumentLoaded = false;
      selectedDocumentLoading = false;
      documentTabCache = {};
      selectedAttemptId = null;
      clearDocumentDetailViews();
    }
    function resetDocuments(clearPage) {
      documentPageLoading = false;
      documentPageError = null;
      if (clearPage) {
        documentItems = [];
        documentCursor = null;
        documentGeneratedAt = null;
        documentPageLoaded = false;
      }
      resetSelectedDocument(null);
      selectedDocumentOpener = null;
      setDocumentPane();
      renderDocumentRows();
      el('documentStatus').textContent = credentials().hasAdmin ? 'Loading captured documents…' : 'Admin access required.';
    }
    function configureDocuments(route, preserveCurrentFilters) {
      var parsed = documentFiltersFromRoute(route, preserveCurrentFilters);
      if (parsed.error) {
        documentRouteValid = false;
        renderDocumentFilters();
        el('documentStatus').textContent = parsed.error;
        setDocumentPane();
        return false;
      }
      var nextFilters = parsed.filters;
      var filtersChanged = documentFilterKey(nextFilters) !== documentFilterKey(documentFilters);
      documentFilters = nextFilters;
      if (filtersChanged) {
        documentItems = [];
        documentCursor = null;
        documentGeneratedAt = null;
        documentPageLoaded = false;
        documentPageError = null;
      }
      renderDocumentFilters();
      var nextId = route.get('documentId') || null;
      var nextTab = route.get('documentTab') || 'overview';
      var nextAttemptId = route.get('attemptId') || null;
      var invalidIdMessage = nextId && !validUuid(nextId) ? 'Document ID must be a UUID.'
        : (nextAttemptId && !validUuid(nextAttemptId) ? 'Attempt ID must be a UUID.'
          : (nextAttemptId && !nextId ? 'Attempt ID requires a selected document.' : null));
      if (invalidIdMessage) {
        documentRouteValid = false;
        resetSelectedDocument(null);
        selectedDocumentOpener = null;
        renderDocumentFilters();
        setDocumentPane();
        el('documentStatus').textContent = invalidIdMessage;
        return false;
      }
      if (nextAttemptId) nextTab = 'attempts';
      if (!DOCUMENT_TABS.includes(nextTab)) {
        documentRouteValid = false;
        el('documentStatus').textContent = 'Choose a valid document detail tab.';
        setDocumentPane();
        return false;
      }
      if (nextId !== selectedDocumentId) resetSelectedDocument(nextId);
      selectedAttemptId = nextAttemptId;
      selectedDocumentTab = nextTab;
      documentRouteValid = true;
      setDocumentPane();
      if (selectedDocumentId && selectedDocumentDetail) renderDocumentDetail();
      if (selectedDocumentId && !selectedDocumentLoaded) {
        el('documentDetailTitle').textContent = 'Loading document…';
        el('documentDetailStatus').textContent = 'Loading document details…';
      }
      return true;
    }
    function loadDocumentsScreen(force) {
      if (!documentRouteValid) return Promise.resolve();
      if (!credentials().hasAdmin) {
        accessRequired();
        return Promise.resolve();
      }
      toggleHidden('documentFilters', false);
      toggleHidden('documentRows', false);
      if (force) {
        documentPageLoaded = false;
        documentPageError = null;
        if (selectedDocumentId) {
          selectedDocumentLoaded = false;
          var cachedSource = documentTabCache.source;
          documentTabCache = cachedSource && cachedSource.loaded ? { source: cachedSource } : {};
        }
      }
      var tasks = [];
      if (!documentPageLoaded) tasks.push(loadDocumentPage(false));
      if (selectedDocumentId) {
        var detail = (!selectedDocumentLoaded || force) ? loadSelectedDocument(!!force) : Promise.resolve(true);
        tasks.push(detail.then(function (loaded) {
          if (loaded && selectedDocumentTab !== 'overview') {
            var state = tabState(selectedDocumentTab);
            var refreshTab = force && selectedDocumentTab !== 'source';
            return (!state.loaded || refreshTab) ? loadDocumentTab(!!refreshTab, false) : Promise.resolve(true);
          }
          return loaded;
        }));
      }
      return Promise.allSettled(tasks).then(function () {});
    }
    function onDocumentFiltersSubmit(event) {
      event.preventDefault();
      var selected = Array.from(el('documentStates').selectedOptions || []).map(function (option) { return option.value; });
      var fromDate = el('documentFrom').value;
      var throughDate = el('documentTo').value;
      var candidate = {
        q: el('documentTitleFilter').value,
        provider: el('documentProvider').value,
        ticker: el('documentTicker').value,
        statuses: selected,
        from: fromDate ? fromDate + 'T00:00:00.000Z' : '',
        to: throughDate ? window.CatalystOperationsModel.utcThroughDate(throughDate) : '',
        dueOnly: el('documentDueOnly').checked,
        runId: documentFilters.runId,
        ingestionRunId: documentFilters.ingestionRunId,
      };
      var normalized = normalizeDocumentFilters(candidate);
      var nextFilters = normalized.filters || candidate;
      documentFilters = nextFilters;
      documentItems = [];
      documentCursor = null;
      documentGeneratedAt = null;
      documentPageError = normalized.error || null;
      documentPageLoaded = !!normalized.error;
      documentPageLoading = false;
      resetSelectedDocument(null);
      selectedDocumentOpener = null;
      selectedAttemptId = null;
      documentRouteValid = !normalized.error;
      renderDocumentFilters();
      setDocumentPane();
      if (normalized.error) el('documentStatus').textContent = normalized.error;
      options.navigate(documentSearch(nextFilters, null));
    }
    function selectDocument(id, opener) {
      selectedDocumentOpener = opener || null;
      options.navigate(documentSearch(documentFilters, { id: id, tab: 'overview' }));
    }
    function closeSelectedDocument() {
      if (!selectedDocumentId) return;
      var opener = selectedDocumentOpener;
      selectedDocumentOpener = null;
      options.navigate(documentSearch(documentFilters, null));
      if (opener && typeof opener.focus === 'function') opener.focus();
      else el('documentRows').focus();
    }
    function selectDocumentTab(tab) {
      if (!selectedDocumentId || !DOCUMENT_TABS.includes(tab)) return;
      var attemptId = tab === 'attempts' ? selectedAttemptId : null;
      options.navigate(documentSearch(documentFilters, { id: selectedDocumentId, tab: tab, attemptId: attemptId }));
    }
    function onDocumentRowsClick(event) {
      var row = event.target.closest('[data-document-id]');
      if (!row) return;
      if (event.preventDefault) event.preventDefault();
      selectDocument(row.dataset.documentId, row);
    }
    function onDocumentDetailClick(event) {
      var runLink = event.target.closest('[data-operation-run-id]');
      if (runLink) {
        if (event.preventDefault) event.preventDefault();
        options.navigate('?view=pipeline&runId=' + encodeURIComponent(runLink.dataset.operationRunId), { returnSearch: currentLocalSearch() });
        return;
      }
      var ingestionLink = event.target.closest('[data-ingestion-run-id]');
      if (ingestionLink) {
        if (event.preventDefault) event.preventDefault();
        options.navigate('?view=pipeline&ingestionRunId=' + encodeURIComponent(ingestionLink.dataset.ingestionRunId), { returnSearch: currentLocalSearch() });
        return;
      }
      var modelLink = event.target.closest('[data-model-run-id]');
      if (modelLink) {
        if (event.preventDefault) event.preventDefault();
        options.navigate('?view=models&modelRunId=' + encodeURIComponent(modelLink.dataset.modelRunId), { returnSearch: currentLocalSearch() });
        return;
      }
      var companyLink = event.target.closest('[data-document-company]');
      if (companyLink) {
        if (event.preventDefault) event.preventDefault();
        options.navigate('?view=company&ticker=' + encodeURIComponent(companyLink.dataset.documentCompany), { returnSearch: currentLocalSearch() });
      }
    }
    function onLoadDocumentsClick() { loadDocumentPage(!!documentCursor); }
    function onLoadAttemptsClick() { loadDocumentTab(false, true); }
    function onLoadDocumentModelsClick() { loadDocumentTab(false, true); }
    function onLoadDocumentEventsClick() { loadDocumentTab(false, true); }

    function normalizeModelFilters(filters) {
      var normalized = Object.assign({}, filters, {
        provider: String(filters.provider || '').trim().toLowerCase(),
        operation: String(filters.operation || '').trim().toLowerCase(),
        model: String(filters.model || '').trim(),
        success: String(filters.success == null ? '' : filters.success),
        documentId: String(filters.documentId || '').trim(),
        attemptId: String(filters.attemptId || '').trim(),
        runId: String(filters.runId || '').trim(),
      });
      if (normalized.provider && !MODEL_PROVIDERS.includes(normalized.provider)) return { error: 'Choose OpenAI as the provider.' };
      if (normalized.operation && !MODEL_OPERATIONS.includes(normalized.operation)) return { error: 'Choose extract or embed as the operation.' };
      if (normalized.model.length > 128 || /[\u0000-\u001f\u007f]/.test(normalized.model)) return { error: 'Model name must contain up to 128 printable characters.' };
      if (normalized.success && normalized.success !== 'true' && normalized.success !== 'false') return { error: 'Choose a valid call outcome.' };
      if (normalized.documentId && !validUuid(normalized.documentId)) return { error: 'The source document ID is invalid.' };
      if (normalized.attemptId && !validUuid(normalized.attemptId)) return { error: 'The attempt ID is invalid.' };
      if (normalized.runId && !validUuid(normalized.runId)) return { error: 'The operation run ID is invalid.' };
      return { filters: normalized };
    }
    function modelFilterKey(filters) {
      return JSON.stringify({ provider: filters.provider, operation: filters.operation, model: filters.model,
        success: filters.success, documentId: filters.documentId, attemptId: filters.attemptId, runId: filters.runId });
    }
    function modelFiltersFromRoute(route, preserveCurrentFilters) {
      var keys = ['provider', 'operation', 'model', 'success', 'documentId', 'attemptId', 'runId'];
      var hasFilters = keys.some(function (key) { return route.has(key); });
      var next = preserveCurrentFilters && !hasFilters
        ? Object.assign({}, modelFilters)
        : { provider: '', operation: '', model: '', success: '', documentId: '', attemptId: '', runId: '' };
      keys.forEach(function (key) { if (route.has(key)) next[key] = route.get(key); });
      return normalizeModelFilters(next);
    }
    function modelSearch(filters, range, selectedId) {
      var route = new URLSearchParams();
      route.set('view', 'models');
      route.set('range', range === '7d' ? '7d' : '24h');
      ['provider', 'operation', 'model', 'success', 'documentId', 'attemptId', 'runId'].forEach(function (key) {
        if (filters[key]) route.set(key, filters[key]);
      });
      if (selectedId) route.set('modelRunId', selectedId);
      return '?' + route.toString();
    }
    function renderModelFilters() {
      el('modelProvider').value = modelFilters.provider;
      el('modelOperation').value = modelFilters.operation;
      el('modelName').value = modelFilters.model;
      el('modelSuccess').value = modelFilters.success;
    }
    function modelRangeFromRoute(route, preserveCurrentRange) {
      if (!route.has('range')) return preserveCurrentRange ? modelRange : '24h';
      var range = route.get('range');
      return range === '24h' || range === '7d' ? range : null;
    }
    function configureModels(route, preserveCurrentFilters) {
      var nextRange = modelRangeFromRoute(route, preserveCurrentFilters);
      var parsed = modelFiltersFromRoute(route, preserveCurrentFilters);
      var nextId = route.get('modelRunId') || null;
      var invalid = !nextRange ? 'Choose a valid activity range.' : parsed.error;
      if (!invalid && nextId && !validUuid(nextId)) invalid = 'Model call ID must be a UUID.';
      if (invalid) {
        modelRouteValid = false;
        clearModelData();
        selectedModelCallId = nextId && validUuid(nextId) ? nextId : null;
        modelStatus(invalid);
        setModelPane();
        return false;
      }
      var changed = nextRange !== modelRange || modelFilterKey(parsed.filters) !== modelFilterKey(modelFilters);
      modelRange = nextRange;
      modelFilters = parsed.filters;
      if (changed) {
        modelGeneration++;
        modelSummary = null;
        modelWindow = null;
        modelItems = [];
        modelCursor = null;
        modelSummaryLoaded = false;
        modelSummaryError = null;
        lastModelSummaryAt = null;
        modelPageLoaded = false;
        modelPageError = null;
      }
      if (selectedModelCallId !== nextId) {
        selectedModelCallId = nextId;
        selectedModelCall = null;
        selectedModelLoaded = false;
        selectedModelLoading = false;
        modelDetailSequence++;
        el('modelDetailTitle').textContent = 'Selected model call';
        el('modelDetailStatus').textContent = '';
        el('modelDetailContent').innerHTML = '';
      }
      modelRouteValid = true;
      renderModelFilters();
      renderModelSummary(modelSummary);
      renderModelRows();
      setModelPane();
      return true;
    }
    function modelStatus(message) { el('modelStatus').textContent = message; }
    function modelCostLabel(value, known, calls) {
      var label = cost(value, known, calls);
      if (label === 'Unknown') return calls > 0 && Number(known) === 0
        ? 'Unknown · no recorded cost (' + integer(known) + '/' + integer(calls) + ' calls)' : label;
      if (Number(known) === Number(calls) && Number(calls) > 0) return label + ' · ' + integer(known) + '/' + integer(calls) + ' calls';
      if (Number(calls) === 0) return label + ' · 0/0 calls';
      return label;
    }
    function coverageMeasure(value, known, expected, units) {
      if (value == null) return 'Unknown · ' + integer(known) + '/' + integer(expected) + ' ' + units;
      var formatted = integer(value);
      return Number(known) < Number(expected)
        ? formatted + ' · partial (' + integer(known) + '/' + integer(expected) + ' ' + units + ')'
        : formatted + ' · ' + integer(known) + '/' + integer(expected) + ' ' + units;
    }
    function latencyValue(value) {
      return value == null || !Number.isFinite(Number(value)) ? 'Unknown' : String(value) + ' ms';
    }
    function renderModelSummary(summary) {
      if (!summary || !summary.totals) {
        el('modelCallsTotal').textContent = 'Unknown';
        el('modelFailedTotal').textContent = 'Unknown';
        el('modelCostTotal').textContent = 'Unknown';
        el('modelTokenSummary').textContent = 'Token coverage is not available.';
        el('modelLatencySummary').textContent = 'Latency coverage is not available.';
        el('modelBreakdown').innerHTML = '<p class="muted">No model summary loaded.</p>';
        return;
      }
      var totals = summary.totals;
      el('modelCallsTotal').textContent = integer(totals.calls);
      el('modelFailedTotal').textContent = integer(totals.failedCalls);
      el('modelCostTotal').textContent = modelCostLabel(totals.estimatedCostUsd, totals.costKnownCalls, totals.calls);
      el('modelTokenSummary').textContent = 'Input tokens: ' + coverageMeasure(totals.inputTokens, totals.inputTokensKnownCalls, totals.calls, 'calls') +
        ' · Output tokens: ' + coverageMeasure(totals.outputTokens, totals.outputTokensKnownCalls,
          totals.outputTokensExpectedCalls, 'expected extract calls');
      el('modelLatencySummary').textContent = 'p50: ' + latencyValue(totals.p50LatencyMs) +
        ' · p95: ' + latencyValue(totals.p95LatencyMs) + ' · latency known for ' + integer(totals.latencyKnownCalls) +
        '/' + integer(totals.calls) + ' calls';
      var groups = Array.isArray(summary.groups) ? summary.groups : [];
      el('modelBreakdown').innerHTML = groups.length ? '<div class="table-scroll"><table class="model-breakdown-table"><caption>Recorded calls grouped by provider, operation and model</caption>' +
        '<thead><tr><th scope="col">Provider</th><th scope="col">Operation</th><th scope="col">Model</th><th scope="col">Calls</th><th scope="col">Failed</th><th scope="col">Estimated cost</th></tr></thead><tbody>' +
        groups.map(function (group) {
          var usage = group.usage || {};
          return '<tr><td>' + esc(group.provider) + '</td><td>' + esc(group.operation) + '</td><td>' + esc(group.model) + '</td>' +
            '<td>' + esc(integer(usage.calls)) + '</td><td>' + esc(integer(usage.failedCalls)) + '</td><td>' +
            esc(modelCostLabel(usage.estimatedCostUsd, usage.costKnownCalls, usage.calls)) + '</td></tr>';
        }).join('') + '</tbody></table></div>' : '<p class="muted">No model calls are recorded for this window.</p>';
      if (summary.groupsTruncated) el('modelBreakdown').innerHTML += '<p class="muted">More groups are not shown; the server returned its first 50 groups.</p>';
    }
    function modelRowCost(item) {
      return item.estimatedCost == null ? 'Unknown' : cost(item.estimatedCost, 1, 1);
    }
    function renderModelRows() {
      var content = modelPageLoading && !modelPageLoaded && !modelItems.length
        ? '<p class="muted">Loading recorded model calls…</p>'
        : (modelItems.length ? '<div class="table-scroll"><table class="model-calls-table"><caption>' +
          modelItems.length + ' loaded recorded calls; summary totals cover the full selected window.</caption>' +
          '<thead><tr><th scope="col">Call</th><th scope="col">Provider / operation / model</th><th scope="col">Outcome</th>' +
          '<th scope="col">Recorded at (UTC)</th><th scope="col">Input / output tokens</th><th scope="col">Estimated cost</th><th scope="col">Latency</th><th scope="col">Associations</th></tr></thead><tbody>' +
          modelItems.map(function (item) {
            return '<tr' + (item.id === selectedModelCallId ? ' class="model-call-selected"' : '') + '><td><button type="button" class="ghost model-row-button" data-model-run-id="' +
              esc(item.id) + '" aria-current="' + (item.id === selectedModelCallId ? 'true' : 'false') + '">Inspect call</button></td>' +
              '<td>' + esc(item.provider) + ' · ' + esc(item.operation) + ' · ' + esc(item.model) + '</td>' +
              '<td><span class="pill ' + (item.success ? 'ok' : 'bad') + '">' + (item.success ? 'Success' : 'Failed') + '</span>' +
              (item.errorCode ? '<br>' + esc(item.errorCode) : '') + '</td>' +
              '<td>' + esc(time(item.createdAt)) + '</td><td>' + esc(integer(item.inputTokens)) + ' / ' + esc(integer(item.outputTokens)) + '</td>' +
              '<td>' + esc(modelRowCost(item)) + '</td><td>' + esc(item.latencyMs == null ? 'Unknown' : integer(item.latencyMs) + ' ms') +
              '</td><td>' + renderModelLinks(item) + '</td></tr>';
          }).join('') + '</tbody></table></div>' : '<p class="muted">No recorded model calls match these filters.</p>');
      if (modelPageError && modelItems.length) content += '<p class="panel-error">' + esc(modelPageError) + '</p>';
      el('modelRows').innerHTML = content;
      toggleHidden('modelRows', false);
      toggleHidden('loadModels', !modelRouteValid || (!modelCursor && modelPageLoaded && !modelPageError));
      el('loadModels').textContent = modelSummaryError ? 'Retry model summary'
        : (modelPageError && !modelCursor ? 'Retry model calls' : (modelPageLoaded ? 'Load more' : 'Load model calls'));
      el('loadModels').disabled = modelPageLoading || modelSummaryLoading || (!modelCursor && modelPageLoaded && !modelPageError);
      if (modelPageError && !modelItems.length) modelStatus(modelPageError);
      else if (modelSummaryError) modelStatus(modelSummaryError);
      else if (modelSummaryLoaded && modelPageLoaded) modelStatus(modelItems.length + ' calls loaded · activity window ' +
        time(modelWindow && modelWindow.from) + ' to ' + time(modelWindow && modelWindow.to) + ' UTC.');
      else if (modelPageLoading || modelSummaryLoading) modelStatus('Loading recorded model usage…');
    }
    function renderModelLinks(call) {
      var links = [];
      var hasSource = validUuid(call.sourceDocumentId);
      if (hasSource) links.push('<a href="?view=documents&amp;documentId=' + encodeURIComponent(call.sourceDocumentId) +
        '&amp;documentTab=source" data-model-source-id="' + esc(call.sourceDocumentId) + '">Source document</a>');
      if (hasSource && validUuid(call.attemptId)) links.push('<a href="?view=documents&amp;documentId=' +
        encodeURIComponent(call.sourceDocumentId) + '&amp;documentTab=attempts&amp;attemptId=' + encodeURIComponent(call.attemptId) +
        '" data-model-attempt-id="' + esc(call.attemptId) + '" data-model-attempt-document-id="' + esc(call.sourceDocumentId) + '">Processing attempt</a>');
      if (validUuid(call.runId)) links.push('<a href="?view=pipeline&amp;runId=' + encodeURIComponent(call.runId) +
        '" data-operation-run-id="' + esc(call.runId) + '">Operation run</a>');
      if (!hasSource) links.unshift('<span class="muted">No source association recorded.</span>');
      return '<p class="model-associations">' + links.join(' · ') + '</p>';
    }
    function renderSelectedModelCall(call) {
      el('modelDetailTitle').textContent = 'Selected model call';
      el('modelDetailContent').innerHTML = '<dl class="model-detail-grid">' +
        [['Call ID', call.id], ['Provider', call.provider], ['Operation', call.operation], ['Model', call.model],
          ['Prompt version', call.promptVersion], ['Extractor version', call.extractorVersion], ['Recorded at (UTC)', time(call.createdAt)],
          ['Outcome', call.success ? 'Success' : 'Failed'], ['Input tokens', integer(call.inputTokens)],
          ['Output tokens', integer(call.outputTokens)], ['Estimated cost', modelRowCost(call)],
          ['Latency', call.latencyMs == null ? 'Unknown' : integer(call.latencyMs) + ' ms'],
          ['Error category', call.errorCode], ['Safe error message', call.errorMessage]].map(function (entry) {
          return '<div><dt>' + esc(entry[0]) + '</dt><dd>' + esc(entry[1] == null || entry[1] === '' ? 'Unknown' : entry[1]) + '</dd></div>';
        }).join('') + '</dl>' + renderModelLinks(call);
    }
    function setModelPane() {
      toggleHidden('modelDetail', !selectedModelCallId || !credentials().hasAdmin);
      if (!selectedModelCallId) {
        el('modelDetailStatus').textContent = '';
        el('modelDetailContent').innerHTML = '';
        return;
      }
      var returnSearch = window.history && window.history.state ? window.history.state.returnSearch : null;
      el('closeModel').textContent = validLocalReturnSearch(returnSearch) ? 'Back to previous view' : 'Back to model calls';
      if (selectedModelCall) renderSelectedModelCall(selectedModelCall);
    }
    function validLocalReturnSearch(value) {
      if (typeof value !== 'string' || !value.startsWith('?') || value.startsWith('??') || value.indexOf('#') >= 0 || value.indexOf('\\') >= 0) return false;
      var view = new URLSearchParams(value.slice(1)).get('view') || 'overview';
      return ['overview', 'pipeline', 'documents', 'models', 'discover', 'company', 'events'].includes(view);
    }
    function clearModelData() {
      modelSummary = null;
      modelWindow = null;
      modelItems = [];
      modelCursor = null;
      modelSummaryLoaded = false;
      modelSummaryError = null;
      modelPageLoaded = false;
      modelPageError = null;
      selectedModelCall = null;
      selectedModelLoaded = false;
      selectedModelLoading = false;
      renderModelSummary(null);
      renderModelRows();
      el('modelDetailStatus').textContent = '';
      el('modelDetailContent').innerHTML = '';
    }
    function modelSummaryPath() {
      var query = window.CatalystOperationsModel.modelSummaryQuery(modelFilters, modelRange);
      return '/internal/operations/model-summary?' + query.toString();
    }
    function modelPagePath(cursor) {
      var query = window.CatalystOperationsModel.modelQuery(Object.assign({}, modelFilters, { cursor: cursor || null }), modelWindow);
      return '/internal/operations/model-runs?' + query.toString();
    }
    function loadModelSummary(force) {
      if (modelSummaryLoading || (modelSummaryLoaded && !force)) return Promise.resolve(modelSummaryLoaded);
      var revision = credentials().revision;
      var requestSequence = sequence;
      var generation = modelGeneration;
      modelSummaryLoading = true;
      modelSummaryError = null;
      if (!modelSummaryLoaded) modelStatus('Loading model summary…');
      renderModelRows();
      return read(modelSummaryPath(), revision, requestSequence, 'models').then(function (result) {
        if (!result.current || generation !== modelGeneration) return false;
        var data = result.data || {};
        if (!data.window || !data.window.from || !data.window.to || !data.totals) throw new Error('INVALID_MODEL_SUMMARY');
        modelSummary = data;
        modelWindow = data.window;
        modelSummaryLoaded = true;
        modelSummaryError = null;
        renderModelSummary(data);
        if (data.generatedAt) {
          lastModelSummaryAt = time(data.generatedAt);
        }
        return true;
      }).catch(function (error) {
        if (!current(revision, requestSequence, 'models') || generation !== modelGeneration || error.name === 'AbortError') return false;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        modelSummaryError = 'Unable to load model summary. Retry the read.';
        return false;
      }).finally(function () {
        if (current(revision, requestSequence, 'models') && generation === modelGeneration) {
          modelSummaryLoading = false;
          if (!modelSummaryLoaded) {
            renderModelRows();
            modelStatus(lastModelSummaryAt
              ? 'Last loaded ' + lastModelSummaryAt + ' · summary refresh failed' : (modelSummaryError || 'Unable to load model summary. Retry the read.'));
          }
        }
      });
    }
    function loadModelPage(appendPage) {
      if (!credentials().hasAdmin || !modelWindow || modelPageLoading || (appendPage && !modelCursor)) return Promise.resolve(false);
      var revision = credentials().revision;
      var requestSequence = sequence;
      var generation = modelGeneration;
      var cursor = appendPage ? modelCursor : null;
      modelPageLoading = true;
      modelPageError = null;
      renderModelRows();
      return read(modelPagePath(cursor), revision, requestSequence, 'models').then(function (result) {
        if (!result.current || generation !== modelGeneration) return false;
        var incoming = Array.isArray(result.data.items) ? result.data.items : [];
        modelItems = appendPage ? window.CatalystOperationsModel.mergePage({ items: modelItems }, { items: incoming }).items : incoming.slice();
        modelCursor = result.data.nextCursor || null;
        modelPageLoaded = true;
        modelPageError = null;
        if (!appendPage && modelSummaryLoaded) {
          var loadedAt = result.data.generatedAt || (modelSummary && modelSummary.generatedAt);
          if (loadedAt) el('lastRefresh').textContent = 'Last loaded ' + time(loadedAt);
        }
        return true;
      }).catch(function (error) {
        if (!current(revision, requestSequence, 'models') || generation !== modelGeneration || error.name === 'AbortError') return false;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        modelPageError = 'Unable to load model calls. Retry the read.';
        return false;
      }).finally(function () {
        if (current(revision, requestSequence, 'models') && generation === modelGeneration) {
          modelPageLoading = false;
          renderModelRows();
        }
      });
    }
    function loadSelectedModel(force) {
      if (!selectedModelCallId || !credentials().hasAdmin || selectedModelLoading) return Promise.resolve(false);
      if (selectedModelLoaded && !force) return Promise.resolve(true);
      var id = selectedModelCallId;
      var detailSequence = ++modelDetailSequence;
      var revision = credentials().revision;
      var requestSequence = sequence;
      selectedModelLoading = true;
      toggleHidden('modelDetail', false);
      el('modelDetailStatus').textContent = selectedModelLoaded ? 'Refreshing model call details…' : 'Loading recorded model call…';
      return read('/internal/operations/model-runs/' + encodeURIComponent(id), revision, requestSequence, 'models').then(function (result) {
        if (!result.current || selectedModelCallId !== id || detailSequence !== modelDetailSequence) return false;
        selectedModelCall = result.data;
        selectedModelLoaded = true;
        el('modelDetailStatus').textContent = '';
        renderSelectedModelCall(result.data);
        setModelPane();
        return true;
      }).catch(function (error) {
        if (!current(revision, requestSequence, 'models') || selectedModelCallId !== id || detailSequence !== modelDetailSequence || error.name === 'AbortError') return false;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        el('modelDetailTitle').textContent = 'Model call unavailable';
        el('modelDetailStatus').textContent = error.code === 'MODEL_RUN_NOT_FOUND'
          ? 'This recorded model call was not found.' : 'Unable to load model call details. Retry the read.';
        return false;
      }).finally(function () {
        if (current(revision, requestSequence, 'models') && selectedModelCallId === id && detailSequence === modelDetailSequence) selectedModelLoading = false;
      });
    }
    function loadModelsScreen(force) {
      if (!modelRouteValid) return Promise.resolve();
      if (!credentials().hasAdmin) {
        accessRequired();
        return Promise.resolve();
      }
      toggleHidden('modelFilters', false);
      toggleHidden('modelRows', false);
      if (force) {
        modelGeneration++;
        modelSummaryLoading = false;
        modelPageLoading = false;
        selectedModelLoading = false;
        modelDetailSequence++;
        modelSummaryLoaded = false;
        modelSummaryError = null;
        modelPageLoaded = false;
        modelPageError = null;
        modelCursor = null;
        if (selectedModelCallId) selectedModelLoaded = false;
      }
      var tasks = [];
      var summaryTask = !modelSummaryLoaded
        ? loadModelSummary(!!force).then(function (loaded) { return loaded ? loadModelPage(false) : false; })
        : (!modelPageLoaded || force ? loadModelPage(false) : Promise.resolve(true));
      tasks.push(summaryTask);
      if (selectedModelCallId && (!selectedModelLoaded || force)) tasks.push(loadSelectedModel(!!force));
      return Promise.allSettled(tasks).then(function () {});
    }
    function onModelFiltersSubmit(event) {
      event.preventDefault();
      var normalized = normalizeModelFilters(Object.assign({}, modelFilters, {
        provider: el('modelProvider').value, operation: el('modelOperation').value,
        model: el('modelName').value, success: el('modelSuccess').value,
      }));
      if (normalized.error) {
        modelStatus(normalized.error);
        return;
      }
      options.navigate(modelSearch(normalized.filters, modelRange, null));
    }
    function selectModelCall(id, opener) {
      if (!validUuid(id)) return;
      modelDetailOpener = opener || null;
      options.navigate(modelSearch(modelFilters, modelRange, id));
    }
    function closeSelectedModel() {
      var returnSearch = window.history && window.history.state ? window.history.state.returnSearch : null;
      var opener = modelDetailOpener;
      modelDetailOpener = null;
      if (validLocalReturnSearch(returnSearch)) {
        options.navigate(returnSearch);
        return;
      }
      options.navigate(modelSearch(modelFilters, modelRange, null));
      if (opener && typeof opener.focus === 'function') opener.focus();
      else el('modelRows').focus();
    }
    function onModelAssociationClick(event) {
      var source = event.target.closest('[data-model-source-id]');
      if (source) {
        if (event.preventDefault) event.preventDefault();
        options.navigate('?view=documents&documentId=' + encodeURIComponent(source.dataset.modelSourceId) + '&documentTab=source',
          { returnSearch: currentLocalSearch() });
        return true;
      }
      var attempt = event.target.closest('[data-model-attempt-id]');
      var documentId = attempt && (attempt.dataset.modelAttemptDocumentId ||
        (selectedModelCall && selectedModelCall.sourceDocumentId));
      if (attempt && validUuid(documentId)) {
        if (event.preventDefault) event.preventDefault();
        options.navigate('?view=documents&documentId=' + encodeURIComponent(documentId) + '&documentTab=attempts&attemptId=' +
          encodeURIComponent(attempt.dataset.modelAttemptId), { returnSearch: currentLocalSearch() });
        return true;
      }
      var run = event.target.closest('[data-operation-run-id]');
      if (run) {
        if (event.preventDefault) event.preventDefault();
        options.navigate('?view=pipeline&runId=' + encodeURIComponent(run.dataset.operationRunId), { returnSearch: currentLocalSearch() });
        return true;
      }
      return false;
    }
    function onModelRowsClick(event) {
      if (onModelAssociationClick(event)) return;
      var row = event.target.closest('[data-model-run-id]');
      if (!row) return;
      if (event.preventDefault) event.preventDefault();
      selectModelCall(row.dataset.modelRunId, row);
    }
    function onModelDetailClick(event) {
      onModelAssociationClick(event);
    }
    function onLoadModelsClick() {
      if (modelSummaryLoading) return;
      if (!modelWindow || !modelSummaryLoaded || modelSummaryError) {
        loadModelsScreen(true);
        return;
      }
      loadModelPage(!!modelCursor);
    }
    var tabHandlers = {};
    DOCUMENT_TABS.forEach(function (tab) {
      tabHandlers[tab] = function () { selectDocumentTab(tab); };
    });
    function pipelineSearch(overrides) {
      var state = Object.assign({ range: pipelineRange, kind: pipelineKind, status: pipelineRunStatus,
        ingestionProvider: ingestionProvider, ingestionStatus: ingestionStatus,
        runId: selectedRunId, ingestionRunId: selectedIngestionRunId }, overrides || {});
      var query = new URLSearchParams();
      query.set('view', 'pipeline');
      query.set('range', state.range);
      query.set('kind', state.kind);
      if (state.status) query.set('status', state.status);
      if (state.ingestionProvider) query.set('ingestionProvider', state.ingestionProvider);
      if (state.ingestionStatus) query.set('ingestionStatus', state.ingestionStatus);
      if (state.runId) query.set('runId', state.runId);
      if (state.ingestionRunId) query.set('ingestionRunId', state.ingestionRunId);
      return '?' + query.toString();
    }
    function renderPipelineTabs() {
      el('pipelineKind').value = pipelineKind;
      el('pipelineTabCycles').setAttribute('aria-selected', String(pipelineKind === 'PIPELINE'));
      el('pipelineTabSnapshots').setAttribute('aria-selected', String(pipelineKind === 'DAILY_SNAPSHOTS'));
    }
    function clearPipelineData() {
      pipelineGeneration++;
      pipelineRuns = [];
      pipelineWindow = null;
      pipelineRunCursor = null;
      pipelineRunsLoaded = false;
      pipelineRunsLoading = false;
      pipelineRunError = null;
      pipelineGeneratedAt = null;
      ingestionItems = [];
      ingestionCursor = null;
      ingestionLoaded = false;
      ingestionLoading = false;
      ingestionError = null;
      ingestionGeneratedAt = null;
      selectedRunDetail = null;
      selectedRunLoaded = false;
      selectedRunLoading = false;
      selectedRunError = null;
      resetRunIngestion();
      runIssues = [];
      runIssueCursor = null;
      runIssuesLoaded = false;
      runIssuesLoading = false;
      runIssuesError = null;
      selectedIngestionRun = null;
      selectedIngestionLoaded = false;
      selectedIngestionLoading = false;
      selectedIngestionError = null;
      pipelineDetailSequence++;
      runIssueSequence++;
      ingestionDetailSequence++;
      snapshotScheduleEnabled = null;
      snapshotScheduleLoaded = false;
      snapshotScheduleLoading = false;
      pipelineOutcomeText = null;
      lastPipelineAt = null;
      el('runRows').innerHTML = '';
      el('runDetailContent').innerHTML = '';
      el('runDetailStatus').textContent = '';
      el('runIssues').innerHTML = '';
      el('ingestionRows').innerHTML = '';
      el('ingestionDetailContent').innerHTML = '';
      el('ingestionDetailStatus').textContent = '';
      toggleHidden('runRows', true);
      toggleHidden('loadRuns', true);
      toggleHidden('runDetail', true);
      toggleHidden('runIssues', true);
      toggleHidden('loadRunIssues', true);
      toggleHidden('ingestionRows', true);
      toggleHidden('loadIngestion', true);
      toggleHidden('ingestionDetail', true);
      updateRunNowButton();
    }
    function configurePipeline(route, preserveCurrentFilters) {
      var filterKeys = ['range', 'kind', 'status', 'ingestionProvider', 'ingestionStatus'];
      var preserve = preserveCurrentFilters && !filterKeys.some(function (key) { return route.has(key); });
      var nextRange = route.has('range') ? route.get('range') : (preserve ? pipelineRange : '24h');
      var nextKind = route.has('kind') ? route.get('kind') : (preserve ? pipelineKind : 'PIPELINE');
      var nextStatus = route.has('status') ? route.get('status') : (preserve ? pipelineRunStatus : '');
      var nextProvider = route.has('ingestionProvider') ? route.get('ingestionProvider').toLowerCase() : (preserve ? ingestionProvider : '');
      var nextIngestionStatus = route.has('ingestionStatus') ? route.get('ingestionStatus') : (preserve ? ingestionStatus : '');
      var nextRunId = route.get('runId') || null;
      var nextIngestionId = route.get('ingestionRunId') || null;
      var invalid = null;
      if (nextRange !== '24h' && nextRange !== '7d') invalid = 'Choose a valid activity range.';
      else if (!PIPELINE_KINDS.includes(nextKind)) invalid = 'Choose Pipeline cycles or Daily snapshots.';
      else if (nextStatus && !OPERATION_STATUSES.includes(nextStatus)) invalid = 'Choose a valid pipeline run status.';
      else if (nextProvider && !INGESTION_PROVIDERS.includes(nextProvider)) invalid = 'Choose Polygon or Finnhub as the ingestion provider.';
      else if (nextIngestionStatus && !INGESTION_STATUSES.includes(nextIngestionStatus)) invalid = 'Choose a valid ingestion status.';
      else if ((nextRunId && !validUuid(nextRunId)) || (nextIngestionId && !validUuid(nextIngestionId))) invalid = 'Run IDs must be UUIDs.';
      if (invalid) {
        pipelineRouteValid = false;
        clearPipelineData();
        pipelineStatus(invalid);
        renderPipelineTabs();
        return false;
      }
      var runFiltersChanged = nextRange !== pipelineRange || nextKind !== pipelineKind || nextStatus !== pipelineRunStatus;
      var ingestionFiltersChanged = nextRange !== pipelineRange || nextProvider !== ingestionProvider || nextIngestionStatus !== ingestionStatus;
      var selectionChanged = nextRunId !== selectedRunId;
      var ingestionSelectionChanged = nextIngestionId !== selectedIngestionRunId;
      if (runFiltersChanged || ingestionFiltersChanged) {
        pipelineGeneration++;
        if (nextRange !== pipelineRange) pipelineWindow = null;
        pipelineRunCursor = null;
        pipelineRunsLoaded = false;
        pipelineRunError = null;
        ingestionCursor = null;
        ingestionLoaded = false;
        ingestionError = null;
      }
      if (selectionChanged) {
        selectedRunId = nextRunId;
        selectedRunDetail = null;
        selectedRunLoaded = false;
        selectedRunLoading = false;
        selectedRunError = null;
        resetRunIngestion();
        runIssues = [];
        runIssueCursor = null;
        runIssuesLoaded = false;
        runIssuesError = null;
        pipelineDetailSequence++;
        runIssueSequence++;
        el('runDetailContent').innerHTML = '';
        el('runDetailStatus').textContent = '';
        el('runIssues').innerHTML = '';
      }
      if (ingestionSelectionChanged) {
        selectedIngestionRunId = nextIngestionId;
        selectedIngestionRun = null;
        selectedIngestionLoaded = false;
        selectedIngestionLoading = false;
        selectedIngestionError = null;
        ingestionDetailSequence++;
        el('ingestionDetailContent').innerHTML = '';
        el('ingestionDetailStatus').textContent = '';
      }
      pipelineRange = nextRange;
      el('activityRange').value = nextRange;
      pipelineKind = nextKind;
      pipelineRunStatus = nextStatus;
      ingestionProvider = nextProvider;
      ingestionStatus = nextIngestionStatus;
      pipelineRouteValid = true;
      el('pipelineRunStatusFilter').value = pipelineRunStatus;
      el('ingestionProvider').value = ingestionProvider;
      el('ingestionStatusFilter').value = ingestionStatus;
      renderPipelineTabs();
      renderPipelineRows();
      renderIngestionRows();
      setRunDetailPane();
      setIngestionDetailPane();
      return true;
    }
    function pipelineStatus(message) { el('pipelineStatus').textContent = message; }
    function runStatusLabel(run) {
      if (run.status === 'RUNNING' && !run.active) return 'Unfinished';
      return run.status || 'Unknown';
    }
    function runStatusClass(run) {
      if (run.status === 'FAILED' || run.status === 'CANCELLED') return 'bad';
      if (run.status === 'PARTIAL' || run.status === 'INTERRUPTED' || (!run.active && run.status === 'RUNNING')) return 'warn';
      if (run.status === 'SUCCESS') return 'ok';
      return 'info';
    }
    function runDuration(run) {
      if (run.durationMs == null) return 'Not recorded';
      return integer(run.durationMs) + ' ms';
    }
    function ingestionOutcomeLabel(run) {
      return run.status === 'RUNNING' && !run.finishedAt ? 'No final outcome recorded' : run.status;
    }
    function renderPipelineRows() {
      var content;
      if (pipelineRunsLoading && !pipelineRunsLoaded && !pipelineRuns.length) content = '<p class="muted">Loading recorded cycles…</p>';
      else if (pipelineRunError && !pipelineRuns.length) content = '<p class="panel-error">' + esc(pipelineRunError) + '</p>';
      else if (!pipelineRuns.length) {
        var empty = pipelineKind === 'DAILY_SNAPSHOTS' && snapshotScheduleLoaded && snapshotScheduleEnabled === false
          ? 'Daily snapshots are disabled. No cycles are recorded.' : 'No recorded cycles yet.';
        if (pipelineKind === 'DAILY_SNAPSHOTS' && !snapshotScheduleLoaded && !snapshotScheduleLoading)
          empty += ' Scheduler state could not be verified; see Settings.';
        content = '<p class="muted">' + esc(empty) + '</p>';
      } else {
        content = '<div class="table-scroll"><table class="pipeline-table"><caption>' + pipelineRuns.length +
          ' recorded ' + (pipelineKind === 'PIPELINE' ? 'pipeline cycles' : 'daily snapshot cycles') +
          ' loaded for the selected period.</caption><thead><tr><th scope="col">Cycle</th><th scope="col">Started</th>' +
          '<th scope="col">Finished / duration</th><th scope="col">Status / phase</th><th scope="col">Document outcomes</th>' +
          '<th scope="col">Events / companies</th></tr></thead><tbody>' + pipelineRuns.map(function (run) {
          var phase = run.active ? run.phase : (run.status === 'RUNNING' ? 'Last recorded: ' + run.phase : run.phase);
          var outcomes = (!run.captureComplete ? 'Recorded so far · ' : '') + 'Considered ' + integer(run.documentsConsidered) + ' · completed ' + integer(run.documentsCompleted) +
            ' · skipped ' + integer(run.documentsSkipped) + ' · retry ' + integer(run.documentsRetryScheduled) +
            ' · terminal ' + integer(run.documentsTerminalFailures);
          return '<tr' + (run.id === selectedRunId ? ' class="pipeline-row-selected"' : '') + '><td data-label="Cycle">' +
            '<button type="button" class="ghost" data-run-id="' + esc(run.id) + '" aria-current="' +
            (run.id === selectedRunId ? 'true' : 'false') + '">Inspect cycle</button><br><span class="muted">' + esc(run.trigger) + '</span></td>' +
            '<td data-label="Started">' + esc(time(run.startedAt)) + '</td><td data-label="Finished / duration">' +
            esc(run.finishedAt ? time(run.finishedAt) : 'Not finished') + '<br><span class="muted">' + esc(runDuration(run)) + '</span></td>' +
            '<td data-label="Status / phase"><span class="pill ' + runStatusClass(run) + '">' + esc(runStatusLabel(run)) +
            '</span><br><span class="muted">' + esc(phase) + '</span></td><td data-label="Document outcomes">' + esc(outcomes) +
            '</td><td data-label="Events / companies">' + esc(integer(run.eventsInserted)) + ' inserted · ' +
            esc(integer(run.eventsReused)) + ' reused<br>' + esc(integer(run.companiesRescored)) + ' rescored · ' +
            esc(integer(run.companiesFailed)) + ' failed</td></tr>';
        }).join('') + '</tbody></table></div>';
      }
      if (pipelineRunError && pipelineRuns.length) content += '<p class="panel-error">' + esc(pipelineRunError) + '</p>';
      el('runRows').innerHTML = content;
      toggleHidden('runRows', false);
      toggleHidden('loadRuns', !!((!pipelineRunCursor && !pipelineRunError) || pipelineRunsLoading));
      el('loadRuns').textContent = pipelineRunError
        ? (pipelineRunCursor ? 'Retry loading runs' : 'Retry pipeline history') : 'Load more runs';
      el('loadRuns').disabled = pipelineRunsLoading;
      renderPipelineStatus();
      updateRunNowButton();
    }
    function renderPipelineStatus() {
      if (!pipelineRouteValid) return;
      if (lastPipelineAt) el('lastRefresh').textContent = 'Last loaded ' + lastPipelineAt;
      if (pipelineOutcomeText) {
        pipelineStatus(pipelineOutcomeText);
        return;
      }
      if (pipelineRunError && ingestionError) pipelineStatus(pipelineRunError + ' Provider ingestion reads also failed: ' + ingestionError);
      else if (pipelineRunError) pipelineStatus(pipelineRunError);
      else if (ingestionError) pipelineStatus(ingestionError);
      else if (pipelineRunsLoaded && ingestionLoaded) {
        var windowText = pipelineWindow ? ' · ' + time(pipelineWindow.from) + ' to ' + time(pipelineWindow.to) + ' UTC' : '';
        pipelineStatus(pipelineRuns.length + ' cycles and ' + ingestionItems.length + ' provider runs loaded' + windowText + '.');
      } else if (pipelineRunsLoading || ingestionLoading) pipelineStatus('Loading recorded pipeline and ingestion history…');
      else pipelineStatus('Pipeline history has not been loaded. Retry the read.');
    }
    function renderIngestionRows() {
      var content;
      if (ingestionLoading && !ingestionLoaded && !ingestionItems.length) content = '<p class="muted">Loading provider ingestion runs…</p>';
      else if (ingestionError && !ingestionItems.length) content = '<p class="panel-error">' + esc(ingestionError) + '</p>';
      else if (!ingestionItems.length) content = '<p class="muted">No provider ingestion runs recorded for this period.</p>';
      else content = '<div class="table-scroll"><table class="pipeline-table"><caption>' + ingestionItems.length +
        ' recorded provider runs loaded.</caption><thead><tr><th scope="col">Provider run</th><th scope="col">Started / finished</th>' +
        '<th scope="col">Outcome</th><th scope="col">Source / operation</th></tr></thead><tbody>' + ingestionItems.map(function (run) {
        var outcome = ingestionOutcomeLabel(run) + ' · ' + integer(run.fetched) +
          ' fetched · ' + integer(run.added) + ' new · ' + integer(run.duplicates) + ' duplicates';
        if (run.errorCode) outcome += ' · ' + run.errorCode;
        var links = '<a href="?view=documents&amp;ingestionRunId=' + encodeURIComponent(run.id) + '" data-ingestion-source-id="' +
          esc(run.id) + '">First-source documents</a>';
        if (validUuid(run.runId)) links += ' · <a href="' + esc(pipelineSearch({ runId: run.runId })) + '" data-operation-run-id="' +
          esc(run.runId) + '">Operation run</a>';
        return '<tr><td data-label="Provider run"><strong>' + esc(run.provider) + '</strong><br>' +
          '<button type="button" class="ghost" data-ingestion-select-id="' + esc(run.id) + '">Inspect ' + esc(run.id) + '</button></td>' +
          '<td data-label="Started / finished">' + esc(time(run.startedAt)) + '<br>' + esc(run.finishedAt ? time(run.finishedAt) : 'Not finished') +
          '<br><span class="muted">' + esc(run.durationMs == null ? 'Not recorded' : integer(run.durationMs) + ' ms') + '</span></td>' +
          '<td data-label="Outcome">' + esc(outcome) + '</td><td data-label="Source / operation">' + links + '</td></tr>';
      }).join('') + '</tbody></table></div>';
      if (ingestionError && ingestionItems.length) content += '<p class="panel-error">' + esc(ingestionError) + '</p>';
      el('ingestionRows').innerHTML = content;
      toggleHidden('ingestionRows', false);
      toggleHidden('loadIngestion', !!((!ingestionCursor && !ingestionError) || ingestionLoading));
      el('loadIngestion').textContent = ingestionError
        ? (ingestionCursor ? 'Retry loading ingestion runs' : 'Retry ingestion history') : 'Load more ingestion runs';
      el('loadIngestion').disabled = ingestionLoading;
      renderPipelineStatus();
    }
    function resetRunIngestion() {
      runIngestionSequence++;
      runIngestionItems = [];
      runIngestionCursor = null;
      runIngestionLoaded = false;
      runIngestionLoading = false;
      runIngestionError = null;
      el('runIngestionRows').innerHTML = '';
      toggleHidden('loadRunIngestion', true);
    }
    function renderRunIngestion() {
      var content = runIngestionItems.length ? '<ul class="pipeline-issue-list">' + runIngestionItems.map(function (run) {
        return '<li><strong>' + esc(run.provider) + '</strong> · ' + esc(ingestionOutcomeLabel(run)) + ' · ' + esc(time(run.startedAt)) +
          '<br><a href="' + esc(pipelineSearch({ ingestionRunId: run.id })) + '" data-ingestion-select-id="' + esc(run.id) +
          '">Inspect ingestion run ' + esc(run.id) + '</a></li>';
      }).join('') + '</ul>' : '<p class="muted">' + (runIngestionLoading ? 'Loading cycle ingestion runs…' : 'No ingestion runs recorded for this cycle.') + '</p>';
      if (runIngestionError) content += '<p class="panel-error">' + esc(runIngestionError) + '</p>';
      el('runIngestionRows').innerHTML = content;
      toggleHidden('loadRunIngestion', runIngestionLoading || (!runIngestionCursor && !runIngestionError));
      el('loadRunIngestion').disabled = runIngestionLoading;
      el('loadRunIngestion').textContent = runIngestionError ? 'Retry cycle ingestion runs' : 'Load more cycle ingestion runs';
    }
    function loadRunIngestion(appendPage) {
      if (!selectedRunId || !credentials().hasAdmin || runIngestionLoading || (appendPage && !runIngestionCursor)) return Promise.resolve(false);
      var id = selectedRunId;
      var revision = credentials().revision;
      var requestSequence = sequence;
      var detailSequence = ++runIngestionSequence;
      var query = new URLSearchParams({ runId: id, limit: '25' });
      if (appendPage) query.set('cursor', runIngestionCursor);
      runIngestionLoading = true;
      runIngestionError = null;
      renderRunIngestion();
      function currentSelection() {
        return current(revision, requestSequence, 'pipeline') && selectedRunId === id && detailSequence === runIngestionSequence;
      }
      return read('/internal/operations/ingestion-runs?' + query.toString(), revision, requestSequence, 'pipeline').then(function (result) {
        if (!result.current || !currentSelection()) return false;
        var page = result.data || {};
        var incoming = Array.isArray(page.items) ? page.items : [];
        runIngestionItems = appendPage ? window.CatalystOperationsModel.mergePage({ items: runIngestionItems }, { items: incoming }).items : incoming.slice();
        runIngestionCursor = page.nextCursor || null;
        runIngestionLoaded = true;
        return true;
      }).catch(function (error) {
        if (!currentSelection() || error.name === 'AbortError') return false;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        runIngestionError = 'Unable to load cycle ingestion runs. Retry the read.';
        return false;
      }).finally(function () {
        if (currentSelection()) {
          runIngestionLoading = false;
          renderRunIngestion();
        }
      });
    }
    function setRunDetailPane() {
      toggleHidden('runDetail', !selectedRunId || !credentials().hasAdmin);
      if (!selectedRunId) {
        el('runDetailStatus').textContent = '';
        el('runDetailContent').innerHTML = '';
        el('runIssues').innerHTML = '';
        toggleHidden('loadRunIssues', true);
        return;
      }
      if (selectedRunDetail) renderSelectedRunDetail(selectedRunDetail);
      else if (!selectedRunLoading) el('runDetailStatus').textContent = selectedRunError || 'Loading recorded cycle detail…';
      renderRunIssues();
    }
    function renderSelectedRunDetail(detail) {
      var run = detail.run || {};
      el('runDetailTitle').textContent = pipelineKind === 'DAILY_SNAPSHOTS' ? 'Selected daily snapshot cycle' : 'Selected pipeline cycle';
      var records = [
        ['Cycle ID', run.id], ['Trigger', run.trigger], ['Status', runStatusLabel(run)], ['Phase', run.phase],
        ['Started at (UTC)', run.startedAt ? time(run.startedAt) : 'Unknown'],
        ['Finished at (UTC)', run.finishedAt ? time(run.finishedAt) : 'Not finished'],
        ['Duration', runDuration(run)], ['Capture', run.captureComplete ? 'Complete' : 'Recorded so far'],
        ['Documents considered', integer(run.documentsConsidered)], ['Documents completed', integer(run.documentsCompleted)],
        ['Documents skipped', integer(run.documentsSkipped)], ['Documents retry scheduled', integer(run.documentsRetryScheduled)],
        ['Terminal document failures', integer(run.documentsTerminalFailures)], ['Events inserted / reused', integer(run.eventsInserted) + ' / ' + integer(run.eventsReused)],
        ['Companies considered', integer(run.companiesConsidered)], ['Companies rescored / failed', integer(run.companiesRescored) + ' / ' + integer(run.companiesFailed)],
        ['Recorded ingestion runs', integer(detail.ingestionRuns)], ['Recorded document attempts', integer(detail.documentAttempts)],
        ['Recorded issues', integer(detail.issues)], ['Safe error category', run.errorCode], ['Safe error message', run.errorMessage],
      ];
      var phases = Array.isArray(detail.phases) ? detail.phases : [];
      var phaseHtml = phases.length ? '<section class="pipeline-phases"><h4>Phase timing</h4><ul class="pipeline-phase-list">' +
        phases.map(function (phase) { return '<li><strong>' + esc(phase.phase) + '</strong><br>Started: ' +
          esc(phase.startedAt ? time(phase.startedAt) : 'Not recorded') + ' · Finished: ' +
          esc(phase.finishedAt ? time(phase.finishedAt) : 'Not recorded') + ' · Duration: ' +
          esc(phase.durationMs == null ? 'Not recorded' : integer(phase.durationMs) + ' ms') + '</li>'; }).join('') + '</ul></section>' : '';
      var documents = '<a href="?view=documents&amp;runId=' + encodeURIComponent(run.id) + '" data-cycle-documents-id="' +
        esc(run.id) + '">Inspect documents for this cycle</a>';
      el('runDetailContent').innerHTML = '<dl class="pipeline-detail-grid">' + records.map(function (entry) {
        return '<div><dt>' + esc(entry[0]) + '</dt><dd>' + esc(entry[1] == null || entry[1] === '' ? 'Unknown' : entry[1]) + '</dd></div>';
      }).join('') + '</dl>' + phaseHtml + '<p>' + documents + '</p>';
      el('runDetailStatus').textContent = selectedRunError || '';
    }
    function renderRunIssues() {
      toggleHidden('runIssues', !selectedRunId || !credentials().hasAdmin);
      var content;
      if (runIssuesLoading && !runIssuesLoaded && !runIssues.length) content = '<p class="muted">Loading recorded issues…</p>';
      else if (runIssuesError && !runIssues.length) content = '<p class="panel-error">' + esc(runIssuesError) + '</p>';
      else if (!runIssues.length) content = '<p class="muted">No recorded issues for this cycle.</p>';
      else content = '<ul class="pipeline-issue-list">' + runIssues.map(function (issue) {
        var links = [];
        if (validUuid(issue.documentId)) links.push('<a href="?view=documents&amp;runId=' + encodeURIComponent(issue.runId) +
          '&amp;documentId=' + encodeURIComponent(issue.documentId) + '" data-run-document-id="' + esc(issue.documentId) + '">Inspect document</a>');
        if (issue.ticker) links.push('<a href="?view=company&amp;ticker=' + encodeURIComponent(issue.ticker) +
          '" data-run-company-ticker="' + esc(issue.ticker) + '">Inspect company</a>');
        return '<li><span class="pill bad">' + esc(issue.phase) + '</span> <strong>' + esc(issue.errorCode) + '</strong><br>' +
          esc(issue.errorMessage) + '<br><span class="muted">Provider: ' + esc(issue.provider || 'Unknown') + '</span>' +
          '<br><span class="muted">' + esc(time(issue.createdAt)) + '</span>' +
          (links.length ? '<p>' + links.join(' · ') + '</p>' : '') + '</li>';
      }).join('') + '</ul>';
      if (runIssuesError && runIssues.length) content += '<p class="panel-error">' + esc(runIssuesError) + '</p>';
      el('runIssues').innerHTML = content;
      toggleHidden('loadRunIssues', !!((!runIssueCursor && !runIssuesError) || runIssuesLoading));
      el('loadRunIssues').textContent = runIssuesError
        ? (runIssueCursor ? 'Retry loading issues' : 'Retry cycle issues') : 'Load more issues';
      el('loadRunIssues').disabled = runIssuesLoading;
    }
    function setIngestionDetailPane() {
      toggleHidden('ingestionDetail', !selectedIngestionRunId || !credentials().hasAdmin);
      if (!selectedIngestionRunId) {
        el('ingestionDetailStatus').textContent = '';
        el('ingestionDetailContent').innerHTML = '';
        return;
      }
      if (selectedIngestionRun) renderSelectedIngestionRun(selectedIngestionRun);
      else if (!selectedIngestionLoading) el('ingestionDetailStatus').textContent = selectedIngestionError || 'Loading selected ingestion run…';
    }
    function renderSelectedIngestionRun(run) {
      el('ingestionDetailTitle').textContent = 'Selected ingestion run';
      if (!run) {
        el('ingestionDetailContent').innerHTML = '';
        el('ingestionDetailStatus').textContent = 'Ingestion run not found.';
        return;
      }
      var records = [['Ingestion run ID', run.id], ['Provider', run.provider], ['Status', ingestionOutcomeLabel(run)],
        ['Started at (UTC)', run.startedAt ? time(run.startedAt) : 'Unknown'],
        ['Finished at (UTC)', run.finishedAt ? time(run.finishedAt) : 'Not finished'],
        ['Duration', run.durationMs == null ? 'Not recorded' : integer(run.durationMs) + ' ms'],
        ['Fetched / new / duplicates', integer(run.fetched) + ' / ' + integer(run.added) + ' / ' + integer(run.duplicates)],
        ['Safe error category', run.errorCode], ['Safe error message', run.error]];
      var links = '<a href="?view=documents&amp;ingestionRunId=' + encodeURIComponent(run.id) + '" data-ingestion-source-id="' +
        esc(run.id) + '">First-source documents</a>';
      if (validUuid(run.runId)) links += ' · <a href="' + esc(pipelineSearch({ runId: run.runId })) + '" data-operation-run-id="' +
        esc(run.runId) + '">Parent operation</a>';
      el('ingestionDetailContent').innerHTML = '<dl class="pipeline-detail-grid">' + records.map(function (entry) {
        return '<div><dt>' + esc(entry[0]) + '</dt><dd>' + esc(entry[1] == null || entry[1] === '' ? 'Unknown' : entry[1]) + '</dd></div>';
      }).join('') + '</dl><p>' + links + '</p>';
      el('ingestionDetailStatus').textContent = selectedIngestionError || '';
    }
    function updateRunNowButton() {
      el('runNow').disabled = !credentials().hasAdmin || pendingPost || knownActivePipelineIds.size > 0;
      el('runNow').setAttribute('aria-describedby', 'runNowCaption');
    }
    function observePipelineRuns(runs) {
      runs.forEach(function (run) {
        if (!run || run.kind !== 'PIPELINE' || !validUuid(run.id)) return;
        if (run.active === true) knownActivePipelineIds.add(run.id);
        else if (run.active === false) knownActivePipelineIds.delete(run.id);
      });
    }
    function pipelineWindowParameters(query) {
      if (pipelineWindow && pipelineWindow.from && pipelineWindow.to) {
        query.set('from', pipelineWindow.from);
        query.set('to', pipelineWindow.to);
      } else query.set('range', pipelineRange);
      return query;
    }
    function pipelineRunsPath(cursor) {
      var query = pipelineWindowParameters(new URLSearchParams());
      query.set('kind', pipelineKind);
      if (pipelineRunStatus) query.set('status', pipelineRunStatus);
      query.set('limit', '25');
      if (cursor) query.set('cursor', cursor);
      return '/internal/operations/runs?' + query.toString();
    }
    function ingestionRunsPath(cursor) {
      var query = pipelineWindowParameters(new URLSearchParams());
      if (ingestionProvider) query.set('provider', ingestionProvider);
      if (ingestionStatus) query.set('status', ingestionStatus);
      query.set('limit', '25');
      if (cursor) query.set('cursor', cursor);
      return '/internal/operations/ingestion-runs?' + query.toString();
    }
    function loadPipelineRunPage(appendPage) {
      if (!credentials().hasAdmin || pipelineRunsLoading || (appendPage && !pipelineRunCursor)) return Promise.resolve(false);
      var revision = credentials().revision;
      var requestSequence = sequence;
      var generation = pipelineGeneration;
      var cursor = appendPage ? pipelineRunCursor : null;
      pipelineRunsLoading = true;
      pipelineRunError = null;
      renderPipelineRows();
      return read(pipelineRunsPath(cursor), revision, requestSequence, 'pipeline').then(function (result) {
        if (!result.current || generation !== pipelineGeneration) return false;
        var page = result.data || {};
        if (!appendPage && page.window) pipelineWindow = page.window;
        var incoming = Array.isArray(page.items) ? page.items : [];
        observePipelineRuns(incoming);
        pipelineRuns = appendPage ? window.CatalystOperationsModel.mergePage({ items: pipelineRuns }, { items: incoming }).items : incoming.slice();
        pipelineRunCursor = page.nextCursor || null;
        pipelineGeneratedAt = page.generatedAt || null;
        pipelineRunsLoaded = true;
        pipelineRunError = null;
        return true;
      }).catch(function (error) {
        if (!current(revision, requestSequence, 'pipeline') || generation !== pipelineGeneration || error.name === 'AbortError') return false;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        pipelineRunError = 'Unable to load pipeline history. Retry the read.';
        return false;
      }).finally(function () {
        if (current(revision, requestSequence, 'pipeline') && generation === pipelineGeneration) {
          pipelineRunsLoading = false;
          renderPipelineRows();
        }
      });
    }
    function loadIngestionPage(appendPage) {
      if (!credentials().hasAdmin || ingestionLoading || (appendPage && !ingestionCursor)) return Promise.resolve(false);
      var revision = credentials().revision;
      var requestSequence = sequence;
      var generation = pipelineGeneration;
      var cursor = appendPage ? ingestionCursor : null;
      ingestionLoading = true;
      ingestionError = null;
      renderIngestionRows();
      return read(ingestionRunsPath(cursor), revision, requestSequence, 'pipeline').then(function (result) {
        if (!result.current || generation !== pipelineGeneration) return false;
        var page = result.data || {};
        var incoming = Array.isArray(page.items) ? page.items : [];
        ingestionItems = appendPage ? window.CatalystOperationsModel.mergePage({ items: ingestionItems }, { items: incoming }).items : incoming.slice();
        ingestionCursor = page.nextCursor || null;
        ingestionGeneratedAt = page.generatedAt || null;
        ingestionLoaded = true;
        ingestionError = null;
        return true;
      }).catch(function (error) {
        if (!current(revision, requestSequence, 'pipeline') || generation !== pipelineGeneration || error.name === 'AbortError') return false;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        ingestionError = 'Unable to load provider ingestion history. Retry the read.';
        return false;
      }).finally(function () {
        if (current(revision, requestSequence, 'pipeline') && generation === pipelineGeneration) {
          ingestionLoading = false;
          renderIngestionRows();
        }
      });
    }
    function loadSelectedRun() {
      if (!selectedRunId || !credentials().hasAdmin || selectedRunLoading) return Promise.resolve(false);
      var id = selectedRunId;
      var revision = credentials().revision;
      var requestSequence = sequence;
      var detailSequence = ++pipelineDetailSequence;
      selectedRunLoading = true;
      selectedRunError = null;
      toggleHidden('runDetail', false);
      el('runDetailStatus').textContent = 'Loading recorded cycle detail…';
      return read('/internal/operations/runs/' + encodeURIComponent(id), revision, requestSequence, 'pipeline').then(function (result) {
        if (!result.current || selectedRunId !== id || detailSequence !== pipelineDetailSequence) return false;
        selectedRunDetail = result.data;
        observePipelineRuns([result.data && result.data.run]);
        selectedRunLoaded = true;
        selectedRunError = null;
        renderSelectedRunDetail(result.data);
        updateRunNowButton();
        return true;
      }).catch(function (error) {
        if (!current(revision, requestSequence, 'pipeline') || selectedRunId !== id || detailSequence !== pipelineDetailSequence || error.name === 'AbortError') return false;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        selectedRunError = error.code === 'OPERATION_RUN_NOT_FOUND' ? 'Pipeline run not found.' : 'Unable to load pipeline run detail. Retry the read.';
        el('runDetailStatus').textContent = selectedRunError;
        return false;
      }).finally(function () {
        if (current(revision, requestSequence, 'pipeline') && selectedRunId === id && detailSequence === pipelineDetailSequence) {
          selectedRunLoading = false;
          setRunDetailPane();
        }
      });
    }
    function loadRunIssues(appendPage) {
      if (!selectedRunId || !credentials().hasAdmin || runIssuesLoading || (appendPage && !runIssueCursor)) return Promise.resolve(false);
      var id = selectedRunId;
      var revision = credentials().revision;
      var requestSequence = sequence;
      var detailSequence = ++runIssueSequence;
      var cursor = appendPage ? runIssueCursor : null;
      runIssuesLoading = true;
      runIssuesError = null;
      renderRunIssues();
      var query = new URLSearchParams({ limit: '25' });
      if (cursor) query.set('cursor', cursor);
      return read('/internal/operations/runs/' + encodeURIComponent(id) + '/issues?' + query.toString(), revision, requestSequence, 'pipeline')
        .then(function (result) {
          if (!result.current || selectedRunId !== id || detailSequence !== runIssueSequence) return false;
          var page = result.data || {};
          var incoming = Array.isArray(page.items) ? page.items : [];
          runIssues = appendPage ? window.CatalystOperationsModel.mergePage({ items: runIssues }, { items: incoming }).items : incoming.slice();
          runIssueCursor = page.nextCursor || null;
          runIssuesLoaded = true;
          runIssuesError = null;
          return true;
        }).catch(function (error) {
          if (!current(revision, requestSequence, 'pipeline') || selectedRunId !== id || detailSequence !== runIssueSequence || error.name === 'AbortError') return false;
          if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
          runIssuesError = 'Unable to load cycle issues. Retry the read.';
          return false;
        }).finally(function () {
          if (current(revision, requestSequence, 'pipeline') && selectedRunId === id && detailSequence === runIssueSequence) {
            runIssuesLoading = false;
            renderRunIssues();
          }
        });
    }
    function loadSelectedIngestion() {
      if (!selectedIngestionRunId || !credentials().hasAdmin || selectedIngestionLoading) return Promise.resolve(false);
      var id = selectedIngestionRunId;
      var revision = credentials().revision;
      var requestSequence = sequence;
      var detailSequence = ++ingestionDetailSequence;
      selectedIngestionLoading = true;
      selectedIngestionError = null;
      toggleHidden('ingestionDetail', false);
      el('ingestionDetailStatus').textContent = 'Loading selected ingestion run…';
      var query = new URLSearchParams({ ingestionRunId: id, limit: '25' });
      return read('/internal/operations/ingestion-runs?' + query.toString(), revision, requestSequence, 'pipeline').then(function (result) {
        if (!result.current || selectedIngestionRunId !== id || detailSequence !== ingestionDetailSequence) return false;
        var items = result.data && Array.isArray(result.data.items) ? result.data.items : [];
        selectedIngestionRun = items.length ? items[0] : null;
        selectedIngestionLoaded = true;
        selectedIngestionError = selectedIngestionRun ? null : 'Ingestion run not found.';
        renderSelectedIngestionRun(selectedIngestionRun);
        return !!selectedIngestionRun;
      }).catch(function (error) {
        if (!current(revision, requestSequence, 'pipeline') || selectedIngestionRunId !== id || detailSequence !== ingestionDetailSequence || error.name === 'AbortError') return false;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        selectedIngestionError = 'Unable to load selected ingestion run. Retry the read.';
        el('ingestionDetailStatus').textContent = selectedIngestionError;
        return false;
      }).finally(function () {
        if (current(revision, requestSequence, 'pipeline') && selectedIngestionRunId === id && detailSequence === ingestionDetailSequence) {
          selectedIngestionLoading = false;
          setIngestionDetailPane();
        }
      });
    }
    function loadSnapshotSchedule() {
      if (snapshotScheduleLoading || snapshotScheduleLoaded || !credentials().hasAdmin) return Promise.resolve(snapshotScheduleLoaded);
      var revision = credentials().revision;
      var requestSequence = sequence;
      snapshotScheduleLoading = true;
      renderPipelineRows();
      return read('/internal/operations/config', revision, requestSequence, 'pipeline').then(function (result) {
        if (!result.current) return false;
        snapshotScheduleEnabled = !!(result.data && result.data.snapshots && result.data.snapshots.enabled);
        snapshotScheduleLoaded = true;
        return true;
      }).catch(function (error) {
        if (!current(revision, requestSequence, 'pipeline') || error.name === 'AbortError') return false;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        snapshotScheduleEnabled = null;
        return false;
      }).finally(function () {
        if (current(revision, requestSequence, 'pipeline')) {
          snapshotScheduleLoading = false;
          if (credentials().hasAdmin) renderPipelineRows();
        }
      });
    }
    function loadPipelineScreen(force) {
      if (!pipelineRouteValid) return Promise.resolve();
      if (!credentials().hasAdmin) {
        accessRequired();
        return Promise.resolve();
      }
      if (force) {
        pipelineGeneration++;
        pipelineWindow = null;
        pipelineRunsLoading = false;
        pipelineRunsLoaded = false;
        pipelineRunCursor = null;
        pipelineRunError = null;
        ingestionLoading = false;
        ingestionLoaded = false;
        ingestionCursor = null;
        ingestionError = null;
        selectedRunLoading = false;
        selectedRunLoaded = false;
        selectedRunError = null;
        resetRunIngestion();
        runIssuesLoading = false;
        runIssuesLoaded = false;
        runIssueCursor = null;
        runIssuesError = null;
        selectedIngestionLoading = false;
        selectedIngestionLoaded = false;
        selectedIngestionError = null;
        pipelineDetailSequence++;
        runIssueSequence++;
        ingestionDetailSequence++;
        pipelineGeneratedAt = null;
        ingestionGeneratedAt = null;
        if (pipelineKind === 'DAILY_SNAPSHOTS') {
          snapshotScheduleLoaded = false;
          snapshotScheduleLoading = false;
          snapshotScheduleEnabled = null;
        }
      }
      renderPipelineRows();
      renderIngestionRows();
      var revision = credentials().revision;
      var requestSequence = sequence;
      var generation = pipelineGeneration;
      var runTask = !pipelineRunsLoaded || force ? loadPipelineRunPage(false) : Promise.resolve(true);
      return runTask.then(function () {
        if (!current(revision, requestSequence, 'pipeline') || generation !== pipelineGeneration) return;
        var tasks = [];
        if (!ingestionLoaded || force) tasks.push(loadIngestionPage(false));
        if (selectedRunId && (!selectedRunLoaded || force)) tasks.push(loadSelectedRun());
        if (selectedRunId && (!runIssuesLoaded || force)) tasks.push(loadRunIssues(false));
        if (selectedRunId && (!runIngestionLoaded || force)) tasks.push(loadRunIngestion(false));
        if (selectedIngestionRunId && (!selectedIngestionLoaded || force)) tasks.push(loadSelectedIngestion());
        if (pipelineKind === 'DAILY_SNAPSHOTS' && (!snapshotScheduleLoaded || force)) tasks.push(loadSnapshotSchedule());
        return Promise.allSettled(tasks);
      }).then(function () {
        if (!current(revision, requestSequence, 'pipeline') || generation !== pipelineGeneration) return;
        if (pipelineRunsLoaded && ingestionLoaded && !pipelineRunError && !ingestionError) {
          lastPipelineAt = time(pipelineGeneratedAt || ingestionGeneratedAt);
        }
        renderPipelineStatus();
        updateRunNowButton();
      });
    }
    function pipelineOutcome(result) {
      return 'Pipeline ' + esc(result.status || 'completed') + ': ' + integer(result.documentsCompleted) + ' documents completed, ' +
        integer(result.documentsSkipped) + ' skipped, ' + integer(result.documentsRetryScheduled) + ' retry scheduled, ' +
        integer(result.documentsTerminalFailures) + ' terminal failures; ' + integer(result.eventsInserted) + ' events inserted and ' +
        integer(result.eventsReused) + ' reused.';
    }
    function triggerPipeline() {
      if (pendingPost || !credentials().hasAdmin || el('runNow').disabled) return;
      var revision = credentials().revision;
      var requestSequence = sequence;
      pendingPost = true;
      pipelineOutcomeText = null;
      updateRunNowButton();
      pipelineStatus('Starting pipeline…');
      api('/internal/ingestion/runs', { method: 'POST' }).then(function (result) {
        if (revision !== credentials().revision || !current(revision, requestSequence, 'pipeline')) return;
        if (result && result.alreadyRunning) {
          pipelineOutcomeText = 'A pipeline cycle is already running';
          pipelineStatus(pipelineOutcomeText);
          loadPipelineScreen(true);
          return;
        }
        if (result && validUuid(result.runId)) {
          pipelineOutcomeText = pipelineOutcome(result);
          options.navigate(pipelineSearch({ runId: result.runId }));
          return;
        }
        pipelineOutcomeText = pipelineOutcome(result || {});
        pipelineStatus(pipelineOutcomeText);
        loadPipelineScreen(true);
      }).catch(function (error) {
        if (revision !== credentials().revision || !current(revision, requestSequence, 'pipeline')) return;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        pipelineOutcomeText = 'The pipeline response was unavailable. The server may still be running; the request was not retried.';
        pipelineStatus(pipelineOutcomeText);
      }).finally(function () {
        pendingPost = false;
        if (screen === 'pipeline') updateRunNowButton();
      });
    }
    function onPipelineTabClick(event) {
      var tab = event.target.closest('[data-pipeline-kind]');
      if (!tab || !PIPELINE_KINDS.includes(tab.dataset.pipelineKind) || tab.dataset.pipelineKind === pipelineKind) return;
      if (event.preventDefault) event.preventDefault();
      options.navigate(pipelineSearch({ kind: tab.dataset.pipelineKind, runId: null }));
    }
    function onPipelineStatusChange() {
      var next = el('pipelineRunStatusFilter').value;
      if (next && !OPERATION_STATUSES.includes(next)) {
        pipelineStatus('Choose a valid pipeline run status.');
        return;
      }
      options.navigate(pipelineSearch({ status: next }));
    }
    function onIngestionFiltersSubmit(event) {
      event.preventDefault();
      var provider = el('ingestionProvider').value.toLowerCase();
      var status = el('ingestionStatusFilter').value;
      if ((provider && !INGESTION_PROVIDERS.includes(provider)) || (status && !INGESTION_STATUSES.includes(status))) {
        pipelineStatus('Choose valid ingestion filters.');
        return;
      }
      options.navigate(pipelineSearch({ ingestionProvider: provider, ingestionStatus: status }));
    }
    function onRunRowsClick(event) {
      var target = event.target.closest('[data-run-id]');
      if (!target || !validUuid(target.dataset.runId)) return;
      if (event.preventDefault) event.preventDefault();
      options.navigate(pipelineSearch({ runId: target.dataset.runId }));
    }
    function onRunDetailClick(event) {
      var cycleDocuments = event.target.closest('[data-cycle-documents-id]');
      if (cycleDocuments && validUuid(cycleDocuments.dataset.cycleDocumentsId)) {
        if (event.preventDefault) event.preventDefault();
        options.navigate('?view=documents&runId=' + encodeURIComponent(cycleDocuments.dataset.cycleDocumentsId), { returnSearch: currentLocalSearch() });
        return;
      }
      var documentLink = event.target.closest('[data-run-document-id]');
      if (documentLink) {
        if (event.preventDefault) event.preventDefault();
        options.navigate('?view=documents&runId=' + encodeURIComponent(selectedRunId || '') +
          '&documentId=' + encodeURIComponent(documentLink.dataset.runDocumentId), { returnSearch: currentLocalSearch() });
        return;
      }
      var companyLink = event.target.closest('[data-run-company-ticker]');
      if (companyLink) {
        if (event.preventDefault) event.preventDefault();
        options.navigate('?view=company&ticker=' + encodeURIComponent(companyLink.dataset.runCompanyTicker), { returnSearch: currentLocalSearch() });
        return;
      }
      onIngestionAssociationClick(event);
    }
    function onIngestionAssociationClick(event) {
      var select = event.target.closest('[data-ingestion-select-id]');
      if (select && validUuid(select.dataset.ingestionSelectId)) {
        if (event.preventDefault) event.preventDefault();
        options.navigate(pipelineSearch({ ingestionRunId: select.dataset.ingestionSelectId }));
        return;
      }
      var source = event.target.closest('[data-ingestion-source-id]');
      if (source && validUuid(source.dataset.ingestionSourceId)) {
        if (event.preventDefault) event.preventDefault();
        options.navigate('?view=documents&ingestionRunId=' + encodeURIComponent(source.dataset.ingestionSourceId), { returnSearch: currentLocalSearch() });
        return;
      }
      var operation = event.target.closest('[data-operation-run-id]');
      if (operation && validUuid(operation.dataset.operationRunId)) {
        if (event.preventDefault) event.preventDefault();
        options.navigate(pipelineSearch({ runId: operation.dataset.operationRunId }));
      }
    }
    function onCloseRun() { options.navigate(pipelineSearch({ runId: null })); }
    function onCloseIngestion() { options.navigate(pipelineSearch({ ingestionRunId: null })); }
    function onRunNowClick() { triggerPipeline(); }
    function onLoadRunsClick() { loadPipelineRunPage(!!pipelineRunCursor); }
    function onLoadRunIssuesClick() { loadRunIssues(!!runIssueCursor); }
    function onLoadRunIngestionClick() { loadRunIngestion(!!runIngestionCursor); }
    function onLoadIngestionClick() { loadIngestionPage(!!ingestionCursor); }
    function rangeQuery() { return params.get('range') === '7d' ? '?range=7d' : '?range=24h'; }
    function loadOverview(revision, requestSequence) {
      var status = el('overviewStatus');
      if (!overviewLoaded) status.textContent = 'Loading operational summary…';
      return read('/internal/operations/overview' + rangeQuery(), revision, requestSequence, 'overview').then(function (result) {
        if (!result.current) return;
        renderOverview(result.data);
        overviewLoaded = true;
        lastOverviewAt = time(result.data.generatedAt);
        el('lastRefresh').textContent = 'Last loaded ' + lastOverviewAt;
        status.textContent = 'Activity window: ' + time(result.data.window && result.data.window.from) +
          ' to ' + time(result.data.window && result.data.window.to) + ' UTC.';
      }).catch(function (error) {
        if (!current(revision, requestSequence, 'overview') || error.name === 'AbortError') return;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        status.textContent = overviewLoaded ? 'Last loaded ' + (lastOverviewAt || 'previously') + ' · refresh failed'
          : 'Unable to load overview. Retry the read.';
      });
    }
    function loadConfig(revision, requestSequence) {
      var status = el('settingsStatus');
      if (!configLoaded) {
        status.textContent = 'Loading configuration…';
        el('settingsConfig').textContent = 'Loading…';
      }
      return read('/internal/operations/config', revision, requestSequence, 'settings').then(function (result) {
        if (!result.current) return;
        renderConfig(result.data);
        configLoaded = true;
        lastConfigAt = time(result.data.generatedAt);
        status.textContent = 'Configuration loaded ' + lastConfigAt + '.';
        el('accessStatus').textContent = 'Admin key available for this tab.';
      }).catch(function (error) {
        if (!current(revision, requestSequence, 'settings') || error.name === 'AbortError') return;
        if (error.status === 403 || error.code === 'FORBIDDEN') return accessRequired();
        if (!configLoaded) el('settingsConfig').textContent = 'Unknown / unavailable';
        status.textContent = configLoaded ? 'Last loaded ' + (lastConfigAt || 'previously') + ' · refresh failed'
          : 'Unable to load configuration. Retry the read.';
      });
    }
    function refresh(force) {
      if (disposed) return Promise.resolve();
      if (force === undefined) force = true;
      if (pending) return pending;
      var revision = credentials().revision;
      var requestSequence = sequence;
      var expectedScreen = screen;
      var tasks = [loadHealth(revision, requestSequence, expectedScreen)];
      if (credentials().hasAdmin && screen === 'overview') tasks.push(loadOverview(revision, requestSequence));
      else if (credentials().hasAdmin && screen === 'settings') tasks.push(loadConfig(revision, requestSequence));
      else if (credentials().hasAdmin && screen === 'pipeline') tasks.push(loadPipelineScreen(force));
      else if (credentials().hasAdmin && screen === 'documents') tasks.push(loadDocumentsScreen(force));
      else if (credentials().hasAdmin && screen === 'models') tasks.push(loadModelsScreen(force));
      else if (!credentials().hasAdmin) accessRequired();
      var operation = Promise.allSettled(tasks).then(function () {});
      var wrapped = operation.finally(function () { if (pending === wrapped) pending = null; });
      pending = wrapped;
      return wrapped;
    }
    function autoRefreshEnabled() { return !!el('autoRefresh').checked; }
    function onVisibilityChange() {
      if (document.hidden) {
        invalidate();
        return;
      }
      if (autoRefreshEnabled() && POLLED.indexOf(screen) >= 0) refresh();
      else if (!healthLoaded || (screen === 'overview' && !overviewLoaded) ||
        (screen === 'pipeline' && (!pipelineRunsLoaded || !ingestionLoaded || (selectedRunId && (!selectedRunLoaded || !runIssuesLoaded || !runIngestionLoaded)) ||
          (selectedIngestionRunId && !selectedIngestionLoaded))) ||
        (screen === 'documents' && (!documentPageLoaded || (selectedDocumentId && !selectedDocumentLoaded) ||
          (selectedDocumentId && selectedDocumentTab !== 'overview' && !tabState(selectedDocumentTab).loaded))) ||
        (screen === 'models' && (!modelSummaryLoaded || !modelPageLoaded || (selectedModelCallId && !selectedModelLoaded))) ||
        (screen === 'settings' && !configLoaded)) refresh(false);
    }
    function onAutoRefreshChange() {
      if (!document.hidden && autoRefreshEnabled() && POLLED.indexOf(screen) >= 0) refresh();
    }
    function init() {
      if (disposed) return;
      el('autoRefresh').checked = true;
      el('autoRefresh').addEventListener('change', onAutoRefreshChange);
      el('documentFilters').addEventListener('submit', onDocumentFiltersSubmit);
      el('documentRows').addEventListener('click', onDocumentRowsClick);
      el('documentDetail').addEventListener('click', onDocumentDetailClick);
      el('closeDocument').addEventListener('click', closeSelectedDocument);
      el('loadDocuments').addEventListener('click', onLoadDocumentsClick);
      el('loadAttempts').addEventListener('click', onLoadAttemptsClick);
      el('loadDocumentModels').addEventListener('click', onLoadDocumentModelsClick);
      el('loadDocumentEvents').addEventListener('click', onLoadDocumentEventsClick);
      el('pipelineView').addEventListener('click', onPipelineTabClick);
      el('pipelineRunStatusFilter').addEventListener('change', onPipelineStatusChange);
      el('runNow').addEventListener('click', onRunNowClick);
      el('runRows').addEventListener('click', onRunRowsClick);
      el('loadRuns').addEventListener('click', onLoadRunsClick);
      el('runDetail').addEventListener('click', onRunDetailClick);
      el('closeRun').addEventListener('click', onCloseRun);
      el('loadRunIngestion').addEventListener('click', onLoadRunIngestionClick);
      el('loadRunIssues').addEventListener('click', onLoadRunIssuesClick);
      el('ingestionFilters').addEventListener('submit', onIngestionFiltersSubmit);
      el('ingestionRows').addEventListener('click', onIngestionAssociationClick);
      el('loadIngestion').addEventListener('click', onLoadIngestionClick);
      el('ingestionDetail').addEventListener('click', onIngestionAssociationClick);
      el('closeIngestion').addEventListener('click', onCloseIngestion);
      el('modelFilters').addEventListener('submit', onModelFiltersSubmit);
      el('modelRows').addEventListener('click', onModelRowsClick);
      el('modelDetail').addEventListener('click', onModelDetailClick);
      el('closeModel').addEventListener('click', closeSelectedModel);
      el('loadModels').addEventListener('click', onLoadModelsClick);
      DOCUMENT_TABS.forEach(function (tab) {
        el('documentTab' + tab[0].toUpperCase() + tab.slice(1)).addEventListener('click', tabHandlers[tab]);
      });
      document.addEventListener('visibilitychange', onVisibilityChange);
      if (!timer) timer = window.setInterval(function () {
        if (document.hidden || !autoRefreshEnabled() || POLLED.indexOf(screen) < 0) return;
        refresh();
      }, POLL_MS);
    }
    function show(route) {
      if (disposed || !route) return Promise.resolve();
      var previousScreen = screen;
      var nextScreen = route.view || 'overview';
      var nextParams = route.params instanceof URLSearchParams ? route.params : new URLSearchParams();
      var nextSearch = nextParams.toString();
      var changed = !shown || nextScreen !== screen || nextSearch !== params.toString();
      if (changed) {
        invalidate();
        screen = nextScreen;
        params = new URLSearchParams(nextSearch);
        shown = true;
        if (screen === 'documents') configureDocuments(params,
          previousScreen !== 'documents' && !route.fromHistory);
        if (screen === 'models') configureModels(params, previousScreen !== 'models' && !route.fromHistory);
        if (screen === 'pipeline') configurePipeline(params, previousScreen !== 'pipeline' && !route.fromHistory);
        if (screen === 'overview' && !overviewLoaded) el('overviewStatus').textContent = 'Loading operational summary…';
        if (screen === 'settings' && !configLoaded) el('settingsStatus').textContent = 'Configuration has not been loaded.';
      }
      if (OPERATIONAL.indexOf(screen) >= 0 && !credentials().hasAdmin) accessRequired();
      if (!changed) return Promise.resolve();
      return refresh(false);
    }
    function credentialsChanged() {
      knownActivePipelineIds.clear();
      if (disposed) return;
      invalidate();
      documentItems = [];
      documentCursor = null;
      documentGeneratedAt = null;
      documentPageLoaded = false;
      documentPageError = null;
      selectedDocumentDetail = null;
      selectedDocumentLoaded = false;
      selectedDocumentLoading = false;
      documentTabCache = {};
      clearDocumentDetailViews();
      clearModelData();
      clearPipelineData();
      setDocumentPane();
      renderDocumentRows();
      if (!credentials().hasAdmin) {
        clearOverview();
        clearConfig();
        accessRequired();
      } else {
        overviewLoaded = false;
        configLoaded = false;
      }
      return refresh(false);
    }
    function ownsView(view) { return OPERATIONAL.indexOf(view) >= 0; }
    function onAccessClick(event) {
      var link = event.target.closest('[data-settings-access]');
      if (!link) return;
      event.preventDefault();
      var returnRoute = new URLSearchParams(params);
      returnRoute.set('view', screen);
      options.navigate('?view=settings', { returnSearch: '?' + returnRoute.toString() });
    }
    function dispose() {
      if (disposed) return;
      invalidate();
      disposed = true;
      if (timer) window.clearInterval(timer);
      timer = null;
      document.removeEventListener('visibilitychange', onVisibilityChange);
      el('autoRefresh').removeEventListener('change', onAutoRefreshChange);
      el('documentFilters').removeEventListener('submit', onDocumentFiltersSubmit);
      el('documentRows').removeEventListener('click', onDocumentRowsClick);
      el('documentDetail').removeEventListener('click', onDocumentDetailClick);
      el('closeDocument').removeEventListener('click', closeSelectedDocument);
      el('loadDocuments').removeEventListener('click', onLoadDocumentsClick);
      el('loadAttempts').removeEventListener('click', onLoadAttemptsClick);
      el('loadDocumentModels').removeEventListener('click', onLoadDocumentModelsClick);
      el('loadDocumentEvents').removeEventListener('click', onLoadDocumentEventsClick);
      el('pipelineView').removeEventListener('click', onPipelineTabClick);
      el('pipelineRunStatusFilter').removeEventListener('change', onPipelineStatusChange);
      el('runNow').removeEventListener('click', onRunNowClick);
      el('runRows').removeEventListener('click', onRunRowsClick);
      el('loadRuns').removeEventListener('click', onLoadRunsClick);
      el('runDetail').removeEventListener('click', onRunDetailClick);
      el('closeRun').removeEventListener('click', onCloseRun);
      el('loadRunIngestion').removeEventListener('click', onLoadRunIngestionClick);
      el('loadRunIssues').removeEventListener('click', onLoadRunIssuesClick);
      el('ingestionFilters').removeEventListener('submit', onIngestionFiltersSubmit);
      el('ingestionRows').removeEventListener('click', onIngestionAssociationClick);
      el('loadIngestion').removeEventListener('click', onLoadIngestionClick);
      el('ingestionDetail').removeEventListener('click', onIngestionAssociationClick);
      el('closeIngestion').removeEventListener('click', onCloseIngestion);
      el('modelFilters').removeEventListener('submit', onModelFiltersSubmit);
      el('modelRows').removeEventListener('click', onModelRowsClick);
      el('modelDetail').removeEventListener('click', onModelDetailClick);
      el('closeModel').removeEventListener('click', closeSelectedModel);
      el('loadModels').removeEventListener('click', onLoadModelsClick);
      DOCUMENT_TABS.forEach(function (tab) {
        el('documentTab' + tab[0].toUpperCase() + tab.slice(1)).removeEventListener('click', tabHandlers[tab]);
      });
      el('overviewSignals').removeEventListener('click', onLocalRouteClick);
      el('overviewMetrics').removeEventListener('click', onLocalRouteClick);
      ACCESS_STATUS_IDS.forEach(function (id) { el(id).removeEventListener('click', onAccessClick); });
    }
    function onLocalRouteClick(event) {
      var link = event.target.closest('[data-local-route]');
      if (!link) return;
      var href = link.dataset.localRoute;
      if (typeof href === 'string' && href.startsWith('?view=')) {
        event.preventDefault();
        options.navigate(href);
      }
    }

    el('overviewSignals').addEventListener('click', onLocalRouteClick);
    el('overviewMetrics').addEventListener('click', onLocalRouteClick);
    ACCESS_STATUS_IDS.forEach(function (id) { el(id).addEventListener('click', onAccessClick); });
    return { init: init, show: show, refresh: refresh, credentialsChanged: credentialsChanged, dispose: dispose, ownsView: ownsView };
  }

  window.CatalystOperations = Object.freeze({ create: create });
})();
