const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const app = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');
const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');

function startDashboard({ search = '', stored = {}, origin = 'https://ops.example', discovery, events, company = {}, deferDiscovery = false, deferAdmin = false, deferTimeline = false } = {}) {
  const requests = [];
  const values = new Map(Object.entries(stored));
  const elements = new Map();
  let reloads = 0;
  let completePipeline;
  let ready;
  const windowHandlers = {};
  const discoveryResolvers = [];
  const adminResolvers = [];
  const timelineResolvers = [];
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
    if (route.endsWith('/timeline') && deferTimeline) {
      return new Promise((resolve) => timelineResolvers.push((body) => resolve({ ok: true, status: 200, json: async () => body })));
    }
    if (route.startsWith('/v1/companies/')) {
      const suffix = route.replace('/v1/companies/DELL', '') || 'metadata';
      const answer = typeof company[suffix] === 'function' ? company[suffix](new URL(url)) : company[suffix];
      return answer === 'error' ? { ok: false, status: 404, json: async () => ({ detail: 'Company not found' }) }
        : { ok: true, status: 200, json: async () => answer || companyFixture[suffix] };
    }
    if (route === '/v1/discovery/catalyzed' && deferDiscovery) {
      return new Promise((resolve) => discoveryResolvers.push((body) => resolve(body === 'error'
        ? { ok: false, status: 503, json: async () => ({ detail: 'Discovery unavailable' }) }
        : { ok: true, status: 200, json: async () => body })));
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
    if (route === '/v1/events') {
      const answer = typeof events === 'function' ? events(new URL(url)) : events;
      return answer === 'error' ? { ok: false, status: 503, json: async () => ({ detail: 'Events unavailable' }) }
        : { ok: true, status: 200, json: async () => answer || eventFixture };
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
  vm.runInNewContext(app, { window, document, fetch, URLSearchParams, URL });
  ready();
  return {
    requests, elements, values, window, document,
    resolveDiscovery(index, body) { discoveryResolvers[index](body); },
    resolveTimeline(index, body) { timelineResolvers[index](body); },
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

const companyFixture = {
  metadata: { ticker: 'DELL', name: 'Dell Technologies', exchange: 'NYSE', sector: 'Technology', industry: 'Hardware', country: 'US', active: true },
  '/catalyst': {
    ticker: 'DELL', score: 68, state: 'CATALYZED', velocity1d: 2, velocity3d: 3, velocity7d: 5,
    positiveScore: 72, negativeScore: 4, directScore: 60, inferredScore: 8,
    totalEvents: 12, events7d: 3, scoreVersion: 'score-v1', taxonomyVersion: 'taxonomy-v1',
    asOf: '2026-10-02T12:00:00Z', stateBand: { state: 'CATALYZED', minScore: 65, maxScore: 80, maxInclusive: false },
    explanationStatus: 'RECONSTRUCTED_SCORE_MATCH',
    scoreCalculation: { contributionSum: 11, familyCount: 2, convergenceMultiplier: 1.1, rawScore: 12.1, normalizationScale: 5, contributionCutoff: 0.1 },
    topDrivers: [{ eventId: 'event-1', type: 'GUIDANCE_RAISE', family: 'GUIDANCE', direction: 'POSITIVE', contribution: 8,
      eventTimestamp: '2026-09-30T09:00:00Z', discoveredAt: '2026-10-01T10:00:00Z', clusterId: 'cluster-1',
      factors: { sign: 1, baseWeight: 10, confidence: 0.9, materialityFactor: 1, surpriseFactor: 1, sourceQualityFactor: 1, directnessFactor: 1, timeDecayFactor: 0.9, value: 8 },
      evidence: [{ quoteOrFact: 'Raised guidance <script>alert(1)</script>', sourceOffsetHint: null }],
      source: { sourceDocumentId: 'source-1', title: 'Quarterly update', provider: 'polygon', publishedAt: '2026-09-30T11:00:00Z', canonicalUrl: 'https://example.com/story' } }],
  },
  '/timeline': { ticker: 'DELL', snapshots: [
    { score: 68, state: 'CATALYZED', asOf: '2026-10-02T12:00:00Z' },
    { score: 44, state: 'WATCH', asOf: '2026-09-30T12:00:00Z' },
  ], transitions: [{ from: 'WATCH', to: 'CATALYZED', score: 68, scoreVersion: 'score-v1', at: '2026-10-02T12:00:00Z' }] },
  '/events': { events: [{ id: 'event-1', type: 'GUIDANCE_RAISE', family: 'GUIDANCE', direction: 'POSITIVE',
    eventTimestamp: '2026-09-30T09:00:00Z', discoveredAt: '2026-10-01T10:00:00Z', clusterId: 'cluster-1',
    evidence: [{ quoteOrFact: 'Raised guidance', sourceOffsetHint: null }],
    source: { sourceDocumentId: 'source-1', title: 'Quarterly update', provider: 'polygon', publishedAt: '2026-09-30T11:00:00Z', canonicalUrl: 'https://example.com/story' } }], nextCursor: null },
};

const discoveryPage = (ticker, total = 1, offset = 0) => ({
  asOf: '2026-10-02T12:02:00Z', total, limit: 20, offset,
  results: [{ ticker, name: ticker + ' Corp', sector: 'Technology', score: 30,
    state: 'WATCH', velocity7d: 1, events7d: 1, scoreVersion: 'score-v1',
    taxonomyVersion: 'taxonomy-v1', asOf: '2026-10-02T12:00:00Z' }],
});

const eventFixture = { events: [{ ...companyFixture['/events'].events[0], ticker: 'DELL', companyName: 'Dell Technologies' }], nextCursor: null };

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

test('discovery failure clears the previous page range', async () => {
  const dashboard = startDashboard({ deferDiscovery: true });
  await waitForRequests(dashboard.requests, 2);
  dashboard.resolveDiscovery(0, discoveryPage('DELL', 44));
  await new Promise(setImmediate);
  assert.match(dashboard.elements.get('pageSummary').textContent, /Results 1–1 of 44/);

  dashboard.elements.get('nextPage').trigger('click');
  await waitForRequests(dashboard.requests, 3);
  dashboard.resolveDiscovery(1, 'error');
  await new Promise(setImmediate);
  assert.equal(dashboard.elements.get('pageSummary').textContent, '');
});

test('company route renders metadata, current metrics, reconstructed evidence, and distinct dates', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL' });
  await waitForRequests(dashboard.requests, 6);
  assert.match(dashboard.elements.get('companyTitle').textContent, /Dell Technologies.*DELL/);
  assert.match(dashboard.elements.get('companyOverview').innerHTML, /NYSE.*Technology.*Hardware/s);
  const score = dashboard.elements.get('companyScore').innerHTML;
  assert.match(score, /68\.0.*CATALYZED.*65.*80/s);
  assert.match(score, /1-day velocity.*3-day velocity.*7-day velocity.*Total events.*Events 7d.*score-v1.*taxonomy-v1/s);
  const why = dashboard.elements.get('companyExplanation').innerHTML;
  assert.match(why, /reconstructed/i);
  assert.match(why, /original driver list/i);
  assert.match(why, /Raised guidance &lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.match(why, /Event date.*First captured.*Source publication date/s);
  assert.match(why, /https:\/\/example.com\/story.*noopener noreferrer/s);
  assert.match(why, /Scoring details/);
  assert.match(dashboard.elements.get('companyEvents').innerHTML, /GUIDANCE_RAISE/);
});

test('unknown ticker reports a company error while independent panels remain visible', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { metadata: 'error' } });
  await waitForRequests(dashboard.requests, 6);
  assert.match(dashboard.elements.get('companyOverview').textContent, /Unable to load company/);
  assert.match(dashboard.elements.get('companyScore').innerHTML, /68\.0/);
  assert.match(dashboard.elements.get('companyEvents').innerHTML, /GUIDANCE_RAISE/);
});

test('one failed company endpoint leaves the other panels usable', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { '/timeline': 'error' } });
  await waitForRequests(dashboard.requests, 6);
  assert.match(dashboard.elements.get('companyHistory').textContent, /Unable to load score history/);
  assert.match(dashboard.elements.get('companyScore').innerHTML, /68\.0/);
  assert.match(dashboard.elements.get('companyEvents').innerHTML, /GUIDANCE_RAISE/);
});

