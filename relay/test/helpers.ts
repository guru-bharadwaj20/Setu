import type { Bytes } from "../src/cbor";
import { PacketType, type PacketBody, type Signer, create, encode, fromHex, signerFromSeed, type Packet } from "../src/packet";
import { CborWriter } from "../src/cbor";
import { MemoryStore } from "../src/store";
import { type Deps, handle } from "../src/app";

export const NOW = 1_800_000_000;

/** Deterministic test keys: RFC 8032 vector 1 seed and a few derivations of it. */
export async function signer(n = 1): Promise<Signer> {
  const seed = fromHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
  seed[31] = (seed[31]! + n) & 0xff;
  return signerFromSeed(seed);
}

export async function sos(s: Signer, over: Partial<PacketBody> = {}): Promise<Packet> {
  return create({
    origin: s.publicKey, type: PacketType.SOS, ts: NOW - 60, latE5: 1297000, lonE5: 7759000,
    accuracyM: 12, headcount: 3, needs: 5, battery: 40, ttl: 172_800, ...over,
  }, s);
}

export async function safe(s: Signer, over: Partial<PacketBody> = {}): Promise<Packet> {
  return create({
    origin: s.publicKey, type: PacketType.SAFE, ts: NOW - 10, latE5: 1297000, lonE5: 7759000,
    accuracyM: 0, headcount: 0, needs: 0, battery: 40, ttl: 86_400, ...over,
  }, s);
}

export function batch(packets: Bytes[]): Bytes {
  const w = new CborWriter().array(packets.length);
  for (const p of packets) w.bytes(p);
  return w.finish();
}

export interface Harness extends Deps {
  store: MemoryStore;
  clock: { now: number };
  call(method: string, path: string, init?: { body?: BodyInit; headers?: Record<string, string> }): Promise<Response>;
}

export async function harness(over: Partial<Deps> = {}): Promise<Harness> {
  const clock = { now: NOW };
  const deps: Deps = {
    store: new MemoryStore(),
    now: () => clock.now,
    rateLimit: 60,
    signer: await signer(99),
    rescuerToken: "secret-token",
    ...over,
  };
  return {
    ...deps,
    store: deps.store as MemoryStore,
    clock,
    call: (method, path, init = {}) =>
      handle(new Request(`https://relay.test${path}`, { method, body: init.body, headers: init.headers }), deps),
  };
}

export { encode };
