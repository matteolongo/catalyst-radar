/* CatalystRadar Ops dashboard — vanilla JS, no dependencies.
 * Reads the versioned API plus the admin run-history endpoints.
 * Keys live in sessionStorage and are sent only to the routes that need them.
 */
(function () {
  'use strict';

  var API_BASE = (window.CATALYST_API_BASE || 'http://localhost:8080').replace(/\/$/, '');
  var ADMIN_KEY_NAME = 'catalyst-admin-key';
  var API_KEY_NAME = 'catalyst-api-key';
  var pipelineRunning = false;

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

  async function refreshLeaders() {
    var page;
    try {
      page = await api('/v1/discovery/catalyzed?limit=5');
    } catch (err) {
      $('leaderRows').innerHTML = '<tr><td colspan="6" class="muted">Unable to load leaders.</td></tr>';
      showError('Discovery: ' + err.message);
      return;
    }
    if (!page.results.length) {
      $('leaderRows').innerHTML = '<tr><td colspan="6" class="muted">No scored companies yet — run the pipeline.</td></tr>';
      return;
    }
    $('leaderRows').innerHTML = page.results.map(function (entry) {
      return '<tr><td><strong>' + esc(entry.ticker) + '</strong><br><span class="muted small">' +
        esc(entry.name) + '</span></td>' +
        '<td class="num">' + Number(entry.score).toFixed(1) + '</td>' +
        '<td>' + statusPill(entry.state) + '</td>' +
        '<td class="num">' + Number(entry.velocity7d).toFixed(1) + '</td>' +
        '<td class="num">' + fmtInt(entry.events7d) + '</td>' +
        '<td>' + esc(fmtTime(entry.asOf)) + '</td></tr>';
    }).join('');
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
    await refreshHealth();
    if (hasAdminKey) {
      await refreshIngestionRuns();
      await refreshModelRuns();
    }
    await refreshLeaders();
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
    setPolling($('autoRefresh').checked);
    refreshAll();
  });
})();
