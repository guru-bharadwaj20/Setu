# Setu Architecture

## System overview

```
Phone A (no signal)   Phone B (no signal)   Phone C (one bar)     Relay (Workers + D1)   Rescuer map
   SOS packet --Nearby--> stored  --Nearby--> stored  --HTTPS-->  sos table  ------------> MapLibre
   <------- ack packet <---------- ack <------------------------- rescuer marks "reached"
```

Every phone runs the same code. There is no coordinator, no leader election and
no identity server. Three things make it work:

1. **Tiny signed packets.** About 150 bytes of CBOR with an Ed25519 signature.
   Small packets cross flaky, short-lived links reliably.
2. **Epidemic routing.** Every phone keeps every live packet it has seen and
   gives any new neighbour the ones that neighbour lacks. A bloom filter of
   seen ids, a TTL and a hop limit stop loops and floods.
3. **Drain on connectivity.** The instant a phone sees internet it uploads its
   entire store to the relay and pulls back acknowledgements for the mesh.

## Modules

```
setu/
  protocol/   pure Kotlin JVM. Packet model, CBOR codec, Ed25519 identity, short codes.
  routing/    pure Kotlin JVM. Bloom filter, bounded store, epidemic router, duty cycle policy.
  sim/        pure Kotlin JVM. Discrete-event simulator running thousands of virtual phones.
  app/        Android (Kotlin, Compose). UI, Room, Nearby transport, foreground service, uploader.
  relay/      Cloudflare Worker (TypeScript) + D1 schema. Upload, dedupe, verify, ack, lookup.
  map/        Static MapLibre page for rescuers and families.
  docs/       Spec, threat model, field test protocol, drill kit.
```

`protocol` and `routing` have zero Android dependencies so they can be tested
on a laptop in milliseconds and reused by a LoRa bridge or a desktop gateway.

## Packet lifecycle

1. User presses SOS. `SosComposer` reads location, battery and toggles, builds
   a `Packet`, signs it with the device key, and hands it to the `PacketStore`.
2. `MeshService` (foreground) runs discovery and advertising via Nearby
   Connections under a `DutyCycle` policy.
3. On a connection, both sides run the exchange protocol:
   - send `HELLO { have: bloom filter bytes }`
   - receive the peer's HELLO, compute `store.missingFrom(peerBloom)`
   - send those packets, highest priority first
   - receive packets, validate signature and TTL, `router.accept(packet)`
4. `ConnectivityWatcher` observes `NET_CAPABILITY_VALIDATED`. When it flips to
   true, `RelayUploader` posts the whole store in batches, receives ACK packets,
   and injects them into the store.
5. A rescuer clicks "reached" on the map. The relay creates a signed ACK packet
   referencing the SOS. Any phone uploading later gets it, carries it into the
   mesh, and the originating phone shows "rescuers have your location".

## Routing policy

- Packet id = first 16 bytes of SHA-256 over the signed body. Collision-free
  in practice, cheap.
- Bloom filter: 4 KiB, 7 indices. At the 2000-packet store bound the false
  positive rate is below 0.1 %; at 4000 ids it is about 2 %. A false positive means a packet is not re-sent to one neighbour,
  which is harmless because the next neighbour will carry it.
- TTL: SOS 48 h, ACK 72 h, SAFE 24 h.
- Max hops: 32. A hop is a transfer between two phones.
- Priority for transmission and retention: SOS > ACK > SAFE.
- Store bound: 2000 packets. Eviction order: expired first, then lowest
  priority, then most hops, then oldest.
- Re-advertisement: a phone re-sends its own SOS every 10 minutes with a fresh
  timestamp. Old copies stay until TTL so stale copies still reach rescuers.

## Battery policy

| Battery | Discovery window | Sleep interval |
|---------|------------------|----------------|
| > 50 %  | 20 s             | 40 s           |
| 30-50 % | 15 s             | 90 s           |
| 15-30 % | 10 s             | 180 s          |
| < 15 %  | 10 s             | 420 s          |

An active SOS forces the top tier regardless of battery. Courier-only mode
uses the table as is.

## Relay

One Worker, one D1 database.

| Route                     | Purpose                                                          |
|---------------------------|------------------------------------------------------------------|
| `POST /v1/upload`         | Batch of packets. Verify, dedupe, store. Returns ACKs for uploaded origins. |
| `GET  /v1/sos?since=`     | Latest SOS per origin for the map.                               |
| `POST /v1/ack`            | Rescuer marks a packet reached. Creates an ACK signed by the relay. |
| `GET  /v1/lookup/:code`   | Family lookup by short code.                                     |
| `GET  /v1/health`         | Liveness.                                                        |

Rate limit: per uploader, per minute, enforced in the Worker with a D1
counter table. Rescuer actions require a shared token set as a Worker secret.

## Security summary

See `docs/THREAT_MODEL.md`. In one line: packets are authentic (Ed25519),
unique (id from hash), bounded (TTL, hops, store size, rate limit) and only
ever as far-reaching as physics allows, because they cannot leave the zone
without a connected phone.
