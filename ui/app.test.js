const assert = require('node:assert/strict');
const test = require('node:test');
const { startDashboard: startDashboardWithRoute, waitForRequests, companyFixture, eventFixture, discoveryPage, html } = require('./test-support');

function startDashboard(options = {}) { return startDashboardWithRoute({ search: '?view=discover', ...options }); }

function sourceClick(dashboard, container, id) {
  dashboard.click(container, { target: { closest: (selector) => selector === '[data-source-document-id]'
    ? { dataset: { sourceDocumentId: id } } : null } });
}

test('source inspection preserves the reconstructed explanation and server state band', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', stored: { 'catalyst-admin-key': 'admin-secret' } });
  await dashboard.flush();
  assert.match(dashboard.html('companyExplanation'), /Reconstructed current explanation: matching score and versions do not prove original driver membership/);
  assert.match(dashboard.html('companyExplanation'), /Use Recorded valuations for the exact inputs retained by captured cycles/);
  assert.match(dashboard.html('companyExplanation'), /documentId=source-1.*Inspect source in backoffice/s);
  assert.match(dashboard.html('companyEvents'), /documentId=source-1.*Inspect source in backoffice/s);
  assert.match(dashboard.html('companyScore'), /68\.0.*CATALYZED.*65\.0.*80\.0/s);
  assert.match(dashboard.html('companyScore'), /Saved as of.*UTC/s);
  assert.doesNotMatch(dashboard.html('companyScore'), /Live/);
  const adminReads = dashboard.requests.filter(({ url }) => new URL(url).pathname.startsWith('/internal/'));
  assert.deepEqual(adminReads.map(({ url }) => new URL(url).pathname), ['/internal/operations/companies/DELL/valuations']);
  assert.equal(new URL(adminReads[0].url).searchParams.get('limit'), '25');
  assert.equal(adminReads[0].headers['X-Admin-Key'], 'admin-secret');
  assert.equal(adminReads[0].headers.Authorization, undefined);
});

test('source inspection requires independent admin access and resumes the local Source tab after setup', async () => {
  const id = '11111111-1111-4111-8111-111111111111';
  const route = '?view=documents&documentId=' + id + '&documentTab=source';
  const dashboard = startDashboard({ search: '?view=events', stored: { 'catalyst-api-key': 'public-secret' },
    events: { events: [{ ...eventFixture.events[0], sourceDocumentId: id }], nextCursor: null } });
  await dashboard.flush();
  assert.match(dashboard.html('eventResults'), /href="\?view=settings"[^>]*data-source-document-id=.*Inspect source in backoffice/);
  sourceClick(dashboard, 'eventResults', id);
  await dashboard.flush();
  assert.equal(dashboard.window.location.search, '?view=settings');
  assert.deepEqual(Object.keys(dashboard.historyState), ['returnSearch']);
  assert.equal(dashboard.historyState.returnSearch, route);
  assert.equal(dashboard.requests.some(({ url }) => new URL(url).pathname.startsWith('/internal/')), false);
  dashboard.element('adminKey').value = 'admin-secret';
  dashboard.click('saveKey');
  await dashboard.flush();
  assert.equal(dashboard.window.location.search, route);
  const body = dashboard.requests.find(({ url }) => new URL(url).pathname.endsWith('/body'));
  assert.ok(body);
  assert.equal(body.headers['X-Admin-Key'], 'admin-secret');
  assert.equal(body.headers.Authorization, undefined);
});

