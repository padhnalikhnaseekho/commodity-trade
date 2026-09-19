import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, computed, signal } from '@angular/core';
import { Applied, Row, RowChange, applyChange, sortedRows } from './patch';

/** How the blotter is connected to the server, shown as a badge so the user always knows whether the numbers are live. */
type Connection = 'connecting' | 'live' | 'reconnecting' | 'historical' | 'error';

/**
 * The blotter: one row per assignment with priced and unpriced quantity, approval and valuation, kept current by a Server-Sent Events stream.
 *
 * Two modes, and the difference is the point of the demo:
 *   LIVE       rows are fetched once and then changed in place by pushed deltas (no polling, no refresh).
 *   AS-OF a BRD  the stream is closed and every row is re-resolved at that business date from the server (reproducibility: pick a past date and the numbers
 *              snap back to what they were then). Going back to Live reopens the stream.
 *
 * WHY EventSource: the data flows one way, it is plain HTTP, and the browser reconnects by itself and sends Last-Event-ID, so the server can replay what was
 * missed (or tell us to reset if we were away too long). Quantities and prices arrive as strings and are shown as strings; the number formatting below is
 * for display only, never for arithmetic.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header>
      <h1>Commodity Blotter</h1>
      <span class="badge" [class]="connection()">{{ label() }}</span>
      <span class="spacer"></span>
      <label>Desk
        <select [value]="desk()" (change)="desk.set($any($event.target).value)">
          <option value="">All desks</option>
          @for (d of desks(); track d) { <option [value]="d">{{ d }}</option> }
        </select>
      </label>
      <label>As of BRD
        <input type="date" [value]="asOf()" (change)="setAsOf($any($event.target).value)">
      </label>
      <button type="button" (click)="setAsOf('')" [disabled]="asOf() === ''">Live</button>
    </header>

    @if (asOf() !== '') {
      <p class="banner">Historical view: every row is resolved as of <strong>{{ asOf() }}</strong>. Nothing here changes; choose Live to follow the desk.</p>
    }

    <p class="summary">{{ visible().length }} assignments · priced {{ format(totalPriced()) }} · unpriced {{ format(totalUnpriced()) }}</p>

    <div class="scroll">
      <table>
        <thead>
          <tr>
            <th>Assignment</th><th>Trade</th><th>Quota</th><th>Desk</th><th>Commodity</th>
            <th class="num">Qty</th><th class="num">Priced</th><th class="num">Unpriced</th><th>Approval</th><th>Status</th>
            <th class="num">Valuation</th><th>Valued as of</th>
          </tr>
        </thead>
        <tbody>
          @for (r of visible(); track r.assignmentRef) {
            <tr [class.flash]="flashed().has(r.assignmentRef)">
              <td class="ref">{{ r.assignmentRef }}</td>
              <td>{{ r.tradeRef }}</td>
              <td>{{ r.quotaRef }}</td>
              <td>{{ r.deskId }}</td>
              <td>{{ r.commodity }}</td>
              <td class="num">{{ format(r.qty) }}</td>
              <td class="num">
                {{ format(r.pricedQty) }}
                <span class="bar" [title]="percent(r) + '% priced'"><span [style.width.%]="percent(r)"></span></span>
              </td>
              <td class="num" [class.over]="r.overFixed">{{ format(r.unpricedQty) }}@if (r.overFixed) { <em> over-fixed</em> }</td>
              <td><span class="pill" [class.ok]="r.approvalStatus === 'APPROVED'">{{ r.approvalStatus === 'APPROVED' ? 'Approved' : 'Unapproved' }}</span></td>
              <td>{{ r.status }}</td>
              <td class="num">
                @if (r.valuation !== null) {
                  {{ format(r.valuation) }}
                  <span class="pill" [class.ok]="r.provisional === false" [class.warn]="r.provisional === true"
                        [title]="'Struck at ' + r.valuationLevel + ' level on the ' + r.valuationEngine + ' engine'">
                    {{ r.provisional ? 'provisional' : 'final' }}{{ r.valuationLevel === 'QUOTA' ? ' · quota' : '' }}
                  </span>
                } @else { <span class="muted">—</span> }
              </td>
              <td>{{ r.valuationAsOf ?? '' }}</td>
            </tr>
          } @empty {
            <tr><td colspan="12" class="muted empty">{{ connection() === 'error' ? 'Cannot reach the server.' : 'No assignments for this selection.' }}</td></tr>
          }
        </tbody>
      </table>
    </div>
  `,
})
export class AppComponent implements OnInit, OnDestroy {
  /** The table, kept in a Map so a pushed change touches one row; published to the template as a sorted list. */
  private readonly table = new Map<string, Row>();
  private stream: EventSource | null = null;

  readonly rows = signal<Row[]>([]);
  readonly desk = signal('');
  readonly asOf = signal('');
  readonly connection = signal<Connection>('connecting');
  readonly flashed = signal<ReadonlySet<string>>(new Set());

  readonly desks = computed(() => [...new Set(this.rows().map(r => r.deskId))].sort());
  readonly visible = computed(() => this.rows().filter(r => this.desk() === '' || r.deskId === this.desk()));
  readonly totalPriced = computed(() => this.visible().reduce((s, r) => s + Number(r.pricedQty), 0));
  readonly totalUnpriced = computed(() => this.visible().reduce((s, r) => s + Number(r.unpricedQty), 0));
  readonly label = computed(() => ({ connecting: 'Connecting…', live: 'Live', reconnecting: 'Reconnecting…', historical: 'As-of view', error: 'Offline' })[this.connection()]);

  ngOnInit(): void {
    void this.load();
  }

  ngOnDestroy(): void {
    this.closeStream();
  }

  /** Re-resolve every row at the chosen BRD, or return to the live view when the date is cleared. */
  setAsOf(date: string): void {
    this.asOf.set(date);
    void this.load();
  }

  private async load(): Promise<void> {
    this.closeStream();
    try {
      const query = this.asOf() === '' ? '' : `?brd=${encodeURIComponent(this.asOf())}`;
      const response = await fetch(`/api/blotter${query}`);
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const rows = (await response.json()) as Row[];
      this.table.clear();
      rows.forEach(r => this.table.set(r.assignmentRef, r));
      this.publish();
      if (this.asOf() === '') this.openStream();
      else this.connection.set('historical');
    } catch {
      this.connection.set('error');
    }
  }

  private openStream(): void {
    this.connection.set('connecting');
    const es = new EventSource('/api/blotter/stream');
    es.onopen = () => this.connection.set('live');
    // The browser reconnects by itself (sending Last-Event-ID); until it does, say so.
    es.onerror = () => this.connection.set('reconnecting');
    es.addEventListener('patch', (event: MessageEvent<string>) => this.onPatch(JSON.parse(event.data) as RowChange));
    // We were away longer than the server can replay: start again from the full table.
    es.addEventListener('reset', () => void this.load());
    this.stream = es;
  }

  private closeStream(): void {
    this.stream?.close();
    this.stream = null;
  }

  private onPatch(change: RowChange): void {
    const applied: Applied = applyChange(this.table, change);
    this.publish();
    if (applied.added || applied.changedFields.length > 0) this.flash(change.assignmentRef);
  }

  private publish(): void {
    this.rows.set(sortedRows(this.table));
  }

  /** Highlights a row that just changed so a live update is visible to the eye. */
  private flash(ref: string): void {
    this.flashed.update(s => new Set(s).add(ref));
    setTimeout(() => this.flashed.update(s => { const n = new Set(s); n.delete(ref); return n; }), 1500);
  }

  /** Display formatting only. The values stay strings everywhere else. */
  format(value: string | number): string {
    return new Intl.NumberFormat('en-US', { minimumFractionDigits: 4, maximumFractionDigits: 4 }).format(Number(value));
  }

  percent(r: Row): number {
    const qty = Number(r.qty);
    return qty > 0 ? Math.min(100, Math.max(0, (Number(r.pricedQty) / qty) * 100)) : 0;
  }
}
