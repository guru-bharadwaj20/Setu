# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/). Wire format changes are always called out.

## [Unreleased]

## [0.1.0] - 2026-11-17

First public release. Not yet field-tested on more than a handful of phones.

### Added

- **Protocol v1**: CBOR packets with integer keys, signed with Ed25519, about 150 bytes each. There are SOS, ACK and SAFE packets, plus six-character Crockford short codes for families. The spec is in `docs/PROTOCOL.md`, with shared test vectors in `docs/vectors/`.
- **Routing**: epidemic store-and-forward with a 4 KiB bloom filter, per-type TTLs, a 32-hop limit, a store bound of 2000 packets with priority eviction, and a radio duty cycle that follows battery level.
- **Simulator**: `./gradlew :sim:run`. It runs hundreds of virtual phones and supports parameter sweeps and CSV output.
- **Android app**: a one-tap SOS with headcount, needs and a note, plus a courier mode and a status screen. Includes a Room-backed queue, a Nearby Connections transport, a foreground mesh service, restart on boot, and a relay upload as soon as the phone has internet. The phone shows when rescuers have acknowledged an SOS.
- **Relay**: Cloudflare Worker and D1. Upload, verify, dedupe, rate limit, ACK signing, family lookup and an hourly purge. No runtime dependencies.
- **Rescuer map**: static MapLibre page with clustering, triage order, "Mark reached", family lookup and CSV export.
- An SOS sent before the phone has a location fix uses position (0, 0), meaning "no fix". The app warns the user, and the map lists that SOS without plotting it.
- CI for all modules, a signed release APK built from a version tag, CodeQL, Dependabot, and issue and PR templates.
- Docs: requirements, architecture, protocol, threat model, field test protocol, relay deploy guide.

### Known limitations

- Phones accept an ACK from any signer. Pinning the relay key is planned for 0.2 (threat model T5).
- Some phone brands stop background services aggressively. A per-brand guide is still to come.
- iOS is not supported.

[Unreleased]: https://github.com/guru-bharadwaj20/Setu/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/guru-bharadwaj20/Setu/releases/tag/v0.1.0
