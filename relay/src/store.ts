import type { Bytes } from "./cbor";
// Storage behind one small interface so the routes can be tested against an
// in-memory copy and so someone can port the relay to another database.

import { PacketType } from "./packet";

export interface Row {
  id: string;          // hex
  type: PacketType;
  origin: string;      // hex
  code: string;        // short code of origin
  ts: number;
  expiresAt: number;
  latE5: number;
  lonE5: number;
  accM: number;
  headcount: number;
  needs: number;
  battery: number;
  hops: number;
  ref: string | null;  // hex, ACK only
  note: string | null;
  wire: Bytes;
  receivedAt: number;
}

/** What the map and family lookup see: the latest SOS per origin plus its status. */
export interface SosView extends Row {
  acked: boolean;
  safe: boolean;
}

export interface Store {
  /** Inserts rows whose id is new. Returns how many were new. */
  insert(rows: Row[]): Promise<number>;
  get(id: string): Promise<Row | null>;
  /** Wire bytes of ACKs whose ref is one of `sosIds`, or refers to any SOS from `origins`. */
  acksFor(sosIds: string[], origins: string[], now: number): Promise<Bytes[]>;
  /** Latest unexpired SOS per origin received after `since`, with ack and safe status. */
  latestSos(since: number, now: number, limit: number): Promise<SosView[]>;
  /** Latest unexpired SOS for every origin whose short code matches. */
  lookup(code: string, now: number): Promise<SosView[]>;
  /** Increments the counter for `key` in `minute` and reports whether it is still within `limit`. */
  rateHit(key: string, minute: number, limit: number): Promise<boolean>;
  /** Drops expired packets and rate rows older than `minute`. */
  purge(now: number, minute: number): Promise<void>;
  /** Upload counters for the health endpoint. */
  counts(): Promise<{ packets: number; sos: number }>;
}

// ---------------------------------------------------------------- in-memory

export class MemoryStore implements Store {
  readonly rows = new Map<string, Row>();
  private readonly rate = new Map<string, number>();

  async insert(rows: Row[]): Promise<number> {
    let n = 0;
    for (const r of rows) {
      if (this.rows.has(r.id)) continue;
      this.rows.set(r.id, r);
      n++;
    }
    return n;
  }

  async get(id: string): Promise<Row | null> {
    return this.rows.get(id) ?? null;
  }

  async acksFor(sosIds: string[], origins: string[], now: number): Promise<Bytes[]> {
    const wanted = new Set(sosIds);
    for (const r of this.rows.values()) {
      if (r.type === PacketType.SOS && origins.includes(r.origin)) wanted.add(r.id);
    }
    const out: Bytes[] = [];
    for (const r of this.rows.values()) {
      if (r.type === PacketType.ACK && r.ref && wanted.has(r.ref) && r.expiresAt > now) out.push(r.wire);
    }
    return out;
  }

  private view(sos: Row, now: number): SosView {
    let acked = false;
    let safe = false;
    for (const r of this.rows.values()) {
      if (r.expiresAt <= now) continue;
      if (r.type === PacketType.ACK && r.ref === sos.id) acked = true;
      if (r.type === PacketType.SAFE && r.origin === sos.origin && r.ts >= sos.ts) safe = true;
    }
    return { ...sos, acked, safe };
  }

  private latestPerOrigin(filter: (r: Row) => boolean, now: number): Row[] {
    const best = new Map<string, Row>();
    for (const r of this.rows.values()) {
      if (r.type !== PacketType.SOS || r.expiresAt <= now || !filter(r)) continue;
      const cur = best.get(r.origin);
      if (!cur || r.ts > cur.ts) best.set(r.origin, r);
    }
    return [...best.values()].sort((a, b) => b.ts - a.ts);
  }

  async latestSos(since: number, now: number, limit: number): Promise<SosView[]> {
    return this.latestPerOrigin((r) => r.receivedAt > since, now).slice(0, limit).map((r) => this.view(r, now));
  }

  async lookup(code: string, now: number): Promise<SosView[]> {
    return this.latestPerOrigin((r) => r.code === code, now).map((r) => this.view(r, now));
  }

  async rateHit(key: string, minute: number, limit: number): Promise<boolean> {
    const k = `${key}:${minute}`;
    const n = (this.rate.get(k) ?? 0) + 1;
    this.rate.set(k, n);
    return n <= limit;
  }

  async purge(now: number, minute: number): Promise<void> {
    for (const [id, r] of this.rows) if (r.expiresAt <= now) this.rows.delete(id);
    for (const k of this.rate.keys()) if (Number(k.substring(k.lastIndexOf(":") + 1)) < minute) this.rate.delete(k);
  }

  async counts(): Promise<{ packets: number; sos: number }> {
    let sos = 0;
    for (const r of this.rows.values()) if (r.type === PacketType.SOS) sos++;
    return { packets: this.rows.size, sos };
  }
}

// ---------------------------------------------------------------------- D1

