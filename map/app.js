// Rescuer map. Polls the relay, draws help requests, lets a rescuer with the
// token mark someone reached, and lets families look up a short code.
// Everything user supplied is written with textContent, never innerHTML.

import { ago, filterList, needsLabels, normaliseCode, relayBase, relayOrigin, status, toCsv, toGeoJson, tokenFor, triage } from "./lib.js";

const POLL_MS = 30_000;
const COLORS = { waiting: "#c62828", acked: "#e08a00", safe: "#2e7d32" };

const $ = (id) => document.getElementById(id);

function store(key, value) {
  try {
    if (value === undefined) return localStorage.getItem(key);
    if (value === "") localStorage.removeItem(key);
    else localStorage.setItem(key, value);
  } catch {
    return null;
  }
  return value;
}

const state = {
  relay: relayOrigin(store("setu.relay") ?? "") ?? relayBase(location.search, location.origin),
  token: store("setu.token") || "",
  // Origin the token was entered for. The token is never sent anywhere else.
  tokenRelay: store("setu.tokenRelay"),
  list: [],
  now: Math.floor(Date.now() / 1000),
  showAcked: true,
  showSafe: false,
  map: null,
  ml: null,
  popup: null,
};

function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === "class") e.className = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v);
  }
  for (const c of children) if (c !== null && c !== undefined) e.append(c);
  return e;
}

function banner(text) {
  const b = $("banner");
  b.hidden = !text;
  b.textContent = text || "";
}

function visible() {
  return triage(filterList(state.list, { showAcked: state.showAcked, showSafe: state.showSafe }));
}

// ----------------------------------------------------------------- render

function describe(s) {
  const needs = needsLabels(s.needs);
  const bits = [
    el("div", { class: "row1" }, el("span", {}, `${s.code} · ${s.headcount} ${s.headcount === 1 ? "person" : "people"}`), el("span", { class: `status-${status(s)}` }, status(s))),
    needs.length ? el("div", { class: "needs" }, needs.join(", ")) : null,
    s.note ? el("div", { class: "note" }, `“${s.note}”`) : null,
    el("div", { class: "muted" }, `${ago(s.ts, state.now)} · ${s.lat === null ? "no location yet" : `±${s.accuracyM} m`} · battery ${s.battery}% · ${s.hops} hops`),
  ];
  return bits;
}

function renderList() {
  const items = visible();
  const list = $("list");
  list.replaceChildren(
    ...items.map((s) =>
      el("li", { class: status(s), tabindex: "0", onclick: () => focus(s), onkeydown: (e) => e.key === "Enter" && focus(s) }, ...describe(s)),
    ),
  );
  $("empty").hidden = items.length > 0;

  const waiting = state.list.filter((s) => status(s) === "waiting");
  const people = waiting.reduce((n, s) => n + s.headcount, 0);
  $("counts").textContent = `${waiting.length} waiting (${people} people) · ${state.list.length} total`;
  $("updated").textContent = `updated ${new Date().toLocaleTimeString()}`;
}

function renderMap() {
  const src = state.map?.getSource("sos");
  if (src) src.setData(toGeoJson(visible()));
}

function render() {
  renderList();
  renderMap();
}

function popupFor(s) {
  const box = el("div", { class: "popup" }, ...describe(s));
  if (status(s) === "waiting") {
    const btn = el("button", { class: "primary" }, "Mark reached");
    btn.addEventListener("click", () => markReached(s, btn));
    box.append(btn);
  }
  return box;
}

function focus(s) {
  if (!state.map || s.lat === null) return;
  state.map.flyTo({ center: [s.lon, s.lat], zoom: Math.max(state.map.getZoom(), 15) });
  state.popup?.remove();
  state.popup = new state.ml.Popup({ maxWidth: "280px" }).setLngLat([s.lon, s.lat]).setDOMContent(popupFor(s)).addTo(state.map);
  $("panel").classList.remove("open");
}

// ---------------------------------------------------------------- network