test('authorized source links use the supplied ID safely and absent IDs produce no inspection link', async () => {
  const id = '11111111-1111-4111-8111-111111111111';
  const dashboard = startDashboard({ search: '?view=events', stored: { 'catalyst-admin-key': 'admin-secret' },
    events: { events: [{ ...eventFixture.events[0], sourceDocumentId: id, source: null }], nextCursor: null } });
  await dashboard.flush();
  assert.match(dashboard.html('eventResults'), /href="\?view=documents&amp;documentId=.*documentTab=source/);
  sourceClick(dashboard, 'eventResults', id);
  await dashboard.flush();
  assert.equal(dashboard.window.location.search, '?view=documents&documentId=' + id + '&documentTab=source');
  assert.equal(dashboard.historyState.returnSearch, '?view=events');
  const absent = startDashboard({ search: '?view=events', events: { events: [{ ...eventFixture.events[0], source: {
    ...eventFixture.events[0].source, sourceDocumentId: null } }], nextCursor: null } });
  await absent.flush();
  assert.doesNotMatch(absent.html('eventResults'), /Inspect source in backoffice/);
  const unsafe = startDashboard({ search: '?view=events', events: { events: [{ ...eventFixture.events[0], sourceDocumentId: '\"><script>x</script>' }], nextCursor: null } });
  await unsafe.flush();
  assert.doesNotMatch(unsafe.html('eventResults'), /<script>/);
});

test('company return controls preserve Discover and Events openers and reject unsafe destinations', async () => {
  for (const [origin, container] of [['?view=discover&sector=Technology', 'discoveryResults'], ['?view=events&ticker=DELL', 'eventResults']]) {
    const dashboard = startDashboard({ search: origin });
    await dashboard.flush();
    dashboard.click(container, { target: { closest: (selector) => selector === '[data-ticker]' ? { dataset: { ticker: 'DELL' } } : null } });
    assert.equal(dashboard.historyState.returnSearch, origin);
    assert.deepEqual(Object.keys(dashboard.historyState), ['returnSearch']);
    assert.equal(dashboard.element('navIntelligence').getAttribute('aria-current'), 'page');
    dashboard.click('backToResults');
    assert.equal(dashboard.window.location.search, origin);
  }
  for (const unsafe of ['https://evil.example', '//evil.example', '??view=events', '?view=unknown', '?view=events#remote', '?view=events\\remote', '?view=events&q=\\remote']) {
    const dashboard = startDashboard({ search: '?view=company&ticker=DELL' });
    dashboard.window.history.pushState({ returnSearch: unsafe }, '', '?view=company&ticker=DELL');
    dashboard.click('backToResults');
    assert.equal(dashboard.window.location.search, '?view=discover', unsafe);
  }
});

test('public timestamps explicitly label UTC for discovery and contextual event dates', async () => {
  const dashboard = startDashboard();
  await dashboard.flush();
  assert.match(dashboard.text('discoveryAsOf'), /UTC/);
  assert.match(dashboard.html('discoveryResults'), /Company as of.*UTC/s);
  dashboard.click('navEvents');
  await dashboard.flush();
  assert.match(dashboard.html('eventResults'), /Event date:.*UTC.*First captured:.*UTC.*Source publication date:.*UTC/s);
});

test('history renders at most 200 stored snapshots and transitions without replaying', async () => {
  const snapshots = Array.from({ length: 201 }, (_, index) => ({ score: index % 100, state: 'WATCH', asOf: new Date(Date.UTC(2026, 0, index + 1)).toISOString() }));
  const transitions = snapshots.map((point) => ({ from: 'NORMAL', to: 'WATCH', score: point.score, at: point.asOf, scoreVersion: 'score-v1' }));
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { '/timeline': { ticker: 'DELL', snapshots, transitions } } });
  await dashboard.flush();
  assert.equal((dashboard.html('companyHistory').match(/class="score-point"/g) || []).length, 200);
  assert.equal((dashboard.html('companyHistory').match(/class="transition-mark"/g) || []).length, 200);
  assert.equal(dashboard.requests.some(({ method, url }) => method !== 'GET' || /replay|recalculate/.test(url)), false);
});

test('stored-history chart tooltips and date columns display UTC even for offset instants', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { '/timeline': {
    ticker: 'DELL', snapshots: [{ score: 44, state: 'WATCH', asOf: '2026-10-01T23:30:00-05:00' }],
    transitions: [{ from: 'NORMAL', to: 'WATCH', score: 44, at: '2026-10-01T23:30:00-05:00', scoreVersion: 'score-v1' }],
  } } });
  await dashboard.flush();
  assert.match(dashboard.html('companyHistory'), /<td\b[^>]*>2026-10-02<\/td>/);
  assert.match(dashboard.html('companyHistory'), /<title>10\/2\/2026, 4:30:00 AM UTC · 44\.0 · WATCH<\/title>/);
  assert.match(dashboard.html('companyHistory'), /<title>10\/2\/2026, 4:30:00 AM UTC · NORMAL → WATCH<\/title>/);
});

