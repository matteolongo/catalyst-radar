const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const window = {};
const file = path.join(__dirname, 'notebooks.js');
if (fs.existsSync(file)) vm.runInNewContext(fs.readFileSync(file, 'utf8'), { window });
const modules = ['companies', 'collect', 'extract', 'group', 'score', 'history'];

test('notebooks explain all six stages and label examples separately from stored records', () => {
  assert.equal(typeof window.CatalystNotebooks?.render, 'function');
  for (const module of modules) {
    const page = window.CatalystNotebooks.render(module, 'DELL');
    for (const text of ['What goes in', 'What happens', 'What comes out', 'Illustrative example',
      'ExampleCo', 'Inspect your records', 'If something is missing']) assert.ok(page.html.includes(text), module + ': ' + text);
    assert.match(page.html, /aria-current="step"/);
    assert.doesNotMatch(page.html, /data-run|onclick|<script/);
  }
});

test('notebook links retain the company and stay within existing record routes', () => {
  const page = window.CatalystNotebooks.render('extract', 'BRK.B');
  assert.match(page.html, /\?view=notebooks&amp;module=collect&amp;ticker=BRK.B/);
  assert.match(page.html, /\?view=notebooks&amp;module=group&amp;ticker=BRK.B/);
  assert.match(page.html, /\?view=documents&amp;ticker=BRK.B/);
  assert.match(window.CatalystNotebooks.render('score', 'DELL').html, /\?view=company&amp;ticker=DELL/);
  assert.match(window.CatalystNotebooks.render('score', '').html, /\?view=discover/);
});

test('notebooks recover unknown modules and escape dynamic company labels and URLs', () => {
  const page = window.CatalystNotebooks.render('<script>bad</script>', 'A"<B');
  assert.match(page.title, /Companies/);
  assert.match(page.html, /Notebook not found/);
  assert.doesNotMatch(page.html, /<script>|A"<B/);
  assert.match(page.html, /A&quot;&lt;B/);
});

test('notebooks explain current limitations without claiming backfilled history or independent execution', () => {
  assert.match(window.CatalystNotebooks.render('collect', '').html, /first provider page/);
  assert.match(window.CatalystNotebooks.render('history', '').html, /discovered today/);
  assert.match(window.CatalystNotebooks.render('group', '').html, /do not multiply/);
  assert.match(window.CatalystNotebooks.render('score', '').html, /application rules/);
  for (const module of modules) assert.match(window.CatalystNotebooks.render(module, '').html, /does not run this stage/);
});
