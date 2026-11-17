// Opt-in end-to-end check against a running relay with a real D1 database:
//
//   npx wrangler d1 execute setu --local --file=schema.sql
//   npx wrangler dev            # with RELAY_SEED and RESCUER_TOKEN in .dev.vars
//   SETU_E2E_URL=http://127.0.0.1:8787 SETU_E2E_TOKEN=... npm test
//
// Skipped unless SETU_E2E_URL is set, so CI and normal runs stay hermetic.

import { describe, expect, it } from "vitest";
import { CborReader } from "../src/cbor";
import { PacketType, create, decode, encode, signerFromSeed, toHex } from "../src/packet";
import { shortCodeOf, display } from "../src/shortcode";
import { batch } from "./helpers";

const URL_ = process.env.SETU_E2E_URL;
const TOKEN = process.env.SETU_E2E_TOKEN ?? "";

describe.skipIf(!URL_)("relay end to end", () => {
  it("stores an SOS, acks it and returns the ACK to the sender", async () => {
    const now = Math.floor(Date.now() / 1000);
    const seed = crypto.getRandomValues(new Uint8Array(32));
    const victim = await signerFromSeed(seed);
    const sos = await create({
      origin: victim.publicKey, type: PacketType.SOS, ts: now, latE5: 1297000, lonE5: 7759000,
      accuracyM: 15, headcount: 2, needs: 9, battery: 30, ttl: 172_800, note: "e2e",
    }, victim);

    const up = await fetch(`${URL_}/v1/upload`, { method: "POST", body: batch([encode(sos)]), headers: { "Content-Type": "application/cbor" } });
    expect(up.status).toBe(200);
    expect(up.headers.get("X-Setu-Stored")).toBe("1");

    const list = (await (await fetch(`${URL_}/v1/sos`)).json()) as { sos: Array<{ id: string; acked: boolean; note: string }> };
    const mine = list.sos.find((s) => s.id === toHex(sos.id));
    expect(mine).toMatchObject({ acked: false, note: "e2e" });

    const ack = await fetch(`${URL_}/v1/ack`, {
      method: "POST", body: JSON.stringify({ id: toHex(sos.id) }),
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${TOKEN}` },
    });
    expect(ack.status).toBe(200);

    const back = await fetch(`${URL_}/v1/upload`, { method: "POST", body: batch([]), headers: { "X-Setu-Origin": toHex(victim.publicKey) } });
    const r = new CborReader(new Uint8Array(await back.arrayBuffer()));
    expect(r.array()).toBe(1);
    const a = await decode(r.bytes(), now);
    expect(a.body.type).toBe(PacketType.ACK);
    expect(toHex(a.body.ref!)).toBe(toHex(sos.id));

    const code = display(await shortCodeOf(victim.publicKey));
    const family = (await (await fetch(`${URL_}/v1/lookup/${code}`)).json()) as { matches: Array<{ acked: boolean; lat: number }> };
    expect(family.matches[0]).toMatchObject({ acked: true, lat: 12.97 });
  });
});
