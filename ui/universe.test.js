const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const window = {};
vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'universe.js'), 'utf8'), { window, URLSearchParams, Date, Number, String, Object });

test('candidate query is bounded and rejects invalid offsets', () => {
  assert.equal(window.CatalystUniverse.query({ offset: 50 }).toString(), 'limit=50&offset=50');
  for (const offset of [-1, 1.5, 'x', 10001]) assert.equal(window.CatalystUniverse.query({ offset }).get('offset'), '0');
});

test('candidate rows escape tickers, explain counts and offer tracking only on request', () => {
  const page = { candidates: [{ ticker: '<X>', documentsStored: 4, documentsNeverQueued: 1,
    firstDiscoveredAt: '2026-10-20T10:00:00Z', lastDiscoveredAt: '2026-10-22T09:00:00Z' }] };
  const markup = window.CatalystUniverse.render(page);
  assert.match(markup, /&lt;X&gt;/);
  assert.doesNotMatch(markup, /<X>/);
  assert.match(markup, /not an event count/);
  assert.doesNotMatch(markup, /data-promote-ticker/);
  assert.match(window.CatalystUniverse.render(page, { promote: true }), /data-promote-ticker="&lt;X&gt;"/);
  assert.match(window.CatalystUniverse.render({ candidates: [] }), /No untracked ticker/);
});
