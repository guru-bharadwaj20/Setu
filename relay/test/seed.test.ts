// Opt-in demo data for the rescuer map. Uploads a few dozen signed SOS and
// SAFE packets scattered around a point so the clusters, colours and lists
// have something to show:
//
//   SETU_SEED_URL=http://127.0.0.1:8787 npx vitest run test/seed.test.ts
//
// Optional: SETU_SEED_LAT / SETU_SEED_LON (degrees, default Bengaluru), SETU_SEED_COUNT.

import { expect, it } from "vitest";
import { PacketType, create, encode, signerFromSeed } from "../src/packet";
import { batch } from "./helpers";
import type { Bytes } from "../src/cbor";

const URL_ = process.env.SETU_SEED_URL;
const NOTES = ["roof", "2 kids, 1 elderly", "water rising", "need insulin", "on terrace", "boat needed", null, null, null];

it.skipIf(!URL_)("seeds demo SOS packets", async () => {
  const lat = Number(process.env.SETU_SEED_LAT ?? "12.97");
  const lon = Number(process.env.SETU_SEED_LON ?? "77.59");
  const count = Number(process.env.SETU_SEED_COUNT ?? "40");
  const now = Math.floor(Date.now() / 1000);
  const wire: Bytes[] = [];
  for (let i = 0; i < count; i++) {
    const s = await signerFromSeed(crypto.getRandomValues(new Uint8Array(32)));
    const ts = now - Math.floor(Math.random() * 6 * 3600);
    const latE5 = Math.round((lat + (Math.random() - 0.5) * 0.15) * 1e5);
    const lonE5 = Math.round((lon + (Math.random() - 0.5) * 0.15) * 1e5);
    const note = NOTES[i % NOTES.length] ?? undefined;
    wire.push(encode(await create({
      origin: s.publicKey, type: PacketType.SOS, ts, latE5, lonE5, accuracyM: 5 + Math.floor(Math.random() * 60),
      headcount: 1 + Math.floor(Math.random() * 8), needs: Math.floor(Math.random() * 32), battery: Math.floor(Math.random() * 100),
      ttl: 172_800, ...(note ? { note } : {}),
    }, s)));
    if (i % 7 === 6) {
      wire.push(encode(await create({
        origin: s.publicKey, type: PacketType.SAFE, ts: ts + 600, latE5, lonE5, accuracyM: 0,
        headcount: 0, needs: 0, battery: 50, ttl: 86_400,
      }, s)));
    }
  }
  const res = await fetch(`${URL_}/v1/upload`, { method: "POST", body: batch(wire), headers: { "Content-Type": "application/cbor" } });
  expect(res.status).toBe(200);
  console.log(`seeded ${res.headers.get("X-Setu-Stored")} packets`);
});
