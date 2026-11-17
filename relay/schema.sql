-- Setu relay schema for Cloudflare D1 (SQLite).
-- Idempotent: safe to run again.

CREATE TABLE IF NOT EXISTS packets (
  id          TEXT PRIMARY KEY,          -- 16-byte packet id, hex
  type        INTEGER NOT NULL,          -- 0 SOS, 1 ACK, 2 SAFE
  origin      TEXT NOT NULL,             -- sender public key, hex
  code        TEXT NOT NULL,             -- 6-char short code of origin
  ts          INTEGER NOT NULL,          -- unix seconds when created
  expires_at  INTEGER NOT NULL,          -- ts + ttl
  lat_e5      INTEGER NOT NULL,
  lon_e5      INTEGER NOT NULL,
  acc_m       INTEGER NOT NULL,
  headcount   INTEGER NOT NULL,
  needs       INTEGER NOT NULL,
  battery     INTEGER NOT NULL,
  hops        INTEGER NOT NULL,
  ref         TEXT,                      -- ACK only: id of the SOS acknowledged
  note        TEXT,
  wire        BLOB NOT NULL,             -- encoded packet, re-sent to phones verbatim
  received_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS packets_origin_ts ON packets(origin, ts DESC);
CREATE INDEX IF NOT EXISTS packets_type_received ON packets(type, received_at);
CREATE INDEX IF NOT EXISTS packets_ref ON packets(ref) WHERE ref IS NOT NULL;
CREATE INDEX IF NOT EXISTS packets_code ON packets(code);
CREATE INDEX IF NOT EXISTS packets_expires ON packets(expires_at);

-- Per-uploader upload counter, one row per minute.
CREATE TABLE IF NOT EXISTS rate (
  key    TEXT NOT NULL,
  minute INTEGER NOT NULL,
  count  INTEGER NOT NULL,
  PRIMARY KEY (key, minute)
);
