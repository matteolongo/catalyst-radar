const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const app = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');

function startDashboard({ search = '', stored = {}, origin = 'https://ops.example', discovery, deferDiscovery = false, deferAdmin = false } = {}) {
  const requests = [];
  const values = new Map(Object.entries(stored));
  const elements = new Map();
  let reloads = 0;
  let completePipeline;
  let ready;
  const windowHandlers = {};
  const discoveryResolvers = [];
  const adminResolvers = [];
  const document = {
    activeElement: null,
    getElementById(id) {
      if (!elements.has(id)) {
        const handlers = {};
        elements.set(id, {
          innerHTML: '', textContent: '', value: '', checked: false,
          disabled: false, href: '', className: '',
          classList: { add(name) { this.owner.hidden = name === 'hidden'; }, remove() { this.owner.hidden = false; } },
          setAttribute(name, value) { this[name] = value; },
          removeAttribute(name) { delete this[name]; },
          focus() { document.activeElement = this; },
          addEventListener(name, handler) { handlers[name] = handler; },
          trigger(name, event = {}) { return handlers[name](event); },
        });
        elements.get(id).classList.owner = elements.get(id);
      }
      return elements.get(id);
    },
    addEventListener(name, handler) {
      if (name === 'DOMContentLoaded') ready = handler;
    },
  };
  const window = {
    location: { origin, search, reload() { reloads++; } },
    history: { pushState(_state, _title, url) { window.location.search = new URL(url, origin).search; } },
    addEventListener(name, handler) { windowHandlers[name] = handler; },
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
    if (route === '/v1/discovery/catalyzed' && deferDiscovery) {
      return new Promise((resolve) => discoveryResolvers.push((body) => resolve({ ok: true, status: 200, json: async () => body })));
    }
    if (route.startsWith('/internal/') && deferAdmin && options.method !== 'POST') {
      return new Promise((resolve) => adminResolvers.push(() => resolve({ ok: true, status: 200, json: async () => ({ runs: [] }) })));
    }
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
    if (route === '/v1/discovery/catalyzed' && discovery === 'error') {
      return { ok: false, status: 503, json: async () => ({ detail: 'Discovery unavailable' }) };
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
      : discovery || { asOf: '2026-10-02T12:02:00Z', total: 1, limit: 20, offset: 0, results: [{
        ticker: 'DELL', name: 'Dell', sector: 'Technology', score: 30, state: 'WATCH',
        velocity7d: 1, events7d: 1, scoreVersion: 'score-v1', taxonomyVersion: 'taxonomy-v1',
        asOf: '2026-10-02T12:00:00Z',
      }] };
    return { ok: true, status: 200, json: async () => body };
  }
  vm.runInNewContext(app, { window, document, fetch, URLSearchParams });
  ready();
  return {
    requests, elements, values, window, document,
    resolveDiscovery(index, body) { discoveryResolvers[index](body); },
    resolveAdmin(index) { adminResolvers[index](); },
    popstate(search) { window.location.search = search; windowHandlers.popstate(); },
    get reloads() { return reloads; },
    completePipeline() { completePipeline(); },
  };
}

async function waitForRequests(requests, count) {
  for (let i = 0; i < 20 && requests.length < count; i++) {
    await new Promise(setImmediate);
  }
  assert.equal(requests.length, count);
  await new Promise(setImmediate);
}

const discoveryPage = (ticker, total = 1, offset = 0) => ({
  asOf: '2026-10-02T12:02:00Z', total, limit: 20, offset,
  results: [{ ticker, name: ticker + ' Corp', sector: 'Technology', score: 30,
    state: 'WATCH', velocity7d: 1, events7d: 1, scoreVersion: 'score-v1',
    taxonomyVersion: 'taxonomy-v1', asOf: '2026-10-02T12:00:00Z' }],
});

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

test('initial load shows Discover with an accessible destination navigation', async () => {
  const dashboard = startDashboard();
  await waitForRequests(dashboard.requests, 2);
  assert.match(html, /<nav[^>]+aria-label="Main navigation"/);
  assert.match(html, /id="discoverView"/);
  assert.match(html, /id="eventsView"/);
  assert.match(html, /id="operationsView"/);
  assert.equal(dashboard.elements.get('discoverView').hidden, false);
  assert.equal(dashboard.elements.get('operationsView').hidden, true);
  assert.match(dashboard.elements.get('discoveryResults').innerHTML, /DELL/);
});

test('a discovery ticker opens a company route and browser back restores Discover', async () => {
  const dashboard = startDashboard();
  await waitForRequests(dashboard.requests, 2);
  dashboard.elements.get('discoveryResults').trigger('click', { target: { closest: () => ({ dataset: { ticker: 'DELL' } }) } });
  assert.equal(dashboard.window.location.search, '?view=company&ticker=DELL');
  assert.equal(dashboard.elements.get('companyView').hidden, false);
  dashboard.popstate('');
  assert.equal(dashboard.elements.get('discoverView').hidden, false);
});

test('discovery filters serialize repeated states and zero velocity', async () => {
  const dashboard = startDashboard();
  await waitForRequests(dashboard.requests, 2);
  dashboard.elements.get('discoveryStates').selectedOptions = [{ value: 'CATALYZED' }, { value: 'HIGH' }];
  dashboard.elements.get('minScore').value = '65';
  dashboard.elements.get('minVelocity7d').value = '0';
  dashboard.elements.get('sector').value = 'Technology';
  dashboard.elements.get('discoverySort').value = 'VELOCITY';
  await dashboard.elements.get('discoveryFilters').trigger('submit', { preventDefault() {} });
  assert.equal(new URL(dashboard.requests.at(-1).url).pathname + new URL(dashboard.requests.at(-1).url).search,
    '/v1/discovery/catalyzed?state=CATALYZED&state=HIGH&minScore=65&minVelocity7d=0&sector=Technology&sort=VELOCITY&limit=20&offset=0');
});

test('next page preserves filters and advances offset', async () => {
  const dashboard = startDashboard({ discovery: { asOf: '2026-10-02T12:02:00Z', total: 44, limit: 20, offset: 0, results: [{ ticker: 'DELL', name: 'Dell', sector: 'Technology', score: 30, state: 'WATCH', velocity7d: 1, events7d: 1, scoreVersion: 'score-v1', asOf: '2026-10-02T12:00:00Z' }] } });
  await waitForRequests(dashboard.requests, 2);
  dashboard.elements.get('sector').value = 'Technology';
  await dashboard.elements.get('discoveryFilters').trigger('submit', { preventDefault() {} });
  await dashboard.elements.get('nextPage').trigger('click');
  const query = new URL(dashboard.requests.at(-1).url).searchParams;
  assert.equal(query.get('sector'), 'Technology');
  assert.equal(query.get('limit'), '20');
  assert.equal(query.get('offset'), '20');
});

test('an empty discovery page gives a clear empty state and disables paging', async () => {
  const dashboard = startDashboard({ discovery: { asOf: '2026-10-02T12:02:00Z', total: 0, limit: 20, offset: 0, results: [] } });
  await waitForRequests(dashboard.requests, 2);
  assert.match(dashboard.elements.get('discoveryStatus').textContent, /No companies match/);
  assert.equal(dashboard.elements.get('nextPage').disabled, true);
});

test('a discovery API failure gives an error state', async () => {
  const dashboard = startDashboard({ discovery: 'error' });
  await waitForRequests(dashboard.requests, 2);
  assert.match(dashboard.elements.get('discoveryStatus').textContent, /Unable to load/);
  assert.match(dashboard.elements.get('banner').textContent, /Discovery unavailable/);
});

test('discovery shows score metadata and separate query and company timestamps', async () => {
  const dashboard = startDashboard();
  await waitForRequests(dashboard.requests, 2);
  assert.match(dashboard.elements.get('discoveryResults').innerHTML, /Technology/);
  assert.match(dashboard.elements.get('discoveryResults').innerHTML, /score-v1/);
  assert.match(dashboard.elements.get('discoveryResults').innerHTML, /Company as of/);
  assert.match(dashboard.elements.get('discoveryAsOf').textContent, /Discovery query as of/);
});

test('older discovery responses cannot replace the latest filtered results or page summary', async () => {
  const dashboard = startDashboard({ deferDiscovery: true });
  await waitForRequests(dashboard.requests, 2);
  dashboard.elements.get('sector').value = 'Old';
  dashboard.elements.get('discoveryFilters').trigger('submit', { preventDefault() {} });
  dashboard.elements.get('sector').value = 'New';
  dashboard.elements.get('discoveryFilters').trigger('submit', { preventDefault() {} });
  await waitForRequests(dashboard.requests, 4);

  dashboard.resolveDiscovery(2, discoveryPage('NEW', 21));
  await new Promise(setImmediate);
  dashboard.resolveDiscovery(1, discoveryPage('OLD', 2));
  dashboard.resolveDiscovery(0, discoveryPage('INITIAL', 1));
  await new Promise(setImmediate);
  assert.match(dashboard.elements.get('discoveryResults').innerHTML, /NEW/);
  assert.doesNotMatch(dashboard.elements.get('discoveryResults').innerHTML, /OLD|INITIAL/);
  assert.equal(dashboard.elements.get('pageSummary').textContent, 'Results 1–1 of 21');
});

test('page controls stay disabled during a discovery request', async () => {
  const dashboard = startDashboard({ deferDiscovery: true });
  await waitForRequests(dashboard.requests, 2);
  dashboard.resolveDiscovery(0, discoveryPage('FIRST', 44));
  await new Promise(setImmediate);
  dashboard.elements.get('nextPage').trigger('click');
  await waitForRequests(dashboard.requests, 3);
  assert.equal(dashboard.elements.get('nextPage').disabled, true);
  dashboard.elements.get('nextPage').trigger('click');
  assert.equal(dashboard.requests.length, 3);
  dashboard.resolveDiscovery(1, discoveryPage('SECOND', 44, 20));
});

test('company navigation, return, and popstate move focus into the visible view', async () => {
  const dashboard = startDashboard();
  await waitForRequests(dashboard.requests, 2);
  const tickerLink = { dataset: { ticker: 'DELL' }, focus() { dashboard.document.activeElement = this; } };
  tickerLink.focus();
  dashboard.elements.get('discoveryResults').trigger('click', { target: { closest: () => tickerLink } });
  assert.equal(dashboard.document.activeElement, dashboard.elements.get('companyTitle'));
  dashboard.elements.get('backToResults').focus();
  dashboard.elements.get('backToResults').trigger('click');
  assert.equal(dashboard.document.activeElement, dashboard.elements.get('discoverTitle'));
  dashboard.popstate('?view=company&ticker=DELL');
  assert.equal(dashboard.document.activeElement, dashboard.elements.get('companyTitle'));
  dashboard.popstate('');
  assert.equal(dashboard.document.activeElement, dashboard.elements.get('discoverTitle'));
});

test('discovery starts without waiting for slow Operations requests', async () => {
  const dashboard = startDashboard({ stored: { 'catalyst-admin-key': 'admin-secret' }, deferAdmin: true });
  await new Promise(setImmediate);
  await new Promise(setImmediate);
  assert.ok(dashboard.requests.some((request) => new URL(request.url).pathname === '/v1/discovery/catalyzed'));
  assert.match(dashboard.elements.get('discoveryResults').innerHTML, /DELL/);
});

test('paging keeps applied filters and page size when controls are edited but not applied', async () => {
  const dashboard = startDashboard({ discovery: discoveryPage('DELL', 120) });
  await waitForRequests(dashboard.requests, 2);
  dashboard.elements.get('sector').value = 'Technology';
  dashboard.elements.get('pageSize').value = '50';
  await dashboard.elements.get('discoveryFilters').trigger('submit', { preventDefault() {} });

  dashboard.elements.get('sector').value = 'Healthcare';
  dashboard.elements.get('pageSize').value = '100';
  await dashboard.elements.get('nextPage').trigger('click');
  const next = new URL(dashboard.requests.at(-1).url).searchParams;
  assert.equal(next.get('sector'), 'Technology');
  assert.equal(next.get('limit'), '50');
  assert.equal(next.get('offset'), '50');

  await dashboard.elements.get('previousPage').trigger('click');
  const previous = new URL(dashboard.requests.at(-1).url).searchParams;
  assert.equal(previous.get('sector'), 'Technology');
  assert.equal(previous.get('limit'), '50');
  assert.equal(previous.get('offset'), '0');
});
