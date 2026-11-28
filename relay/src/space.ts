// One phone's space: its latest snapshot, the queue of the AI's changes, and what the phone did with
// them. A SQLite Durable Object, so every space is isolated and keeps its own small database.
import { DurableObject } from 'cloudflare:workers';
import { buildView, applyOp, OpError, type View } from './rules';
import { handleMcp } from './mcp';
import type { Env, Op, OpStatus, Snapshot } from './types';

export const MAX_SNAPSHOT_BYTES = 1_000_000;
export const MAX_OPS_PER_DAY = 200;
const KEEP_DONE_OPS_DAYS = 30;

export interface AckResult {
  opId: string;
  status: 'applied' | 'rejected';
  reason?: string;
}

export interface OpRecord {
  opId: string;
  op: Op;
  status: OpStatus;
  reason?: string;
  summary: string;
  createdAt: number;
  ackedAt?: number;
}

export interface SpaceStatus {
  registered: boolean;
  snapshotAt?: number;
  phoneGeneratedAt?: number;
  today?: string;
  groups?: string[];
  pending: number;
}

export class Space extends DurableObject<Env> {
  private sql: SqlStorage;
  private cachedSnapshot?: Snapshot;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.init();
  }

  private init() {
    this.sql.exec(`CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)`);
    this.sql.exec(
      `CREATE TABLE IF NOT EXISTS ops (seq INTEGER PRIMARY KEY AUTOINCREMENT, opId TEXT NOT NULL UNIQUE,
        json TEXT NOT NULL, status TEXT NOT NULL, reason TEXT, summary TEXT NOT NULL, createdAt INTEGER NOT NULL,
        ackedAt INTEGER)`,
    );
    this.sql.exec(`CREATE TABLE IF NOT EXISTS daily (day TEXT PRIMARY KEY, count INTEGER NOT NULL)`);
  }

  // --- registration and the phone's token -------------------------------------------------------

  register(tokenHash: string): boolean {
    if (this.meta('tokenHash')) return false;
    this.setMeta('tokenHash', tokenHash);
    this.setMeta('createdAt', String(Date.now()));
    return true;
  }

  verify(tokenHash: string): boolean {
    const stored = this.meta('tokenHash');
    return !!stored && timingSafeEqual(stored, tokenHash);
  }

  isRegistered(): boolean {
    return !!this.meta('tokenHash');
  }

  /** Disconnect: the space forgets everything. */
  async wipe(): Promise<void> {
    this.cachedSnapshot = undefined;
    await this.ctx.storage.deleteAll();
    this.init();
  }

  // --- the phone's side ---------------------------------------------------------------------------

  /** Errors come back as values: an exception crossing the RPC boundary loses its class. */
  putSnapshot(json: string): { pending: number } | { error: string } {
    if (json.length > MAX_SNAPSHOT_BYTES) return { error: 'Snapshot is larger than 1 MB.' };
    let snapshot: Snapshot;
    try {
      snapshot = JSON.parse(json) as Snapshot;
    } catch {
      return { error: 'Not a snapshot.' };
    }
    if (typeof snapshot.version !== 'number' || !snapshot.vocab || !Array.isArray(snapshot.exercises)) {
      return { error: 'Not a snapshot.' };
    }
    this.setMeta('snapshot', json);
    this.setMeta('snapshotAt', String(Date.now()));
    this.cachedSnapshot = snapshot;
    this.prune();
    return { pending: this.count('pending') };
  }

  /** The ops the phone has not reported back yet, oldest first. */
  pullOps(): { opId: string; op: Op }[] {
    return this.sql
      .exec<{ opId: string; json: string }>(`SELECT opId, json FROM ops WHERE status = 'pending' ORDER BY seq`)
      .toArray()
      .map((r) => ({ opId: r.opId, op: JSON.parse(r.json) as Op }));
  }

  ack(results: AckResult[]): number {
    const now = Date.now();
    let n = 0;
    for (const r of results) {
      if (r.status !== 'applied' && r.status !== 'rejected') continue;
      const cursor = this.sql.exec(
        `UPDATE ops SET status = ?, reason = ?, ackedAt = ? WHERE opId = ? AND status = 'pending'`,
        r.status, r.reason?.slice(0, 500) ?? null, now, r.opId,
      );
      n += cursor.rowsWritten;
    }
    return n;
  }

  status(): SpaceStatus {
    const s = this.snapshot();
    return {
      registered: this.isRegistered(),
      snapshotAt: num(this.meta('snapshotAt')),
      phoneGeneratedAt: s?.generatedAtMillis,
      today: s?.today,
      groups: s?.groups,
      pending: this.count('pending'),
    };
  }

  // --- the AI's side ------------------------------------------------------------------------------

  async fetch(request: Request): Promise<Response> {
    if (!this.isRegistered()) return new Response('This space was disconnected in the app.', { status: 404 });
    return handleMcp(request, this);
  }

  snapshot(): Snapshot | undefined {
    if (!this.cachedSnapshot) {
      const json = this.meta('snapshot');
      if (json) this.cachedSnapshot = JSON.parse(json) as Snapshot;
    }
    return this.cachedSnapshot;
  }

  /** The snapshot with every op it does not contain yet: pending ones, and applied after it arrived. */
  view(): View | undefined {
    const s = this.snapshot();
    if (!s) return undefined;
    const since = num(this.meta('snapshotAt')) ?? 0;
    const ops = this.sql
      .exec<{ json: string }>(
        `SELECT json FROM ops WHERE status = 'pending' OR (status = 'applied' AND ackedAt > ?) ORDER BY seq`, since,
      )
      .toArray()
      .map((r) => JSON.parse(r.json) as Op);
    return buildView(s, ops);
  }

  /**
   * Checks [ops] in order against the view and queues them all, or none: the first that fails
   * stops the batch with its reason, so the AI can fix it and send the batch again.
   */
  submit(ops: { op: Op; summary: string }[]): { queued: number } {
    const view = this.view() ?? fail('The phone has not synced yet: open the MyHealth app, then try again.');
    const day = new Date().toISOString().slice(0, 10);
    const used = this.sql.exec<{ count: number }>(`SELECT count FROM daily WHERE day = ?`, day).toArray()[0]?.count ?? 0;
    if (used + ops.length > MAX_OPS_PER_DAY) fail(`Daily limit of ${MAX_OPS_PER_DAY} changes reached; try again tomorrow.`);
    ops.forEach(({ op }, i) => {
      try {
        applyOp(view, op);
      } catch (e) {
        if (e instanceof OpError && ops.length > 1) throw new OpError(`Item ${i + 1}: ${e.message} Nothing was saved.`);
        throw e;
      }
    });
    const now = Date.now();
    this.ctx.storage.transactionSync(() => {
      for (const { op, summary } of ops) {
        this.sql.exec(
          `INSERT INTO ops (opId, json, status, summary, createdAt) VALUES (?, ?, 'pending', ?, ?)`,
          op.opId, JSON.stringify(op), summary, now,
        );
      }
      this.sql.exec(
        `INSERT INTO daily (day, count) VALUES (?, ?) ON CONFLICT(day) DO UPDATE SET count = count + excluded.count`,
        day, ops.length,
      );
    });
    return { queued: ops.length };
  }

  recentOps(limit = 50): OpRecord[] {
    return this.sql
      .exec<{ opId: string; json: string; status: OpStatus; reason: string | null; summary: string; createdAt: number; ackedAt: number | null }>(
        `SELECT opId, json, status, reason, summary, createdAt, ackedAt FROM ops ORDER BY seq DESC LIMIT ?`, limit,
      )
      .toArray()
      .map((r) => ({
        opId: r.opId, op: JSON.parse(r.json) as Op, status: r.status, reason: r.reason ?? undefined, summary: r.summary,
        createdAt: r.createdAt, ackedAt: r.ackedAt ?? undefined,
      }));
  }

  // --- helpers ------------------------------------------------------------------------------------

  private prune() {
    const cutoff = Date.now() - KEEP_DONE_OPS_DAYS * 86_400_000;
    this.sql.exec(`DELETE FROM ops WHERE status != 'pending' AND createdAt < ?`, cutoff);
    this.sql.exec(`DELETE FROM daily WHERE day < ?`, new Date(cutoff).toISOString().slice(0, 10));
  }

  private count(status: OpStatus): number {
    return this.sql.exec<{ n: number }>(`SELECT COUNT(*) AS n FROM ops WHERE status = ?`, status).one().n;
  }

  private meta(key: string): string | undefined {
    return this.sql.exec<{ value: string }>(`SELECT value FROM meta WHERE key = ?`, key).toArray()[0]?.value;
  }

  private setMeta(key: string, value: string) {
    this.sql.exec(`INSERT INTO meta (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value`, key, value);
  }
}

const fail = (message: string): never => {
  throw new OpError(message);
};

const num = (s: string | undefined) => (s ? Number(s) : undefined);

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
