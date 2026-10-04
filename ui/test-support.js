const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');

function startDashboard({ search = '', stored = {}, origin = 'https://ops.example', discovery, events, company = {},
  overview, config, health = { status: 'UP' }, deferDiscovery = false, deferAdmin = false,
  deferTimeline = false, deferOverview = false, deferConfig = false } = {}) {
  const requests = [];
  const values = new Map(Object.entries(stored));
  const elements = new Map();
  const ids = new Set(Array.from(html.matchAll(/\bid="([^"]+)"/g), (match) => match[1]));
  let reloads = 0;
  let completePipeline;
  let ready;
  const windowHandlers = {};
  const documentHandlers = {};
  const discoveryResolvers = [];
  const adminResolvers = [];
  const timelineResolvers = [];
  const overviewResolvers = [];
  const configResolvers = [];
  const intervals = new Map();
  let nextInterval = 1;
  let historyState = {};
  function elementFor(id) {
    if (!ids.has(id)) throw new Error('Unknown static DOM id: ' + id);
    if (!elements.has(id)) {
      const handlers = {};
      const attributes = {};
      const element = {
        id, innerHTML: '', textContent: '', value: '', checked: false,
        disabled: false, href: '', className: '', hidden: false, dataset: {},
        selectedOptions: [], options: [],
        classList: {
          add(name) { if (name === 'hidden') element.hidden = true; },
          remove(name) { if (name === 'hidden') element.hidden = false; },
          toggle(name, force) { element.hidden = name === 'hidden' ? (force === undefined ? !element.hidden : force) : element.hidden; return element.hidden; },
          contains(name) { return name === 'hidden' && element.hidden; },
        },
        setAttribute(name, value) { attributes[name] = String(value); if (name === 'hidden') this.hidden = true; },
        getAttribute(name) { return attributes[name] || null; },
        removeAttribute(name) { delete attributes[name]; if (name === 'hidden') this.hidden = false; },
        focus() { document.activeElement = this; },
        addEventListener(name, handler) { (handlers[name] ||= []).push(handler); },
        removeEventListener(name, handler) { handlers[name] = (handlers[name] || []).filter((candidate) => candidate !== handler); },
        trigger(name, event = {}) { return (handlers[name] || []).map((handler) => handler({ target: this, preventDefault() {}, ...event })).at(-1); },
        closest(selector) { return selector.startsWith('[data-') && this.dataset[selector.slice(6, -1).replace(/^data-/, '').replace(/-([a-z])/g, (_, ch) => ch.toUpperCase())] ? this : null; },
        _handlers: handlers,
      };
      elements.set(id, element);
    }
    return elements.get(id);
  }
  const document = {
    activeElement: null,
    hidden: false,
    getElementById(id) {
      return elementFor(id);
    },
    addEventListener(name, handler) {
      if (name === 'DOMContentLoaded') ready = handler;
      else documentHandlers[name] = handler;
    },
    removeEventListener(name, handler) { if (documentHandlers[name] === handler) delete documentHandlers[name]; },
    querySelectorAll(selector) {
      if (selector === '[data-ticker]') return Array.from(elements.values()).filter((element) => element.dataset.ticker);
      return [];
    },
  };
  const window = {
    location: { origin, search, reload() { reloads++; } },
    history: { pushState(state, _title, url) { historyState = state || {}; window.location.search = new URL(url, origin).search; }, get state() { return historyState; } },
    addEventListener(name, handler) { windowHandlers[name] = handler; },
    sessionStorage: {
      getItem(key) { return values.get(key) || null; },
      setItem(key, value) { values.set(key, value); },
      removeItem(key) { values.delete(key); },
    },
    setInterval(callback, delay) { const id = nextInterval++; intervals.set(id, { callback, delay }); return id; },
    clearInterval(id) { intervals.delete(id); },
    setTimeout,
    clearTimeout,
  };
  function response(body, status = 200) {
    return { ok: status >= 200 && status < 300, status, json: async () => body };
  }
  async function fetch(url, options) {
    const requestUrl = new URL(url, origin);
    const method = (options.method || 'GET').toUpperCase();
    requests.push({ url: requestUrl.href, method, headers: options.headers || {}, body: options.body, signal: options.signal });
    const route = requestUrl.pathname;
    if (route === '/actuator/health' && method === 'GET') {
      if (health === 'error') return response({ detail: 'Health unavailable', code: 'TEMPORARY_UNAVAILABLE', status: 503, requestId: 'request-health' }, 503);
      return response(typeof health === 'function' ? health(requestUrl) : health);
    }
    if (route === '/internal/operations/overview' && method === 'GET') {
      if (!options.headers?.['X-Admin-Key']) return response({ detail: 'Admin key required', code: 'FORBIDDEN', status: 403, requestId: 'request-auth' }, 403);
      if (deferOverview) return new Promise((resolve) => overviewResolvers.push((value) => resolve(value === 'error'
        ? response({ detail: 'Overview unavailable', code: 'TEMPORARY_UNAVAILABLE', status: 503, requestId: 'request-overview' }, 503)
        : response(value || overviewFixture))));
      const answer = typeof overview === 'function' ? overview(requestUrl, requests.length - 1) : overview;
      return answer === 'error' ? response({ detail: 'Overview unavailable', code: 'TEMPORARY_UNAVAILABLE', status: 503, requestId: 'request-overview' }, 503)
        : response(answer || overviewFixture);
    }
    if (route === '/internal/operations/config' && method === 'GET') {
      if (!options.headers?.['X-Admin-Key']) return response({ detail: 'Admin key required', code: 'FORBIDDEN', status: 403, requestId: 'request-auth' }, 403);
      if (deferConfig) return new Promise((resolve) => configResolvers.push((value) => resolve(value === 'error'
        ? response({ detail: 'Configuration unavailable', code: 'TEMPORARY_UNAVAILABLE', status: 503, requestId: 'request-config' }, 503)
        : response(value || configFixture))));
      const answer = typeof config === 'function' ? config(requestUrl) : config;
      return answer === 'error' ? response({ detail: 'Configuration unavailable', code: 'TEMPORARY_UNAVAILABLE', status: 503, requestId: 'request-config' }, 503)
        : response(answer || configFixture);
    }
    if (route.endsWith('/timeline') && deferTimeline) {
      return new Promise((resolve) => timelineResolvers.push((body) => resolve(body === 'error' ? response({ detail: 'Timeline unavailable' }, 503) : response(body))));
    }
    if (route.startsWith('/v1/companies/')) {
      const companyPath = route.startsWith('/v1/companies/DELL') ? route.replace('/v1/companies/DELL', '') : null;
      if (companyPath === null) throw new Error('Unrecognized company route: ' + route);
      const suffix = companyPath || 'metadata';
      const answer = typeof company[suffix] === 'function' ? company[suffix](requestUrl) : company[suffix];
      return answer === 'error' ? response({ detail: 'Company not found' }, 404) : response(answer || companyFixture[suffix]);
    }
    if (route === '/v1/discovery/catalyzed' && method === 'GET' && deferDiscovery) {
      return new Promise((resolve) => discoveryResolvers.push((body) => resolve(body === 'error' ? response({ detail: 'Discovery unavailable' }, 503) : response(body))));
    }
    if (route.startsWith('/internal/') && deferAdmin && method !== 'POST') {
      return new Promise((resolve) => adminResolvers.push(() => resolve(response({ runs: [] }))));
    }
    if (route === '/internal/ingestion/runs' && method === 'POST') {
      return new Promise((resolve) => {
        completePipeline = () => resolve(response({
          status: 'SUCCESS', documentsProcessed: 1, eventsExtracted: 1,
          companiesRescored: 1, error: null, documentsSkipped: 0,
          documentsRetryScheduled: 0, documentsTerminalFailures: 0,
          alreadyRunning: false,
        }));
      });
    }
    if (route.startsWith('/internal/') && !options.headers?.['X-Admin-Key']) {
      return response({ detail: 'Admin key required' }, 403);
    }
    if (route === '/v1/discovery/catalyzed' && method === 'GET') {
      if (discovery === 'error') return response({ detail: 'Discovery unavailable' }, 503);
      return response(typeof discovery === 'function' ? discovery(requestUrl) : discovery || discoveryFixture);
    }
    if (route === '/v1/events' && method === 'GET') {
      const answer = typeof events === 'function' ? events(requestUrl) : events;
      return answer === 'error' ? response({ detail: 'Events unavailable' }, 503) : response(answer || eventFixture);
    }
    if (route === '/internal/ingestion/runs' && method === 'GET') return response({ runs: [{
        id: 'a7f2cd30-6717-4e3a-8870-4bdba9d7af95', provider: 'polygon', status: 'SUCCESS',
        fetched: 1, added: 1, duplicates: 0, error: null, finishedAt: '2026-10-02T12:00:00Z',
      }] });
    if (route === '/internal/model-runs' && method === 'GET') return response({ runs: [{
        id: 'b7f2cd30-6717-4e3a-8870-4bdba9d7af95', provider: 'openai', operation: 'extract',
        model: 'model', promptVersion: 'prompt-v1', extractorVersion: 'extractor-v1',
        sourceDocumentId: null, inputTokens: 10, outputTokens: 2, latencyMs: 100,
        estimatedCost: 0.01, success: true, error: null, createdAt: '2026-10-02T12:01:00Z',
      }] });
    throw new Error('Unrecognized API request: ' + method + ' ' + route);
  }
  const context = { window, document, fetch, URLSearchParams, URL, AbortController, Promise, setImmediate };
  for (const file of ['operations-model.js', 'operations.js', 'app.js']) {
    const filePath = path.join(__dirname, file);
    if (fs.existsSync(filePath)) {
      let source = fs.readFileSync(filePath, 'utf8');
      if (file === 'app.js') {
        const end = source.lastIndexOf('})();');
        source = source.slice(0, end) + 'window.__testApi = api;\n' + source.slice(end);
      }
      vm.runInNewContext(source, context, { filename: file });
    }
  }
  ready();
  return {
    requests, elements, values, window, document, html, context,
    resolveDiscovery(index, body) { discoveryResolvers[index](body); },
    resolveTimeline(index, body) { timelineResolvers[index](body); },
    resolveAdmin(index) { adminResolvers[index](); },
    resolveOverview(index, body) { overviewResolvers[index](body); },
    resolveConfig(index, body) { configResolvers[index](body); },
    popstate(search) { window.location.search = search; windowHandlers.popstate(); },
    setHidden(hidden) { document.hidden = hidden; if (documentHandlers.visibilitychange) documentHandlers.visibilitychange(); },
    tick() { for (const timer of Array.from(intervals.values())) timer.callback(); },
    get intervalCount() { return intervals.size; },
    text(id) { return elementFor(id).textContent; },
    html(id) { return elementFor(id).innerHTML; },
    click(id, event) { return elementFor(id).trigger('click', event); },
    flush() { return flush(); },
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

const discoveryFixture = { asOf: '2026-10-02T12:02:00Z', total: 1, limit: 20, offset: 0, results: [{
  ticker: 'DELL', name: 'Dell', sector: 'Technology', score: 30, state: 'WATCH',
  velocity7d: 1, events7d: 1, scoreVersion: 'score-v1', taxonomyVersion: 'taxonomy-v1', asOf: '2026-10-02T12:00:00Z',
}] };
const overviewFixture = {
  generatedAt: '2026-10-04T12:00:00Z', window: { from: '2026-10-03T12:00:00Z', to: '2026-10-04T12:00:00Z' }, activeCompanies: 0,
  ingestionFreshness: { status: 'NEVER', lastSuccessAt: null, ageSeconds: null, expectedIntervalSeconds: 1800, staleAfterSeconds: 5400 },
  snapshotFreshness: { status: 'NEVER', lastSuccessAt: null, ageSeconds: null, expectedIntervalSeconds: 86400, staleAfterSeconds: 259200 },
  queue: { pending: 0, processing: 0, retrying: 0, terminal: 0, unresolved: 0, notTracked: 0, waiting: 0, due: 0, oldestDueAt: null, oldestDueDocumentId: null },
  activity: { documentsNew: 0, documentsFetched: 0, documentsDuplicate: 0, eventsInserted: 0, clustersCreated: 0, successfulRecalculations: null, recalculationFailures: null, recalculationHistoryAvailable: false },
  models: { calls: 0, successfulCalls: 0, failedCalls: 0, inputTokens: null, inputTokensKnownCalls: 0, outputTokens: null, outputTokensExpectedCalls: 0, outputTokensKnownCalls: 0, estimatedCostUsd: null, costKnownCalls: 0, latencyKnownCalls: 0, p50LatencyMs: null, p95LatencyMs: null },
  dependencies: [], signals: [], activeRuns: [], historyStartedAt: null,
};
const configFixture = {
  generatedAt: '2026-10-04T12:00:00Z', publicApiAuthEnabled: true, singleInstance: true,
  ingestion: { enabled: true, intervalSeconds: 1800 }, snapshots: { enabled: true, intervalSeconds: 86400 },
  primaryNewsProvider: 'polygon', fallbackNewsProvider: 'finnhub', pipelineBatchSize: 25, pipelineMaxAttempts: 3, pipelineRetryDelaySeconds: 300,
  providers: [{ name: 'polygon', configured: true }, { name: 'finnhub', configured: false }, { name: 'openai', configured: true }],
  versions: { score: 'score-v1', taxonomy: 'taxonomy-v1', prompt: 'prompt-v1', extractor: 'extractor-v1', extractionModel: 'model', embeddingModel: 'embed-model' },
};

async function flush() {
  for (let index = 0; index < 20; index++) await new Promise((resolve) => setImmediate(resolve));
}

module.exports = { startDashboard, waitForRequests, flush, companyFixture, eventFixture, discoveryPage, discoveryFixture, overviewFixture, configFixture, html };