for (const [name, search, path, panel, fresh] of [
  ['Discovery', '?view=discover', '/v1/discovery/catalyzed', 'discoveryResults', discoveryPage('DELL')],
  ['Events', '?view=events', '/v1/events', 'eventResults', eventFixture],
  ['company metadata', '?view=company&ticker=DELL', '/v1/companies/DELL', 'companyOverview', companyFixture.metadata],
  ['company catalyst', '?view=company&ticker=DELL', '/v1/companies/DELL/catalyst', 'companyScore', companyFixture['/catalyst']],
  ['company history', '?view=company&ticker=DELL', '/v1/companies/DELL/timeline', 'companyHistory', companyFixture['/timeline']],
  ['company events', '?view=company&ticker=DELL', '/v1/companies/DELL/events', 'companyEvents', companyFixture['/events']],
]) {
  test(name + ' aborts pending reads when credentials change on the same route', async () => {
    const dashboard = startDashboard({ search, deferPublicPaths: [path] });
    await dashboard.flush();
    const obsolete = dashboard.requests.find(({ url }) => new URL(url).pathname === path);
    dashboard.element('apiKey').value = 'replacement-public-key';
    dashboard.click('saveKey');
    await dashboard.flush();
    assert.equal(obsolete.signal && obsolete.signal.aborted, true);
    dashboard.resolvePublic(path, 0, 'error');
    await dashboard.flush();
    assert.doesNotMatch(dashboard.text('banner'), /Obsolete public failure/);
    assert.doesNotMatch(dashboard.text(panel), /Unable to load/);
    dashboard.resolvePublic(path, 1, fresh);
    await dashboard.flush();
    assert.notEqual(dashboard.html(panel), '');
  });
  for (const credential of ['apiKey', 'adminKey']) {
    for (const outcome of ['success', 'error']) {
      test(name + ' rejects stale ' + outcome + ' after hidden ' + credential + ' changes', async () => {
        const dashboard = startDashboard({ search, deferPublicPaths: [path] });
        await dashboard.flush();
        dashboard.click('navSettings');
        dashboard.element(credential).value = 'replacement-secret';
        dashboard.click('saveKey');
        dashboard.popstate(search);
        await dashboard.flush();
        // A late old finally must also leave the replacement request pending.
        dashboard.resolvePublic(path, 0, outcome === 'error' ? 'error' : fresh);
        await dashboard.flush();
        assert.equal(dashboard.html(panel), '', 'obsolete response repainted the panel');
        assert.doesNotMatch(dashboard.text(panel), /Unable to load/);
        assert.doesNotMatch(dashboard.text('banner'), /Obsolete public failure/);
        if (name === 'Events') assert.equal(dashboard.element('loadEvents').disabled, true);
        if (name === 'Discovery') assert.equal(dashboard.element('nextPage').disabled, true);
        dashboard.resolvePublic(path, 1, fresh);
        await dashboard.flush();
        assert.notEqual(dashboard.html(panel), '');
      });
    }
  }
}

test('clearing keys immediately removes public evidence cached on hidden screens', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL' });
  await dashboard.flush();
  dashboard.click('navEvents');
  await dashboard.flush();
  dashboard.click('navDiscover');
  await dashboard.flush();
  dashboard.click('navSettings');
  dashboard.click('clearKey');
  for (const panel of ['companyOverview', 'companyScore', 'companyHistory', 'companyExplanation', 'companyEvents', 'eventResults', 'discoveryResults']) {
    assert.equal(dashboard.html(panel), '', panel + ' retains old credential evidence');
  }
});

test('obsolete invalid-company validation cannot replace Discovery after a credential change', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=INVALID!', deferDiscovery: true });
  await dashboard.flush();
  dashboard.click('navSettings');
  dashboard.element('apiKey').value = 'replacement-public-key';
  dashboard.click('saveKey');
  dashboard.click('navDiscover');
  await dashboard.flush();
  dashboard.resolveDiscovery(1, discoveryPage('DELL'));
  await dashboard.flush();
  dashboard.resolveDiscovery(0, discoveryPage('OLD'));
  await dashboard.flush();
  assert.equal(dashboard.text('discoveryStatus'), '1 matching companies');
  assert.match(dashboard.html('discoveryResults'), /DELL/);
  assert.doesNotMatch(dashboard.html('discoveryResults'), /OLD/);
});