interface D1Row {
  id: string; type: number; origin: string; code: string; ts: number; expires_at: number;
  lat_e5: number; lon_e5: number; acc_m: number; headcount: number; needs: number; battery: number;
  hops: number; ref: string | null; note: string | null; wire: ArrayBuffer; received_at: number;
  acked?: number; safe?: number;
}

function fromD1(r: D1Row): Row {
  return {
    id: r.id, type: r.type as PacketType, origin: r.origin, code: r.code, ts: r.ts, expiresAt: r.expires_at,
    latE5: r.lat_e5, lonE5: r.lon_e5, accM: r.acc_m, headcount: r.headcount, needs: r.needs, battery: r.battery,
    hops: r.hops, ref: r.ref, note: r.note, wire: new Uint8Array(r.wire), receivedAt: r.received_at,
  };
}

const LATEST_SOS_SQL = `
  SELECT p.*,
    EXISTS(SELECT 1 FROM packets a WHERE a.type = 1 AND a.ref = p.id AND a.expires_at > ?1) AS acked,
    EXISTS(SELECT 1 FROM packets s WHERE s.type = 2 AND s.origin = p.origin AND s.ts >= p.ts AND s.expires_at > ?1) AS safe
  FROM packets p
  WHERE p.type = 0 AND p.expires_at > ?1
    AND p.ts = (SELECT MAX(q.ts) FROM packets q WHERE q.type = 0 AND q.origin = p.origin AND q.expires_at > ?1)`;

export class D1Store implements Store {
  constructor(private readonly db: D1Database) {}

  async insert(rows: Row[]): Promise<number> {
    if (rows.length === 0) return 0;
    const stmt = this.db.prepare(
      `INSERT OR IGNORE INTO packets
       (id, type, origin, code, ts, expires_at, lat_e5, lon_e5, acc_m, headcount, needs, battery, hops, ref, note, wire, received_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    );
    const results = await this.db.batch(rows.map((r) => stmt.bind(
      r.id, r.type, r.origin, r.code, r.ts, r.expiresAt, r.latE5, r.lonE5, r.accM, r.headcount, r.needs, r.battery,
      r.hops, r.ref, r.note, r.wire, r.receivedAt,
    )));
    return results.reduce((n, res) => n + (res.meta.changes ?? 0), 0);
  }

  async get(id: string): Promise<Row | null> {
    const r = await this.db.prepare("SELECT * FROM packets WHERE id = ?").bind(id).first<D1Row>();
    return r ? fromD1(r) : null;
  }

  async acksFor(sosIds: string[], origins: string[], now: number): Promise<Bytes[]> {
    if (sosIds.length === 0 && origins.length === 0) return [];
    const idList = sosIds.map(() => "?").join(",") || "NULL";
    const originList = origins.map(() => "?").join(",") || "NULL";
    const sql = `
      SELECT a.wire FROM packets a
      WHERE a.type = 1 AND a.expires_at > ? AND a.ref IS NOT NULL AND (
        a.ref IN (${idList})
        OR a.ref IN (SELECT s.id FROM packets s WHERE s.type = 0 AND s.origin IN (${originList}))
      )`;
    const res = await this.db.prepare(sql).bind(now, ...sosIds, ...origins).all<{ wire: ArrayBuffer }>();
    return res.results.map((r) => new Uint8Array(r.wire));
  }

  async latestSos(since: number, now: number, limit: number): Promise<SosView[]> {
    const res = await this.db
      .prepare(`${LATEST_SOS_SQL} AND p.received_at > ?2 ORDER BY p.ts DESC LIMIT ?3`)
      .bind(now, since, limit)
      .all<D1Row>();
    return res.results.map((r) => ({ ...fromD1(r), acked: !!r.acked, safe: !!r.safe }));
  }

  async lookup(code: string, now: number): Promise<SosView[]> {
    const res = await this.db
      .prepare(`${LATEST_SOS_SQL} AND p.code = ?2 ORDER BY p.ts DESC LIMIT 20`)
      .bind(now, code)
      .all<D1Row>();
    return res.results.map((r) => ({ ...fromD1(r), acked: !!r.acked, safe: !!r.safe }));
  }

  async rateHit(key: string, minute: number, limit: number): Promise<boolean> {
    const r = await this.db
      .prepare(
        `INSERT INTO rate (key, minute, count) VALUES (?, ?, 1)
         ON CONFLICT(key, minute) DO UPDATE SET count = count + 1
         RETURNING count`,
      )
      .bind(key, minute)
      .first<{ count: number }>();
    return (r?.count ?? 1) <= limit;
  }

  async purge(now: number, minute: number): Promise<void> {
    await this.db.batch([
      this.db.prepare("DELETE FROM packets WHERE expires_at <= ?").bind(now),
      this.db.prepare("DELETE FROM rate WHERE minute < ?").bind(minute),
    ]);
  }

  async counts(): Promise<{ packets: number; sos: number }> {
    const r = await this.db
      .prepare("SELECT COUNT(*) AS packets, SUM(CASE WHEN type = 0 THEN 1 ELSE 0 END) AS sos FROM packets")
      .first<{ packets: number; sos: number | null }>();
    return { packets: r?.packets ?? 0, sos: r?.sos ?? 0 };
  }
}