test('history shows zero and one snapshot without inventing a line', async () => {
  for (const snapshots of [[], [{ score: 44, state: 'WATCH', asOf: '2026-09-30T12:00:00Z' }]]) {
    const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { '/timeline': { ticker: 'DELL', snapshots, transitions: [] } } });
    await waitForRequests(dashboard.requests, 6);
    const chart = dashboard.elements.get('companyHistory').innerHTML;
    assert.match(chart, snapshots.length ? /Insufficient history.*44/s : /No score history/);
    assert.doesNotMatch(chart, /<polyline/);
  }
});

test('history keeps persisted transitions visible when no snapshots are returned', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { '/timeline': {
    ticker: 'DELL', snapshots: [], transitions: [{ from: 'NORMAL', to: 'WATCH', score: 30,
      scoreVersion: 'score-v1', at: '2026-09-29T12:00:00Z' }],
  } } });
  await waitForRequests(dashboard.requests, 6);
  const history = dashboard.elements.get('companyHistory').innerHTML;
  assert.match(history, /No score history/);
  assert.match(history, /State transitions at persisted times/);
  assert.match(history, /2026-09-29T12:00:00Z.*NORMAL → WATCH/s);
});

test('history chart includes transition times outside snapshot extent', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { '/timeline': {
    ticker: 'DELL', snapshots: [
      { score: 44, state: 'WATCH', asOf: '2026-09-30T12:00:00Z' },
      { score: 68, state: 'CATALYZED', asOf: '2026-10-02T12:00:00Z' },
    ], transitions: [{ from: 'NORMAL', to: 'WATCH', score: 30,
      scoreVersion: 'score-v1', at: '2026-09-29T12:00:00Z' }],
  } } });
  await waitForRequests(dashboard.requests, 6);
  const history = dashboard.elements.get('companyHistory').innerHTML;
  assert.match(history, /<path class="transition-mark" d="M30 20V130"/);
  assert.match(history, /<circle class="score-point" cx="210"/);
  assert.doesNotMatch(history, /<polyline/);
});

