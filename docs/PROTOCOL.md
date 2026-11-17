# Setu Protocol v1

One page. Anything that can speak this can join the mesh or bridge it.

## Packet

A packet is a CBOR array:

```
[ body: bytes, sig: bytes(64), hops: uint ]
```

`body` is the CBOR encoding of a map with integer keys. `sig` is the Ed25519
signature over `body` by the key in field 1. `hops` is outside the signed body
because every relaying phone increments it.

| Key | Name   | Type       | Notes                                                    |
|-----|--------|------------|----------------------------------------------------------|
| 0   | v      | uint       | Protocol version. Always `1`.                            |
| 1   | origin | bytes(32)  | Ed25519 public key of the sender.                        |
| 2   | type   | uint       | 0 = SOS, 1 = ACK, 2 = SAFE (sender reports they are safe)|
| 3   | ts     | uint       | Unix seconds when created.                               |
| 4   | lat    | int        | Latitude * 1e5 (about 1 m precision). See "No fix".      |
| 5   | lon    | int        | Longitude * 1e5.                                         |
| 6   | acc    | uint       | Accuracy in metres, 0 = unknown.                         |
| 7   | head   | uint       | Headcount, 0..99.                                        |
| 8   | needs  | uint       | Bit flags: 1 water, 2 food, 4 medical, 8 trapped, 16 evacuate |
| 9   | batt   | uint       | Battery percent 0..100.                                  |
| 10  | ttl    | uint       | Seconds to live from `ts`.                               |
| 11  | ref    | bytes(16)  | ACK only. Id of the SOS being acknowledged.              |
| 12  | note   | tstr       | Optional, at most 40 bytes of UTF-8.                     |

Keys must be encoded in ascending order with no duplicates (CBOR canonical
form) so that the id is stable across implementations.

**No fix.** A phone that has no location when the user presses SOS still
sends at once, with `lat = lon = 0` and `acc = 0`. Receivers treat exactly
(0, 0) as "position unknown": the relay reports `lat`/`lon` as null and the map
lists the request without plotting it. Nobody needs rescuing at Null Island,
which is open sea. Resends pick up a fix as soon as one exists.

## Id

`id = SHA-256(body)[0..16)`. The relay and every phone dedupe on `id`.

## Validity

A packet is valid when:

1. CBOR parses, `v == 1`, `origin` is 32 bytes, `sig` is 64 bytes.
2. `sig` verifies over `body` with `origin`.
3. `ts <= now + 3600` and `ts + ttl > now` (one hour of clock skew allowed).
4. `hops < 32`.
5. The encoded packet is at most 256 bytes.
6. `ttl <= 259200` (72 h).

Invalid packets are dropped silently and never relayed.

## Exchange (over any reliable byte stream)

Each side sends one `HELLO`, then any number of `PACKET` frames, then `BYE`.

```
frame = CBOR [ kind: uint, payload: bytes ]
kind 0 HELLO   payload = bloom filter bytes (4096 bytes, 7 hashes)
kind 1 PACKET  payload = encoded packet
kind 2 BYE     payload = empty
```

Bloom filter: bit array of 32768 bits, byte `k` bit `b` set means bit index
`8k + b`. Because an id is already a SHA-256 prefix, no extra hash function is
needed. For index `i` in `0..6`:

```
h_i = ((id[2i] << 8) | id[2i+1]) & 0x7fff
```

That is, the seven indices are the low 15 bits of the first seven big-endian
16-bit words of the id.

On receiving HELLO, a side sends every stored packet whose id is not in the
peer's filter, highest priority first, then BYE. Priority: SOS > ACK > SAFE,
then fewer hops, then newer `ts`.

## Short code

For family lookup, a sender's short code is the first 6 characters of
Crockford base32 of `SHA-256(origin)`, shown as `ABC-DEF`. Collisions are
tolerated. A lookup shows every match.

## Relay HTTP

`POST /v1/upload` with body `application/cbor`: a CBOR array of byte strings,
each one encoded packet (the outer `[body, sig, hops]` array as bytes). The
optional `X-Setu-Origin` header carries the uploader's public key in hex.
Response: `application/cbor`, a CBOR array of byte strings, each an encoded ACK
packet whose `ref` matches any uploaded SOS id, plus any ACK for the uploading
phone's own origin. Batches are at most 200 packets; the phone loops until the
store is drained.

Everything beyond the packet and the exchange is optional for a bridge. A LoRa
node that only carries SOS packets and never ACKs is still a conforming node.
