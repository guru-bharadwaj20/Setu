# Setu: Problem Statement and Requirements

Setu (Sanskrit: "bridge") is an Android app that turns every phone in a disaster
zone into a relay, so an SOS carrying a person's location reaches rescuers even
when every cell tower is down.

## 1. Problem statement

During floods, cyclones, earthquakes, landslides and deliberate network
shutdowns, the cellular and fibre backbone in the worst-hit zone goes down for
hours to days. People in that zone still hold charged phones but have no way to
tell anyone "we are six people on a roof at this point, one is diabetic".

Observed in: Kerala floods 2018, Cyclone Fani 2019, Cyclone Biparjoy 2023,
Himachal floods 2023, Wayanad landslides 2024, and numerous government-imposed
internet shutdowns.

Consequences:

- Rescue agencies (NDRF, SDRF, Navy, volunteers) lose the first hours guessing
  where people are.
- Families outside the zone cannot confirm anyone is alive.
- People move or stay based on no information.

Existing tools do not fit:

| Tool       | Why it does not solve this                                     |
|------------|----------------------------------------------------------------|
| Bridgefy   | Closed source, documented security flaws, chat-first           |
| Briar      | Messaging for activists, not SOS-first, not location-first     |
| Meshtastic | Needs LoRa hardware nobody owns during a flood                 |
| bitchat    | Bluetooth chat, no location, no rescuer side                   |

Gap: nothing is India-first, SOS-first, free, open source, and paired with a
rescuer-facing map.

## 2. Goals

1. A person with no connectivity can send an SOS (location, headcount, needs,
   battery) with one button press.
2. The SOS propagates phone to phone over Bluetooth / Wi-Fi Direct without any
   coordinator.
3. Any phone that regains connectivity uploads every packet it carries to a relay.
4. Rescuers see SOS points on a map, cluster them, and mark them reached.
5. The acknowledgement travels back into the mesh so the sender knows rescuers
   have their location.
6. Zero cost to build, run and distribute.
7. Open protocol so others can build LoRa bridges, iOS ports or ham gateways.

## 3. Non-goals (v1)

- General purpose chat.
- iOS background relaying (platform does not allow it).
- End-to-end encryption of SOS content. SOS content is meant to be read by any
  rescuer; integrity and authenticity matter more than secrecy.
- Long-range (more than ~100 m per hop) radio. A mesh needs density.

## 4. Stakeholders

| Stakeholder          | Need                                                   |
|----------------------|--------------------------------------------------------|
| Person in the zone   | One button, works with no network, drains little battery|
| Courier (safe person)| Help carry packets without being asked anything        |
| Rescuer              | Map, clustering, triage, mark reached, no Google bill  |
| Family outside       | Look up a short code and see "alive at time T, place P"|
| SDMA / NDRF          | Auditable, self-hostable, free, India-first            |
| Contributor          | Small protocol, tests, simulator, clear docs           |

## 5. Functional requirements

### FR-1 SOS creation
- FR-1.1 One screen, one large SOS button, five need toggles: water, food,
  medical, trapped, evacuate.
- FR-1.2 Headcount selector (1 to 99).
- FR-1.3 Location from GPS with accuracy. If no fix, send last known fix and
  flag it.
- FR-1.4 Packet includes battery percentage so rescuers can prioritise.
- FR-1.5 Packet is signed with a device key generated on first run.
- FR-1.6 Packet is at most ~150 bytes encoded.
- FR-1.7 The app re-sends an updated SOS periodically (default every 10 min)
  while active, with the same origin and a new timestamp.

### FR-2 Mesh transport
- FR-2.1 Discover nearby Setu phones using Google Nearby Connections
  (P2P_CLUSTER strategy).
- FR-2.2 On connection, exchange packet summaries and transfer packets the peer
  has not seen.
- FR-2.3 Routing is epidemic store-and-forward: bloom filter of seen ids, TTL,
  hop count, priority SOS > ACK > other.
- FR-2.4 A foreground service keeps relaying while the app is backgrounded.
- FR-2.5 Relaying survives reboot (boot receiver).

### FR-3 Storage
- FR-3.1 Packets persist in Room (SQLite).
- FR-3.2 The queue is bounded; when full, oldest non-SOS packets drop first,
  then oldest SOS with the most hops.
- FR-3.3 Expired packets (TTL) are purged.

### FR-4 Relay upload
- FR-4.1 When connectivity is detected, upload every stored packet to the relay.
- FR-4.2 Relay deduplicates by packet id and verifies signature.
- FR-4.3 Relay returns pending ACKs for the uploaded origins; the phone injects
  them into the mesh.

### FR-5 Rescuer map
- FR-5.1 Static page with MapLibre GL and OpenStreetMap tiles.
- FR-5.2 Cluster points, colour by age and need.
- FR-5.3 "Mark reached" button per SOS creates an ACK.
- FR-5.4 Family lookup by short code derived from the sender's public key.

### FR-6 Battery
- FR-6.1 Duty-cycled discovery: scan window and sleep interval configurable.
- FR-6.2 Interval grows as battery drops (tiers at 50 %, 30 %, 15 %).
- FR-6.3 "Courier only" mode for safe people who want to help carry.
- FR-6.4 Prompt for battery optimisation exemption and show per-brand
  keep-alive guide.

## 6. Non-functional requirements

| ID    | Requirement                                                            |
|-------|------------------------------------------------------------------------|
| NFR-1 | minSdk 24 (Android 7), covers > 95 % of Android phones in India        |
| NFR-2 | Release APK under 5 MB so it can spread via Nearby Share                |
| NFR-3 | No paid service anywhere in the stack                                   |
| NFR-4 | Relay self-hostable from one TypeScript file on a free tier             |
| NFR-5 | Packets tamper-evident (Ed25519), replay-resistant (id + timestamp)     |
| NFR-6 | Per-device rate limit on relay                                          |
| NFR-7 | Routing core is pure Kotlin, unit tested, runnable in a simulator       |
| NFR-8 | All code Apache-2.0, all docs CC-BY-4.0                                 |

## 7. Constraints and assumptions

- Bluetooth range 10 to 50 m, Wi-Fi Direct ~100 m. Mesh needs density; Setu
  helps in camps, villages, apartment blocks and queues, not one person alone.
- Some OEMs (Xiaomi, Oppo, Vivo) kill background services aggressively.
- Relay free tier: 100k requests/day is well above a single disaster's volume.
- Adoption, not technology, is the dominant risk. The drill kit and SDMA pitch
  are first-class deliverables.

## 8. Success metrics (field test)

- Median delivery time for an SOS to cross 5 hops in a campus test.
- Delivery ratio at 10 and 15 phones.
- mAh per hour in courier mode at each battery tier.
- APK size.
