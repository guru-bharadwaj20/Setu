import type { Bytes } from "../src/cbor";
import { describe, expect, it } from "vitest";
import { CborReader } from "../src/cbor";
import { PacketType, decode, toHex } from "../src/packet";
import { NOW, batch, encode, harness, safe, signer, sos } from "./helpers";

async function acksIn(res: Response): Promise<Bytes[]> {
  expect(res.status).toBe(200);
  expect(res.headers.get("Content-Type")).toBe("application/cbor");
  const r = new CborReader(new Uint8Array(await res.arrayBuffer()));
  const n = r.array();
  const out: Bytes[] = [];
  for (let i = 0; i < n; i++) out.push(r.bytes());
  r.end();
  return out;
}

describe("POST /v1/upload", () => {
  it("stores valid packets once and ignores junk", async () => {
    const h = await harness();
    const a = await signer(1);
    const p1 = await sos(a);
    const p2 = await sos(a, { ts: NOW - 30 });
    const body = batch([encode(p1), encode(p2), encode(p1), new Uint8Array([1, 2, 3])]);
    const res = await h.call("POST", "/v1/upload", { body, headers: { "Content-Type": "application/cbor" } });
    expect(await acksIn(res)).toEqual([]);
    expect(res.headers.get("X-Setu-Received")).toBe("4");
    expect(res.headers.get("X-Setu-Stored")).toBe("2");
    expect(h.store.rows.size).toBe(2);

    const again = await h.call("POST", "/v1/upload", { body });
    expect(again.headers.get("X-Setu-Stored")).toBe("0");
  });

  it("returns ACKs for uploaded SOS ids and for the uploader's own origin", async () => {
    const h = await harness();
    const victim = await signer(1);
    const courier = await signer(2);
    const p = await sos(victim);
    await h.call("POST", "/v1/upload", { body: batch([encode(p)]) });

    const ackRes = await h.call("POST", "/v1/ack", {
      body: JSON.stringify({ id: toHex(p.id) }),
      headers: { Authorization: "Bearer secret-token", "Content-Type": "application/json" },
    });
    expect(ackRes.status).toBe(200);

    // A courier re-uploading the same SOS gets the ACK to carry back.
    const r1 = await acksIn(await h.call("POST", "/v1/upload", {
      body: batch([encode(p)]), headers: { "X-Setu-Origin": toHex(courier.publicKey) },
    }));
    expect(r1).toHaveLength(1);
    const ack = await decode(r1[0]!, NOW);
    expect(ack.body.type).toBe(PacketType.ACK);
    expect(toHex(ack.body.ref!)).toBe(toHex(p.id));
    expect(toHex(ack.body.origin)).toBe(toHex(h.signer!.publicKey));

    // The victim uploading an empty batch (nothing left to send) still gets it by origin.
    const r2 = await acksIn(await h.call("POST", "/v1/upload", {
      body: batch([]), headers: { "X-Setu-Origin": toHex(victim.publicKey) },
    }));
    expect(r2).toHaveLength(1);

    // A stranger with an empty batch gets nothing.
    const r3 = await acksIn(await h.call("POST", "/v1/upload", { body: batch([]) }));
    expect(r3).toHaveLength(0);
  });

  it("rejects malformed bodies, oversize batches and bad origin headers", async () => {
    const h = await harness();
    expect((await h.call("POST", "/v1/upload", { body: new Uint8Array([0xff]) })).status).toBe(400);
    expect((await h.call("POST", "/v1/upload", { body: batch([]), headers: { "X-Setu-Origin": "nope" } })).status).toBe(400);
    const big = batch(new Array(201).fill(new Uint8Array(1)));
    expect((await h.call("POST", "/v1/upload", { body: big })).status).toBe(413);
  });

  it("rate limits per uploader per minute", async () => {
    const h = await harness({ rateLimit: 2 });
    const headers = { "X-Setu-Origin": "ab".repeat(32) };
    expect((await h.call("POST", "/v1/upload", { body: batch([]), headers })).status).toBe(200);
    expect((await h.call("POST", "/v1/upload", { body: batch([]), headers })).status).toBe(200);
    expect((await h.call("POST", "/v1/upload", { body: batch([]), headers })).status).toBe(429);
    expect((await h.call("POST", "/v1/upload", { body: batch([]), headers: { "X-Setu-Origin": "cd".repeat(32) } })).status).toBe(200);
    h.clock.now += 60;
    expect((await h.call("POST", "/v1/upload", { body: batch([]), headers })).status).toBe(200);
  });
});

