const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
// Arrays from the VM realm have a different prototype; compare their contents.
const plain = (value) => JSON.parse(JSON.stringify(value));
const window = {};
vm.runInNewContext(fs.readFileSync(path.join(__dirname, 'following.js'), 'utf8'), { window, JSON, Array, String });

test('following list parses defensively and normalizes tickers', () => {
  assert.deepEqual(plain(window.CatalystFollowing.parse(null)), []);
  assert.deepEqual(plain(window.CatalystFollowing.parse('not json')), []);
  assert.deepEqual(plain(window.CatalystFollowing.parse('{"a":1}')), []);
  assert.deepEqual(plain(window.CatalystFollowing.parse('["nvda", "DELL", "dell", "bad ticker", 7]')), ['DELL', 'NVDA']);
  const many = JSON.stringify(Array.from({ length: 60 }, (_, i) => 'T' + i));
  assert.equal(window.CatalystFollowing.parse(many).length, 50);
});

test('toggling follows and unfollows and refuses to exceed the limit', () => {
  assert.deepEqual(plain(window.CatalystFollowing.toggle([], 'dell')), ['DELL']);
  assert.deepEqual(plain(window.CatalystFollowing.toggle(['DELL', 'NVDA'], 'DELL')), ['NVDA']);
  const full = Array.from({ length: 50 }, (_, i) => 'T' + i);
  assert.equal(window.CatalystFollowing.toggle(full, 'NEW'), null);
  assert.equal(window.CatalystFollowing.toggle(full, 'T1').length, 49);
});
