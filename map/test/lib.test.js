import { describe, expect, it } from "vitest";
import { ago, filterList, needsLabels, normaliseCode, relayBase, status, toCsv, toGeoJson, triage } from "../lib.js";

const base = { id: "a", code: "ABC-DEF", lat: 12.97, lon: 77.59, accuracyM: 10, headcount: 1, needs: 0, battery: 50, hops: 1, ts: 1000, note: null, acked: false, safe: false };
const mk = (over) => ({ ...base, ...over });

describe("labels and time", () => {
  it("decodes needs flags", () => {
    expect(needsLabels(0)).toEqual([]);
    expect(needsLabels(1 | 4 | 16)).toEqual(["water", "medical", "evacuate"]);
  });

  it("formats ages", () => {
    expect(ago(100, 130)).toBe("just now");
    expect(ago(0, 240)).toBe("4 min ago");
    expect(ago(0, 3 * 3600 + 5)).toBe("3 h ago");
    expect(ago(0, 2 * 86400)).toBe("2 d ago");
    expect(ago(500, 100)).toBe("just now");
  });
});

describe("triage", () => {
  it("puts waiting, urgent, larger and older groups first", () => {
    const list = [
      mk({ id: "safe", safe: true, headcount: 50 }),
      mk({ id: "acked", acked: true, needs: 8 }),
      mk({ id: "small-new", ts: 2000 }),
      mk({ id: "small-old", ts: 1000 }),
      mk({ id: "big", headcount: 6 }),
      mk({ id: "trapped", needs: 8 }),
    ];
    expect(triage(list).map((s) => s.id)).toEqual(["trapped", "big", "small-old", "small-new", "acked", "safe"]);
  });

  it("filters by status", () => {
    const list = [mk({ id: "w" }), mk({ id: "a", acked: true }), mk({ id: "s", safe: true, acked: true })];
    expect(status(list[2])).toBe("safe");
    expect(filterList(list).map((s) => s.id)).toEqual(["w", "a"]);
    expect(filterList(list, { showAcked: false, showSafe: true }).map((s) => s.id)).toEqual(["w", "s"]);
  });
});

describe("export", () => {
  it("builds GeoJSON in lon,lat order", () => {
    const g = toGeoJson([mk({})]);
    expect(g.features[0].geometry.coordinates).toEqual([77.59, 12.97]);
    expect(g.features[0].properties.status).toBe("waiting");
  });

  it("writes CSV that survives commas, quotes and formula injection", () => {
    const csv = toCsv([mk({ note: 'roof, "blue" door' }), mk({ note: "=HYPERLINK(1)" })]);
    const lines = csv.trim().split("\r\n");
    expect(lines[0]).toMatch(/^code,status,lat,lon/);
    expect(lines[1]).toContain('"roof, ""blue"" door"');
    expect(lines[1]).toContain("1970-01-01T00:16:40.000Z");
    expect(lines[2]).toContain("'=HYPERLINK(1)");
  });
});

describe("input", () => {
  it("normalises short codes like the app", () => {
    expect(normaliseCode("abc-dio")).toBe("ABCD10");
    expect(normaliseCode(" 47z 33q ")).toBe("47Z33Q");
    expect(normaliseCode("abcu12")).toBe("ABCV12");
    expect(normaliseCode("abc")).toBeNull();
  });

  it("picks the relay from the query string or the page origin", () => {
    expect(relayBase("?relay=https://relay.example/", "https://map.example")).toBe("https://relay.example");
    expect(relayBase("", "https://map.example")).toBe("https://map.example");
    expect(relayBase("?relay=javascript:alert(1)", "https://map.example")).toBe("https://map.example");
  });
});
