# Setu Threat Model

Status: v0.1.0, reviewed against the code in this repository.

Setu carries calls for help through phones that belong to strangers. The design
goal is that every packet is **authentic, bounded and readable by any rescuer**.
Keeping SOS content secret is a non-goal (see `REQUIREMENTS.md`, non-goals).

## Assets

| Asset                         | Why it matters                                          |
|-------------------------------|---------------------------------------------------------|
| SOS content (position, needs) | Wrong data sends boats to the wrong roof                |
| Delivery                      | A dropped SOS is a person nobody looks for              |
| ACK state                     | A false "rescuers have your location" can stop people from signalling |
| Device signing key            | Anyone holding it can speak as you                      |
| Phone battery                 | A flat phone can no longer relay or call for help       |
| Relay availability            | The only bridge between the zone and rescuers           |
| Rescuer token                 | Lets its holder mark people as reached                  |

## Actors

- **Person in need.** Trusted to tell the truth about their own situation.
- **Courier phones.** Untrusted. Any phone in range, including malicious ones.
- **Relay operator.** Trusted with everything that reaches the relay.
- **Rescuers.** Trusted, and authenticated by a shared token.
- **Public and families.** Can read the SOS list and look up a code.
- **Attacker.** Either in radio range with one or more phones, or anywhere on the internet.

## Threats and mitigations

| # | Threat | Mitigation in v0.1.0 | Residual risk |
|---|--------|----------------------|---------------|
| T1 | A courier alters an SOS in transit, for example to move the pin | The origin key signs the body with Ed25519. Every hop and the relay verify it (`PacketCodec.decode` in the app, `decode` in `relay/src/packet.ts`). Only `hops` sits outside the signature. | A courier can raise `hops`. That only makes the packet hit the 32-hop limit sooner. |
| T2 | An attacker forges an SOS as someone else | Forging needs the origin's private key. The short code comes from the public key, so no other key can claim a family's code. | None, unless the key is stolen (T8). |
| T3 | One device floods the mesh with thousands of SOS packets | Each phone stores at most 2000 packets and evicts by priority. The relay rate-limits each uploader (60 per minute by default). Each batch holds at most 200 packets and has a fixed maximum size in bytes. | An attacker with many keys can still crowd out the stores of nearby phones. Rescuer triage and the short radio range limit the damage. |
| T4 | An attacker replays old packets | The packet id is the SHA-256 of the signed body, so a replayed copy is detected as a duplicate. TTL is checked on receipt, and timestamps more than one hour in the future are rejected. | A replayed packet that is still inside its TTL is just another copy of a real request. |
| T5 | **A forged ACK** tells someone that rescuers are coming | v0.1.0 accepts any well-formed ACK whose `ref` matches the user's SOS. To limit the harm, the SOS screen keeps the Resend button and the phone re-sends the SOS every 10 minutes until the user marks themselves safe. | **Known gap.** v0.2 will pin the relay's public keys in settings and ignore ACKs from any other signer. |
| T6 | A malformed packet crashes a phone or the relay | The CBOR decoder is strict: length limits, canonical key order and bounded strings. Both sides have malformed-input tests. Kotlin and TypeScript share the same test vectors (`docs/vectors`). | Low. |
| T7 | A chatty neighbour drains the battery | The radio duty cycle follows battery level. Each connection runs one exchange, and a bloom filter means only missing packets are sent. | A hostile phone can keep reconnecting. Nearby Connections slows this down somewhat. |
| T8 | The device key is stolen | The key is encrypted with an Android Keystore key and excluded from cloud backup and device transfer. | A rooted or seized phone can read it. Keys are cheap, and the user can reset their identity. |
| T9 | Someone marks people as reached without authority | `POST /v1/ack` needs `Authorization: Bearer <RESCUER_TOKEN>`, which is stored as a Worker secret. The map ties the saved token to the relay origin it was entered for, so a crafted `?relay=` link cannot collect it. | The token is shared. Rotate it with `wrangler secret put` if it leaks. Per-rescuer accounts are future work. |
| T10 | Location privacy | A position is sent only when the user presses SOS. Family lookup rounds positions to about 1 km. | `GET /v1/sos` gives exact positions to anyone who knows the relay URL, because volunteers need them. An operator who needs more protection can put the map and `/v1/sos` behind Cloudflare Access. |
| T11 | XSS or CSV injection on the rescuer map | The map uses a strict CSP and writes all user text with `textContent`. `?relay=` accepts only http(s) URLs. In CSV exports, cells that start with `=`, `+`, `-` or `@` get a leading apostrophe so spreadsheets do not run them as formulas. | Low. |
| T12 | The relay is down or censored | Phones keep carrying packets for 48 h and upload to whichever relay URL is configured. Anyone can deploy a relay in minutes. | Rescuers see nothing until some phone reaches a working relay. |
| T13 | Supply chain | The relay has no runtime npm dependencies. GitHub Actions are pinned to commit SHAs, and Dependabot keeps them current. CI signs the release APK with a keystore held as a secret and publishes its SHA-256. | Gradle and AndroidX dependencies are trusted. |

## Out of scope

- Jamming of the 2.4 GHz band. No phone software can overcome physics.
- A compromised relay operator, who can drop or read packets and forge ACKs. Run your own relay.
- Working out who is near whom by watching Bluetooth traffic. Bluetooth advertising is visible by nature.

## Reporting

See [`SECURITY.md`](../SECURITY.md).
