# Security Policy

Setu is meant for emergencies. A bug that drops, alters or fakes an SOS can
cost lives, so security reports get priority over everything else.

## Supported versions

| Version | Supported |
|---------|-----------|
| 0.1.x   | Yes       |
| < 0.1   | No        |

## Reporting a vulnerability

**Please do not open a public issue.**

Use [GitHub private vulnerability reporting](https://github.com/guru-bharadwaj20/Setu/security/advisories/new)
(**Security > Report a vulnerability**). If that is not available to you,
contact the maintainer through their [GitHub profile](https://github.com/guru-bharadwaj20)
and ask for a private channel.

Please include:

- the affected part (app, relay, map, protocol) and version
- steps to reproduce, or a proof of concept
- the impact as you see it, for example "a courier can move another person's pin"

## What to expect

| Step | Target |
|------|--------|
| First reply | Within 3 days |
| Assessment and severity | Within 7 days |
| Fix released | Within 30 days for high severity, sooner if it is being exploited |

You will be credited in the advisory and the changelog unless you ask not to be.
Please give us a reasonable window to ship a fix before you disclose publicly.

## Scope

In scope:

- Forging, altering or suppressing packets (see `docs/THREAT_MODEL.md`)
- Crashing or draining phones with crafted packets
- Relay authentication, rate-limit bypass, injection, data exposure beyond what the threat model accepts
- XSS or injection in the rescuer map
- Weaknesses in the release signing or CI pipeline

Known and documented, and not a new finding:

- v0.1.x accepts ACKs from any signer (threat model T5)
- `GET /v1/sos` is public by design (threat model T10)

Out of scope: radio jamming, attacks that need a rooted phone you already
control, and denial of service against Cloudflare itself.

## Safe harbour

We will not take action against good-faith research that respects this
policy, avoids privacy violations, and does not touch relays you do not own.
Never send test SOS packets through a relay used in a real emergency.