test('history sorts actual snapshots, labels transitions, and requests capped ranges', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL' });
  await waitForRequests(dashboard.requests, 6);
  const chart = dashboard.elements.get('companyHistory').innerHTML;
  assert.ok(chart.indexOf('2026-09-30') < chart.indexOf('2026-10-02'));
  assert.match(chart, /WATCH → CATALYZED/);
  assert.match(chart, /<table/);
  assert.equal((chart.match(/class="score-point"/g) || []).length, 2);
  assert.doesNotMatch(chart, /<polyline|class="score-line"/);
  const timeline = dashboard.requests.find(({ url }) => new URL(url).pathname.endsWith('/timeline'));
  assert.equal(new URL(timeline.url).searchParams.get('limit'), '200');
  dashboard.elements.get('historyRange').value = 'max';
  await dashboard.elements.get('historyRange').trigger('change');
  const max = new URL(dashboard.requests.at(-1).url);
  assert.equal(max.searchParams.get('from'), null);
  assert.equal(max.searchParams.get('limit'), '200');
  assert.match(dashboard.elements.get('historyRangeNote').textContent, /capped at 200/i);
});

test('an earlier history range response cannot replace the newest selected range', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', deferTimeline: true });
  await waitForRequests(dashboard.requests, 6);
  dashboard.elements.get('historyRange').value = 'max';
  dashboard.elements.get('historyRange').trigger('change');
  await waitForRequests(dashboard.requests, 7);
  dashboard.resolveTimeline(1, { ticker: 'DELL', snapshots: [{ score: 90, state: 'HIGH', asOf: '2026-10-02T12:00:00Z' }], transitions: [] });
  await new Promise(setImmediate);
  dashboard.resolveTimeline(0, { ticker: 'DELL', snapshots: [{ score: 10, state: 'NORMAL', asOf: '2026-09-01T12:00:00Z' }], transitions: [] });
  await new Promise(setImmediate);
  assert.match(dashboard.elements.get('companyHistory').innerHTML, /90\.0/);
  assert.doesNotMatch(dashboard.elements.get('companyHistory').innerHTML, /10\.0|2026-09-01/);
});

