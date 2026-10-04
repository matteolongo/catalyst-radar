(function () {
  'use strict';

  var POLL_MS = 30000;
  var POLLED = ['overview', 'pipeline', 'documents', 'models'];
  var OPERATIONAL = ['overview', 'pipeline', 'documents', 'models', 'settings'];
  var ACCESS_STATUS_IDS = ['overviewStatus', 'pipelineStatus', 'documentStatus', 'modelStatus'];
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
      }
      el('lastRefresh').textContent = 'No successful refresh yet';
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
    function refresh() {
      if (disposed) return Promise.resolve();
      if (pending) return pending;
      var revision = credentials().revision;
      var requestSequence = sequence;
      var expectedScreen = screen;
      var tasks = [loadHealth(revision, requestSequence, expectedScreen)];
      if (credentials().hasAdmin && screen === 'overview') tasks.push(loadOverview(revision, requestSequence));
      else if (credentials().hasAdmin && screen === 'settings') tasks.push(loadConfig(revision, requestSequence));
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
        (screen === 'settings' && !configLoaded)) refresh();
    }
    function onAutoRefreshChange() {
      if (!document.hidden && autoRefreshEnabled() && POLLED.indexOf(screen) >= 0) refresh();
    }
    function init() {
      if (disposed) return;
      el('autoRefresh').checked = true;
      el('autoRefresh').addEventListener('change', onAutoRefreshChange);
      document.addEventListener('visibilitychange', onVisibilityChange);
      if (!timer) timer = window.setInterval(function () {
        if (document.hidden || !autoRefreshEnabled() || POLLED.indexOf(screen) < 0) return;
        refresh();
      }, POLL_MS);
    }
    function show(route) {
      if (disposed || !route) return Promise.resolve();
      var nextScreen = route.view || 'overview';
      var nextParams = route.params instanceof URLSearchParams ? route.params : new URLSearchParams();
      var nextSearch = nextParams.toString();
      var changed = !shown || nextScreen !== screen || nextSearch !== params.toString();
      if (changed) {
        invalidate();
        screen = nextScreen;
        params = new URLSearchParams(nextSearch);
        shown = true;
        if (screen === 'overview' && !overviewLoaded) el('overviewStatus').textContent = 'Loading operational summary…';
        if (screen === 'settings' && !configLoaded) el('settingsStatus').textContent = 'Configuration has not been loaded.';
      }
      if (OPERATIONAL.indexOf(screen) >= 0 && !credentials().hasAdmin) accessRequired();
      if (!changed) return Promise.resolve();
      return refresh();
    }
    function credentialsChanged() {
      if (disposed) return;
      invalidate();
      if (!credentials().hasAdmin) {
        clearOverview();
        clearConfig();
        accessRequired();
      } else {
        overviewLoaded = false;
        configLoaded = false;
      }
      return refresh();
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
