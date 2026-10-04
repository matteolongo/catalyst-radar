const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const file = path.join(__dirname, 'operations-model.js');
const source = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
const window = {};
vm.runInNewContext(source, { window, URLSearchParams });
const model = window.CatalystOperationsModel;

function helper(name) {
  assert.equal(typeof model?.[name], 'function', 'CatalystOperationsModel.' + name + ' is exported');
  return model[name];
}

test('document filters become bounded internal query parameters', () => {
  const query = helper('documentQuery')({
    q: 'guidance', provider: 'polygon', ticker: 'DELL', statuses: ['PENDING', 'RETRYABLE_ERROR'],
    from: '2026-10-01T00:00:00.000Z', to: '2026-10-02T00:00:00.000Z', dueOnly: true,
    runId: 'run-1', ingestionRunId: 'ingestion-1', cursor: 'cursor-2',
  });
  assert.deepEqual(Array.from(query.entries()), [
    ['q', 'guidance'], ['provider', 'polygon'], ['ticker', 'DELL'],
    ['status', 'PENDING'], ['status', 'RETRYABLE_ERROR'],
    ['from', '2026-10-01T00:00:00.000Z'], ['to', '2026-10-02T00:00:00.000Z'],
    ['dueOnly', 'true'], ['runId', 'run-1'], ['ingestionRunId', 'ingestion-1'],
    ['limit', '25'], ['cursor', 'cursor-2'],
  ]);
});

test('model filters and the shared window are encoded consistently', () => {
  const query = helper('modelQuery')({ provider: 'openai', operation: 'extract', model: 'gpt-test', success: false,
    documentId: 'doc-1', attemptId: 'attempt-1', runId: 'run-1', cursor: 'cursor-2' }, '7d');
  assert.deepEqual(Array.from(query.entries()), [
    ['range', '7d'], ['provider', 'openai'], ['operation', 'extract'], ['model', 'gpt-test'],
    ['success', 'false'], ['documentId', 'doc-1'], ['attemptId', 'attempt-1'],
    ['runId', 'run-1'], ['limit', '25'], ['cursor', 'cursor-2'],
  ]);
});

test('merging pages deduplicates IDs and retains feed order', () => {
  const merged = helper('mergePage')(
    { items: [{ id: 'a' }, { id: 'b' }], nextCursor: 'next-1' },
    { items: [{ id: 'b', title: 'duplicate' }, { id: 'c' }], nextCursor: null },
  );
  assert.deepEqual(merged.items.map((item) => item.id), ['a', 'b', 'c']);
  assert.equal(merged.items[1].title, undefined);
  assert.equal(merged.nextCursor, null);
});

test('a selected through date includes the full UTC day without millisecond loss', () => {
  assert.equal(helper('utcThroughDate')('2026-10-04'), '2026-10-05T00:00:00.000Z');
});

test('missing model cost is unknown while a measured zero remains zero', () => {
  const format = helper('formatKnownCost');
  assert.equal(format(null, 0, 3), 'Unknown');
  assert.equal(format(0, 3, 3), '$0.000000');
  assert.equal(format(0.1, 2, 3), '$0.100000 · partial (2/3 calls)');
});

test('signal routes use only known local destinations and encode IDs as query values', () => {
  const route = helper('signalRoute');
  const due = route({ code: 'DUE_DOCUMENTS', documentId: 'doc-1' });
  assert.ok(due.startsWith('?view=documents&'));
  assert.equal(new URLSearchParams(due.slice(1)).get('documentId'), 'doc-1');
  assert.ok(route({ code: 'SNAPSHOTS_OVERDUE' }).startsWith('?view=pipeline&'));
  assert.ok(route({ code: 'AUTHENTICATION_FAILURE', provider: 'openai' }).startsWith('?view=models&'));
  assert.equal(route({ code: 'UNTRUSTED', url: 'https://evil.example' }), null);
});
