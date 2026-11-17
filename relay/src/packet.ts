import type { Bytes } from "./cbor";
// Packet encode, decode and validation. Mirrors protocol/PacketCodec.kt rule
// for rule; a change to either one must land in both.

import { CborError, CborReader, CborWriter } from "./cbor";

export const Protocol = {
  VERSION: 1,
  ORIGIN_BYTES: 32,
  SIG_BYTES: 64,
  ID_BYTES: 16,
  MAX_PACKET_BYTES: 256,
  MAX_HOPS: 32,
  MAX_TTL_SECONDS: 259_200,
  MAX_CLOCK_SKEW_SECONDS: 3_600,
  MAX_NOTE_BYTES: 40,
  MAX_HEADCOUNT: 99,
  TTL_ACK_SECONDS: 259_200,
} as const;

export const enum PacketType { SOS = 0, ACK = 1, SAFE = 2 }

const enum Key { V = 0, ORIGIN, TYPE, TS, LAT, LON, ACC, HEAD, NEEDS, BATT, TTL, REF, NOTE }

export class PacketError extends Error {}

export interface PacketBody {
  origin: Bytes;
  type: PacketType;
  ts: number;
  latE5: number;
  lonE5: number;
  accuracyM: number;
  headcount: number;
  needs: number;
  battery: number;
  ttl: number;
  ref?: Bytes;
  note?: string;
}

export interface Packet {
  body: PacketBody;
  bodyBytes: Bytes;
  signature: Bytes;
  hops: number;
  id: Bytes;
}

export function toHex(b: Bytes): string {
  let s = "";
  for (const x of b) s += x.toString(16).padStart(2, "0");
  return s;
}

export function fromHex(s: string): Bytes {
  if (s.length % 2 !== 0 || /[^0-9a-fA-F]/.test(s)) throw new PacketError("bad hex");
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(s.substring(2 * i, 2 * i + 2), 16);
  return out;
}

export function encodeBody(b: PacketBody): Bytes {
  let n = 11;
  if (b.ref) n++;
  if (b.note) n++;
  const w = new CborWriter().map(n);
  w.uint(Key.V).uint(Protocol.VERSION);
  w.uint(Key.ORIGIN).bytes(b.origin);
  w.uint(Key.TYPE).uint(b.type);
  w.uint(Key.TS).uint(b.ts);
  w.uint(Key.LAT).int(b.latE5);
  w.uint(Key.LON).int(b.lonE5);
  w.uint(Key.ACC).uint(b.accuracyM);
  w.uint(Key.HEAD).uint(b.headcount);
  w.uint(Key.NEEDS).uint(b.needs);
  w.uint(Key.BATT).uint(b.battery);
  w.uint(Key.TTL).uint(b.ttl);
  if (b.ref) w.uint(Key.REF).bytes(b.ref);
  if (b.note) w.uint(Key.NOTE).text(b.note);
  return w.finish();
}

export function decodeBody(bytes: Bytes): PacketBody {
  try {
    const r = new CborReader(bytes);
    const n = r.map();
    if (n < 11 || n > 13) throw new PacketError(`body has ${n} keys`);
    let v = -1, type = -1, ts = -1, acc = -1, head = -1, needs = -1, batt = -1, ttl = -1;
    let origin: Bytes | undefined, lat: number | undefined, lon: number | undefined;
    let ref: Bytes | undefined, note: string | undefined;
    let lastKey = -1;
    for (let i = 0; i < n; i++) {
      const k = r.uint();
      if (k <= lastKey) throw new PacketError("keys not strictly ascending");
      lastKey = k;
      switch (k) {
        case Key.V: v = r.uint(); break;
        case Key.ORIGIN: origin = r.bytes(); break;
        case Key.TYPE: type = r.uint(); break;
        case Key.TS: ts = r.uint(); break;
        case Key.LAT: lat = r.int(); break;
        case Key.LON: lon = r.int(); break;
        case Key.ACC: acc = r.uint(); break;
        case Key.HEAD: head = r.uint(); break;
        case Key.NEEDS: needs = r.uint(); break;
        case Key.BATT: batt = r.uint(); break;
        case Key.TTL: ttl = r.uint(); break;
        case Key.REF: ref = r.bytes(); break;
        case Key.NOTE: note = r.text(); break;
        default: throw new PacketError(`unknown key ${k}`);
      }
    }
    r.end();
    if (v !== Protocol.VERSION) throw new PacketError(`unsupported version ${v}`);
    if (!origin) throw new PacketError("missing origin");
    if (origin.length !== Protocol.ORIGIN_BYTES) throw new PacketError("origin must be 32 bytes");
    if (type !== PacketType.SOS && type !== PacketType.ACK && type !== PacketType.SAFE) throw new PacketError(`unknown type ${type}`);
    if (lat === undefined) throw new PacketError("missing lat");
    if (lon === undefined) throw new PacketError("missing lon");
    if (ts < 0 || acc < 0 || head < 0 || needs < 0 || batt < 0 || ttl < 0) throw new PacketError("missing required field");
    if (ref && ref.length !== Protocol.ID_BYTES) throw new PacketError("ref must be 16 bytes");
    if (lat < -90_00000 || lat > 90_00000 || lon < -180_00000 || lon > 180_00000) throw new PacketError("bad coordinates");
    if (acc > 0x7fffffff || head > 0x7fffffff || needs > 0x7fffffff || batt > 0x7fffffff) throw new PacketError("field too large");
    const body: PacketBody = { origin, type, ts, latE5: lat, lonE5: lon, accuracyM: acc, headcount: head, needs, battery: batt, ttl };
    if (ref) body.ref = ref;
    if (note !== undefined) body.note = note;
    return body;
  } catch (e) {
    if (e instanceof CborError) throw new PacketError(`cbor: ${e.message}`);
    throw e;
  }
}

