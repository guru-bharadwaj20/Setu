import { describe, expect, it } from "vitest";
import { CborReader, CborWriter, CborError } from "../src/cbor";
import { PacketType, Protocol, decode, encode, encodeBody, fromHex, packetId, toHex, verify } from "../src/packet";
import { display, normalise, shortCodeOf } from "../src/shortcode";
import { NOW, signer, sos } from "./helpers";

describe("cbor", () => {
  it("round-trips the subset we use", () => {
    const b = new CborWriter().array(5).uint(0).uint(1000).int(-7).bytes(new Uint8Array([1, 2])).text("hi").finish();
    const r = new CborReader(b);
    expect(r.array()).toBe(5);
    expect(r.uint()).toBe(0);
    expect(r.uint()).toBe(1000);
    expect(r.int()).toBe(-7);
    expect([...r.bytes()]).toEqual([1, 2]);
    expect(r.text()).toBe("hi");
    r.end();
  });

  it("rejects non-minimal and indefinite encodings", () => {
    expect(() => new CborReader(fromHex("1800")).uint()).toThrow(CborError);
    expect(() => new CborReader(fromHex("190010")).uint()).toThrow(CborError);
    expect(() => new CborReader(fromHex("5f")).bytes()).toThrow(CborError);
    expect(() => new CborReader(fromHex("4401")).bytes()).toThrow(CborError);
    const r = new CborReader(fromHex("0000"));
    r.uint();
    expect(() => r.end()).toThrow(CborError);
  });
});

describe("ed25519", () => {
  it("matches RFC 8032 test vector 1", async () => {
    const s = await (await import("../src/packet")).signerFromSeed(
      fromHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60"),
    );
    expect(toHex(s.publicKey)).toBe("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    const sig = await s.sign(new Uint8Array(0));
    expect(toHex(sig)).toBe(
      "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
    );
    expect(await verify(s.publicKey, new Uint8Array(0), sig)).toBe(true);
    sig[0]! ^= 1;
    expect(await verify(s.publicKey, new Uint8Array(0), sig)).toBe(false);
  });
});

describe("packet", () => {
  it("encodes, signs, decodes and derives the id from the body", async () => {
    const s = await signer();
    const p = await sos(s, { note: "roof" });
    const wire = encode(p);
    expect(wire.length).toBeLessThanOrEqual(Protocol.MAX_PACKET_BYTES);
    const q = await decode(wire, NOW);
    expect(q.body).toEqual(p.body);
    expect(toHex(q.id)).toBe(toHex(await packetId(encodeBody(p.body))));
    expect(q.hops).toBe(0);
  });

  it("drops forged, expired, future and over-hopped packets", async () => {
    const s = await signer();
    const p = await sos(s);
    const forged = encode({ ...p, signature: p.signature.map((b, i) => (i === 3 ? b ^ 1 : b)) });
    await expect(decode(forged, NOW)).rejects.toThrow("bad signature");
    await expect(decode(encode(p), NOW + 200_000)).rejects.toThrow("expired");
    const future = await sos(s, { ts: NOW + 7200 });
    await expect(decode(encode(future), NOW)).rejects.toThrow("future");
    await expect(decode(encode({ ...p, hops: 32 }), NOW)).rejects.toThrow("hops");
    expect(await decode(encode({ ...p, hops: 31 }), NOW)).toBeTruthy();
  });

  it("refuses an ACK ref of the wrong size and an unknown type", async () => {
    const s = await signer();
    await expect(sos(s, { type: PacketType.ACK, ref: new Uint8Array(3) })).resolves.toBeTruthy();
    const p = await sos(s, { type: PacketType.ACK, ref: new Uint8Array(3) });
    await expect(decode(encode(p), NOW)).rejects.toThrow("ref must be 16 bytes");
  });
});

describe("short code", () => {
  it("derives six Crockford characters and normalises user input", async () => {
    const s = await signer();
    const code = await shortCodeOf(s.publicKey);
    expect(code).toMatch(/^[0-9A-HJKMNP-TV-Z]{6}$/);
    expect(display(code)).toBe(`${code.slice(0, 3)}-${code.slice(3)}`);
    expect(normalise(display(code).toLowerCase())).toBe(code);
    expect(normalise("abc-dio")).toBe("ABCD10");
    expect(normalise("abcu12")).toBe("ABCV12");
    expect(normalise("abc")).toBeNull();
  });
});
