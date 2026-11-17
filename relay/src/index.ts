import type { Bytes } from "./cbor";
// Setu relay. One Worker, five routes. See docs/ARCHITECTURE.md and
// docs/PROTOCOL.md "Relay HTTP".

import { CborError, CborReader, CborWriter } from "./cbor";
import { PacketType, Protocol, Signer, create, decode, encode, fromHex, signerFromSeed, toHex, tryDecode, type Packet } from "./packet";
import { D1Store, type Row, type SosView, type Store } from "./store";
import { display, normalise, shortCodeOf } from "./shortcode";

export interface Env {
  DB: D1Database;
  RATE_LIMIT_PER_MINUTE?: string;
  RELAY_SEED?: string;
  RESCUER_TOKEN?: string;
}

export const MAX_BATCH = 200;
export const MAX_BODY_BYTES = MAX_BATCH * (Protocol.MAX_PACKET_BYTES + 4) + 16;
const MAX_SOS_RESULTS = 5000;

/** Everything the routes need, so tests can supply a memory store and a fixed clock. */
export interface Deps {
  store: Store;
  now: () => number;
  rateLimit: number;
  signer: Signer | null;
  rescuerToken: string | null;
}

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization, X-Setu-Origin",
};

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", "Cache-Control": "no-store", ...CORS },
  });
}

function error(status: number, message: string): Response {
  return json({ error: message }, status);
}

export async function handle(req: Request, d: Deps): Promise<Response> {
  const url = new URL(req.url);
  const path = url.pathname.replace(/\/+$/, "") || "/";
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });

  try {
    if (req.method === "GET" && path === "/v1/health") return health(d);
    if (req.method === "POST" && path === "/v1/upload") return upload(req, d);
    if (req.method === "GET" && path === "/v1/sos") return listSos(url, d);
    if (req.method === "POST" && path === "/v1/ack") return ack(req, d);
    if (req.method === "GET" && path.startsWith("/v1/lookup/")) return lookup(decodeURIComponent(path.substring("/v1/lookup/".length)), d);
    if (req.method === "GET" && path === "/") return json({ name: "setu-relay", docs: "https://github.com/guru-bharadwaj20/Setu" });
    return error(404, "not found");
  } catch (e) {
    console.error(e);
    return error(500, "internal error");
  }
}

async function health(d: Deps): Promise<Response> {
  const c = await d.store.counts();
  return json({ ok: true, now: d.now(), packets: c.packets, sos: c.sos, canAck: d.signer !== null });
}

async function rowOf(p: Packet, now: number): Promise<Row> {
  return {
    id: toHex(p.id),
    type: p.body.type,
    origin: toHex(p.body.origin),
    code: await shortCodeOf(p.body.origin),
    ts: p.body.ts,
    expiresAt: p.body.ts + p.body.ttl,
    latE5: p.body.latE5,
    lonE5: p.body.lonE5,
    accM: p.body.accuracyM,
    headcount: p.body.headcount,
    needs: p.body.needs,
    battery: p.body.battery,
    hops: p.hops,
    ref: p.body.ref ? toHex(p.body.ref) : null,
    note: p.body.note ?? null,
    wire: encode(p),
    receivedAt: now,
  };
}

async function upload(req: Request, d: Deps): Promise<Response> {
  const now = d.now();
  const uploader = req.headers.get("X-Setu-Origin")?.toLowerCase() ?? null;
  if (uploader !== null && !/^[0-9a-f]{64}$/.test(uploader)) return error(400, "bad X-Setu-Origin");
  const key = uploader ?? `ip:${req.headers.get("CF-Connecting-IP") ?? "unknown"}`;
  if (!(await d.store.rateHit(key, Math.floor(now / 60), d.rateLimit))) return error(429, "slow down");

  const len = Number(req.headers.get("Content-Length") ?? "0");
  if (len > MAX_BODY_BYTES) return error(413, "batch too large");
  const bytes = new Uint8Array(await req.arrayBuffer());
  if (bytes.length > MAX_BODY_BYTES) return error(413, "batch too large");

  let encoded: Bytes[];
  try {
    const r = new CborReader(bytes);
    const n = r.array();
    if (n > MAX_BATCH) return error(413, `at most ${MAX_BATCH} packets per batch`);
    encoded = [];
    for (let i = 0; i < n; i++) encoded.push(r.bytes());
    r.end();
  } catch (e) {
    if (e instanceof CborError) return error(400, `bad body: ${e.message}`);
    throw e;
  }

  // Invalid packets are dropped silently, as everywhere else in the mesh.
  const rows: Row[] = [];
  const sosIds: string[] = [];
  for (const b of encoded) {
    const p = await tryDecode(b, now);
    if (!p) continue;
    const row = await rowOf(p, now);
    rows.push(row);
    if (p.body.type === PacketType.SOS) sosIds.push(row.id);
  }
  const stored = await d.store.insert(rows);

  const origins = uploader ? [uploader] : [];
  const acks = await d.store.acksFor(sosIds, origins, now);
  const w = new CborWriter().array(acks.length);
  for (const a of acks) w.bytes(a);
  return new Response(w.finish(), {
    headers: {
      "Content-Type": "application/cbor",
      "X-Setu-Received": String(encoded.length),
      "X-Setu-Stored": String(stored),
      ...CORS,
    },
  });
}