test('the dashboard sends requests only to the origin serving it', async () => {
  const dashboard = startDashboard({
    search: '?view=discover&api=https://example.invalid',
    stored: { 'catalyst-admin-key': 'admin-secret' },
  });
  await waitForRequests(dashboard.requests, 2);
  assert.ok(dashboard.requests.every(({ url }) => url.startsWith('https://ops.example/')));
});

test('each credential is sent only to the API routes that need it', async () => {
  const dashboard = startDashboard({
    search: '?view=settings',
    stored: { 'catalyst-admin-key': 'admin-secret', 'catalyst-api-key': 'public-secret' },
  });
  await dashboard.flush();
  const health = dashboard.requests.find((request) => new URL(request.url).pathname === '/actuator/health');
  const config = dashboard.requests.find((request) => new URL(request.url).pathname === '/internal/operations/config');
  assert.equal(health.headers['X-Admin-Key'], undefined);
  assert.equal(health.headers.Authorization, undefined);
  assert.equal(config.headers['X-Admin-Key'], 'admin-secret');
  assert.equal(config.headers.Authorization, undefined);
  dashboard.popstate('?view=discover');
  await dashboard.flush();
  const discovery = dashboard.requests.find((request) => new URL(request.url).pathname === '/v1/discovery/catalyzed');
  assert.equal(discovery.headers.Authorization, 'Bearer public-secret');
  assert.equal(discovery.headers['X-Admin-Key'], undefined);
});

test('Settings keeps the credential form visible and loads no config without admin access', async () => {
  const dashboard = startDashboard({ search: '?view=settings' });
  await dashboard.flush();
  assert.equal(dashboard.requests.some((request) => new URL(request.url).pathname === '/internal/operations/config'), false);
  assert.match(html, /id="adminKey"/);
  assert.match(html, /id="apiKey"/);
  assert.match(dashboard.text('accessStatus'), /Admin access required/);
});

test('the legacy Operations route aliases to Pipeline without starting work', async () => {
  const dashboard = startDashboard({ search: '?view=operations', stored: { 'catalyst-admin-key': 'admin-secret' } });
  await dashboard.flush();
  assert.equal(dashboard.text('pageTitle'), 'Pipeline');
  assert.equal(dashboard.requests.some((request) => request.method === 'POST'), false);
});

test('initial load defaults to Overview with an accessible destination navigation', async () => {
  const dashboard = startDashboardWithRoute();
  await dashboard.flush();
  assert.match(html, /<nav[^>]+aria-label="Main navigation"/);
  assert.match(html, /id="navOverview"/);
  assert.match(html, /id="navIntelligence"/);
  assert.match(html, /id="discoverView"/);
  assert.match(html, /id="eventsView"/);
  assert.match(html, /id="overviewView"/);
  assert.equal(dashboard.elements.get('overviewView').hidden, false);
  assert.equal(dashboard.elements.get('discoverView').hidden, true);
  assert.match(dashboard.text('pageTitle'), /Overview/);
});

test('a discovery ticker opens a company route and browser back restores Discover', async () => {
  const dashboard = startDashboard();
  await waitForRequests(dashboard.requests, 2);
  dashboard.elements.get('discoveryResults').trigger('click', { target: { closest: () => ({ dataset: { ticker: 'DELL' } }) } });
  assert.equal(dashboard.window.location.search, '?view=company&ticker=DELL');
  assert.equal(dashboard.elements.get('companyView').hidden, false);
  dashboard.popstate('?view=discover');
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
  dashboard.popstate('?view=discover');
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
  await waitForRequests(dashboard.requests, 5);
  assert.match(dashboard.elements.get('companyTitle').textContent, /Dell Technologies.*DELL/);
  assert.match(dashboard.elements.get('companyOverview').innerHTML, /NYSE.*Technology.*Hardware/s);
  const score = dashboard.elements.get('companyScore').innerHTML;
  assert.match(score, /68\.0.*CATALYZED.*65.*80/s);
  assert.match(score, /1-day velocity.*3-day velocity.*7-day velocity.*Total events.*Events 7d.*score-v1.*taxonomy-v1/s);
  const why = dashboard.elements.get('companyExplanation').innerHTML;
  assert.match(why, /reconstructed/i);
  assert.match(why, /matching score and versions do not prove original driver membership/i);
  assert.match(why, /Use Recorded valuations for the exact inputs retained by captured cycles/);
  assert.match(why, /Raised guidance &lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.match(why, /Event date.*First captured.*Source publication date/s);
  assert.match(why, /https:\/\/example.com\/story.*noopener noreferrer/s);
  assert.match(why, /Scoring details/);
  assert.match(dashboard.elements.get('companyEvents').innerHTML, /GUIDANCE_RAISE/);
});

test('unknown ticker reports a company error while independent panels remain visible', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { metadata: 'error' } });
  await waitForRequests(dashboard.requests, 5);
  assert.match(dashboard.elements.get('companyOverview').textContent, /Unable to load company/);
  assert.match(dashboard.elements.get('companyScore').innerHTML, /68\.0/);
  assert.match(dashboard.elements.get('companyEvents').innerHTML, /GUIDANCE_RAISE/);
});

