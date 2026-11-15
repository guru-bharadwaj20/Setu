# Setu Project Plan

## Tech stack decisions

| Layer        | Choice                                          | Why                                                                 |
|--------------|-------------------------------------------------|---------------------------------------------------------------------|
| App          | Kotlin, Jetpack Compose, minSdk 24, target 36   | Covers more than 95 % of Android phones in India, including budget devices |
| Radio        | Google Nearby Connections, `P2P_CLUSTER`        | Handles Bluetooth, BLE and Wi-Fi Direct without special hardware    |
| Routing      | Epidemic store-and-forward, bloom filter, TTL   | Proven in delay-tolerant networking, needs no coordinator           |
| Storage      | Room (SQLite), bounded queue                    | Survives reboots, tiny footprint                                    |
| Packets      | CBOR with integer keys, Ed25519 signatures      | Compact, tamper-evident, no identity server                         |
| Crypto       | `net.i2p.crypto:eddsa` (pure Java, ~100 KB)     | Works on Android 7, keeps the APK small                             |
| Relay        | Cloudflare Workers + D1                         | Free tier, one TypeScript file, anyone can self-host                |
| Map          | MapLibre GL JS + OpenStreetMap tiles            | No map bill, runs on a laptop in a tent                             |
| CI           | GitHub Actions                                  | Free for public repos, builds the APK for releases                  |
| Licence      | Apache-2.0 for code, CC-BY-4.0 for docs          | Governments and companies can adopt without legal review            |

## Milestones

| # | Milestone                                             | Deliverable                                                     |
|---|-------------------------------------------------------|-----------------------------------------------------------------|
| 1 | Protocol core                                         | `protocol` module: packet, CBOR, signatures, short code, tests  |
| 2 | Routing core                                          | `routing` module: bloom filter, store, router, duty cycle, tests |
| 3 | Simulator                                             | `sim` module: 1000 virtual phones on a laptop, metrics          |
| 4 | App skeleton                                          | Compose UI: SOS, courier, status; Room queue                    |
| 5 | Mesh transport                                        | Nearby Connections exchange, foreground service, boot receiver  |
| 6 | Drain and ack                                         | Connectivity watcher, relay uploader, ack injection             |
| 7 | Relay                                                 | Worker + D1, upload, verify, dedupe, ack, lookup, rate limit    |
| 8 | Rescuer map                                           | MapLibre page, clustering, reached button, family lookup        |
| 9 | Security and quality                                  | Threat model, lint, CI, bug fixes, optimisation                 |
| 10| Release                                               | README, licence, conduct, security policy, v0.1.0 on GitHub     |

## Field test protocol (summary)

See `docs/FIELD_TEST.md`. Three stages: corridor (3 phones), building
(5 phones), campus (10 to 15 phones). Published numbers: hops, delivery time,
delivery ratio, mAh per hour.

## Risks

| Risk                                       | Mitigation                                                        |
|--------------------------------------------|-------------------------------------------------------------------|
| Bluetooth range needs density              | Say so on the first screen. Target camps, blocks and queues       |
| OEMs kill background services              | Foreground notification, exemption prompt, per-brand guide        |
| iOS cannot relay in background             | Android first; open protocol so a foreground iOS port is possible |
| False SOS and spam                         | Signatures, per-device rate limits, rescuer triage, physical locality |
| Adoption                                   | Drill kit and SDMA pitch are first-class deliverables             |