async function refresh() {
  try {
    const res = await fetch(`${state.relay}/v1/sos`, { cache: "no-store" });
    if (!res.ok) throw new Error(`relay answered ${res.status}`);
    const j = await res.json();
    state.now = j.now;
    state.list = j.sos;
    banner("");
    render();
  } catch (e) {
    banner(`Cannot reach the relay at ${state.relay}: ${e.message}. Showing the last list.`);
  }
}

async function markReached(s, btn) {
  const token = tokenFor(state.relay, state.token, state.tokenRelay);
  if (!token) {
    showTab("settings");
    banner(`Enter the rescuer token for ${state.relay} in Settings first.`);
    return;
  }
  btn.disabled = true;
  btn.textContent = "Sending…";
  try {
    const res = await fetch(`${state.tokenRelay}/v1/ack`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ id: s.id }),
    });
    if (res.status === 401) throw new Error("token rejected");
    if (!res.ok) throw new Error(`relay answered ${res.status}`);
    s.acked = true;
    render();
    // Redraw the open popup so its status reads "acked" instead of "waiting".
    state.popup?.setDOMContent(popupFor(s));
  } catch (e) {
    btn.disabled = false;
    btn.textContent = "Mark reached";
    banner(`Could not mark reached: ${e.message}`);
  }
}

async function lookup(raw) {
  const out = $("lookup-result");
  const code = normaliseCode(raw);
  if (!code) {
    out.replaceChildren(el("p", { class: "status-waiting" }, "A code has six letters or digits, like 47Z-33Q."));
    return;
  }
  out.replaceChildren(el("p", { class: "muted" }, "Looking up…"));
  try {
    const res = await fetch(`${state.relay}/v1/lookup/${code}`, { cache: "no-store" });
    if (!res.ok) throw new Error(`relay answered ${res.status}`);
    const j = await res.json();
    if (j.matches.length === 0) {
      out.replaceChildren(el("p", {}, `Nothing from ${j.code} has reached the relay yet. Messages can take hours to hop out of an area with no network. Check again later.`));
      return;
    }
    const words = {
      waiting: "Asked for help. Rescuers have not marked them reached yet.",
      acked: "Rescuers have seen this request and marked it reached.",
      safe: "Marked themselves safe.",
    };
    out.replaceChildren(
      ...j.matches.map((m) =>
        el("div", { class: "match" },
          el("div", { class: `status-${status(m)}` }, words[status(m)]),
          el("div", {}, `${m.headcount} ${m.headcount === 1 ? "person" : "people"} · sent ${ago(m.ts, state.now)}`),
          el("div", { class: "muted" }, m.lat === null ? "Their phone had no location fix yet." : `Near ${m.lat.toFixed(2)}, ${m.lon.toFixed(2)}`),
        ),
      ),
    );
  } catch (e) {
    out.replaceChildren(el("p", { class: "status-waiting" }, `Lookup failed: ${e.message}`));
  }
}

// --------------------------------------------------------------------- ui

function showTab(name) {
  for (const b of document.querySelectorAll("nav button")) b.setAttribute("aria-selected", String(b.dataset.tab === name));
  for (const t of ["list", "family", "settings"]) $(`tab-${t}`).hidden = t !== name;
  $("panel").classList.add("open");
}