test('mismatched explanations suppress attribution and unsafe source URLs stay plain text', async () => {
  for (const status of ['SCORE_MISMATCH', 'VERSION_MISMATCH']) {
    const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: {
      '/catalyst': { ...companyFixture['/catalyst'], explanationStatus: status },
      '/events': { events: [{ ...companyFixture['/events'].events[0], source: { ...companyFixture['/events'].events[0].source, canonicalUrl: 'javascript:alert(1)' } }], nextCursor: null },
    } });
    await waitForRequests(dashboard.requests, 6);
    const why = dashboard.elements.get('companyExplanation').innerHTML;
    assert.match(why, /explanation unavailable/i);
    assert.doesNotMatch(why, /Raised guidance|Scoring details|Positive summary/);
    assert.doesNotMatch(dashboard.elements.get('companyEvents').innerHTML, /href="javascript:/);
  }
});

test('event cursor appends a later report into its loaded cluster', async () => {
  const second = { ...companyFixture['/events'].events[0], id: 'event-2', evidence: [{ quoteOrFact: 'Second report', sourceOffsetHint: null }] };
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { '/events': (url) =>
    url.searchParams.has('cursor') ? { events: [second], nextCursor: null }
      : { ...companyFixture['/events'], nextCursor: 'event-1' } } });
  await waitForRequests(dashboard.requests, 6);
  assert.equal(dashboard.elements.get('loadCompanyEvents').disabled, false);
  await dashboard.elements.get('loadCompanyEvents').trigger('click');
  const feed = dashboard.elements.get('companyEvents').innerHTML;
  assert.equal((feed.match(/class="event-cluster"/g) || []).length, 1);
  assert.match(feed, /Raised guidance.*Second report/s);
  assert.equal(new URL(dashboard.requests.at(-1).url).searchParams.get('cursor'), 'event-1');
});

test('a failed next event page keeps loaded evidence and allows retry', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { '/events': (url) =>
    url.searchParams.has('cursor') ? 'error' : { ...companyFixture['/events'], nextCursor: 'event-1' } } });
  await waitForRequests(dashboard.requests, 6);
  await dashboard.elements.get('loadCompanyEvents').trigger('click');
  assert.match(dashboard.elements.get('companyEvents').innerHTML, /Raised guidance/);
  assert.match(dashboard.elements.get('companyEventsStatus').textContent, /Unable to load more events/);
  assert.equal(dashboard.elements.get('loadCompanyEvents').disabled, false);
});

test('scoring details retain the returned numeric factor precision', async () => {
  const driver = { ...companyFixture['/catalyst'].topDrivers[0], factors: { ...companyFixture['/catalyst'].topDrivers[0].factors, confidence: 0.9876 } };
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { '/catalyst': { ...companyFixture['/catalyst'], topDrivers: [driver] } } });
  await waitForRequests(dashboard.requests, 6);
  assert.match(dashboard.elements.get('companyExplanation').innerHTML, /0\.9876/);
});

test('event search sends only bounded API filters and labels discovery time', async () => {
  const dashboard = startDashboard({ search: '?view=events' });
  await waitForRequests(dashboard.requests, 3);
  dashboard.elements.get('eventTicker').value = 'DELL';
  dashboard.elements.get('eventFamily').value = 'GUIDANCE';
  dashboard.elements.get('eventType').value = 'GUIDANCE_RAISE';
  dashboard.elements.get('eventDirection').value = 'POSITIVE';
  dashboard.elements.get('eventFrom').value = '2026-10-01';
  dashboard.elements.get('eventTo').value = '2026-10-02';
  dashboard.elements.get('eventPageSize').value = '20';
  await dashboard.elements.get('eventFilters').trigger('submit', { preventDefault() {} });
  const url = new URL(dashboard.requests.at(-1).url);
  assert.equal(url.pathname + url.search,
    '/v1/events?ticker=DELL&family=GUIDANCE&type=GUIDANCE_RAISE&direction=POSITIVE&from=2026-10-01T00%3A00%3A00Z&to=2026-10-02T23%3A59%3A59.999Z&limit=20');
  assert.match(html, /First captured from/);
  assert.match(html, /First captured through/);
  const rendered = dashboard.elements.get('eventResults').innerHTML;
  assert.match(rendered, /Event date:/);
  assert.match(rendered, /Source publication date:/);
  assert.match(rendered, /First captured:/);
});

