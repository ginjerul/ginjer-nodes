/*
 * NodesMap viewer
 *
 * Data (all polled with ETags, so unchanged data costs a 304):
 *   data/map.json            map setup (NodesMap)
 *   data/players.json        visible players (NodesMap, in memory)
 *   data/tiles.json          recently redrawn tiles (NodesMap, in memory)
 *   data/nodes/version.json  one hash per Nodes layer below (Nodes exporter)
 *   data/nodes/world.json    territories + resource nodes      (rarely changes)
 *   data/nodes/state.json    towns, nations, forts             (changes on claims etc.)
 *   data/nodes/features.json ports, towers, refineries, drops  (rarely changes)
 *   data/nodes/live.json     war, flags, occupation, warzones, raids, supply drop
 */
(() => {
  "use strict";

  // ---------------------------------------------------------------- utils
  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => Array.from(el.querySelectorAll(s));
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const fmtInt = (n) => (n ?? 0).toLocaleString();
  const now = () => Date.now() + S.clockOffset;
  const ll = (x, z) => L.latLng(z, x);
  const chunkCenter = (cx, cz) => ll(cx * 16 + 8, cz * 16 + 8);

  function fmtDur(ms) {
    if (ms == null || !isFinite(ms)) return "—";
    ms = Math.max(0, ms);
    const s = Math.floor(ms / 1000), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60;
    if (h > 0) return `${h}:${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
    return `${m}:${String(sec).padStart(2, "0")}`;
  }
  function fmtLong(ms) {
    const s = Math.round(ms / 1000);
    if (s < 60) return `${s}s`;
    const m = Math.floor(s / 60);
    if (m < 60) return `${m}m`;
    return `${Math.floor(m / 60)}h ${m % 60}m`;
  }
  function hexToRgb(h) {
    const n = parseInt(h.slice(1), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  function shade(h, f) {
    const [r, g, b] = hexToRgb(h);
    const c = (v) => Math.max(0, Math.min(255, Math.round(f >= 0 ? v + (255 - v) * f : v * (1 + f))));
    return "#" + ((1 << 24) | (c(r) << 16) | (c(g) << 8) | c(b)).toString(16).slice(1);
  }
  const store = {
    get(k, d) { try { const v = localStorage.getItem("nodesmap." + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem("nodesmap." + k, JSON.stringify(v)); } catch { /* private mode */ } },
  };

  // ---------------------------------------------------------------- state
  const S = {
    info: null,
    world: null, state: null, live: null, features: null,
    versions: {},
    players: [],
    clockOffset: 0,
    terr: new Map(),        // id -> territory
    chunkTerr: new Map(),   // chunk key -> territory id
    towns: new Map(),       // name -> town
    nations: new Map(),     // name -> nation
    owner: new Map(),       // territory id -> town
    occupier: new Map(),    // territory id -> town (captured territories)
    residentTown: new Map(),// player name (lower) -> town
    resources: {},          // resource node name -> {icon}
    resIcons: {},           // icon name -> url
    geom: new Map(),        // territory id -> latlng rings
    tileBust: new Map(),
    tilesSince: 0,
  };

  const MODES = [
    ["towns", "Towns"], ["nations", "Nations"], ["diplomacy", "Diplomacy"],
    ["resources", "Resource"], ["supply", "Supply"], ["plain", "Terrain"],
  ];
  const LAYERS = [
    ["territories", "Territory fill", true],
    ["borders", "Town borders", true],
    ["townLabels", "Town names", true],
    ["nationLabels", "Nation names", true],
    ["terrLabels", "Territory names", false],
    ["resources", "Resource icons", true],
    ["players", "Players", true],
    ["war", "War flags", true],
    ["warzones", "Warzones", true],
    ["raids", "Raids", true],
    ["forts", "Fortifications", true],
    ["outposts", "Outposts", true],
    ["ports", "Ports", true],
    ["towers", "Mining towers", true],
    ["refineries", "Refineries", true],
    ["drops", "Supply drops", true],
    ["chunks", "Chunk grid", false],
  ];
  const prefs = {
    mode: store.get("mode", "towns"),
    diploTown: store.get("diploTown", ""),
    resource: store.get("resource", ""),
    layers: Object.assign(Object.fromEntries(LAYERS.map(([k, , d]) => [k, d])), store.get("layers", {})),
    sidebar: store.get("sidebar", window.innerWidth > 760),
  };

  // ---------------------------------------------------------------- map
  const CRS = L.extend({}, L.CRS.Simple, { transformation: new L.Transformation(1, 0, 1, 0) });
  const map = L.map("map", { crs: CRS, zoomControl: false, attributionControl: false, minZoom: -5, maxZoom: 3, zoomSnap: 1, wheelPxPerZoomLevel: 90, inertia: true, fadeAnimation: false });
  L.control.zoom({ position: "bottomright" }).addTo(map);

  function pane(name, z) {
    const p = map.createPane(name);
    p.style.zIndex = z;
    return p;
  }
  pane("terr", 410); pane("occ", 420); pane("borders", 430); pane("zones", 440);
  pane("labels", 610); pane("pins", 620); pane("players", 640); pane("flags", 650);
  pane("grid", 405);
  const R = {
    terr: L.svg({ pane: "terr", padding: 0.5 }),
    occ: L.svg({ pane: "occ", padding: 0.5 }),
    borders: L.svg({ pane: "borders", padding: 0.5 }),
    zones: L.svg({ pane: "zones", padding: 0.5 }),
    grid: L.canvas({ pane: "grid", padding: 0.2 }),
  };

  const G = {}; // layer groups by name
  for (const k of ["terr", "occ", "borders", "zones", "townLabels", "nationLabels", "terrLabels", "resources", "players", "flags", "raids", "forts", "outposts", "ports", "towers", "refineries", "drops", "grid"]) {
    G[k] = L.layerGroup();
  }
  const terrPolys = new Map(); // id -> L.Polygon

  // tiles
  let tileLayer = null;
  const TileLayer = L.TileLayer.extend({
    getTileUrl(c) {
      const k = -c.z;
      const key = `${k}/${c.x}_${c.y}`;
      const b = S.tileBust.get(key);
      return `tiles/${encodeURIComponent(S.info.world)}/${key}.png${b ? "?t=" + b : ""}`;
    },
  });

  // ---------------------------------------------------------------- svg patterns (striped = occupied)
  const patterns = new Set();
  /** striped fill (c1 background or "none", c2 stripes); defined in every svg renderer */
  function stripe(c1, c2, op2 = 0.9) {
    const id = "p" + (c1 + c2 + op2).replace(/[^a-z0-9]/gi, "");
    if (patterns.has(id)) return `url(#${id})`;
    for (const r of [R.terr, R.occ, R.zones]) {
      const root = r._container;
      if (!root) continue;
      let defs = root.querySelector("defs");
      if (!defs) { defs = document.createElementNS("http://www.w3.org/2000/svg", "defs"); root.prepend(defs); }
      const p = document.createElementNS("http://www.w3.org/2000/svg", "pattern");
      p.setAttribute("id", id);
      p.setAttribute("patternUnits", "userSpaceOnUse");
      p.setAttribute("width", "10"); p.setAttribute("height", "10");
      p.setAttribute("patternTransform", "rotate(45)");
      p.innerHTML = (c1 === "none" ? "" : `<rect width="10" height="10" fill="${c1}"/>`) + `<rect width="4" height="10" fill="${c2}" fill-opacity="${op2}"/>`;
      defs.appendChild(p);
    }
    patterns.add(id);
    return `url(#${id})`;
  }

  // ---------------------------------------------------------------- data loading
  async function getJSON(url, syncClock) {
    const r = await fetch(url, { cache: "no-cache" });
    if (!r.ok) throw new Error(url + " " + r.status);
    if (syncClock) {
      // line countdowns up with the server clock (HTTP Date header, 1s resolution)
      const d = Date.parse(r.headers.get("Date") || "");
      if (!isNaN(d)) S.clockOffset = d + 500 - Date.now();
    }
    return r.json();
  }

  async function init() {
    S.info = await getJSON("data/map.json");
    document.title = S.info.title;
    $("#title").textContent = S.info.title;
    const levels = S.info.zoomOutLevels;
    map.setMinZoom(-levels);
    tileLayer = new TileLayer("", {
      tileSize: S.info.tileSize, minZoom: -levels, maxZoom: 3, minNativeZoom: -levels, maxNativeZoom: 0,
      noWrap: true, updateWhenIdle: false, keepBuffer: 3, errorTileUrl: "data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==",
    }).addTo(map);

    S.fitPending = !applyHash();
    if (S.fitPending) map.setView(ll(S.info.spawn.x, S.info.spawn.z), -2);

    try { S.resIcons = await getJSON("icons/resource_icons.json"); } catch { /* optional */ }

    for (const r of Object.values(R)) map.addLayer(r); // create svg containers up front (patterns live in them)
    for (const g of Object.values(G)) g.addTo(map);
    buildUi();
    if (S.info.players === false) setPlayersEnabled(false);
    syncLayerVisibility();

    await pollVersions();
    pollPlayers();
    setInterval(pollVersions, 2500);
    setInterval(pollPlayers, 2000);
    setInterval(pollTiles, 15000);
    setInterval(tickTimers, 1000);
  }

  async function pollVersions() {
    let v;
    try {
      v = await getJSON("data/nodes/version.json", true);
      $("#live-dot").className = "on";
    } catch {
      $("#live-dot").className = S.info.nodes ? "err" : "";
      return;
    }
    const changed = {};
    for (const k of ["world", "state", "features", "live"]) {
      if (v[k] && v[k] !== S.versions[k]) changed[k] = true;
    }
    if (!Object.keys(changed).length) return;
    const load = async (k) => {
      try { S[k] = await getJSON(`data/nodes/${k}.json`); S.versions[k] = v[k]; } catch (e) { delete changed[k]; }
    };
    await Promise.all(Object.keys(changed).map(load));
    onData(changed);
  }

  async function pollPlayers() {
    try {
      const d = await getJSON("data/players.json");
      S.players = d.players || [];
      setPlayersEnabled(!d.disabled);
      if (prefs.layers.players) drawPlayers();
    } catch { /* ignore */ }
  }

  /** the server can turn player markers off entirely (/nodesmap players off) */
  function setPlayersEnabled(on) {
    if (S.playersOn === on) return;
    S.playersOn = on;
    const row = document.querySelector('#layer-toggles input[data-layer="players"]')?.closest("label");
    if (row) row.hidden = !on;
    const opt = document.querySelector('.sort[data-list="towns"] option[value="online"]');
    if (opt) opt.hidden = !on;
    if (!on) G.players.clearLayers();
  }

  async function pollTiles() {
    let d;
    try { d = await getJSON("data/tiles.json"); } catch { return; }
    if (!S.tilesSince) { S.tilesSince = d.now; return; }
    for (const [key, ts] of d.updates || []) {
      if (ts <= S.tilesSince) continue;
      S.tileBust.set(key, ts);
      const [k, xz] = key.split("/");
      const [x, z] = xz.split("_");
      const t = tileLayer && tileLayer._tiles[`${x}:${z}:${-k}`];
      if (t) t.el.src = tileLayer.getTileUrl({ x: +x, y: +z, z: -k });
    }
    S.tilesSince = d.now;
  }

  // ---------------------------------------------------------------- indexing
  function indexWorld() {
    S.terr.clear(); S.chunkTerr.clear(); S.geom.clear();
    S.resources = S.world.resources || {};
    for (const t of S.world.territories) {
      S.terr.set(t.id, t);
      const c = t.chunks;
      for (let i = 0; i < c.length; i += 2) S.chunkTerr.set(NodesGeom.key(c[i], c[i + 1]), t.id);
      S.geom.set(t.id, NodesGeom.outline(c, 16).map((ring) => ring.map(([x, z]) => ll(x, z))));
    }
  }

  function indexState() {
    S.towns.clear(); S.nations.clear(); S.owner.clear(); S.occupier.clear(); S.residentTown.clear();
    for (const n of S.state.nations) { n.townList = []; S.nations.set(n.name, n); }
    for (const t of S.state.towns) {
      S.towns.set(t.name, t);
      for (const id of t.territories) S.owner.set(id, t);
      for (const id of t.captured || []) S.occupier.set(id, t);
      for (const r of t.residents) S.residentTown.set(r.toLowerCase(), t);
      const n = t.nation && S.nations.get(t.nation);
      if (n) n.townList.push(t);
    }
    for (const n of S.nations.values()) {
      n.territories = n.townList.reduce((a, t) => a + t.territories.length, 0);
      n.residents = n.townList.reduce((a, t) => a + t.residents.length, 0);
    }
  }

  function nationColor(t) {
    const n = t && t.nation && S.nations.get(t.nation);
    return n ? n.color : null;
  }
  function onlineCount(t) {
    let n = 0;
    const set = new Set(t.residents.map((r) => r.toLowerCase()));
    for (const p of S.players) if (set.has(p.name.toLowerCase())) n++;
    return n;
  }

  // ---------------------------------------------------------------- reacting to data
  function onData(changed) {
    if (changed.world && S.world) {
      indexWorld(); buildTerritories();
      if (S.fitPending && S.terr.size) {
        S.fitPending = false;
        const b = L.latLngBounds([]);
        for (const t of S.terr.values()) b.extend(chunkCenter(t.core[0], t.core[1]));
        map.fitBounds(b.pad(0.08), { animate: false });
      }
    }
    if (changed.state && S.state) indexState();
    if ((changed.world || changed.state) && S.world && S.state) {
      styleTerritories(); drawBorders(); drawLabels(); drawResources(); drawForts(); drawOutposts();
      renderLists(); refreshModeExtra(); buildSearch(); refreshInfo();
    }
    if (changed.features && S.features) drawFeatures();
    if ((changed.live || changed.state) && S.live && S.world) {
      drawWar(); drawZones(); styleTerritories(); renderWar(); refreshInfo();
    }
    if (changed.state) drawPlayers();
  }

  // ---------------------------------------------------------------- territories
  function buildTerritories() {
    G.terr.clearLayers();
    terrPolys.clear();
    for (const [id, rings] of S.geom) {
      const p = L.polygon(rings, { renderer: R.terr, weight: 1, smoothFactor: 0.5, interactive: false });
      terrPolys.set(id, p);
      G.terr.addLayer(p);
    }
    drawChunkGrid();
  }

  function supplyCut(id, town) {
    if (!town || !town.supplied) return false;
    return !town.supplied.includes(id);
  }

  function terrStyle(id) {
    const town = S.owner.get(id);
    const occ = S.occupier.get(id);
    const mode = prefs.mode;
    const showFill = prefs.layers.territories;
    let fill = null, op = 0.42, stroke = "rgba(255,255,255,0.22)", weight = 1;

    if (mode === "plain") {
      return { fill: !!town, fillColor: town ? town.color : "#000", fillOpacity: town ? 0.08 : 0, color: town ? shade(town.color, 0.25) : "rgba(255,255,255,0.18)", weight: 1, opacity: 0.8 };
    }
    if (mode === "towns" && town) { fill = town.color; stroke = shade(town.color, -0.25); }
    if (mode === "nations" && town) { fill = nationColor(town) || town.color; op = nationColor(town) ? 0.48 : 0.25; stroke = shade(fill, -0.3); }
    if (mode === "diplomacy") {
      const me = S.towns.get(prefs.diploTown);
      if (me && town) {
        const myNation = me.nation, rel =
          town === me ? "own" :
          me.enemies.includes(town.name) || (myNation && S.nations.get(myNation)?.enemies.includes(town.nation)) ? "enemy" :
          myNation && town.nation === myNation ? "nation" :
          me.allies.includes(town.name) || (myNation && S.nations.get(myNation)?.allies.includes(town.nation)) ? "ally" :
          me.vassals?.includes(town.name) || me.overlord === town.name ? "vassal" : "neutral";
        fill = { own: "#4f8ad9", nation: "#2fb3a6", ally: "#46a758", vassal: "#9b6bd6", enemy: "#e5484d", neutral: "#6b7280" }[rel];
        op = rel === "neutral" ? 0.25 : 0.5;
      } else if (town) { fill = "#6b7280"; op = 0.22; }
    }
    if (mode === "resources") {
      const t = S.terr.get(id);
      const hit = prefs.resource && t && t.resources.includes(prefs.resource);
      fill = hit ? "#f5c542" : town ? "#3b4250" : null;
      op = hit ? 0.62 : 0.3;
      stroke = hit ? "#f5c542" : stroke;
    }
    if (mode === "supply" && town) {
      fill = town.color;
      if (supplyCut(id, town)) {
        return { fill: showFill, fillColor: stripe(town.color, "#e5484d"), fillOpacity: 0.7, color: "#e5484d", weight: 1.5, opacity: 1 };
      }
    }
    if (!fill) return { fill: false, color: stroke, weight, opacity: 1 };
    if (occ && occ !== town && prefs.layers.war) {
      return { fill: showFill, fillColor: stripe(fill, occ.color), fillOpacity: 0.62, color: stroke, weight, opacity: 1 };
    }
    return { fill: showFill, fillColor: fill, fillOpacity: op, color: stroke, weight, opacity: 1 };
  }

  function styleTerritories() {
    if (!S.state) return;
    for (const [id, p] of terrPolys) p.setStyle(terrStyle(id));
  }

  /** thick outline around each town's whole realm (or each nation's in nation mode) */
  const borderCache = new Map();
  function drawBorders() {
    G.borders.clearLayers();
    if (!S.state || !S.world) return;
    const byNation = prefs.mode === "nations";
    const groups = new Map();
    for (const t of S.towns.values()) {
      const key = byNation && t.nation ? "n:" + t.nation : "t:" + t.name;
      const color = byNation && t.nation ? nationColor(t) : t.color;
      if (!groups.has(key)) groups.set(key, { color, ids: [] });
      groups.get(key).ids.push(...t.territories);
    }
    for (const [key, g] of groups) {
      const sig = g.ids.slice().sort((a, b) => a - b).join(",");
      let rings = borderCache.get(key);
      if (!rings || rings.sig !== sig) {
        const cells = [];
        for (const id of g.ids) { const t = S.terr.get(id); if (t) cells.push(...t.chunks); }
        rings = NodesGeom.outline(cells, 16).map((r) => r.map(([x, z]) => ll(x, z)));
        rings.sig = sig;
        borderCache.set(key, rings);
      }
      if (!rings.length) continue;
      G.borders.addLayer(L.polygon(rings, { renderer: R.borders, fill: false, color: shade(g.color, 0.35), weight: byNation ? 3.5 : 2.5, opacity: 0.95, interactive: false }));
    }
  }

  function drawChunkGrid() {
    G.grid.clearLayers();
    if (!prefs.layers.chunks || map.getZoom() < 0) return;
    const b = map.getBounds();
    const x0 = Math.floor(b.getWest() / 16) * 16, x1 = Math.ceil(b.getEast() / 16) * 16;
    const z0 = Math.floor(b.getNorth() / 16) * 16, z1 = Math.ceil(b.getSouth() / 16) * 16;
    const lines = [];
    for (let x = x0; x <= x1; x += 16) lines.push([ll(x, z0), ll(x, z1)]);
    for (let z = z0; z <= z1; z += 16) lines.push([ll(x0, z), ll(x1, z)]);
    G.grid.addLayer(L.polyline(lines, { renderer: R.grid, color: "#000", opacity: 0.25, weight: 1, interactive: false }));
  }

  // ---------------------------------------------------------------- labels + icons
  function drawLabels() {
    G.townLabels.clearLayers(); G.nationLabels.clearLayers(); G.terrLabels.clearLayers();
    const capitals = new Set([...S.nations.values()].map((n) => n.capital));
    for (const t of S.towns.values()) {
      const home = S.terr.get(t.home);
      if (!home) continue;
      const cap = capitals.has(t.name) ? `<span class="cap">★</span>` : "";
      G.townLabels.addLayer(L.marker(chunkCenter(home.core[0], home.core[1]), {
        pane: "labels", interactive: false, keyboard: false,
        icon: L.divIcon({ className: "", iconSize: null, html: `<div class="lbl town">${cap}${esc(t.name)}</div>` }),
      }));
    }
    for (const n of S.nations.values()) {
      const cells = [];
      for (const t of n.townList) for (const id of t.territories) { const tr = S.terr.get(id); if (tr) cells.push(tr.core); }
      const c = NodesGeom.labelCell(cells);
      if (!c) continue;
      G.nationLabels.addLayer(L.marker(chunkCenter(c[0], c[1]), {
        pane: "labels", interactive: false, keyboard: false,
        icon: L.divIcon({ className: "", iconSize: null, html: `<div class="lbl nation" style="color:${shade(n.color, 0.55)}">${esc(n.name)}<span class="n">${n.townList.length} towns</span></div>` }),
      }));
    }
    for (const t of S.terr.values()) {
      if (!t.name) continue;
      G.terrLabels.addLayer(L.marker(ll(t.core[0] * 16 + 8, t.core[1] * 16 + 20), {
        pane: "labels", interactive: false, keyboard: false,
        icon: L.divIcon({ className: "", iconSize: null, html: `<div class="lbl terr">${esc(t.name)}</div>` }),
      }));
    }
  }

  function resIconUrl(nodeName) {
    const r = S.resources[nodeName];
    const icon = (r && r.icon) || nodeName;
    return S.resIcons[icon] || S.resIcons[String(icon).toLowerCase()] || null;
  }

  function drawResources() {
    G.resources.clearLayers();
    for (const t of S.terr.values()) {
      const urls = t.resources.map(resIconUrl).filter(Boolean).slice(0, 3);
      if (!urls.length) continue;
      G.resources.addLayer(L.marker(ll(t.core[0] * 16 + 8, t.core[1] * 16 + 8), {
        pane: "labels", interactive: false, keyboard: false,
        icon: L.divIcon({ className: "", iconSize: null, html: `<div class="res-stack" style="margin-top:18px">${urls.map((u) => `<img src="${esc(u)}" alt="">`).join("")}</div>` }),
      }));
    }
  }

  function pin(cls, html, latlng, tip, onClick, paneName = "pins") {
    const m = L.marker(latlng, { pane: paneName, icon: L.divIcon({ className: "", iconSize: null, html: `<div class="pin ${cls}">${html}</div>` }) });
    if (tip) m.bindTooltip(tip, { className: "nm-tip", direction: "top", offset: [0, -14] });
    if (onClick) m.on("click", onClick);
    return m;
  }

  const ICON = {
    tower: `<svg viewBox="0 0 24 24"><path d="M8 21l2-14h4l2 14M7 7h10M9 3h6v4H9zM8 14h8"/></svg>`,
    refinery: `<svg viewBox="0 0 24 24"><path d="M3 21V11l5 3V11l5 3V7l8 4v10zM3 21h18"/></svg>`,
    drop: `<svg viewBox="0 0 24 24"><path d="M12 3c-4 0-7 3-7 6h14c0-3-3-6-7-6zM5 9l7 8 7-8M12 17v2M9 19h6v3H9z"/></svg>`,
    outpost: `<svg viewBox="0 0 24 24"><path d="M6 21V4M6 4h11l-3 4 3 4H6"/></svg>`,
  };

  function drawFeatures() {
    G.ports.clearLayers(); G.towers.clearLayers(); G.refineries.clearLayers(); G.drops.clearLayers();
    const f = S.features || {};
    for (const p of f.ports || []) {
      G.ports.addLayer(pin("port", `<img src="icons/anchor.png" alt="">`, ll(p.x, p.z),
        `<b>${esc(p.name)}</b><br>${p.public ? "Public port" : "Port"}${p.groups?.length ? " · " + esc(p.groups.join(", ")) : ""}`));
    }
    for (const t of f.towers || []) {
      const rewards = (t.rewards || []).map((r) => `${r.amount}× ${esc(prettyMat(r.material))}`).join("<br>");
      G.towers.addLayer(pin("tower", ICON.tower, ll(t.x, t.z),
        `<b>Mining tower</b> <span style="color:var(--faint)">${esc(t.id)}</span><br>Cooldown ${fmtLong(t.cooldownSeconds * 1000)}${rewards ? "<br>" + rewards : ""}`));
    }
    for (const r of f.refineries || []) {
      const outs = (r.outputs || []).map((o) => `${o.amount}× ${esc(prettyMat(o.material))}`).join("<br>");
      G.refineries.addLayer(pin("refinery", ICON.refinery, ll(r.x, r.z),
        `<b>Refinery</b> <span style="color:var(--faint)">${esc(r.id)}</span><br>Every ${fmtLong(r.intervalMs)}${outs ? "<br>" + outs : ""}`));
    }
    drawDrops();
  }

  function drawDrops() {
    G.drops.clearLayers();
    const f = S.features || {};
    const active = S.live && S.live.supplyDrop && S.live.supplyDrop.active;
    for (const d of f.dropPoints || []) {
      const isActive = active && active.id === d.id;
      G.drops.addLayer(pin("drop" + (isActive ? " active" : ""), ICON.drop, ll(d.x, d.z),
        isActive ? `<b>Supply drop!</b><br>${active.landed ? "Landed" : "Landing"}` : `<b>Drop zone</b> ${esc(d.id)}`));
    }
  }

  function drawForts() {
    G.forts.clearLayers();
    const forts = (S.state && S.state.forts) || {};
    for (const [id, lvl] of Object.entries(forts)) {
      const t = S.terr.get(+id);
      if (!t || !lvl) continue;
      const town = S.owner.get(+id);
      const m = pin("fort", `F${lvl}`, ll(t.core[0] * 16 + 15, t.core[1] * 16 + 1), `<b>Fortification level ${lvl}</b><br>${esc(t.name || "Territory " + t.id)}`, () => showTerritory(t.id));
      m.options.icon.options.html = `<div class="pin fort" style="background:${town ? shade(town.color, -0.35) : "#333"}">F${lvl}</div>`;
      G.forts.addLayer(m);
    }
  }

  function drawOutposts() {
    G.outposts.clearLayers();
    for (const t of S.towns.values()) {
      for (const o of t.outposts || []) {
        const m = pin("outpost", ICON.outpost, ll(o.x, o.z), `<b>${esc(o.name)}</b><br>Outpost of ${esc(t.name)}`, () => showTown(t.name));
        m.options.icon.options.html = `<div class="pin outpost" style="border-color:${t.color};color:${t.color}">${ICON.outpost}</div>`;
        G.outposts.addLayer(m);
      }
    }
  }

  const prettyMat = (m) => String(m || "").toLowerCase().replace(/_/g, " ");

  // ---------------------------------------------------------------- players
  function drawPlayers() {
    G.players.clearLayers();
    if (!prefs.layers.players) return;
    for (const p of S.players) {
      const town = S.residentTown.get(p.name.toLowerCase());
      const c = town ? town.color : "#ffffff";
      const m = L.marker(ll(p.x, p.z), {
        pane: "players",
        icon: L.divIcon({
          className: "", iconSize: null,
          html: `<div class="player" style="--c:${c}"><img src="https://mc-heads.net/avatar/${esc(p.uuid)}/16" alt="" onerror="this.replaceWith(Object.assign(document.createElement('i'),{className:'dot'}))"><span>${esc(p.name)}</span></div>`,
        }),
      });
      m.bindTooltip(`<b>${esc(p.name)}</b>${town ? "<br>" + esc(town.name) + (town.nation ? " · " + esc(town.nation) : "") : "<br>No town"}<br><span style="color:var(--faint)">${p.x}, ${p.z}</span>`, { className: "nm-tip", direction: "top", offset: [0, -10] });
      if (town) m.on("click", () => showTown(town.name));
      G.players.addLayer(m);
    }
  }

  // ---------------------------------------------------------------- war
  function attackRemaining(a) {
    return a.remainingMs - (S.live.at ? now() - S.live.at : 0);
  }

  function drawWar() {
    G.occ.clearLayers(); G.flags.clearLayers();
    const live = S.live;
    if (!live || !prefs.layers.war) return;
    // occupied chunks grouped by occupier town
    const byTown = new Map();
    for (const [x, z, town] of live.occupied || []) {
      if (!byTown.has(town)) byTown.set(town, []);
      byTown.get(town).push(x, z);
    }
    for (const [name, cells] of byTown) {
      const t = S.towns.get(name);
      const color = t ? t.color : "#e5484d";
      const rings = NodesGeom.outline(cells, 16).map((r) => r.map(([x, z]) => ll(x, z)));
      const poly = L.polygon(rings, { renderer: R.occ, fillColor: stripe("none", color, 0.85), fillOpacity: 1, color: shade(color, 0.3), weight: 1.5, interactive: true });
      poly.bindTooltip(`Occupied by <b>${esc(name)}</b><br>${cells.length / 2} chunks`, { className: "nm-tip", sticky: true });
      G.occ.addLayer(poly);
    }
    // active flags
    for (const a of live.attacks || []) {
      const t = S.towns.get(a.town);
      const color = t ? t.color : "#e5484d";
      const pct = Math.round(a.progress * 100);
      const terr = S.terr.get(S.chunkTerr.get(NodesGeom.key(a.cx, a.cz)));
      G.flags.addLayer(L.rectangle([ll(a.cx * 16, a.cz * 16), ll(a.cx * 16 + 16, a.cz * 16 + 16)], { renderer: R.zones, color: "#ff5f62", weight: 2, dashArray: "4 4", fill: false, interactive: false }));
      const m = L.marker(ll(a.fx + 0.5, a.fz + 0.5), {
        pane: "flags",
        icon: L.divIcon({ className: "", iconSize: null, html: `<div class="flag" style="--c:${color};--p:${pct}"><div class="ring"></div><div class="core">${pct}</div></div>` }),
      });
      m.bindTooltip(() => `<b>${esc(a.town)}</b> attacking ${esc(terr ? terr.name || "territory " + terr.id : "chunk")}<br>by ${esc(a.attacker || "?")} · ${pct}% · ${fmtDur(attackRemaining(a))} left`, { className: "nm-tip", direction: "top", offset: [0, -18] });
      if (terr) m.on("click", () => showTerritory(terr.id));
      G.flags.addLayer(m);
    }
  }

  function drawZones() {
    G.zones.clearLayers(); G.raids.clearLayers();
    const live = S.live;
    if (!live) return;
    if (prefs.layers.warzones) {
      for (const z of live.warzones || []) {
        if (!z.enabled) continue;
        const town = S.towns.get(z.town);
        if (!town) continue;
        for (const id of town.territories) {
          const g = S.geom.get(id);
          if (g) G.zones.addLayer(L.polygon(g, { renderer: R.zones, fill: false, color: "#f5c542", weight: 3, dashArray: "10 6", interactive: false }));
        }
        const home = S.terr.get(town.home);
        if (home) {
          const tag = L.marker(ll(home.core[0] * 16 + 8, home.core[1] * 16 - 24), {
            pane: "flags", interactive: true,
            icon: L.divIcon({ className: "", iconSize: null, html: `<div class="timer-tag" data-ends="${z.endsAt}" style="--c:#f5c542"><small>WARZONE ${esc(z.town)}</small><span class="t">${fmtDur(z.endsAt - now())}</span></div>` }),
          });
          tag.on("click", () => openTab("war"));
          G.zones.addLayer(tag);
        }
      }
    }
    if (prefs.layers.raids) {
      for (const r of live.raids || []) {
        const g = S.geom.get(r.territory);
        const t = S.terr.get(r.territory);
        if (!g || !t) continue;
        const isLive = r.phase === "LIVE";
        G.raids.addLayer(L.polygon(g, { renderer: R.zones, fill: true, fillColor: isLive ? "#e5484d" : "#f59e0b", fillOpacity: 0.12, color: isLive ? "#ff5f62" : "#f59e0b", weight: 3, dashArray: isLive ? null : "6 6", interactive: false }));
        const ends = isLive ? r.liveEndsAt : r.prepEndsAt;
        G.raids.addLayer(L.marker(ll(t.core[0] * 16 + 8, t.core[1] * 16 + 8), {
          pane: "flags", interactive: false,
          icon: L.divIcon({ className: "", iconSize: null, html: `<div class="timer-tag" data-ends="${ends}" style="--c:${isLive ? "#ff5f62" : "#f59e0b"}"><small>RAID ${isLive ? "LIVE" : "PREP"} · ${esc(r.attacker)} → ${esc(r.defender)}</small><span class="t">${fmtDur(ends - now())}</span></div>` }),
        }));
      }
    }
    drawDrops();
  }

  function tickTimers() {
    for (const el of $$(".timer-tag[data-ends]")) {
      const t = el.querySelector(".t");
      if (t) t.textContent = fmtDur(+el.dataset.ends - now());
    }
    for (const el of $$("[data-countdown]")) el.textContent = fmtDur(+el.dataset.countdown - now());
    updateWarPill();
  }

  // ---------------------------------------------------------------- war panel
  function renderWar() {
    const live = S.live || {};
    const war = live.war || {};
    const atk = live.attacks || [];
    const zones = (live.warzones || []).filter((z) => z.enabled);
    const raids = live.raids || [];
    const drop = live.supplyDrop;
    let h = `<div class="war-head ${war.enabled ? "on" : ""}"><b>${war.enabled ? "War is active" : "Peace time"}</b>${war.enabled && war.endsAt ? `<span>ends in <b data-countdown="${war.endsAt}">${fmtDur(war.endsAt - now())}</b></span>` : ""}</div>`;

    h += `<h3>Flags in progress (${atk.length})</h3>`;
    if (!atk.length) h += `<div class="empty">No active flag attacks.</div>`;
    for (const a of atk.slice().sort((x, y) => y.progress - x.progress)) {
      const terr = S.terr.get(S.chunkTerr.get(NodesGeom.key(a.cx, a.cz)));
      const owner = terr && S.owner.get(terr.id);
      h += `<div class="card" data-fly="${a.fx},${a.fz}"><div class="t"><span>${esc(a.town)} → ${esc(owner ? owner.name : "unclaimed")}</span><span>${Math.round(a.progress * 100)}%</span></div><div class="s">${esc(terr ? terr.name || "Territory " + terr.id : "")} · ${esc(a.attacker || "")}</div><div class="bar"><i style="width:${a.progress * 100}%;background:${S.towns.get(a.town)?.color || "var(--war)"}"></i></div></div>`;
    }

    h += `<h3>Warzones</h3>`;
    if (!zones.length) h += `<div class="empty">No warzone is running.</div>`;
    for (const z of zones) {
      const scores = Object.entries(z.scores || {}).sort((a, b) => b[1] - a[1]).slice(0, 6);
      const town = S.towns.get(z.town);
      h += `<div class="card" data-town-fly="${esc(z.town)}"><div class="t"><span>${esc(z.town)}</span><span data-countdown="${z.endsAt}">${fmtDur(z.endsAt - now())}</span></div><div class="s">Holding: <b>${esc(z.holder || "nobody")}</b>${town ? ` · ${town.territories.length} territories` : ""}</div>${scores.length ? `<div class="score">${scores.map(([n, ms]) => `<span>${esc(n)}</span><span>${fmtLong(ms)}</span>`).join("")}</div>` : ""}</div>`;
    }

    h += `<h3>Raids</h3>`;
    if (!raids.length) h += `<div class="empty">No raids.</div>`;
    for (const r of raids) {
      const t = S.terr.get(r.territory);
      const ends = r.phase === "LIVE" ? r.liveEndsAt : r.prepEndsAt;
      h += `<div class="card" data-terr="${r.territory}"><div class="t"><span>${esc(r.attacker)} → ${esc(r.defender)}</span><span class="badge ${r.phase === "LIVE" ? "war" : "gold"}">${esc(r.phase)}</span></div><div class="s">${esc(t ? t.name || "Territory " + t.id : "")} · <span data-countdown="${ends}">${fmtDur(ends - now())}</span></div></div>`;
    }

    if (drop) {
      h += `<h3>Supply drop</h3>`;
      if (drop.active) {
        h += `<div class="card" data-fly="${drop.active.x},${drop.active.z}"><div class="t"><span>Drop ${drop.active.landed ? "landed" : "incoming"}</span><span class="badge gold">${esc(drop.active.id)}</span></div><div class="s">${drop.active.landed ? "Expires in" : "Lands in"} <span data-countdown="${drop.active.landed ? drop.active.expiresAt : drop.active.landsAt}">—</span> · ${drop.active.x}, ${drop.active.z}</div></div>`;
      } else if (drop.nextDropAt) {
        h += `<div class="card"><div class="t"><span>Next drop</span><span data-countdown="${drop.nextDropAt}">${fmtDur(drop.nextDropAt - now())}</span></div></div>`;
      }
    }

    const occByTown = new Map();
    for (const t of S.towns.values()) if (t.captured?.length) occByTown.set(t.name, t.captured.length);
    if (occByTown.size) {
      h += `<h3>Occupied territories</h3><div class="list">`;
      for (const [n, c] of [...occByTown].sort((a, b) => b[1] - a[1])) {
        const t = S.towns.get(n);
        h += `<div class="row" data-town="${esc(n)}"><span class="sw" style="background:${t.color}"></span><span class="nm">${esc(n)}</span><span class="stat">${c}</span></div>`;
      }
      h += `</div>`;
    }
    $("#war-panel").innerHTML = h;
    $('#tabs [data-tab="war"]').classList.toggle("has-alert", !!(war.enabled || atk.length || zones.length || raids.length));
    updateWarPill();
  }

  function updateWarPill() {
    const pill = $("#war-pill");
    const live = S.live;
    if (!live || !live.war || !live.war.enabled) { pill.hidden = true; return; }
    const n = (live.attacks || []).length;
    pill.hidden = false;
    pill.textContent = `War${live.war.endsAt ? " · " + fmtDur(live.war.endsAt - now()) : ""}${n ? ` · ${n} flag${n > 1 ? "s" : ""}` : ""}`;
  }

  // ---------------------------------------------------------------- sidebar lists
  function renderLists() {
    const tf = ($('.filter[data-list="towns"]').value || "").toLowerCase();
    const ts = $('.sort[data-list="towns"]').value;
    let towns = [...S.towns.values()].filter((t) => !tf || t.name.toLowerCase().includes(tf) || (t.nation || "").toLowerCase().includes(tf));
    const on = new Map(towns.map((t) => [t.name, onlineCount(t)]));
    const sorters = {
      territories: (a, b) => b.territories.length - a.territories.length,
      residents: (a, b) => b.residents.length - a.residents.length,
      online: (a, b) => on.get(b.name) - on.get(a.name),
      name: (a, b) => a.name.localeCompare(b.name),
    };
    towns.sort(sorters[ts] || sorters.territories);
    $("#list-towns").innerHTML = towns.length ? towns.map((t) => `<div class="row" data-town="${esc(t.name)}"><span class="sw" style="background:${t.color}"></span><div><div class="nm">${esc(t.name)}</div><div class="sub">${esc(t.nation || "No nation")}${on.get(t.name) ? ` · <span style="color:var(--ok)">${on.get(t.name)} online</span>` : ""}</div></div><span class="stat">${t.territories.length} terr<br>${t.residents.length} res</span></div>`).join("") : `<div class="empty">No towns.</div>`;

    const nf = ($('.filter[data-list="nations"]').value || "").toLowerCase();
    const ns = $('.sort[data-list="nations"]').value;
    let nations = [...S.nations.values()].filter((n) => !nf || n.name.toLowerCase().includes(nf));
    const nsorters = {
      territories: (a, b) => b.territories - a.territories,
      towns: (a, b) => b.townList.length - a.townList.length,
      residents: (a, b) => b.residents - a.residents,
      name: (a, b) => a.name.localeCompare(b.name),
    };
    nations.sort(nsorters[ns] || nsorters.territories);
    $("#list-nations").innerHTML = nations.length ? nations.map((n) => `<div class="row" data-nation="${esc(n.name)}"><span class="sw" style="background:${n.color}"></span><div><div class="nm">${esc(n.name)}</div><div class="sub">Capital ${esc(n.capital)} · ${n.townList.length} towns</div></div><span class="stat">${n.territories} terr<br>${n.residents} res</span></div>`).join("") : `<div class="empty">No nations.</div>`;
  }

  // ---------------------------------------------------------------- info panels
  let infoTarget = null;
  function openInfo(html, target) {
    infoTarget = target;
    $("#info-body").innerHTML = html;
    $("#info").hidden = false;
  }
  function refreshInfo() {
    if (!infoTarget || $("#info").hidden) return;
    const [k, v] = infoTarget;
    if (k === "terr") showTerritory(v, false);
    if (k === "town") showTown(v, false);
    if (k === "nation") showNation(v, false);
  }
  const townChip = (name) => {
    const t = S.towns.get(name);
    return `<span class="chip" data-town="${esc(name)}"><span class="dot" style="background:${t ? t.color : "#777"}"></span>${esc(name)}</span>`;
  };
  const nationChip = (name) => {
    const n = S.nations.get(name);
    return `<span class="chip" data-nation="${esc(name)}"><span class="dot" style="background:${n ? n.color : "#777"}"></span>${esc(name)}</span>`;
  };

  function showTerritory(id, fly = false) {
    const t = S.terr.get(id);
    if (!t) return;
    const town = S.owner.get(id), occ = S.occupier.get(id);
    const live = S.live || {};
    const raid = (live.raids || []).find((r) => r.territory === id);
    const fort = S.state?.forts?.[id];
    const zone = town && (live.warzones || []).find((z) => z.town === town.name && z.enabled);
    const flags = (live.attacks || []).filter((a) => S.chunkTerr.get(NodesGeom.key(a.cx, a.cz)) === id);
    const occChunks = (live.occupied || []).filter(([x, z]) => S.chunkTerr.get(NodesGeom.key(x, z)) === id).length;
    const badges = [];
    if (town && town.home === id) badges.push(`<span class="badge gold">Town home</span>`);
    if (occ && occ !== town) badges.push(`<span class="badge war">Occupied by ${esc(occ.name)}</span>`);
    if (raid) badges.push(`<span class="badge war">Raid ${esc(raid.phase.toLowerCase())}</span>`);
    if (zone) badges.push(`<span class="badge gold">Warzone</span>`);
    if (fort) badges.push(`<span class="badge">Fort level ${fort}</span>`);
    if (town && town.supplied) badges.push(supplyCut(id, town) ? `<span class="badge war">Supply cut</span>` : `<span class="badge ok">Supplied</span>`);
    if (t.wild) badges.push(`<span class="badge">Borders wilderness</span>`);
    const res = t.resources.map((r) => { const u = resIconUrl(r); return `<div class="res">${u ? `<img src="${esc(u)}" alt="">` : ""}${esc(r)}</div>`; }).join("");
    openInfo(`
      <div class="info-kicker">Territory #${t.id}</div>
      <h2 class="info-title">${esc(t.name || "Unnamed territory")}</h2>
      <div class="info-sub">${town ? `Owned by ${townChip(town.name)}${town.nation ? " " + nationChip(town.nation) : ""}` : "Unclaimed"}</div>
      ${badges.length ? `<div class="badges">${badges.join("")}</div>` : ""}
      <dl class="kv">
        <dt>Chunks</dt><dd>${t.chunks.length / 2}</dd>
        <dt>Claim cost</dt><dd>${t.cost ?? "—"}</dd>
        <dt>Core</dt><dd>${t.core[0] * 16 + 8}, ${t.core[1] * 16 + 8}</dd>
        ${flags.length ? `<dt>Flags</dt><dd>${flags.length} in progress</dd>` : ""}
        ${occChunks ? `<dt>Occupied chunks</dt><dd>${occChunks}</dd>` : ""}
      </dl>
      ${res ? `<div class="section-h">Resources</div><div class="res-grid">${res}</div>` : ""}
    `, ["terr", id]);
    if (fly) map.flyTo(chunkCenter(t.core[0], t.core[1]), Math.max(map.getZoom(), -1));
  }

  function showTown(name, fly = false) {
    const t = S.towns.get(name);
    if (!t) return;
    const nation = t.nation && S.nations.get(t.nation);
    const online = new Set(S.players.map((p) => p.name.toLowerCase()));
    const isCap = nation && nation.capital === t.name;
    const live = S.live || {};
    const zone = (live.warzones || []).find((z) => z.town === t.name);
    const res = (r) => {
      const role = r === t.leader ? " ♛" : t.officers.includes(r) ? " ·" : "";
      return `<span class="chip plain ${online.has(r.toLowerCase()) ? "online" : ""}">${esc(r)}${role}</span>`;
    };
    const badges = [];
    if (isCap) badges.push(`<span class="badge gold">Capital of ${esc(nation.name)}</span>`);
    badges.push(`<span class="badge">${t.open ? "Open to join" : "Invite only"}</span>`);
    if (t.overlord) badges.push(`<span class="badge">Vassal of ${esc(t.overlord)}</span>`);
    if (zone) badges.push(`<span class="badge gold">Warzone${zone.enabled ? " · active" : ""}</span>`);
    if (t.claims && t.claims[0] > t.claims[1]) badges.push(`<span class="badge war">Over claim limit</span>`);
    if (t.supplied) {
      const cut = t.territories.filter((id) => !t.supplied.includes(id)).length;
      if (cut) badges.push(`<span class="badge war">${cut} ${cut === 1 ? "territory" : "territories"} cut off</span>`);
    }
    const sortedRes = t.residents.slice().sort((a, b) => (a === t.leader ? -1 : b === t.leader ? 1 : (online.has(b.toLowerCase()) - online.has(a.toLowerCase())) || a.localeCompare(b)));
    openInfo(`
      <div class="info-kicker">Town</div>
      <h2 class="info-title"><span class="sw" style="background:${t.color}"></span>${esc(t.name)}</h2>
      <div class="info-sub">${nation ? nationChip(nation.name) : "No nation"}</div>
      <div class="badges">${badges.join("")}</div>
      <dl class="kv">
        <dt>Leader</dt><dd>${esc(t.leader || "—")}</dd>
        <dt>Residents</dt><dd>${t.residents.length} (${t.residents.filter((r) => online.has(r.toLowerCase())).length} online)</dd>
        <dt>Territories</dt><dd>${t.territories.length}</dd>
        ${t.captured?.length ? `<dt>Occupying</dt><dd>${t.captured.length}</dd>` : ""}
        ${t.claims ? `<dt>Claims</dt><dd>${t.claims[0]} / ${t.claims[1]}</dd>` : ""}
      </dl>
      ${t.allies.length ? `<div class="section-h">Allies</div><div class="chips">${t.allies.map(townChip).join("")}</div>` : ""}
      ${t.enemies.length ? `<div class="section-h">At war with</div><div class="chips">${t.enemies.map(townChip).join("")}</div>` : ""}
      ${t.vassals?.length ? `<div class="section-h">Vassals</div><div class="chips">${t.vassals.map(townChip).join("")}</div>` : ""}
      ${t.outposts?.length ? `<div class="section-h">Outposts</div><div class="chips">${t.outposts.map((o) => `<span class="chip plain" data-fly="${o.x},${o.z}" style="cursor:pointer">${esc(o.name)}</span>`).join("")}</div>` : ""}
      <div class="section-h">Residents</div><div class="chips">${sortedRes.map(res).join("")}</div>
    `, ["town", name]);
    if (fly) {
      const home = S.terr.get(t.home);
      if (home) map.flyTo(chunkCenter(home.core[0], home.core[1]), Math.max(map.getZoom(), -2));
    }
  }

  function showNation(name, fly = false) {
    const n = S.nations.get(name);
    if (!n) return;
    openInfo(`
      <div class="info-kicker">Nation</div>
      <h2 class="info-title"><span class="sw" style="background:${n.color}"></span>${esc(n.name)}</h2>
      <div class="info-sub">Capital ${townChip(n.capital)}</div>
      <dl class="kv">
        <dt>Towns</dt><dd>${n.townList.length}</dd>
        <dt>Territories</dt><dd>${n.territories}</dd>
        <dt>Residents</dt><dd>${n.residents}</dd>
      </dl>
      <div class="section-h">Towns</div><div class="chips">${n.townList.map((t) => townChip(t.name)).join("")}</div>
      ${n.allies.length ? `<div class="section-h">Allies</div><div class="chips">${n.allies.map(nationChip).join("")}</div>` : ""}
      ${n.enemies.length ? `<div class="section-h">At war with</div><div class="chips">${n.enemies.map(nationChip).join("")}</div>` : ""}
    `, ["nation", name]);
    if (fly) {
      const cap = S.towns.get(n.capital);
      const home = cap && S.terr.get(cap.home);
      if (home) map.flyTo(chunkCenter(home.core[0], home.core[1]), Math.max(Math.min(map.getZoom(), -2), -3));
    }
  }

  // ---------------------------------------------------------------- search
  let searchIndex = [];
  function buildSearch() {
    searchIndex = [];
    for (const t of S.towns.values()) searchIndex.push({ kind: "town", label: t.name, color: t.color, go: () => showTown(t.name, true) });
    for (const n of S.nations.values()) searchIndex.push({ kind: "nation", label: n.name, color: n.color, go: () => showNation(n.name, true) });
    for (const t of S.terr.values()) if (t.name) searchIndex.push({ kind: "territory", label: t.name, go: () => showTerritory(t.id, true) });
    for (const t of S.towns.values()) for (const r of t.residents) searchIndex.push({ kind: "player", label: r, color: t.color, go: () => goPlayer(r) });
  }
  function goPlayer(name) {
    const p = S.players.find((x) => x.name.toLowerCase() === name.toLowerCase());
    const town = S.residentTown.get(name.toLowerCase());
    if (town) showTown(town.name, !p);
    if (p) map.flyTo(ll(p.x, p.z), Math.max(map.getZoom(), 0));
  }
  let srSel = 0, srItems = [];
  function runSearch(q) {
    const box = $("#search-results");
    q = q.trim().toLowerCase();
    if (!q) { box.hidden = true; return; }
    const coord = q.match(/^(-?\d+)[ ,]+(-?\d+)$/);
    srItems = [];
    if (coord) srItems.push({ kind: "coords", label: `Go to ${coord[1]}, ${coord[2]}`, go: () => map.flyTo(ll(+coord[1], +coord[2]), Math.max(map.getZoom(), 0)) });
    // online players not in any town
    for (const p of S.players) if (!S.residentTown.has(p.name.toLowerCase()) && p.name.toLowerCase().includes(q)) srItems.push({ kind: "player", label: p.name, go: () => map.flyTo(ll(p.x, p.z), 0) });
    const starts = [], inc = [];
    for (const it of searchIndex) {
      const l = it.label.toLowerCase();
      if (l.startsWith(q)) starts.push(it); else if (l.includes(q)) inc.push(it);
    }
    srItems.push(...starts, ...inc);
    srItems = srItems.slice(0, 30);
    srSel = 0;
    box.innerHTML = srItems.length ? srItems.map((it, i) => `<div class="sr ${i === 0 ? "sel" : ""}" data-i="${i}">${it.color ? `<span class="sw" style="width:10px;height:10px;border-radius:3px;background:${it.color}"></span>` : ""}<span>${esc(it.label)}</span><span class="kind">${it.kind}</span></div>`).join("") : `<div class="empty">Nothing found</div>`;
    box.hidden = false;
  }
  function pickSearch(i) {
    const it = srItems[i];
    if (!it) return;
    it.go();
    $("#search-results").hidden = true;
    $("#search").blur();
    if (window.innerWidth <= 760) setSidebar(false);
  }

  // ---------------------------------------------------------------- hover + hash
  function hover(id) {
    const t = S.terr.get(id);
    if (!t) return;
    const town = S.owner.get(id);
    $("#hover-terr").textContent = `${t.name || "Territory " + t.id}${town ? " · " + town.name : ""}`;
  }
  // territory clicks: O(1) chunk lookup instead of thousands of interactive svg paths
  map.on("click", (e) => {
    const id = S.chunkTerr.get(NodesGeom.key(Math.floor(e.latlng.lng) >> 4, Math.floor(e.latlng.lat) >> 4));
    if (id !== undefined) showTerritory(id);
    else { $("#info").hidden = true; infoTarget = null; }
  });
  map.on("mousemove", (e) => {
    const x = Math.floor(e.latlng.lng), z = Math.floor(e.latlng.lat);
    $("#coords").textContent = `x ${x}  z ${z}`;
    const id = S.chunkTerr.get(NodesGeom.key(x >> 4, z >> 4));
    if (id === undefined) $("#hover-terr").textContent = "";
    else hover(id);
  });

  function applyHash() {
    const m = location.hash.match(/^#(-?\d+),(-?\d+),(-?\d+)/);
    if (!m) return false;
    map.setView(ll(+m[1], +m[2]), +m[3]);
    return true;
  }
  map.on("moveend", () => {
    const c = map.getCenter();
    history.replaceState(null, "", `#${Math.round(c.lng)},${Math.round(c.lat)},${map.getZoom()}`);
    if (prefs.layers.chunks) drawChunkGrid();
  });
  map.on("zoomend", syncLayerVisibility);
  window.addEventListener("hashchange", applyHash);

  // ---------------------------------------------------------------- layers + modes
  function syncLayerVisibility() {
    const z = map.getZoom();
    const want = {
      terr: true,
      borders: prefs.layers.borders && prefs.mode !== "plain",
      townLabels: prefs.layers.townLabels && z >= -2,
      nationLabels: prefs.layers.nationLabels && z <= -3,
      terrLabels: prefs.layers.terrLabels && z >= -1,
      resources: prefs.layers.resources && z >= -1,
      players: prefs.layers.players,
      occ: prefs.layers.war, flags: prefs.layers.war,
      zones: prefs.layers.warzones,
      raids: prefs.layers.raids,
      forts: prefs.layers.forts && z >= -1,
      outposts: prefs.layers.outposts && z >= -1,
      ports: prefs.layers.ports && z >= -2,
      towers: prefs.layers.towers && z >= -2,
      refineries: prefs.layers.refineries && z >= -2,
      drops: prefs.layers.drops,
      grid: prefs.layers.chunks,
    };
    map.getContainer().classList.toggle("far", z < -1);
    for (const [k, on] of Object.entries(want)) {
      const has = map.hasLayer(G[k]);
      if (on && !has) G[k].addTo(map);
      if (!on && has) map.removeLayer(G[k]);
    }
  }

  function setMode(m) {
    prefs.mode = m; store.set("mode", m);
    $$("#modes button").forEach((b) => b.classList.toggle("active", b.dataset.mode === m));
    refreshModeExtra();
    styleTerritories(); drawBorders(); syncLayerVisibility();
  }

  function refreshModeExtra() {
    const el = $("#mode-extra");
    if (prefs.mode === "diplomacy") {
      const towns = [...S.towns.keys()].sort((a, b) => a.localeCompare(b));
      el.innerHTML = `<select id="diplo-town"><option value="">Pick a town…</option>${towns.map((n) => `<option ${n === prefs.diploTown ? "selected" : ""}>${esc(n)}</option>`).join("")}</select>
        <div class="chips" style="margin-top:8px">${[["#4f8ad9", "Own"], ["#2fb3a6", "Nation"], ["#46a758", "Ally"], ["#9b6bd6", "Vassal"], ["#e5484d", "Enemy"], ["#6b7280", "Neutral"]].map(([c, l]) => `<span class="chip plain"><span class="dot" style="background:${c}"></span>${l}</span>`).join("")}</div>`;
      $("#diplo-town").onchange = (e) => { prefs.diploTown = e.target.value; store.set("diploTown", prefs.diploTown); styleTerritories(); };
    } else if (prefs.mode === "resources") {
      const names = Object.keys(S.resources).sort();
      const counts = new Map();
      for (const t of S.terr.values()) for (const r of t.resources) counts.set(r, (counts.get(r) || 0) + 1);
      el.innerHTML = `<select id="res-pick"><option value="">Pick a resource…</option>${names.map((n) => `<option value="${esc(n)}" ${n === prefs.resource ? "selected" : ""}>${esc(n)} (${counts.get(n) || 0})</option>`).join("")}</select>`;
      $("#res-pick").onchange = (e) => { prefs.resource = e.target.value; store.set("resource", prefs.resource); styleTerritories(); };
    } else if (prefs.mode === "supply") {
      const any = [...S.towns.values()].some((t) => t.supplied);
      el.innerHTML = any ? `<div class="chips"><span class="chip plain"><span class="dot" style="background:#46a758"></span>Connected to home</span><span class="chip plain"><span class="dot" style="background:#e5484d"></span>Cut off</span></div>` : `<p class="hint">Supply lines are turned off in the Nodes map export config.</p>`;
    } else {
      el.innerHTML = "";
    }
  }

  // ---------------------------------------------------------------- ui wiring
  function openTab(name) {
    setSidebar(true);
    $$("#tabs button").forEach((b) => b.classList.toggle("active", b.dataset.tab === name));
    $$(".tab").forEach((t) => t.classList.toggle("active", t.id === "tab-" + name));
  }
  function setSidebar(open) {
    prefs.sidebar = open; store.set("sidebar", open);
    document.body.classList.toggle("sidebar-closed", !open);
  }

  function buildUi() {
    setSidebar(prefs.sidebar);
    $("#menu-btn").onclick = () => setSidebar(!prefs.sidebar);
    $$("#tabs button").forEach((b) => (b.onclick = () => openTab(b.dataset.tab)));
    $("#war-pill").onclick = () => openTab("war");
    $("#info-close").onclick = () => { $("#info").hidden = true; infoTarget = null; };

    $("#modes").innerHTML = MODES.map(([k, l]) => `<button data-mode="${k}" class="${k === prefs.mode ? "active" : ""}">${l}</button>`).join("");
    $$("#modes button").forEach((b) => (b.onclick = () => setMode(b.dataset.mode)));

    $("#layer-toggles").innerHTML = LAYERS.map(([k, l]) => `<label class="tgl"><input type="checkbox" data-layer="${k}" ${prefs.layers[k] ? "checked" : ""}>${l}</label>`).join("");
    $$("#layer-toggles input").forEach((i) => (i.onchange = () => {
      prefs.layers[i.dataset.layer] = i.checked;
      store.set("layers", prefs.layers);
      if (["territories", "war"].includes(i.dataset.layer)) styleTerritories();
      if (i.dataset.layer === "war") drawWar();
      if (["warzones", "raids"].includes(i.dataset.layer)) drawZones();
      if (i.dataset.layer === "players") drawPlayers();
      if (i.dataset.layer === "chunks") drawChunkGrid();
      syncLayerVisibility();
    }));

    $$(".filter, .sort").forEach((el) => (el.oninput = renderLists));

    // delegated clicks for chips/rows/cards anywhere in panels
    document.addEventListener("click", (e) => {
      const el = e.target.closest("[data-town],[data-nation],[data-terr],[data-fly],[data-town-fly]");
      if (!el || !el.closest("#sidebar,#info")) return;
      if (el.dataset.town) showTown(el.dataset.town, true);
      else if (el.dataset.nation) showNation(el.dataset.nation, true);
      else if (el.dataset.terr) showTerritory(+el.dataset.terr, true);
      else if (el.dataset.townFly) showTown(el.dataset.townFly, true);
      else if (el.dataset.fly) { const [x, z] = el.dataset.fly.split(",").map(Number); map.flyTo(ll(x, z), Math.max(map.getZoom(), 0)); }
      if (window.innerWidth <= 760 && el.closest("#sidebar")) setSidebar(false);
    });

    const s = $("#search");
    s.addEventListener("input", () => runSearch(s.value));
    s.addEventListener("focus", () => s.value && runSearch(s.value));
    s.addEventListener("keydown", (e) => {
      const items = $$("#search-results .sr");
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        srSel = (srSel + (e.key === "ArrowDown" ? 1 : -1) + items.length) % Math.max(items.length, 1);
        items.forEach((el, i) => el.classList.toggle("sel", i === srSel));
      } else if (e.key === "Enter") pickSearch(srSel);
      else if (e.key === "Escape") { $("#search-results").hidden = true; s.blur(); }
    });
    $("#search-results").addEventListener("mousedown", (e) => {
      const el = e.target.closest(".sr");
      if (el) { e.preventDefault(); pickSearch(+el.dataset.i); }
    });
    s.addEventListener("blur", () => setTimeout(() => ($("#search-results").hidden = true), 120));
    document.addEventListener("keydown", (e) => {
      if (e.key === "/" && document.activeElement !== s) { e.preventDefault(); s.focus(); }
    });
    refreshModeExtra();
  }

  init().catch((e) => {
    console.error(e);
    document.body.insertAdjacentHTML("beforeend", `<div style="position:absolute;inset:auto 0 60px 0;text-align:center;color:#ffb3b5;z-index:2000">Could not load the map: ${esc(e.message)}</div>`);
  });
})();
