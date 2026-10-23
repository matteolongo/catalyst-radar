const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const window = {};
const file = path.join(__dirname, 'leaderboard.js');
vm.runInNewContext(fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '', { window, URLSearchParams });

test('leaderboard query preserves signed and zero filters with bounded paging', () => {
  assert.equal(typeof window.CatalystLeaderboard?.query, 'function');
  const query = window.CatalystLeaderboard.query({ states: ['WATCH', 'HIGH'], minScore: 0,
    minVelocity7d: -2, sector: ' Technology ', sort: 'VELOCITY', limit: 20, offset: 40 });
  assert.deepEqual(query.getAll('state'), ['WATCH', 'HIGH']);
  assert.equal(query.get('minScore'), '0');
  assert.equal(query.get('minVelocity7d'), '-2');
  assert.equal(query.get('sector'), 'Technology');
  assert.equal(query.get('sort'), 'VELOCITY');
  assert.equal(query.get('limit'), '20');
  assert.equal(query.get('offset'), '40');
});

test('leaderboard query rejects unsupported sorts and invalid bounds', () => {
  assert.equal(typeof window.CatalystLeaderboard?.query, 'function');
  for (const filters of [{ sort: 'DECLINE' }, { limit: 101 }, { limit: 1.5 }, { offset: -1 },
    { minScore: 101 }, { minScore: -1 }, { minVelocity7d: 'NaN' }, { states: ['INVALID'] }]) {
    assert.throws(() => window.CatalystLeaderboard.query(filters));
  }
  const query = window.CatalystLeaderboard.query({ minScore: '', minVelocity7d: '', sector: ' ' });
  assert.equal(query.toString(), 'sort=SCORE&limit=20&offset=0');
});

const page = { asOf: '2026-10-22T10:00:00Z', total: 2, limit: 20, offset: 0, results: [
  { ticker: 'ZZZ', name: '<img src=x onerror=alert(1)>', sector: 'Technology', state: 'WATCH',
    score: 30, velocity7d: -2, events7d: 0, scoreVersion: 'score-v1', taxonomyVersion: 'taxonomy-v1', asOf: '2026-10-22T09:00:00Z' },
  { ticker: 'AAA', name: 'Second', state: 'HIGH', score: 90, velocity7d: 0, events7d: 1, asOf: '2026-10-22T08:00:00Z' },
] };

test('leaderboard rendering preserves server order and escapes returned text', () => {
  assert.equal(typeof window.CatalystLeaderboard?.render, 'function');
  const before = JSON.stringify(page);
  const markup = window.CatalystLeaderboard.render(page, { sort: 'SCORE' });
  assert.ok(markup.indexOf('ZZZ') < markup.indexOf('AAA'));
  assert.match(markup, /&lt;img/);
  assert.doesNotMatch(markup, /<img/);
  assert.match(markup, /<table/);
  assert.match(markup, /aria-sort="descending"/);
  assert.match(markup, /UTC/);
  assert.match(markup, /−2\.0/);
  assert.match(markup, />0\.0</);
  assert.equal(JSON.stringify(page), before);
});

test('compact leaderboard keeps timestamps and distinguishes missing values from zero', () => {
  assert.equal(typeof window.CatalystLeaderboard?.render, 'function');
  const markup = window.CatalystLeaderboard.render({ ...page, results: [
    { ...page.results[0], score: null, velocity7d: 3, asOf: null }, page.results[1],
  ] }, { compact: true, sort: 'VELOCITY' });
  assert.match(markup, /Unavailable/);
  assert.match(markup, /\+3\.0/);
  assert.match(markup, /0\.0/);
  assert.match(markup, /2026-10-22/);
  assert.doesNotMatch(markup, /Events discovered|<th[^>]*>Sector/);
});
