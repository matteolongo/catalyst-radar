const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const app = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');

function startDashboard({ search = '', stored = {}, origin = 'https://ops.example' } = {}) {
  const requests = [];
  const values = new Map(Object.entries(stored));
  const elements = new Map();
  let reloads = 0;
  let completePipeline;
  let ready;
  const document = {
    getElementById(id) {
      if (!elements.has(id)) {
        const handlers = {};
        elements.set(id, {
          innerHTML: '', textContent: '', value: '', checked: false,
          disabled: false, href: '', className: '',
          classList: { add() {}, remove() {} },
          addEventListener(name, handler) { handlers[name] = handler; },
          trigger(name) { return handlers[name](); },
        });
      }
      return elements.get(id);
    },
    addEventListener(name, handler) {
      if (name === 'DOMContentLoaded') ready = handler;
    },
  };
  const window = {
    location: { origin, search, reload() { reloads++; } },
    sessionStorage: {
      getItem(key) { return values.get(key) || null; },
      setItem(key, value) { values.set(key, value); },
      removeItem(key) { values.delete(key); },
    },
    setInterval() { return 1; },
    clearInterval() {},
  };
  async function fetch(url, options) {
    requests.push({ url, headers: options.headers });
    const route = new URL(url).pathname;
    if (route === '/internal/ingestion/runs' && options.method === 'POST') {
      return new Promise((resolve) => {
        completePipeline = () => resolve({ ok: true, status: 200, json: async () => ({
          status: 'SUCCESS', documentsProcessed: 1, eventsExtracted: 1,
          companiesRescored: 1, error: null, documentsSkipped: 0,
          documentsRetryScheduled: 0, documentsTerminalFailures: 0,
          alreadyRunning: false,
        }) });
      });
    }
    if (route.startsWith('/internal/') && options.headers['X-Admin-Key'] !== 'admin-secret') {
      return { ok: false, status: 403, json: async () => ({ detail: 'Admin key required' }) };
    }
    const body = route === '/actuator/health' ? { status: 'UP' }
      : route === '/internal/ingestion/runs' ? { runs: [{
        id: 'a7f2cd30-6717-4e3a-8870-4bdba9d7af95', provider: 'polygon', status: 'SUCCESS',
        fetched: 1, added: 1, duplicates: 0, error: null, finishedAt: '2026-10-02T12:00:00Z',
      }] }
      : route === '/internal/model-runs' ? { runs: [{
        id: 'b7f2cd30-6717-4e3a-8870-4bdba9d7af95', provider: 'openai', operation: 'extract',
        model: 'model', promptVersion: 'prompt-v1', extractorVersion: 'extractor-v1',
        sourceDocumentId: null, inputTokens: 10, outputTokens: 2, latencyMs: 100,
        estimatedCost: 0.01, success: true, error: null, createdAt: '2026-10-02T12:01:00Z',
      }] }
      : { asOf: '2026-10-02T12:02:00Z', total: 1, limit: 5, offset: 0, results: [{
        ticker: 'DELL', name: 'Dell', sector: 'Technology', score: 30, state: 'WATCH',
        velocity7d: 1, events7d: 1, scoreVersion: 'score-v1', taxonomyVersion: 'taxonomy-v1',
        asOf: '2026-10-02T12:00:00Z',
      }] };
    return { ok: true, status: 200, json: async () => body };
  }
  vm.runInNewContext(app, { window, document, fetch, URLSearchParams });
  ready();
  return {
    requests, elements, values,
    get reloads() { return reloads; },
    completePipeline() { completePipeline(); },
  };
}

async function waitForRequests(requests, count) {
  for (let i = 0; i < 20 && requests.length < count; i++) {
    await new Promise(setImmediate);
  }
  assert.equal(requests.length, count);
}

test('the dashboard sends requests only to the origin serving it', async () => {
  const dashboard = startDashboard({
    search: '?api=https://example.invalid',
    stored: { 'catalyst-admin-key': 'admin-secret' },
  });
  await waitForRequests(dashboard.requests, 4);
  assert.ok(dashboard.requests.every(({ url }) => url.startsWith('https://ops.example/')));
});

test('each credential is sent only to the API routes that need it', async () => {
  const dashboard = startDashboard({
    stored: { 'catalyst-admin-key': 'admin-secret', 'catalyst-api-key': 'public-secret' },
  });
  await waitForRequests(dashboard.requests, 4);
  const byPath = Object.fromEntries(dashboard.requests.map((request) => [new URL(request.url).pathname, request.headers]));
  assert.equal(byPath['/actuator/health']['X-Admin-Key'], undefined);
  assert.equal(byPath['/actuator/health'].Authorization, undefined);
  assert.equal(byPath['/internal/ingestion/runs']['X-Admin-Key'], 'admin-secret');
  assert.equal(byPath['/internal/ingestion/runs'].Authorization, undefined);
  assert.equal(byPath['/v1/discovery/catalyzed'].Authorization, 'Bearer public-secret');
  assert.equal(byPath['/v1/discovery/catalyzed']['X-Admin-Key'], undefined);
});

test('clearing keys removes protected run data from the page', async () => {
  const dashboard = startDashboard({ stored: { 'catalyst-admin-key': 'admin-secret' } });
  await waitForRequests(dashboard.requests, 4);
  const rows = dashboard.elements.get('ingestionRows');
  assert.match(rows.innerHTML, /polygon/);

  dashboard.elements.get('clearKey').trigger('click');
  assert.doesNotMatch(rows.innerHTML, /polygon/);
  assert.equal(dashboard.elements.get('runNow').disabled, true);
  assert.equal(dashboard.values.has('catalyst-admin-key'), false);
  assert.equal(dashboard.reloads, 1);
});

test('refreshing while a pipeline run is pending keeps the run button disabled', async () => {
  const dashboard = startDashboard({ stored: { 'catalyst-admin-key': 'admin-secret' } });
  await waitForRequests(dashboard.requests, 4);
  const button = dashboard.elements.get('runNow');
  button.trigger('click');
  assert.equal(button.disabled, true);

  dashboard.elements.get('refresh').trigger('click');
  assert.equal(button.disabled, true);
  dashboard.completePipeline();
});