function download(name, text, type) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = el("a", { href: url, download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function wire() {
  for (const b of document.querySelectorAll("nav button")) b.addEventListener("click", () => showTab(b.dataset.tab));
  $("panel-toggle").addEventListener("click", () => {
    const open = $("panel").classList.toggle("open");
    $("panel-toggle").setAttribute("aria-expanded", String(open));
  });
  $("show-acked").addEventListener("change", (e) => { state.showAcked = e.target.checked; render(); });
  $("show-safe").addEventListener("change", (e) => { state.showSafe = e.target.checked; render(); });
  $("export").addEventListener("click", () =>
    download(`setu-${new Date().toISOString().slice(0, 16).replace(/[:T]/g, "")}.csv`, toCsv(visible()), "text/csv"),
  );
  $("lookup").addEventListener("submit", (e) => { e.preventDefault(); lookup($("code").value); });
  $("relay").value = state.relay;
  // Do not pre-fill the token for a relay it was not saved for, or one click on
  // Save after following a crafted ?relay= link would hand it over.
  $("token").value = tokenFor(state.relay, state.token, state.tokenRelay);
  $("save-settings").addEventListener("click", () => {
    const typed = $("relay").value.trim();
    const r = typed ? relayOrigin(typed) : "";
    if (r === null) { banner("The relay URL must start with https://"); return; }
    state.relay = r || relayBase(location.search, location.origin);
    state.token = $("token").value.trim();
    state.tokenRelay = state.token ? state.relay : "";
    store("setu.relay", r);
    store("setu.token", state.token);
    store("setu.tokenRelay", state.tokenRelay);
    banner("");
    refresh();
  });
}

function initMap() {
  // Loaded by a classic script tag in index.html. Its worker comes from a
  // blob: URL, which the page's CSP allows; a CDN-hosted module worker would not be.
  state.ml = globalThis.maplibregl;
  if (!state.ml) {
    banner("The map library did not load (offline?). The list still works.");
    return;
  }
  // The SOS layers are part of the initial style rather than added on "load":
  // "load" waits for every base tile, and on a poor link people would stare
  // at an empty map while the markers were already known.
  const map = new state.ml.Map({
    container: "map",
    center: [78.9, 21.0],
    zoom: 4,
    attributionControl: { compact: true },
    style: {
      version: 8,
      glyphs: "https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf",
      sources: {
        osm: {
          type: "raster",
          tiles: ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
          tileSize: 256,
          maxzoom: 19,
          attribution: "© OpenStreetMap contributors",
        },
        sos: {
          type: "geojson",
          data: toGeoJson(visible()),
          cluster: true,
          clusterRadius: 40,
          clusterProperties: { people: ["+", ["get", "headcount"]] },
        },
      },
      layers: [
        { id: "osm", type: "raster", source: "osm" },
        {
          id: "clusters", type: "circle", source: "sos", filter: ["has", "point_count"],
          paint: { "circle-color": COLORS.waiting, "circle-opacity": 0.85, "circle-radius": ["step", ["get", "point_count"], 16, 10, 22, 50, 30] },
        },
        {
          id: "cluster-count", type: "symbol", source: "sos", filter: ["has", "point_count"],
          layout: { "text-field": ["get", "point_count_abbreviated"], "text-font": ["Open Sans Semibold"], "text-size": 13 },
          paint: { "text-color": "#ffffff" },
        },
        {
          id: "points", type: "circle", source: "sos", filter: ["!", ["has", "point_count"]],
          paint: {
            "circle-color": ["match", ["get", "status"], "safe", COLORS.safe, "acked", COLORS.acked, COLORS.waiting],
            "circle-radius": ["interpolate", ["linear"], ["get", "headcount"], 1, 7, 20, 14],
            "circle-stroke-width": 2,
            "circle-stroke-color": "#ffffff",
          },
        },
      ],
    },
  });
  map.addControl(new state.ml.NavigationControl({ showCompass: false }));
  map.on("click", "clusters", async (e) => {
    const f = e.features[0];
    const zoom = await map.getSource("sos").getClusterExpansionZoom(f.properties.cluster_id);
    map.easeTo({ center: f.geometry.coordinates, zoom });
  });
  map.on("click", "points", (e) => {
    const s = state.list.find((x) => x.id === e.features[0].properties.id);
    if (s) focus(s);
  });
  for (const id of ["clusters", "points"]) {
    map.on("mouseenter", id, () => { map.getCanvas().style.cursor = "pointer"; });
    map.on("mouseleave", id, () => { map.getCanvas().style.cursor = ""; });
  }
  // A refresh may land before the style is parsed; push the latest list once it is.
  map.once("styledata", renderMap);
  state.map = map;
}

let fitted = false;
function fitOnce() {
  if (fitted || !state.map || state.list.length === 0) return;
  fitted = true;
  const b = new state.ml.LngLatBounds();
  for (const s of state.list) if (s.lat !== null) b.extend([s.lon, s.lat]);
  if (b.isEmpty()) { fitted = false; return; }
  state.map.fitBounds(b, { padding: 60, maxZoom: 14, duration: 0 });
}

wire();
await refresh();
initMap();
fitOnce();
setInterval(async () => { await refresh(); fitOnce(); }, POLL_MS);
