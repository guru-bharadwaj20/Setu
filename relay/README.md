# Setu relay

A Cloudflare Worker with a D1 database. Phones that regain internet upload the
packets they carry. The relay sends back acknowledgements, and the rescuer map
reads open SOS packets from it.

- No runtime dependencies, about 900 lines of TypeScript.
- Runs on the Cloudflare free tier.
- Verifies every signature and drops duplicates, expired packets and packets dated in the future.

## API

| Route | Auth | Purpose |
|-------|------|---------|
| `POST /v1/upload` | none, rate-limited | Body: `application/cbor`, an array of encoded packets (200 at most). Response: the ACKs for the uploader's origins. |
| `GET /v1/sos?since=<unix>` | none | The latest SOS from each origin that is still inside its TTL, with ACK and safe state. |
| `POST /v1/ack` | `Bearer RESCUER_TOKEN` | `{"id": "<32 hex>"}`. Creates one ACK, signed by the relay, per SOS. |
| `GET /v1/lookup/<code>` | none | Family lookup. Positions are rounded to about 1 km. |
| `GET /v1/health` | none | Liveness check and packet counts. |

A position of `null` means the phone had no location fix (see "No fix" in `docs/PROTOCOL.md`).

## Deploy

You need Node 22.12 or newer and a Cloudflare account.

```sh
cd relay
npm ci
npx wrangler login

# 1. Database
npx wrangler d1 create setu          # paste the database_id into wrangler.toml
npm run db:migrate                   # applies schema.sql, safe to re-run

# 2. Secrets
openssl rand -hex 32 | npx wrangler secret put RELAY_SEED       # signs ACK packets
openssl rand -base64 24 | npx wrangler secret put RESCUER_TOKEN # give this to rescuers

# 3. Ship
npm run deploy
curl https://setu-relay.<your-subdomain>.workers.dev/v1/health
```

Keep `RELAY_SEED` the same from one deploy to the next. Phones already carry
ACKs signed with it.

Then point the phones at your relay in **Settings > Relay URL**, and open the map with
`?relay=https://setu-relay.<your-subdomain>.workers.dev`.

The cron trigger in `wrangler.toml` runs hourly. It deletes expired packets and
old rate-limit rows.

## Local development

```sh
printf 'RELAY_SEED=%s\nRESCUER_TOKEN=local-token\n' "$(openssl rand -hex 32)" > .dev.vars
npm run db:migrate:local
npm run dev                          # http://127.0.0.1:8787
```

`.dev.vars` is git-ignored. Never commit real secrets.

## Tests

```sh
npm run typecheck
npm test                             # unit, route and shared-vector tests
SETU_WRITE_VECTORS=1 npm test        # regenerate docs/vectors after a deliberate format change
```

Two opt-in suites run against a live relay:

```sh
SETU_E2E_URL=http://127.0.0.1:8787 SETU_E2E_TOKEN=local-token npm test   # upload, list, ack, lookup
SETU_SEED_URL=http://127.0.0.1:8787 SETU_SEED_COUNT=40 npm test               # demo SOS for the map
```

`SETU_SEED_LAT` and `SETU_SEED_LON` move the seeded SOS packets. Never point
either suite at a production relay.

## Configuration

| Name | Kind | Default | Meaning |
|------|------|---------|---------|
| `DB` | D1 binding | | The `setu` database |
| `RATE_LIMIT_PER_MINUTE` | var | `60` | Uploads per minute per uploader key or IP |
| `RELAY_SEED` | secret | | 32-byte hex Ed25519 seed. Without it, the relay cannot create ACKs |
| `RESCUER_TOKEN` | secret | | Bearer token for `POST /v1/ack`. Without it, the route returns 503 |