test('one failed company endpoint leaves the other panels usable', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { '/timeline': 'error' } });
  await waitForRequests(dashboard.requests, 5);
  assert.match(dashboard.elements.get('companyHistory').textContent, /Unable to load score history/);
  assert.match(dashboard.elements.get('companyScore').innerHTML, /68\.0/);
  assert.match(dashboard.elements.get('companyEvents').innerHTML, /GUIDANCE_RAISE/);
});

test('history shows zero and one snapshot without inventing a line', async () => {
  for (const snapshots of [[], [{ score: 44, state: 'WATCH', asOf: '2026-09-30T12:00:00Z' }]]) {
    const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { '/timeline': { ticker: 'DELL', snapshots, transitions: [] } } });
    await waitForRequests(dashboard.requests, 5);
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
  await waitForRequests(dashboard.requests, 5);
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
  await waitForRequests(dashboard.requests, 5);
  const history = dashboard.elements.get('companyHistory').innerHTML;
  assert.match(history, /<path class="transition-mark" d="M30 20V130"/);
  assert.match(history, /<circle class="score-point" cx="210"/);
  assert.doesNotMatch(history, /<polyline/);
});

test('history sorts actual snapshots, labels transitions, and requests capped ranges', async () => {
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL' });
  await waitForRequests(dashboard.requests, 5);
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
  await waitForRequests(dashboard.requests, 5);
  dashboard.elements.get('historyRange').value = 'max';
  dashboard.elements.get('historyRange').trigger('change');
  await waitForRequests(dashboard.requests, 6);
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
    await waitForRequests(dashboard.requests, 5);
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
  await waitForRequests(dashboard.requests, 5);
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
  await waitForRequests(dashboard.requests, 5);
  await dashboard.elements.get('loadCompanyEvents').trigger('click');
  assert.match(dashboard.elements.get('companyEvents').innerHTML, /Raised guidance/);
  assert.match(dashboard.elements.get('companyEventsStatus').textContent, /Unable to load more events/);
  assert.equal(dashboard.elements.get('loadCompanyEvents').disabled, false);
});

test('scoring details retain the returned numeric factor precision', async () => {
  const driver = { ...companyFixture['/catalyst'].topDrivers[0], factors: { ...companyFixture['/catalyst'].topDrivers[0].factors, confidence: 0.9876 } };
  const dashboard = startDashboard({ search: '?view=company&ticker=DELL', company: { '/catalyst': { ...companyFixture['/catalyst'], topDrivers: [driver] } } });
  await waitForRequests(dashboard.requests, 5);
  assert.match(dashboard.elements.get('companyExplanation').innerHTML, /0\.9876/);
});