export function encode(p: Pick<Packet, "bodyBytes" | "signature" | "hops">): Bytes {
  return new CborWriter().array(3).bytes(p.bodyBytes).bytes(p.signature).uint(p.hops).finish();
}

export async function packetId(bodyBytes: Bytes): Promise<Bytes> {
  const digest = await crypto.subtle.digest("SHA-256", bodyBytes);
  return new Uint8Array(digest).slice(0, Protocol.ID_BYTES);
}

// Ed25519 via WebCrypto. Workers and Node 20+ both support it.
const PKCS8_PREFIX = fromHex("302e020100300506032b657004220420");

export async function verify(origin: Bytes, message: Bytes, sig: Bytes): Promise<boolean> {
  try {
    const key = await crypto.subtle.importKey("raw", origin, { name: "Ed25519" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "Ed25519" }, key, sig, message);
  } catch {
    return false;
  }
}

export interface Signer {
  publicKey: Bytes;
  sign(message: Bytes): Promise<Bytes>;
}

export async function signerFromSeed(seed: Bytes): Promise<Signer> {
  if (seed.length !== 32) throw new PacketError("seed must be 32 bytes");
  const pkcs8 = new Uint8Array(PKCS8_PREFIX.length + 32);
  pkcs8.set(PKCS8_PREFIX);
  pkcs8.set(seed, PKCS8_PREFIX.length);
  const priv = await crypto.subtle.importKey("pkcs8", pkcs8, { name: "Ed25519" }, true, ["sign"]);
  // Derive the public key by exporting the JWK, which carries both halves.
  const jwk = await crypto.subtle.exportKey("jwk", priv);
  const publicKey = base64UrlDecode(jwk.x ?? "");
  return {
    publicKey,
    async sign(message) {
      return new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, priv, message));
    },
  };
}

function base64UrlDecode(s: string): Bytes {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(s.length / 4) * 4, "=");
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** Builds and signs a fresh packet with zero hops. */
export async function create(body: PacketBody, signer: Signer): Promise<Packet> {
  if (toHex(body.origin) !== toHex(signer.publicKey)) throw new PacketError("origin must be the signer's public key");
  const bodyBytes = encodeBody(body);
  const signature = await signer.sign(bodyBytes);
  const size = encode({ bodyBytes, signature, hops: 0 }).length;
  if (size > Protocol.MAX_PACKET_BYTES) throw new PacketError(`packet is ${size} bytes, limit ${Protocol.MAX_PACKET_BYTES}`);
  return { body, bodyBytes, signature, hops: 0, id: await packetId(bodyBytes) };
}

/** Parses and fully validates a packet from an untrusted source. Throws PacketError. */
export async function decode(bytes: Bytes, now: number, verifySignature = true): Promise<Packet> {
  if (bytes.length > Protocol.MAX_PACKET_BYTES) throw new PacketError(`packet too large: ${bytes.length}`);
  let bodyBytes: Bytes, signature: Bytes, hops: number;
  try {
    const r = new CborReader(bytes);
    if (r.array() !== 3) throw new PacketError("outer array must have 3 items");
    bodyBytes = r.bytes();
    signature = r.bytes();
    hops = r.uint();
    r.end();
  } catch (e) {
    if (e instanceof CborError) throw new PacketError(`cbor: ${e.message}`);
    throw e;
  }
  if (signature.length !== Protocol.SIG_BYTES) throw new PacketError("sig must be 64 bytes");
  if (hops >= Protocol.MAX_HOPS) throw new PacketError(`too many hops: ${hops}`);
  const body = decodeBody(bodyBytes);
  if (body.ttl > Protocol.MAX_TTL_SECONDS) throw new PacketError("ttl too long");
  if (body.ts > now + Protocol.MAX_CLOCK_SKEW_SECONDS) throw new PacketError("ts in the future");
  if (body.ts + body.ttl <= now) throw new PacketError("expired");
  if (verifySignature && !(await verify(body.origin, bodyBytes, signature))) throw new PacketError("bad signature");
  return { body, bodyBytes, signature, hops, id: await packetId(bodyBytes) };
}

export async function tryDecode(bytes: Bytes, now: number): Promise<Packet | null> {
  try {
    return await decode(bytes, now);
  } catch (e) {
    if (e instanceof PacketError) return null;
    throw e;
  }
}