describe("GET /v1/sos", () => {
  it("lists the latest SOS per origin with ack and safe status", async () => {
    const h = await harness();
    const a = await signer(1);
    const b = await signer(2);
    const old = await sos(a, { ts: NOW - 600 });
    const latest = await sos(a, { ts: NOW - 60, headcount: 4 });
    const other = await sos(b, { ts: NOW - 100 });
    await h.call("POST", "/v1/upload", { body: batch([encode(old), encode(latest), encode(other), encode(await safe(b))]) });

    const res = await h.call("GET", "/v1/sos");
    const j = (await res.json()) as { sos: Array<{ id: string; headcount: number; safe: boolean; acked: boolean; lat: number }> };
    expect(j.sos).toHaveLength(2);
    expect(j.sos[0]!.id).toBe(toHex(latest.id));
    expect(j.sos[0]!.headcount).toBe(4);
    expect(j.sos[0]!.safe).toBe(false);
    expect(j.sos[0]!.lat).toBeCloseTo(12.97, 5);
    expect(j.sos[1]!.id).toBe(toHex(other.id));
    expect(j.sos[1]!.safe).toBe(true);

    expect((await h.call("GET", "/v1/sos?since=abc")).status).toBe(400);
    const none = (await (await h.call("GET", `/v1/sos?since=${NOW + 1}`)).json()) as { sos: unknown[] };
    expect(none.sos).toHaveLength(0);
  });
});

describe("POST /v1/ack", () => {
  it("needs the token, needs a stored SOS and is idempotent", async () => {
    const h = await harness();
    const p = await sos(await signer(1));
    const headers = { Authorization: "Bearer secret-token", "Content-Type": "application/json" };
    expect((await h.call("POST", "/v1/ack", { body: JSON.stringify({ id: toHex(p.id) }) })).status).toBe(401);
    expect((await h.call("POST", "/v1/ack", { body: JSON.stringify({ id: toHex(p.id) }), headers })).status).toBe(404);
    expect((await h.call("POST", "/v1/ack", { body: JSON.stringify({ id: "zz" }), headers })).status).toBe(400);

    await h.call("POST", "/v1/upload", { body: batch([encode(p)]) });
    const first = (await (await h.call("POST", "/v1/ack", { body: JSON.stringify({ id: toHex(p.id) }), headers })).json()) as { ackId: string; existing: boolean };
    const second = (await (await h.call("POST", "/v1/ack", { body: JSON.stringify({ id: toHex(p.id) }), headers })).json()) as { ackId: string; existing: boolean };
    expect(first.existing).toBe(false);
    expect(second).toEqual({ ackId: first.ackId, existing: true });
    expect(h.store.rows.size).toBe(2);

    const list = (await (await h.call("GET", "/v1/sos")).json()) as { sos: Array<{ acked: boolean }> };
    expect(list.sos[0]!.acked).toBe(true);
  });

  it("reports 503 when no relay key is configured", async () => {
    const h = await harness({ signer: null });
    const p = await sos(await signer(1));
    await h.call("POST", "/v1/upload", { body: batch([encode(p)]) });
    const res = await h.call("POST", "/v1/ack", {
      body: JSON.stringify({ id: toHex(p.id) }), headers: { Authorization: "Bearer secret-token" },
    });
    expect(res.status).toBe(503);
  });
});

describe("GET /v1/lookup/:code", () => {
  it("finds a sender by short code and coarsens the position", async () => {
    const h = await harness();
    const a = await signer(1);
    const p = await sos(a);
    await h.call("POST", "/v1/upload", { body: batch([encode(p)]) });
    const code = [...h.store.rows.values()][0]!.code;

    const res = await h.call("GET", `/v1/lookup/${code.slice(0, 3)}-${code.slice(3).toLowerCase()}`);
    const j = (await res.json()) as { matches: Array<{ lat: number; lon: number; acked: boolean; safe: boolean }> };
    expect(j.matches).toHaveLength(1);
    expect(j.matches[0]!.lat).toBe(12.97);
    expect(j.matches[0]!.lon).toBe(77.59);
    expect(j.matches[0]!.acked).toBe(false);

    expect((await h.call("GET", "/v1/lookup/xx")).status).toBe(400);
    const miss = (await (await h.call("GET", "/v1/lookup/ZZZZZZ")).json()) as { matches: unknown[] };
    expect(miss.matches).toHaveLength(0);
  });
});

describe("misc", () => {
  it("answers health, CORS preflight and 404", async () => {
    const h = await harness();
    const health = (await (await h.call("GET", "/v1/health")).json()) as { ok: boolean; canAck: boolean };
    expect(health).toMatchObject({ ok: true, canAck: true });
    expect((await h.call("OPTIONS", "/v1/upload")).status).toBe(204);
    expect((await h.call("GET", "/nope")).status).toBe(404);
  });

  it("purges expired packets", async () => {
    const h = await harness();
    const p = await sos(await signer(1), { ttl: 100 });
    await h.call("POST", "/v1/upload", { body: batch([encode(p)]) });
    await h.store.purge(NOW + 1000, 0);
    expect(h.store.rows.size).toBe(0);
  });
});