test('event search sends only bounded API filters and labels discovery time', async () => {
  const dashboard = startDashboard({ search: '?view=events' });
  await waitForRequests(dashboard.requests, 2);
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
  await waitForRequests(dashboard.requests, 2);
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

test('public event reads keep credentials scoped and do not load hidden admin feeds', async () => {
  const dashboard = startDashboard({ search: '?view=events', stored: { 'catalyst-api-key': 'public-secret' } });
  await waitForRequests(dashboard.requests, 2);
  let eventRequest = dashboard.requests.find((request) => new URL(request.url).pathname === '/v1/events');
  assert.equal(eventRequest.headers.Authorization, 'Bearer public-secret');
  assert.equal(eventRequest.headers['X-Admin-Key'], undefined);
  assert.equal(dashboard.requests.some((request) => new URL(request.url).pathname.startsWith('/internal/')), false);
  dashboard.document.getElementById('adminKey').value = 'admin-secret';
  dashboard.elements.get('saveKey').trigger('click');
  await waitForRequests(dashboard.requests, 4);
  eventRequest = dashboard.requests.filter((request) => new URL(request.url).pathname === '/v1/events').at(-1);
  assert.equal(eventRequest.headers.Authorization, 'Bearer public-secret');
  assert.equal(eventRequest.headers['X-Admin-Key'], undefined);
  assert.equal(dashboard.requests.some((request) => new URL(request.url).pathname.startsWith('/internal/')), false);
});

test('a failed event continuation leaves captured evidence available for retry', async () => {
  const dashboard = startDashboard({ search: '?view=events', events: (url) =>
    url.searchParams.has('cursor') ? 'error' : { ...eventFixture, nextCursor: 'opaque-page-2' } });
  await waitForRequests(dashboard.requests, 2);
  await dashboard.elements.get('loadEvents').trigger('click');
  assert.match(dashboard.elements.get('eventResults').innerHTML, /Raised guidance/);
  assert.match(dashboard.elements.get('eventStatus').textContent, /Unable to load more/);
  assert.equal(dashboard.elements.get('loadEvents').disabled, false);
});

test('event API strings render as escaped text without hidden admin reads', async () => {
  const event = { ...eventFixture.events[0], companyName: '<img src=x onerror=alert(1)>',
    evidence: [{ quoteOrFact: '<script>alert(1)</script>', sourceOffsetHint: null }] };
  const dashboard = startDashboard({ search: '?view=events', stored: { 'catalyst-admin-key': 'admin-secret' },
    events: { events: [event], nextCursor: null } });
  await waitForRequests(dashboard.requests, 2);
  const rendered = dashboard.elements.get('eventResults').innerHTML;
  assert.doesNotMatch(rendered, /<img|<script>/);
  assert.match(rendered, /&lt;script&gt;alert/);
  assert.equal(dashboard.requests.some((request) => new URL(request.url).pathname.startsWith('/internal/')), false);
});

test('an event without source metadata still distinguishes its missing publication date', async () => {
  const dashboard = startDashboard({ search: '?view=events', events: {
    events: [{ ...eventFixture.events[0], source: null }], nextCursor: null,
  } });
  await waitForRequests(dashboard.requests, 2);
  const rendered = dashboard.elements.get('eventResults').innerHTML;
  assert.match(rendered, /Event date:/);
  assert.match(rendered, /Source publication date:.*–/s);
  assert.match(rendered, /First captured:/);
});





test('shared API helper rejects off-origin paths and caller-controlled headers', async () => {
  const dashboard = startDashboard({ search: '?view=settings', health: 'error', stored: {
    'catalyst-admin-key': 'admin-secret', 'catalyst-api-key': 'public-secret',
  } });
  const api = dashboard.context.window.__testApi;
  await assert.rejects(api('https://evil.example/v1/events'), /local absolute paths/);
  await assert.rejects(api('//evil.example/internal/operations/config'), /local absolute paths/);
  await assert.rejects(api('/v1/events', { headers: { Authorization: 'Bearer attacker' } }), /Unsupported API request option/);
  await assert.rejects(api('/v1/events', { body: { query: 'x' } }), /Only POST requests may have a body/);
  await assert.rejects(api('/actuator/health'), (error) => error.status === 503 &&
    error.code === 'TEMPORARY_UNAVAILABLE' && error.requestId === 'request-health');
  const health = dashboard.requests.filter((request) => new URL(request.url).pathname === '/actuator/health').at(-1);
  assert.equal(health.headers.Authorization, undefined);
  assert.equal(health.headers['X-Admin-Key'], undefined);
  assert.equal(health.headers['Content-Type'], undefined);
});

test('shared API helper adds JSON content type only to explicit POST bodies', async () => {
  const dashboard = startDashboard({ search: '?view=pipeline', stored: { 'catalyst-admin-key': 'admin-secret' } });
  const api = dashboard.context.window.__testApi;
  const post = api('/internal/ingestion/runs', { method: 'POST', body: { start: true } });
  await dashboard.flush();
  const request = dashboard.requests.find((item) => item.method === 'POST');
  assert.equal(request.headers['X-Admin-Key'], 'admin-secret');
  assert.equal(request.headers.Authorization, undefined);
  assert.equal(request.headers['Content-Type'], 'application/json');
  assert.equal(request.body, JSON.stringify({ start: true }));
  dashboard.completePipeline();
  await post;
});
