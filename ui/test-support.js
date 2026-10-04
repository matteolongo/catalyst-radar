const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const html = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');

function startDashboard({ search = '', stored = {}, origin = 'https://ops.example', discovery, events, company = {},
  overview, config, health = { status: 'UP' }, deferDiscovery = false, deferAdmin = false,
  deferTimeline = false, deferOverview = false, deferConfig = false, documents, documentDetail,
  documentBody, documentAttempts, documentEvents, documentModelRuns,
  deferDocuments = false, deferDocumentList = false, modelSummary, modelCalls, modelDetail,
  modelPageSize = 25, deferModelSummary = false, deferModelCalls = false, deferModelDetails = false,
  operationRuns, operationRunDetail, operationIssues, ingestionRuns, pipelineResult, deferPipeline = true,
  deferPublicPaths = [] } = {}) {
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
  const publicResolvers = new Map();
  const documentResolvers = new Map();
  const documentListResolvers = [];
  const modelSummaryResolvers = [];
  const modelCallPageResolvers = [];
  const modelDetailResolvers = new Map();
  let pipelineResolver;
  const intervals = new Map();
  let nextInterval = 1;
  let historyState = {};
  const historyEntries = [];
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
      if (selector === '[data-attempt-id]') {
        const source = elementFor('documentAttempts').innerHTML;
        return Array.from(source.matchAll(/data-attempt-id="([^"]+)"/g), (match) => ({
          dataset: { attemptId: match[1] }, focus() { document.activeElement = this; },
        }));
      }
      return [];
    },
  };
  const window = {
    location: { origin, search, reload() { reloads++; } },
    history: { pushState(state, _title, url) { historyState = state || {}; historyEntries.push(url); window.location.search = new URL(url, origin).search; }, get state() { return historyState; } },
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
    if (deferPublicPaths.includes(route) && method === 'GET') return new Promise((resolve) => {
      const queued = publicResolvers.get(route) || [];
      queued.push((body) => resolve(body === 'error'
        ? response({ detail: 'Obsolete public failure' }, 503) : response(body)));
      publicResolvers.set(route, queued);
    });
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
    if (route === '/internal/operations/documents' && method === 'GET') {
      if (!options.headers?.['X-Admin-Key']) return response({ detail: 'Admin key required', code: 'FORBIDDEN', status: 403 }, 403);
      if (deferDocumentList) return new Promise((resolve) => documentListResolvers.push((body) => resolve(body === 'error'
        ? response({ detail: 'Documents unavailable', code: 'TEMPORARY_UNAVAILABLE', status: 503 }, 503)
        : response(body || documentsPageFixture))));
      const answer = typeof documents === 'function' ? documents(requestUrl) : documents;
      return answer === 'error' ? response({ detail: 'Documents unavailable', code: 'TEMPORARY_UNAVAILABLE', status: 503 }, 503)
        : response(answer || documentsPageFixture);
    }
    const documentMatch = route.match(/^\/internal\/operations\/documents\/([^/]+)(?:\/(body|events|attempts|model-runs))?$/);
    if (documentMatch && method === 'GET') {
      if (!options.headers?.['X-Admin-Key']) return response({ detail: 'Admin key required', code: 'FORBIDDEN', status: 403 }, 403);
      const id = decodeURIComponent(documentMatch[1]);
      const resource = documentMatch[2] || 'detail';
      if (resource === 'detail' && deferDocuments) return new Promise((resolve) => {
        const queued = documentResolvers.get(id) || [];
        queued.push((body) => resolve(body === 'error'
          ? response({ detail: 'Document unavailable', code: 'DOCUMENT_NOT_FOUND', status: 404 }, 404)
          : response(body || documentDetailFixture(id))));
        documentResolvers.set(id, queued);
      });
      const configured = { detail: documentDetail, body: documentBody, attempts: documentAttempts,
        events: documentEvents, 'model-runs': documentModelRuns }[resource];
      const value = typeof configured === 'function' ? configured(requestUrl, id) : configured;
      const fallback = { detail: documentDetailFixture(id), body: documentBodyFixture(id),
        attempts: documentAttemptsPageFixture, events: documentEventsFixture,
        'model-runs': documentModelPageFixture }[resource];
      return value === 'error' ? response({ detail: 'Document tab unavailable', code: 'TEMPORARY_UNAVAILABLE', status: 503 }, 503)
        : response(value || fallback);
    }
    if (route.endsWith('/timeline') && deferTimeline) {
      return new Promise((resolve) => timelineResolvers.push((body) => resolve(body === 'error' ? response({ detail: 'Timeline unavailable' }, 503) : response(body))));
    }
    if (route === '/internal/operations/model-summary' && method === 'GET') {
      if (!options.headers?.['X-Admin-Key']) return response({ detail: 'Admin key required', code: 'FORBIDDEN' }, 403);
      if (deferModelSummary) return new Promise((resolve) => modelSummaryResolvers.push((body) => resolve(body === 'error'
        ? response({ detail: 'Model summary unavailable', code: 'TEMPORARY_UNAVAILABLE' }, 503)
        : response(body || modelSummaryFixture()))));
      const answer = typeof modelSummary === 'function' ? modelSummary(requestUrl) : modelSummary;
      return answer === 'error' ? response({ detail: 'Model summary unavailable', code: 'TEMPORARY_UNAVAILABLE' }, 503)
        : response(answer || modelSummaryFixture());
    }
    if (route === '/internal/operations/runs' && method === 'GET') {
      if (!options.headers?.['X-Admin-Key']) return response({ detail: 'Admin key required', code: 'FORBIDDEN' }, 403);
      const answer = typeof operationRuns === 'function' ? operationRuns(requestUrl) : operationRuns;
      return answer === 'error' ? response({ detail: 'Operation runs unavailable', code: 'TEMPORARY_UNAVAILABLE' }, 503)
        : response(answer || operationRunsPageFixture(0));
    }
    const runIssuesMatch = route.match(/^\/internal\/operations\/runs\/([^/]+)\/issues$/);
    if (runIssuesMatch && method === 'GET') {
      if (!options.headers?.['X-Admin-Key']) return response({ detail: 'Admin key required', code: 'FORBIDDEN' }, 403);
      const answer = typeof operationIssues === 'function' ? operationIssues(requestUrl, decodeURIComponent(runIssuesMatch[1])) : operationIssues;
      return answer === 'error' ? response({ detail: 'Operation issues unavailable', code: 'TEMPORARY_UNAVAILABLE' }, 503)
        : response(answer || operationIssuesPageFixture());
    }
    const runDetailMatch = route.match(/^\/internal\/operations\/runs\/([^/]+)$/);
    if (runDetailMatch && method === 'GET') {
      if (!options.headers?.['X-Admin-Key']) return response({ detail: 'Admin key required', code: 'FORBIDDEN' }, 403);
      const id = decodeURIComponent(runDetailMatch[1]);
      const answer = typeof operationRunDetail === 'function' ? operationRunDetail(requestUrl, id) : operationRunDetail;
      return answer === 'error' ? response({ detail: 'Operation run unavailable', code: 'OPERATION_RUN_NOT_FOUND' }, 404)
        : response(answer || operationRunDetailFixture(id));
    }
    if (route === '/internal/operations/ingestion-runs' && method === 'GET') {
      if (!options.headers?.['X-Admin-Key']) return response({ detail: 'Admin key required', code: 'FORBIDDEN' }, 403);
      const answer = typeof ingestionRuns === 'function' ? ingestionRuns(requestUrl) : ingestionRuns;
      return answer === 'error' ? response({ detail: 'Ingestion runs unavailable', code: 'TEMPORARY_UNAVAILABLE' }, 503)
        : response(answer || ingestionRunsPageFixture(0));
    }
    if (route === '/internal/operations/model-runs' && method === 'GET') {
      if (!options.headers?.['X-Admin-Key']) return response({ detail: 'Admin key required', code: 'FORBIDDEN' }, 403);
      if (deferModelCalls) return new Promise((resolve) => modelCallPageResolvers.push((body) => resolve(body === 'error'
        ? response({ detail: 'Model calls unavailable', code: 'TEMPORARY_UNAVAILABLE' }, 503)
        : response(body || modelCallsPageFixture(modelPageSize)))));
      const answer = typeof modelCalls === 'function' ? modelCalls(requestUrl) : modelCalls;
      return answer === 'error' ? response({ detail: 'Model calls unavailable', code: 'TEMPORARY_UNAVAILABLE' }, 503)
        : response(answer || modelCallsPageFixture(modelPageSize));
    }
    const modelDetailMatch = route.match(/^\/internal\/operations\/model-runs\/([^/]+)$/);
    if (modelDetailMatch && method === 'GET') {
      if (!options.headers?.['X-Admin-Key']) return response({ detail: 'Admin key required', code: 'FORBIDDEN' }, 403);
      const id = decodeURIComponent(modelDetailMatch[1]);
      if (deferModelDetails) return new Promise((resolve) => {
        const queued = modelDetailResolvers.get(id) || [];
        queued.push((body) => resolve(body === 'error'
          ? response({ detail: 'Model call unavailable', code: 'MODEL_RUN_NOT_FOUND' }, 404)
          : response(body || modelCallFixture(id))));
        modelDetailResolvers.set(id, queued);
      });
      const answer = typeof modelDetail === 'function' ? modelDetail(requestUrl, id) : modelDetail;
      return answer === 'error' ? response({ detail: 'Model call unavailable', code: 'MODEL_RUN_NOT_FOUND' }, 404)
        : response(answer || modelCallFixture(id));
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
      if (!options.headers?.['X-Admin-Key']) return response({ detail: 'Admin key required', code: 'FORBIDDEN' }, 403);
      if (deferPipeline) return new Promise((resolve) => { pipelineResolver = (body) => resolve(response(body || pipelineResultFixture)); });
      const answer = typeof pipelineResult === 'function' ? pipelineResult(requestUrl) : pipelineResult;
      return answer === 'error' ? Promise.reject(new Error('Pipeline response unavailable')) : response(answer || pipelineResultFixture);
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
    resolvePublic(route, index, body) { publicResolvers.get(route)[index](body); },
    resolveTimeline(index, body) { timelineResolvers[index](body); },
    resolveAdmin(index) { adminResolvers[index](); },
    resolveOverview(index, body) { overviewResolvers[index](body); },
    resolveConfig(index, body) { configResolvers[index](body); },
    resolveDocument(id, body) { const queue = documentResolvers.get(id) || []; const resolve = queue.shift(); if (!resolve) throw new Error('No deferred document request for ' + id); resolve(body); },
    resolveDocumentList(index, body) { documentListResolvers[index](body); },
    resolveModelSummary(index, body) { modelSummaryResolvers[index](body); },
    resolveModelCalls(index, body) { modelCallPageResolvers[index](body); },
    resolveModelDetail(id, body) { const queue = modelDetailResolvers.get(id) || []; const resolve = queue.shift(); if (!resolve) throw new Error('No deferred model detail request for ' + id); resolve(body); },
    popstate(search) { window.location.search = search; windowHandlers.popstate(); },
    setHidden(hidden) { document.hidden = hidden; if (documentHandlers.visibilitychange) documentHandlers.visibilitychange(); },
    tick() { for (const timer of Array.from(intervals.values())) timer.callback(); },
    get intervalCount() { return intervals.size; },
    text(id) { return elementFor(id).textContent; },
    html(id) { return elementFor(id).innerHTML; },
    element(id) { return elementFor(id); },
    click(id, event) { return elementFor(id).trigger('click', event); },
    flush() { return flush(); },
    get reloads() { return reloads; },
    completePipeline(body) { if (!pipelineResolver) throw new Error('No deferred pipeline response'); pipelineResolver(body); pipelineResolver = null; },
    get historyState() { return historyState; },
    historyEntries,
    loadedModelRows() { return Array.from(elementFor('modelRows').innerHTML.matchAll(/data-model-run-id="[^"]+"/g)).length; },
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

function documentListItemFixture(id = '11111111-1111-4111-8111-111111111111', extra = {}) {
  return {
    id, provider: 'polygon', title: 'Quarterly filing', publishedAt: '2026-10-03T11:00:00Z',
    discoveredAt: '2026-10-03T12:00:00Z', createdAt: '2026-10-03T12:01:00Z', state: 'RETRYABLE_ERROR',
    attemptCount: 2, nextAttemptAt: '2026-10-04T12:00:00Z', updatedAt: '2026-10-03T12:02:00Z',
    lastErrorCode: 'PROVIDER_TIMEOUT', lastErrorMessage: 'The provider request timed out.',
    tickers: ['ACME'], tickersTruncated: false, eventReports: 1, canonicalClusters: 1,
    firstIngestionRunId: '11111111-1111-4111-8111-111111111111', ...extra,
  };
}

function documentDetailFixture(id = '11111111-1111-4111-8111-111111111111', extra = {}) {
  return {
    generatedAt: '2026-10-04T12:05:00Z', document: documentListItemFixture(id, { title: 'Source ' + id }),
    canonicalUrl: 'https://example.com/filing', providerDocumentId: 'provider-doc-1',
    completedAt: null, maxAttempts: 5, capturedAttemptCount: 1, unrecordedAttemptCount: 1,
    historyAvailable: true,
    companies: [{ id: '22222222-2222-4222-8222-222222222222', ticker: 'ACME', name: 'Acme Corporation',
      latestSnapshotAsOf: '2026-10-03T13:00:00Z', latestSnapshotCreatedAt: '2026-10-03T13:01:00Z' }],
    companiesTotal: 1, companiesTruncated: false, modelCallsRecorded: 1, eventReports: 1, canonicalClusters: 1,
    ...extra,
  };
}

const documentsPageFixture = { generatedAt: '2026-10-04T12:05:00Z', window: null,
  items: [documentListItemFixture()], limit: 25, nextCursor: null };
const documentAttemptsPageFixture = { generatedAt: '2026-10-04T12:05:00Z', window: null, items: [{
  id: '33333333-3333-4333-8333-333333333333', documentId: '11111111-1111-4111-8111-111111111111', runId: '44444444-4444-4444-8444-444444444444',
  number: 2, status: 'RETRYABLE_ERROR', startedAt: '2026-10-03T12:02:00Z', finishedAt: '2026-10-03T12:02:03Z',
  updatedAt: '2026-10-03T12:02:03Z', durationMs: 3000, nextAttemptAt: '2026-10-04T12:00:00Z',
  eventsInserted: 0, eventsReused: 0, errorCode: 'PROVIDER_TIMEOUT', errorMessage: 'The provider request timed out.',
  modelCallIds: ['55555555-5555-4555-8555-555555555555'], modelCallsTotal: 1, modelCallsTruncated: false,
}], limit: 25, nextCursor: null };
const documentEventsFixture = { events: [{
  id: '66666666-6666-4666-8666-666666666666', type: 'GUIDANCE_RAISE', family: 'GUIDANCE', direction: 'POSITIVE',
  confidence: 0.9, magnitude: null, surprise: null, materiality: null, sourceQuality: 'PRIMARY',
  expectedHorizon: 'SHORT_TERM', directness: 'DIRECT', scheduled: false,
  eventTimestamp: '2026-10-03T10:00:00Z', discoveredAt: '2026-10-03T12:00:00Z', clusterId: '77777777-7777-4777-8777-777777777777',
  sourceDocumentId: '11111111-1111-4111-8111-111111111111', taxonomyVersion: 'taxonomy-v1', extractorVersion: 'extractor-v1',
  evidence: [{ quoteOrFact: 'Raised full-year guidance.', sourceOffsetHint: null }], ticker: 'ACME', companyName: 'Acme Corporation',
  source: { sourceDocumentId: '11111111-1111-4111-8111-111111111111', title: 'Quarterly filing', provider: 'polygon', publishedAt: '2026-10-03T11:00:00Z', canonicalUrl: 'https://example.com/filing' },
}], nextCursor: null };
const documentModelPageFixture = { generatedAt: '2026-10-04T12:05:00Z', window: null, items: [{
  id: '55555555-5555-4555-8555-555555555555', provider: 'openai', operation: 'extract', model: 'model-v1',
  promptVersion: 'prompt-v1', extractorVersion: 'extractor-v1', sourceDocumentId: '11111111-1111-4111-8111-111111111111',
  attemptId: '33333333-3333-4333-8333-333333333333', runId: '44444444-4444-4444-8444-444444444444',
  inputTokens: 30, outputTokens: 10, latencyMs: 1200, estimatedCost: 0.0012,
  success: true, errorCode: null, errorMessage: null, createdAt: '2026-10-03T12:02:01Z',
}], limit: 25, nextCursor: null };

const modelWindowFixture = { from: '2026-10-03T12:00:00Z', to: '2026-10-04T12:00:00Z' };

function modelUsageFixture(calls, costKnownCalls, cost, extra = {}) {
  return {
    calls, successfulCalls: calls, failedCalls: 0,
    inputTokens: calls === 0 ? 0 : calls * 10, inputTokensKnownCalls: calls,
    outputTokens: calls === 0 ? 0 : calls * 4, outputTokensExpectedCalls: calls, outputTokensKnownCalls: calls,
    estimatedCostUsd: cost, costKnownCalls,
    latencyKnownCalls: calls, p50LatencyMs: calls === 0 ? null : 120, p95LatencyMs: calls === 0 ? null : 240,
    ...extra,
  };
}

function modelSummaryFixture(calls = 1, costKnownCalls = calls, cost = calls === 0 ? 0 : 0.01, extra = {}) {
  const usage = modelUsageFixture(calls, costKnownCalls, cost);
  return {
    generatedAt: '2026-10-04T12:05:00Z', window: modelWindowFixture, totals: usage,
    groups: [{ provider: 'openai', operation: 'extract', model: 'model-v1', usage }],
    groupsTruncated: false,
    ...extra,
  };
}

function modelCallFixture(id = '55555555-5555-4555-8555-555555555555', extra = {}) {
  return {
    id, provider: 'openai', operation: 'extract', model: 'model-v1', promptVersion: 'prompt-v1', extractorVersion: 'extractor-v1',
    sourceDocumentId: '11111111-1111-4111-8111-111111111111', attemptId: '33333333-3333-4333-8333-333333333333',
    runId: '44444444-4444-4444-8444-444444444444', inputTokens: 30, outputTokens: 10, latencyMs: 1200,
    estimatedCost: 0.0012, success: true, errorCode: null, errorMessage: null, createdAt: '2026-10-03T12:02:01Z',
    ...extra,
  };
}

function modelCallsPageFixture(count = 25, extra = {}) {
  const items = Array.from({ length: count }, (_, index) => modelCallFixture(
    'aaaaaaaa-aaaa-4aaa-8aaa-' + String(index + 1).padStart(12, '0'),
  ));
  return { generatedAt: '2026-10-04T12:05:00Z', window: modelWindowFixture, items, limit: 25, nextCursor: null, ...extra };
}

const operationWindowFixture = { from: '2026-10-03T12:00:00Z', to: '2026-10-04T12:00:00Z' };
const operationRunIdFixture = '77777777-7777-4777-8777-777777777777';
const ingestionRunIdFixture = '88888888-8888-4888-8888-888888888888';

function operationRunFixture(id = operationRunIdFixture, extra = {}) {
  return {
    id, kind: 'PIPELINE', trigger: 'SCHEDULED', status: 'SUCCESS', active: false, phase: 'FINISHED',
    asOf: '2026-10-04T11:00:00Z', startedAt: '2026-10-04T11:00:00Z', finishedAt: '2026-10-04T11:02:00Z',
    updatedAt: '2026-10-04T11:02:00Z', durationMs: 120000, captureComplete: true,
    documentsConsidered: 4, documentsCompleted: 2, documentsSkipped: 1, documentsRetryScheduled: 1,
    documentsTerminalFailures: 0, eventsInserted: 3, eventsReused: 2, companiesConsidered: 2,
    companiesRescored: 2, companiesFailed: 0, errorCode: null, errorMessage: null, ...extra,
  };
}

function operationRunsPageFixture(count = 25, extra = {}) {
  return {
    generatedAt: '2026-10-04T12:05:00Z', window: operationWindowFixture,
    items: Array.from({ length: count }, (_, index) => operationRunFixture(
      '99999999-9999-4999-8999-' + String(index + 1).padStart(12, '0'),
    )), limit: 25, nextCursor: null, ...extra,
  };
}

function operationRunDetailFixture(id = operationRunIdFixture, extra = {}) {
  return {
    generatedAt: '2026-10-04T12:05:00Z', run: operationRunFixture(id), ingestionRuns: 1, documentAttempts: 4, issues: 1,
    phases: [
      { phase: 'INGESTION', startedAt: '2026-10-04T11:00:00Z', finishedAt: '2026-10-04T11:00:20Z', durationMs: 20000 },
      { phase: 'PROCESSING', startedAt: '2026-10-04T11:00:20Z', finishedAt: '2026-10-04T11:01:30Z', durationMs: 70000 },
      { phase: 'SCORING', startedAt: '2026-10-04T11:01:30Z', finishedAt: '2026-10-04T11:02:00Z', durationMs: 30000 },
    ], ...extra,
  };
}

function operationIssuesPageFixture(count = 1, extra = {}) {
  return {
    generatedAt: '2026-10-04T12:05:00Z', window: null,
    items: Array.from({ length: count }, (_, index) => ({
      id: 'aaaaaaaa-bbbb-4ccc-8ddd-' + String(index + 1).padStart(12, '0'), runId: operationRunIdFixture,
      phase: 'PROCESSING', documentId: '11111111-1111-4111-8111-111111111111',
      companyId: '33333333-3333-4333-8333-333333333333', ticker: 'ACME',
      errorCode: 'EXTRACTION_FAILED', errorMessage: 'The recorded extraction attempt failed.', createdAt: '2026-10-04T11:01:00Z',
    })), limit: 25, nextCursor: null, ...extra,
  };
}

function ingestionRunFixture(id = ingestionRunIdFixture, extra = {}) {
  return {
    id, provider: 'polygon', status: 'SUCCESS', fetched: 8, added: 5, duplicates: 3, error: null,
    finishedAt: '2026-10-04T11:00:20Z', startedAt: '2026-10-04T11:00:00Z', durationMs: 20000,
    runId: operationRunIdFixture, errorCode: null, ...extra,
  };
}

function ingestionRunsPageFixture(count = 25, extra = {}) {
  return {
    generatedAt: '2026-10-04T12:05:00Z', window: operationWindowFixture,
    items: Array.from({ length: count }, (_, index) => ingestionRunFixture(
      'bbbbbbbb-cccc-4ddd-8eee-' + String(index + 1).padStart(12, '0'),
    )), limit: 25, nextCursor: null, ...extra,
  };
}

const pipelineResultFixture = {
  status: 'SUCCESS', documentsProcessed: 2, eventsExtracted: 3, companiesRescored: 2, error: null,
  documentsSkipped: 1, documentsRetryScheduled: 0, documentsTerminalFailures: 0, alreadyRunning: false,
  documentsConsidered: 3, documentsCompleted: 2, eventsInserted: 2, eventsReused: 1, runId: null,
};

function documentBodyFixture(id = '11111111-1111-4111-8111-111111111111', extra = {}) {
  return { id, text: 'Quoted source: <script>alert(1)</script>\nSecond line.', originalCharacters: 50000, truncated: true, ...extra };
}

async function flush() {
  for (let index = 0; index < 20; index++) await new Promise((resolve) => setImmediate(resolve));
}

module.exports = { startDashboard, waitForRequests, flush, companyFixture, eventFixture, discoveryPage, discoveryFixture,
  overviewFixture, configFixture, documentListItemFixture, documentsPageFixture, documentDetailFixture,
  documentBodyFixture, documentAttemptsPageFixture, documentEventsFixture, documentModelPageFixture,
  modelUsageFixture, modelSummaryFixture, modelCallFixture, modelCallsPageFixture, modelWindowFixture,
  operationWindowFixture, operationRunIdFixture, ingestionRunIdFixture, operationRunFixture, operationRunsPageFixture,
  operationRunDetailFixture, operationIssuesPageFixture, ingestionRunFixture, ingestionRunsPageFixture, pipelineResultFixture, html };