function publicView(v: SosView) {
  return {
    id: v.id,
    code: display(v.code),
    ts: v.ts,
    receivedAt: v.receivedAt,
    expiresAt: v.expiresAt,
    lat: v.latE5 / 1e5,
    lon: v.lonE5 / 1e5,
    accuracyM: v.accM,
    headcount: v.headcount,
    needs: v.needs,
    battery: v.battery,
    hops: v.hops,
    note: v.note,
    acked: v.acked,
    safe: v.safe,
  };
}

async function listSos(url: URL, d: Deps): Promise<Response> {
  const since = Number(url.searchParams.get("since") ?? "0");
  if (!Number.isFinite(since) || since < 0) return error(400, "bad since");
  const rows = await d.store.latestSos(since, d.now(), MAX_SOS_RESULTS);
  return json({ now: d.now(), sos: rows.map(publicView) });
}

/** Families get status and a rough position (about 1 km), not the exact spot. */
function familyView(v: SosView) {
  return {
    code: display(v.code),
    ts: v.ts,
    lat: Math.round(v.latE5 / 1000) / 100,
    lon: Math.round(v.lonE5 / 1000) / 100,
    headcount: v.headcount,
    acked: v.acked,
    safe: v.safe,
  };
}

async function lookup(raw: string, d: Deps): Promise<Response> {
  const code = normalise(raw);
  if (!code) return error(400, "a code looks like ABC-DEF");
  const rows = await d.store.lookup(code, d.now());
  return json({ code: display(code), matches: rows.map(familyView) });
}

async function ack(req: Request, d: Deps): Promise<Response> {
  if (!d.rescuerToken) return error(503, "rescuer token not configured");
  const auth = req.headers.get("Authorization") ?? "";
  if (auth !== `Bearer ${d.rescuerToken}`) return error(401, "unauthorised");
  if (!d.signer) return error(503, "relay key not configured");

  let body: { id?: unknown };
  try {
    body = (await req.json()) as { id?: unknown };
  } catch {
    return error(400, "json body required");
  }
  if (typeof body.id !== "string" || !/^[0-9a-f]{32}$/i.test(body.id)) return error(400, "id must be 32 hex chars");
  const id = body.id.toLowerCase();

  const now = d.now();
  const sos = await d.store.get(id);
  if (!sos || sos.type !== PacketType.SOS) return error(404, "no such SOS");

  // Idempotent: one ACK per SOS is enough for the mesh.
  const existing = await d.store.acksFor([id], [], now);
  if (existing.length > 0) {
    const p = await decode(existing[0]!, now, false);
    return json({ ackId: toHex(p.id), existing: true });
  }

  const p = await create(
    {
      origin: d.signer.publicKey,
      type: PacketType.ACK,
      ts: now,
      latE5: sos.latE5,
      lonE5: sos.lonE5,
      accuracyM: 0,
      headcount: 0,
      needs: 0,
      battery: 100,
      ttl: Protocol.TTL_ACK_SECONDS,
      ref: fromHex(id),
    },
    d.signer,
  );
  await d.store.insert([await rowOf(p, now)]);
  return json({ ackId: toHex(p.id), existing: false });
}

// ------------------------------------------------------------------ Worker

let cachedSigner: { seed: string; signer: Signer } | null = null;

async function depsFromEnv(env: Env): Promise<Deps> {
  let signer: Signer | null = null;
  if (env.RELAY_SEED) {
    if (cachedSigner?.seed !== env.RELAY_SEED) cachedSigner = { seed: env.RELAY_SEED, signer: await signerFromSeed(fromHex(env.RELAY_SEED)) };
    signer = cachedSigner.signer;
  }
  return {
    store: new D1Store(env.DB),
    now: () => Math.floor(Date.now() / 1000),
    rateLimit: Number(env.RATE_LIMIT_PER_MINUTE ?? "60"),
    signer,
    rescuerToken: env.RESCUER_TOKEN ?? null,
  };
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    return handle(req, await depsFromEnv(env));
  },

  async scheduled(_event: ScheduledEvent, env: Env): Promise<void> {
    const d = await depsFromEnv(env);
    const now = d.now();
    await d.store.purge(now, Math.floor(now / 60) - 2);
  },
};
