/**
 * Applying live row changes to the blotter's local copy of the table. Pure functions, no Angular: they are unit tested with the plain Node test runner.
 *
 * WHY deltas: the server pushes JSON Patch operations per row (RFC 6902, relative to the row), already coalesced per row every 250 ms. The client applies them
 * to a Map keyed by assignment ref, so a change to one field of one row touches exactly that, with no refetch and no lookups.
 *   add ""       -> the whole row is new to the view (the value is the full row)
 *   replace /f   -> one field changed
 *   remove ""    -> the row left the view
 */

/** A blotter row as the server sends it. Only assignmentRef is relied on by the logic here; every other field is displayed as given. */
export interface Row {
  assignmentRef: string;
  tradeRef: string;
  quotaRef: string;
  deskId: string;
  businessLine: string;
  commodity: string | null;
  brd: string;
  qty: string;
  pricedQty: string;
  unpricedQty: string;
  overFixed: boolean;
  approvalStatus: string;
  status: string;
  valuation: string | null;
  valuationAsOf: string | null;
  valuationLevel: string | null;
  valuationEngine: string | null;
  provisional: boolean | null;
}

export interface PatchOp {
  op: 'add' | 'replace' | 'remove';
  path: string;
  value?: unknown;
}

export interface RowChange {
  assignmentRef: string;
  deskId: string;
  ops: PatchOp[];
}

/** What applying a change did, so the UI can flash exactly what moved. */
export interface Applied {
  added: boolean;
  removed: boolean;
  changedFields: string[];
}

/** Applies one change to the table in place. Unknown rows for a replace are ignored (the next reset or reload brings the full row). */
export function applyChange(rows: Map<string, Row>, change: RowChange): Applied {
  const result: Applied = { added: false, removed: false, changedFields: [] };
  for (const op of change.ops) {
    if (op.op === 'add' && op.path === '') {
      rows.set(change.assignmentRef, { ...(op.value as Row) });
      result.added = true;
    } else if (op.op === 'remove' && op.path === '') {
      rows.delete(change.assignmentRef);
      result.removed = true;
    } else if (op.op === 'replace' && op.path.startsWith('/')) {
      const row = rows.get(change.assignmentRef);
      if (!row) continue;
      const field = op.path.substring(1);
      (row as unknown as Record<string, unknown>)[field] = op.value;
      result.changedFields.push(field);
    }
  }
  return result;
}

/** Natural order of nested refs: 1.1.2 before 1.1.10 (a plain string sort would put 1.1.10 first). */
export function compareRefs(a: string, b: string): number {
  const pa = a.split('.').map(Number);
  const pb = b.split('.').map(Number);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

/** The table as a list in natural ref order. */
export function sortedRows(rows: Map<string, Row>): Row[] {
  return [...rows.values()].sort((x, y) => compareRefs(x.assignmentRef, y.assignmentRef));
}