test('event cursor appends reports and preserves source evidence when returning from company', async () => {
  const second = { ...eventFixture.events[0], id: 'event-2', evidence: [{ quoteOrFact: 'Second report', sourceOffsetHint: null }] };
  const dashboard = startDashboard({ search: '?view=events', events: (url) =>
    url.searchParams.has('cursor') ? { events: [second], nextCursor: null }
      : { ...eventFixture, nextCursor: 'opaque-page-2' } });
  await waitForRequests(dashboard.requests, 3);
  await dashboard.elements.get('loadEvents').trigger('click');
  assert.equal(new URL(dashboard.requests.at(-1).url).searchParams.get('cursor'), 'opaque-page-2');
  assert.match(dashboard.elements.get('eventResults').innerHTML, /Raised guidance.*Second report/s);
  assert.match(dashboard.elements.get('eventResults').innerHTML, /cluster-1/);
  dashboard.elements.get('eventResults').trigger('click', { target: { closest: () => ({ dataset: { ticker: 'DELL' } }) }, preventDefault() {} });
  assert.equal(dashboard.window.location.search, '?view=company&ticker=DELL');
  dashboard.popstate('?view=events');
  assert.match(dashboard.elements.get('eventResults').innerHTML, /Raised guidance.*Second report/s);
  assert.equal(dashboard.requests.filter((request) => new URL(request.url).pathname === '/v1/events').length, 2);
});

test('events use public credentials and Operations remains admin gated with model provenance', async () => {
  const dashboard = startDashboard({ search: '?view=events', stored: { 'catalyst-api-key': 'public-secret' } });
  await waitForRequests(dashboard.requests, 3);
  const eventRequest = dashboard.requests.find((request) => new URL(request.url).pathname === '/v1/events');
  assert.equal(eventRequest.headers.Authorization, 'Bearer public-secret');
  assert.equal(eventRequest.headers['X-Admin-Key'], undefined);
  assert.equal(dashboard.requests.filter((request) => new URL(request.url).pathname.startsWith('/internal/')).length, 0);
  assert.equal(dashboard.elements.get('runNow').disabled, true);
  dashboard.document.getElementById('adminKey').value = 'admin-secret';
  dashboard.elements.get('saveKey').trigger('click');
  await waitForRequests(dashboard.requests, 8);
  assert.match(dashboard.elements.get('ingestionRows').innerHTML, /polygon/);
  assert.match(dashboard.elements.get('modelRows').innerHTML, /prompt-v1.*extractor-v1.*Source document/s);
  assert.equal(dashboard.elements.get('totalCost').textContent, '$0.010000');
  assert.equal(dashboard.elements.get('runNow').disabled, false);
});

test('a failed event continuation leaves captured evidence available for retry', async () => {
  const dashboard = startDashboard({ search: '?view=events', events: (url) =>
    url.searchParams.has('cursor') ? 'error' : { ...eventFixture, nextCursor: 'opaque-page-2' } });
  await waitForRequests(dashboard.requests, 3);
  await dashboard.elements.get('loadEvents').trigger('click');
  assert.match(dashboard.elements.get('eventResults').innerHTML, /Raised guidance/);
  assert.match(dashboard.elements.get('eventStatus').textContent, /Unable to load more/);
  assert.equal(dashboard.elements.get('loadEvents').disabled, false);
});

test('event API strings render as escaped text and model-run details remain available', async () => {
  const event = { ...eventFixture.events[0], companyName: '<img src=x onerror=alert(1)>',
    evidence: [{ quoteOrFact: '<script>alert(1)</script>', sourceOffsetHint: null }] };
  const dashboard = startDashboard({ search: '?view=events', stored: { 'catalyst-admin-key': 'admin-secret' },
    events: { events: [event], nextCursor: null } });
  await waitForRequests(dashboard.requests, 5);
  const rendered = dashboard.elements.get('eventResults').innerHTML;
  assert.doesNotMatch(rendered, /<img|<script>/);
  assert.match(rendered, /&lt;script&gt;alert/);
  assert.match(dashboard.elements.get('modelRows').innerHTML, /<details>/);
});

test('an event without source metadata still distinguishes its missing publication date', async () => {
  const dashboard = startDashboard({ search: '?view=events', events: {
    events: [{ ...eventFixture.events[0], source: null }], nextCursor: null,
  } });
  await waitForRequests(dashboard.requests, 3);
  const rendered = dashboard.elements.get('eventResults').innerHTML;
  assert.match(rendered, /Event date:/);
  assert.match(rendered, /Source publication date:.*–/s);
  assert.match(rendered, /First captured:/);
});
