// Pure helpers for the rescuer map. No DOM, no network, so they can be unit
// tested under Node. The page itself lives in app.js.

/** Needs bit flags, docs/PROTOCOL.md "needs". */
export const NEEDS = [
  [1, "water"],
  [2, "food"],
  [4, "medical"],
  [8, "trapped"],
  [16, "evacuate"],
];

export function needsLabels(flags) {
  return NEEDS.filter(([bit]) => (flags & bit) !== 0).map(([, label]) => label);
}

/** "just now", "4 min ago", "3 h ago", "2 d ago". */
export function ago(ts, now) {
  const s = Math.max(0, now - ts);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  return `${Math.floor(s / 86400)} d ago`;
}

/** Map marker state. Safe beats acked beats waiting. */
export function status(s) {
  if (s.safe) return "safe";
  if (s.acked) return "acked";
  return "waiting";
}

/**
 * Triage order for the side list: people still waiting first, trapped or
 * medical before the rest, then more people, then oldest first so nobody
 * slides off the bottom.
 */
export function triage(list) {
  const rank = { waiting: 0, acked: 1, safe: 2 };
  const urgent = (s) => ((s.needs & (4 | 8)) !== 0 ? 0 : 1);
  return [...list].sort(
    (a, b) =>
      rank[status(a)] - rank[status(b)] ||
      urgent(a) - urgent(b) ||
      b.headcount - a.headcount ||
      a.ts - b.ts,
  );
}

export function filterList(list, { showAcked = true, showSafe = false } = {}) {
  return list.filter((s) => {
    const st = status(s);
    if (st === "safe") return showSafe;
    if (st === "acked") return showAcked;
    return true;
  });
}

export function toGeoJson(list) {
  return {
    type: "FeatureCollection",
    // A null position is the protocol's "no fix"; those are listed, not plotted.
    features: list.filter((s) => s.lat !== null && s.lon !== null).map((s) => ({
      type: "Feature",
      geometry: { type: "Point", coordinates: [s.lon, s.lat] },
      properties: { id: s.id, status: status(s), headcount: s.headcount },
    })),
  };
}

function csvCell(v) {
  const t = v === null || v === undefined ? "" : String(v);
  // Quote everything that could break a row, and defuse spreadsheet formulas.
  const safe = /^[=+\-@\t\r]/.test(t) ? `'${t}` : t;
  return /[",\n\r]/.test(safe) ? `"${safe.replace(/"/g, '""')}"` : safe;
}

export function toCsv(list) {
  const head = ["code", "status", "lat", "lon", "accuracy_m", "headcount", "needs", "battery", "hops", "sent_utc", "note", "id"];
  const rows = list.map((s) => [
    s.code, status(s), s.lat, s.lon, s.accuracyM, s.headcount, needsLabels(s.needs).join(" "),
    s.battery, s.hops, new Date(s.ts * 1000).toISOString(), s.note, s.id,
  ]);
  return [head, ...rows].map((r) => r.map(csvCell).join(",")).join("\r\n") + "\r\n";
}

/** Same rules as the app and relay: Crockford base32, O->0, I/L->1, U->V. */
export function normaliseCode(input) {
  const map = { O: "0", I: "1", L: "1", U: "V" };
  const c = input.toUpperCase().replace(/[\s-]/g, "").replace(/[OILU]/g, (ch) => map[ch]);
  return /^[0-9A-HJKMNP-TV-Z]{6}$/.test(c) ? c : null;
}

/** Relay base URL from ?relay=, else the page's own origin. Trailing slashes dropped. */
export function relayBase(search, origin) {
  const q = new URLSearchParams(search).get("relay");
  const base = q && /^https?:\/\//.test(q) ? q : origin;
  return base.replace(/\/+$/, "");
}
