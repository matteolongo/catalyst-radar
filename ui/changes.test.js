const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const window = {};
vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'changes.js'), 'utf8'), { window, URLSearchParams, Date, Number, Object, String });

const now = Date.parse('2026-11-16T12:00:00Z');

test('changes query covers the chosen period and stays bounded', () => {
  const day = window.CatalystChanges.query({ range: '24h', now });
  assert.equal(day.get('since'), '2026-11-15T12:00:00.000Z');
  assert.equal(day.get('limit'), '50');
  const week = window.CatalystChanges.query({ range: '7d', now });
  assert.equal(week.get('since'), '2026-11-09T12:00:00.000Z');
  const unknown = window.CatalystChanges.query({ range: '90d', now });
  assert.equal(unknown.get('since'), '2026-11-15T12:00:00.000Z');
  const followed = window.CatalystChanges.query({ range: '24h', now, tickers: ['DELL', 'NVDA'] });
  assert.deepEqual(followed.getAll('ticker'), ['DELL', 'NVDA']);
});

const page = { since: '2026-11-15T12:00:00Z', asOf: '2026-11-16T12:00:00Z', nextCursor: null, transitions: [
  { ticker: 'NVDA', name: '<b>Nvidia</b>', fromState: 'CATALYZED', toState: 'HIGH', direction: 'UPGRADE', score: 81, transitionedAt: '2026-11-16T11:00:00Z' },
  { ticker: 'INTC', name: 'Intel', fromState: 'BUILDING', toState: 'WATCH', direction: 'DOWNGRADE', score: 30, transitionedAt: '2026-11-16T09:00:00Z' },
  { ticker: 'DELL', name: 'Dell', fromState: 'NORMAL', toState: 'WATCH', direction: 'UPGRADE', score: 26, transitionedAt: '2026-11-15T20:00:00Z' },
] };

test('changes split by direction, keep server order and escape text', () => {
  const markup = window.CatalystChanges.render(page);
  const [up, down] = markup.split('Moved down');
  assert.match(up, /Moved up <span class="muted">\(2\)/);
  assert.ok(up.indexOf('NVDA') < up.indexOf('DELL'));
  assert.match(down, /INTC/);
  assert.doesNotMatch(up, /INTC/);
  assert.match(markup, /&lt;b&gt;Nvidia/);
  assert.doesNotMatch(markup, /<b>Nvidia/);
  assert.match(markup, /data-change-ticker="NVDA"/);
});

test('changes mark entries newer than the last visit', () => {
  const markup = window.CatalystChanges.render(page, { lastVisit: '2026-11-16T08:00:00Z' });
  assert.equal((markup.match(/change-new/g) || []).length, 2);
  assert.match(markup, /2 new since your last visit/);
  assert.doesNotMatch(window.CatalystChanges.render(page), /change-new|since your last visit/);
});

test('empty and truncated periods say so', () => {
  const empty = window.CatalystChanges.render({ transitions: [], nextCursor: null });
  assert.match(empty, /No company moved to a higher state/);
  assert.match(empty, /No company moved to a lower state/);
  assert.match(window.CatalystChanges.render({ ...page, nextCursor: 'x' }), /Showing the latest 50 changes/);
});
