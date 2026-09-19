import { test } from 'node:test';
import assert from 'node:assert/strict';
import { applyChange, compareRefs, sortedRows, Row, RowChange } from '../src/app/patch';

function row(ref: string, over: Partial<Row> = {}): Row {
  return {
    assignmentRef: ref, tradeRef: ref.split('.')[0], quotaRef: ref.split('.').slice(0, 2).join('.'), deskId: 'DESK-1', businessLine: 'BULK',
    commodity: 'Coal', brd: '2026-09-18', qty: '100.0000', pricedQty: '0.0000', unpricedQty: '100.0000', overFixed: false, approvalStatus: 'UNAPPROVED',
    status: 'ACTIVE', valuation: null, valuationAsOf: null, valuationLevel: null, valuationEngine: null, provisional: null, ...over,
  };
}

// PROVES a field-level replace changes exactly those fields and reports them (so the UI flashes only what moved).
test('replace updates only the named fields and reports them', () => {
  const rows = new Map([['1.1.1', row('1.1.1')]]);
  const change: RowChange = { assignmentRef: '1.1.1', deskId: 'DESK-1', ops: [
    { op: 'replace', path: '/pricedQty', value: '40.0000' }, { op: 'replace', path: '/unpricedQty', value: '60.0000' } ] };

  const applied = applyChange(rows, change);

  assert.deepEqual(applied.changedFields, ['pricedQty', 'unpricedQty']);
  assert.equal(rows.get('1.1.1')!.pricedQty, '40.0000');
  assert.equal(rows.get('1.1.1')!.qty, '100.0000');           // untouched
  assert.equal(rows.get('1.1.1')!.commodity, 'Coal');         // untouched
});

test('add puts a whole new row in the table and remove takes it out', () => {
  const rows = new Map<string, Row>();
  const added = applyChange(rows, { assignmentRef: '2.1.1', deskId: 'DESK-1', ops: [{ op: 'add', path: '', value: row('2.1.1') }] });
  assert.equal(added.added, true);
  assert.equal(rows.size, 1);

  const removed = applyChange(rows, { assignmentRef: '2.1.1', deskId: 'DESK-1', ops: [{ op: 'remove', path: '' }] });
  assert.equal(removed.removed, true);
  assert.equal(rows.size, 0);
});

test('a replace for a row the client does not have is ignored, not invented', () => {
  const rows = new Map<string, Row>();
  const applied = applyChange(rows, { assignmentRef: '9.9.9', deskId: 'DESK-1', ops: [{ op: 'replace', path: '/pricedQty', value: '1.0000' }] });
  assert.deepEqual(applied.changedFields, []);
  assert.equal(rows.size, 0);
});

// PROVES the server's coalesced patch (an add followed by folded replaces) and the same changes applied one by one land on the same row.
test('applying the changes one by one equals applying the one merged change', () => {
  const start = new Map<string, Row>();
  const stepwise = new Map<string, Row>();
  applyChange(stepwise, { assignmentRef: '1.1.1', deskId: 'DESK-1', ops: [{ op: 'add', path: '', value: row('1.1.1') }] });
  applyChange(stepwise, { assignmentRef: '1.1.1', deskId: 'DESK-1', ops: [{ op: 'replace', path: '/pricedQty', value: '10.0000' }] });
  applyChange(stepwise, { assignmentRef: '1.1.1', deskId: 'DESK-1', ops: [{ op: 'replace', path: '/pricedQty', value: '30.0000' }, { op: 'replace', path: '/approvalStatus', value: 'APPROVED' }] });

  applyChange(start, { assignmentRef: '1.1.1', deskId: 'DESK-1', ops: [{ op: 'add', path: '', value: row('1.1.1', { pricedQty: '30.0000', approvalStatus: 'APPROVED' }) }] });

  assert.deepEqual(stepwise.get('1.1.1'), start.get('1.1.1'));
});

test('refs sort naturally, not as text', () => {
  assert.ok(compareRefs('1.1.2', '1.1.10') < 0);                       // as text "1.1.10" would come first
  const rows = new Map(['1.1.10', '1.1.2', '1.1.1', '1.2.1', '2.1.1', '10.1.1'].map(r => [r, row(r)] as [string, Row]));
  assert.deepEqual(sortedRows(rows).map(r => r.assignmentRef), ['1.1.1', '1.1.2', '1.1.10', '1.2.1', '2.1.1', '10.1.1']);
});
