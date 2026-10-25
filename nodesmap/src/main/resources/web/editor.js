/*
 * NodesMap world editor
 *
 * Edits plugins/nodes/world.json (territories + resource nodes) on top of the
 * live terrain tiles. All editing happens in the browser; "Check" and "Apply"
 * send the whole world to the server, where Nodes validates it, refuses
 * anything that would break claims, backs up the old file and hot-reloads.
 */
(() => {
  "use strict";

  // ---------------------------------------------------------------- utils
  const $ = (s, el = document) => el.querySelector(s);
  const $$ = (s, el = document) => Array.from(el.querySelectorAll(s));
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const SPAN = 0x400000; // same packing as NodesGeom.key
  const K = (x, z) => x * SPAN + z;
  const KX = (k) => Math.round(k / SPAN);
  const KZ = (k) => k - KX(k) * SPAN;
  const ll = (x, z) => L.latLng(z, x);
  const store = {
    get(k, d) { try { const v = localStorage.getItem("nodesmap.ed." + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
    set(k, v) { try { localStorage.setItem("nodesmap.ed." + k, JSON.stringify(v)); } catch { /* ignore */ } },
  };
  // the 6 territory colors Nodes uses (index stored in world.json)
  const PALETTE = ["#d1694f", "#5b8ad6", "#d6b14a", "#58b386", "#a36bd1", "#4fb0c6"];

  function mulberry32(a) {
    return function () {
      a |= 0; a = (a + 0x6d2b79f5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  // ---------------------------------------------------------------- state
  const E = {
    token: null, user: null, nodesOnline: false,
    info: null,
    top: {},                 // other top-level keys of world.json, kept as-is
    resources: {},           // resource node name -> definition object
    terr: new Map(),         // id -> territory
    owner: new Map(),        // chunk key -> territory id
    nextId: 0,
    sel: new Set(),
    claimed: new Map(),      // territory id -> {town, color, kind}
    cost: { constant: 10, scale: 0.25 },
    icons: {},
    undo: [], redo: [],
    savedVersion: 0, version: 0,
    tool: "select",
    brush: store.get("brush", 2),
    opt: Object.assign({ land: true, wild: true, lock: true, labels: true, icons: true, owners: true, grid: false }, store.get("opt", {})),
  };

  // ---------------------------------------------------------------- map
  const CRS = L.extend({}, L.CRS.Simple, { transformation: new L.Transformation(1, 0, 1, 0) });
  const map = L.map("map", { crs: CRS, zoomControl: false, attributionControl: false, minZoom: -5, maxZoom: 3, boxZoom: false, doubleClickZoom: false, fadeAnimation: false, keyboard: false });
  L.control.zoom({ position: "bottomright" }).addTo(map);
  map.setView([0, 0], -2);
  for (const [n, z] of [["terr", 410], ["claim", 415], ["sel", 420], ["over", 600], ["ui", 650]]) map.createPane(n).style.zIndex = z;
  const R = { terr: L.canvas({ pane: "terr", padding: 0.3 }), claim: L.svg({ pane: "claim", padding: 0.3 }), sel: L.svg({ pane: "sel", padding: 0.3 }), ui: L.svg({ pane: "ui" }) };
  const G = { terr: L.layerGroup().addTo(map), claim: L.layerGroup().addTo(map), sel: L.layerGroup().addTo(map), labels: L.layerGroup().addTo(map), icons: L.layerGroup().addTo(map), grid: L.layerGroup().addTo(map), ui: L.layerGroup().addTo(map) };
  const polys = new Map(); // id -> polygon
  let tileLayer = null;

  // ---------------------------------------------------------------- api
  async function api(path, opts = {}) {
    const r = await fetch(path, { ...opts, headers: { ...(opts.headers || {}), Authorization: "Bearer " + E.token } });
    let body = null;
    try { body = await r.json(); } catch { body = null; }
    if (!r.ok) {
      const msg = (body && body.error) || `${r.status} ${r.statusText}`;
      const e = new Error(msg);
      e.status = r.status;
      throw e;
    }
    return body;
  }

  // ---------------------------------------------------------------- loading
  function readToken() {
    const m = location.hash.match(/token=([A-Za-z0-9_-]+)/);
    if (m) {
      try { sessionStorage.setItem("nodesmap.token", m[1]); } catch { /* ignore */ }
      history.replaceState(null, "", location.pathname + location.search);
      return m[1];
    }
    try { return sessionStorage.getItem("nodesmap.token"); } catch { return null; }
  }

  async function init() {
    buildUi();
    E.token = readToken();
    try { E.info = await (await fetch("data/map.json", { cache: "no-cache" })).json(); } catch { E.info = { world: "world", tileSize: 512, zoomOutLevels: 5, spawn: { x: 0, z: 0 } }; }
    setupTiles();
    try { E.icons = await (await fetch("icons/resource_icons.json")).json(); } catch { /* optional */ }
    try {
      const w = await (await fetch("data/nodes/world.json", { cache: "no-cache" })).json();
      if (w.costConfig) E.cost = w.costConfig;
    } catch { /* optional */ }
    await loadClaims();

    if (!E.token) {
      modal(`<h2>Sign in to edit</h2><p>Run <kbd>/nodesmap editor</kbd> in game (or in the console) and open the link it gives you. Links expire after a while for safety.</p>
        <p>You can still import a <b>world.json</b> file and edit it offline, then download the result.</p>
        <div class="foot"><button class="btn" data-close>Edit a file offline</button></div>`);
      setSaveState();
      return;
    }
    try {
      const s = await api("api/editor/session");
      E.user = s.user;
      E.nodesOnline = !!s.nodes;
    } catch (e) {
      modal(`<h2>Session expired</h2><p>${esc(e.message)}</p><div class="foot"><button class="btn" data-close>Edit a file offline</button></div>`);
      E.token = null;
      setSaveState();
      return;
    }
    if (E.nodesOnline) await loadFromServer(true);
    else toast("Nodes isn't running on the server, so you can only edit and download files.", true);
    setSaveState();
  }

  async function loadFromServer(fit) {
    const w = await api("api/editor/world");
    loadWorld(w);
    if (fit) fitWorld();
    toast(`Loaded ${E.terr.size} territories from the server`);
  }

  async function loadClaims() {
    E.claimed.clear();
    try {
      const st = await (await fetch("data/nodes/state.json", { cache: "no-cache" })).json();
      for (const t of st.towns || []) {
        for (const id of t.territories || []) E.claimed.set(id, { town: t.name, color: t.color, kind: "owned" });
        for (const id of t.captured || []) if (!E.claimed.has(id)) E.claimed.set(id, { town: t.name, color: t.color, kind: "occupied" });
      }
    } catch { /* no live data */ }
  }

  function setupTiles() {
    const levels = E.info.zoomOutLevels ?? 5;
    map.setMinZoom(-levels);
    tileLayer = L.tileLayer("", {
      tileSize: E.info.tileSize || 512, minZoom: -levels, maxZoom: 3, minNativeZoom: -levels, maxNativeZoom: 0, noWrap: true, keepBuffer: 2,
      errorTileUrl: "data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==",
    });
    tileLayer.getTileUrl = (c) => `tiles/${encodeURIComponent(E.info.world)}/${-c.z}/${c.x}_${c.y}.png`;
    tileLayer.addTo(map);
    map.setView(ll(E.info.spawn?.x || 0, E.info.spawn?.z || 0), -2);
  }

  function fitWorld() {
    if (!E.terr.size) return;
    const b = L.latLngBounds([]);
    for (const t of E.terr.values()) if (t.core) b.extend(ll(t.core[0] * 16 + 8, t.core[1] * 16 + 8));
    if (b.isValid()) map.fitBounds(b.pad(0.1), { animate: false });
  }

  const COMPUTED = new Set(["name", "color", "coreChunk", "chunks", "nodes", "neighbors", "isEdge", "size"]);

  function loadWorld(w) {
    E.terr.clear(); E.owner.clear(); E.sel.clear(); E.undo = []; E.redo = [];
    E.top = {};
    for (const [k, v] of Object.entries(w)) if (k !== "nodes" && k !== "territories") E.top[k] = v;
    E.resources = w.nodes || {};
    let maxId = -1;
    for (const [ids, t] of Object.entries(w.territories || {})) {
      const id = +ids;
      if (!Number.isFinite(id)) continue;
      maxId = Math.max(maxId, id);
      const chunks = new Set();
      const c = t.chunks || [];
      for (let i = 0; i + 1 < c.length; i += 2) {
        const k = K(c[i], c[i + 1]);
        if (E.owner.has(k)) continue; // first territory wins on duplicates
        chunks.add(k);
        E.owner.set(k, id);
      }
      const extra = {};
      for (const [kk, vv] of Object.entries(t)) if (!COMPUTED.has(kk)) extra[kk] = vv;
      E.terr.set(id, { id, name: t.name || "", color: Number.isInteger(t.color) ? t.color : -1, core: Array.isArray(t.coreChunk) ? [t.coreChunk[0], t.coreChunk[1]] : null, chunks, nodes: [...(t.nodes || [])], extra });
    }
    E.nextId = maxId + 1;
    for (const t of E.terr.values()) { fixCore(t); }
    recolorAll(true);
    E.version = E.savedVersion = 0;
    rebuildAll();
  }

  // ---------------------------------------------------------------- geometry helpers
  function neighborsOf(t) {
    const out = new Set();
    let edge = false;
    for (const k of t.chunks) {
      const x = KX(k), z = KZ(k);
      for (const n of [K(x + 1, z), K(x - 1, z), K(x, z + 1), K(x, z - 1)]) {
        const o = E.owner.get(n);
        if (o === undefined) edge = true;
        else if (o !== t.id) out.add(o);
      }
    }
    return { neighbors: out, edge };
  }

  function fixCore(t) {
    if (!t.chunks.size) { t.core = null; return; }
    if (t.core && t.chunks.has(K(t.core[0], t.core[1]))) return;
    const cells = [];
    for (const k of t.chunks) cells.push([KX(k), KZ(k)]);
    t.core = NodesGeom.labelCell(cells);
  }

  function components(chunks) {
    const left = new Set(chunks);
    const out = [];
    while (left.size) {
      const start = left.values().next().value;
      left.delete(start);
      const comp = [start];
      for (let i = 0; i < comp.length; i++) {
        const x = KX(comp[i]), z = KZ(comp[i]);
        for (const n of [K(x + 1, z), K(x - 1, z), K(x, z + 1), K(x, z - 1)]) {
          if (left.has(n)) { left.delete(n); comp.push(n); }
        }
      }
      out.push(comp);
    }
    return out;
  }

  function terrCost(t) {
    let c = E.cost.constant, s = E.cost.scale;
    for (const n of t.nodes) {
      const r = E.resources[n];
      if (r && r.cost) { c += r.cost.constant || 0; s *= r.cost.scale ?? 1; }
    }
    return c + Math.round(s * t.chunks.size);
  }

  /** greedy 6-coloring so neighbors differ (like the old editor's generateColors) */
  function recolorAll(onlyMissing) {
    const ids = [...E.terr.keys()];
    const nb = new Map(ids.map((id) => [id, neighborsOf(E.terr.get(id)).neighbors]));
    ids.sort((a, b) => nb.get(b).size - nb.get(a).size);
    for (const id of ids) {
      const t = E.terr.get(id);
      if (onlyMissing && t.color >= 0) continue;
      if (!onlyMissing || t.color < 0) t.color = -1;
    }
    for (const id of ids) {
      const t = E.terr.get(id);
      if (t.color >= 0) continue;
      const used = new Set([...nb.get(id)].map((n) => E.terr.get(n)?.color));
      let c = 0;
      while (c < 6 && used.has(c)) c++;
      t.color = c < 6 ? c : id % 6;
    }
  }

  function recolorOne(t) {
    const { neighbors } = neighborsOf(t);
    const used = new Set([...neighbors].map((n) => E.terr.get(n)?.color));
    if (t.color >= 0 && !used.has(t.color)) return;
    let c = 0;
    while (c < 6 && used.has(c)) c++;
    t.color = c < 6 ? c : t.id % 6;
  }

  // ---------------------------------------------------------------- land mask (from rendered tiles)
  const land = new Map(); // "rx,rz" -> {state, data}
  function regionOf(cx, cz) { return [Math.floor(cx / 32), Math.floor(cz / 32)]; }
  function loadRegion(rx, rz) {
    const key = rx + "," + rz;
    if (land.has(key)) return land.get(key);
    const r = { state: "loading", data: null };
    land.set(key, r);
    const img = new Image();
    img.onload = () => {
      try {
        const c = document.createElement("canvas");
        c.width = c.height = 512;
        const g = c.getContext("2d", { willReadFrequently: true });
        g.drawImage(img, 0, 0);
        r.data = g.getImageData(0, 0, 512, 512).data;
        r.state = "ok";
      } catch { r.state = "missing"; }
    };
    img.onerror = () => { r.state = "missing"; };
    img.src = `tiles/${encodeURIComponent(E.info.world)}/0/${rx}_${rz}.png?e=1`;
    return r;
  }
  /** true = land, false = water/ungenerated, null = not loaded yet */
  function isLand(k) {
    const cx = KX(k), cz = KZ(k);
    const [rx, rz] = regionOf(cx, cz);
    const r = loadRegion(rx, rz);
    if (r.state === "missing") return false;
    if (r.state !== "ok") return null;
    const px = (cx - rx * 32) * 16 + 8, pz = (cz - rz * 32) * 16 + 8;
    const i = (pz * 512 + px) * 4;
    const d = r.data;
    if (d[i + 3] < 128) return false;
    const red = d[i], g = d[i + 1], b = d[i + 2];
    if (b > red + 35 && b > g + 18) return false; // water / ice
    return true;
  }
  function preloadLand() {
    if (!E.info || !E.opt.land || map.getZoom() < -3) return;
    const b = map.getBounds();
    const x0 = Math.floor(b.getWest() / 512), x1 = Math.floor(b.getEast() / 512);
    const z0 = Math.floor(b.getNorth() / 512), z1 = Math.floor(b.getSouth() / 512);
    if ((x1 - x0 + 1) * (z1 - z0 + 1) > 120) return;
    for (let z = z0; z <= z1; z++) for (let x = x0; x <= x1; x++) loadRegion(x, z);
  }

  // ---------------------------------------------------------------- history
  let op = null;
  const touched = new Set();

  function snap(id) {
    const t = E.terr.get(id);
    if (!t) return null;
    return { id, name: t.name, color: t.color, core: t.core ? [...t.core] : null, chunks: new Set(t.chunks), nodes: [...t.nodes], extra: JSON.parse(JSON.stringify(t.extra)) };
  }
  function begin(label) {
    op = { label, before: new Map(), res: null };
    touched.clear();
  }
  function touch(id) {
    if (op && !op.before.has(id)) op.before.set(id, snap(id));
    touched.add(id);
  }
  function touchRes() {
    if (op && op.res == null) op.res = JSON.stringify(E.resources);
  }
  function end() {
    if (!op) return;
    const o = op;
    op = null;
    // clean up: empty territories disappear, cores stay valid, colors stay distinct
    for (const id of touched) {
      const t = E.terr.get(id);
      if (!t) continue;
      if (!t.chunks.size) {
        E.terr.delete(id);
        E.sel.delete(id);
        continue;
      }
      fixCore(t);
      recolorOne(t);
    }
    const after = new Map();
    let changed = o.res != null && o.res !== JSON.stringify(E.resources);
    for (const [id, b] of o.before) {
      const a = snap(id);
      after.set(id, a);
      if (!changed && !sameSnap(a, b)) changed = true;
    }
    if (changed) {
      E.undo.push({ label: o.label, before: o.before, after, resBefore: o.res, resAfter: o.res != null ? JSON.stringify(E.resources) : null });
      if (E.undo.length > 200) E.undo.shift();
      E.redo = [];
      E.version++;
    }
    redraw([...o.before.keys()]);
    touched.clear();
    refreshUi();
  }
  function sameSnap(a, b) {
    if (!a || !b) return a === b;
    if (a.name !== b.name || a.chunks.size !== b.chunks.size || a.nodes.join() !== b.nodes.join()) return false;
    if (String(a.core) !== String(b.core)) return false;
    for (const k of a.chunks) if (!b.chunks.has(k)) return false;
    return true;
  }
  function restore(states) {
    // remove first, then assign, so chunks moving between territories end up right
    for (const id of states.keys()) {
      const t = E.terr.get(id);
      if (!t) continue;
      for (const k of t.chunks) if (E.owner.get(k) === id) E.owner.delete(k);
      E.terr.delete(id);
    }
    for (const [id, s] of states) {
      if (!s) { E.sel.delete(id); continue; }
      const t = { id, name: s.name, color: s.color, core: s.core ? [...s.core] : null, chunks: new Set(s.chunks), nodes: [...s.nodes], extra: JSON.parse(JSON.stringify(s.extra)) };
      E.terr.set(id, t);
      for (const k of t.chunks) E.owner.set(k, id);
      E.nextId = Math.max(E.nextId, id + 1);
    }
  }
  function undo() {
    const h = E.undo.pop();
    if (!h) return;
    restore(h.before);
    if (h.resBefore != null) E.resources = JSON.parse(h.resBefore);
    E.redo.push(h);
    E.version--;
    redraw([...h.before.keys()]);
    refreshUi();
    toast("Undid: " + h.label);
  }
  function redo() {
    const h = E.redo.pop();
    if (!h) return;
    restore(h.after);
    if (h.resAfter != null) E.resources = JSON.parse(h.resAfter);
    E.undo.push(h);
    E.version++;
    redraw([...h.after.keys()]);
    refreshUi();
    toast("Redid: " + h.label);
  }

  // ---------------------------------------------------------------- editing primitives
  function isLocked(id) { return E.opt.lock && E.claimed.has(id); }

  function newTerritory() {
    const id = E.nextId++;
    touch(id);
    const t = { id, name: "", color: -1, core: null, chunks: new Set(), nodes: [], extra: {} };
    E.terr.set(id, t);
    return t;
  }

  /** move chunk k to territory id (null = wilderness). Returns true if it changed. */
  function assign(k, id, { onlyWild = false, landOnly = false } = {}) {
    const cur = E.owner.get(k);
    if (cur === id) return false;
    if (cur !== undefined) {
      if (onlyWild || isLocked(cur)) return false;
    }
    if (id != null) {
      if (isLocked(id)) return false;
      if (landOnly && isLand(k) === false) return false;
    }
    if (cur !== undefined) {
      touch(cur);
      E.terr.get(cur).chunks.delete(k);
    }
    if (id != null) {
      touch(id);
      E.terr.get(id).chunks.add(k);
      E.owner.set(k, id);
    } else {
      E.owner.delete(k);
    }
    return true;
  }

  function brushKeys(cx, cz, r) {
    const out = [];
    const rr = r * r + r;
    for (let dz = -r; dz <= r; dz++) for (let dx = -r; dx <= r; dx++) if (dx * dx + dz * dz <= rr) out.push(K(cx + dx, cz + dz));
    return out;
  }

  // ---------------------------------------------------------------- drawing
  function terrStyle(t) {
    const sel = E.sel.has(t.id);
    const base = PALETTE[((t.color % 6) + 6) % 6];
    return { renderer: R.terr, fillColor: base, fillOpacity: sel ? 0.55 : 0.32, color: sel ? "#ffffff" : "rgba(10,12,16,0.55)", weight: sel ? 2.5 : 1, interactive: false };
  }
  function ringsOf(t) {
    const cells = [];
    for (const k of t.chunks) cells.push(KX(k), KZ(k));
    return NodesGeom.outline(cells, 16).map((r) => r.map(([x, z]) => ll(x, z)));
  }
  function redraw(ids) {
    for (const id of ids) {
      const t = E.terr.get(id);
      const p = polys.get(id);
      if (!t) {
        if (p) { G.terr.removeLayer(p); polys.delete(id); }
        continue;
      }
      const rings = ringsOf(t);
      if (p) { p.setLatLngs(rings); p.setStyle(terrStyle(t)); }
      else { const np = L.polygon(rings, terrStyle(t)); polys.set(id, np); G.terr.addLayer(np); }
    }
    drawOverlays();
  }
  function rebuildAll() {
    G.terr.clearLayers();
    polys.clear();
    redraw([...E.terr.keys()]);
    refreshUi();
  }
  function restyle() {
    for (const [id, p] of polys) { const t = E.terr.get(id); if (t) p.setStyle(terrStyle(t)); }
  }

  let overlayTimer = 0;
  function drawOverlays() {
    clearTimeout(overlayTimer);
    overlayTimer = setTimeout(drawOverlaysNow, 30);
  }
  function drawOverlaysNow() {
    if (!E.info) return;
    G.labels.clearLayers(); G.icons.clearLayers(); G.claim.clearLayers(); G.sel.clearLayers();
    const z = map.getZoom();
    const b = map.getBounds().pad(0.2);
    if (E.opt.owners) {
      // one outline per town around its claimed territories
      const byTown = new Map();
      for (const [id, c] of E.claimed) {
        const t = E.terr.get(id);
        if (!t) continue;
        if (!byTown.has(c.town)) byTown.set(c.town, { color: c.color, cells: [] });
        const arr = byTown.get(c.town).cells;
        for (const k of t.chunks) arr.push(KX(k), KZ(k));
      }
      for (const [, g] of byTown) {
        const rings = NodesGeom.outline(g.cells, 16).map((r) => r.map(([x, zz]) => ll(x, zz)));
        if (rings.length) G.claim.addLayer(L.polygon(rings, { renderer: R.claim, fill: true, fillColor: g.color, fillOpacity: 0.18, color: g.color, weight: 3, dashArray: "8 5", interactive: false }));
      }
    }
    for (const t of E.terr.values()) {
      if (!t.core) continue;
      const p = ll(t.core[0] * 16 + 8, t.core[1] * 16 + 8);
      if (!b.contains(p)) continue;
      const claim = E.claimed.get(t.id);
      if (E.opt.labels && z >= -2) {
        const lock = claim && E.opt.lock ? `<span class="lock">🔒</span> ` : "";
        G.labels.addLayer(L.marker(p, { pane: "over", interactive: false, keyboard: false, icon: L.divIcon({ className: "", iconSize: null, html: `<div class="ed-label">${lock}${esc(t.name || "")}<span class="id">#${t.id}${z >= 0 ? " · " + t.chunks.size : ""}</span></div>` }) }));
      }
      if (E.opt.icons && z >= -1 && t.nodes.length) {
        const imgs = t.nodes.slice(0, 4).map((n) => iconUrl(n)).filter(Boolean).map((u) => `<img src="${esc(u)}" alt="">`).join("");
        if (imgs) G.icons.addLayer(L.marker(p, { pane: "over", interactive: false, keyboard: false, icon: L.divIcon({ className: "", iconSize: null, html: `<div class="ed-icons" style="margin-top:12px">${imgs}</div>` }) }));
      }
      if (E.sel.has(t.id) && z >= -2) {
        G.sel.addLayer(L.marker(p, { pane: "ui", interactive: false, keyboard: false, icon: L.divIcon({ className: "", iconSize: null, html: `<div class="ed-core" title="core"></div>` }) }));
      }
    }
    drawGrid();
  }
  function drawGrid() {
    G.grid.clearLayers();
    if (!E.opt.grid || map.getZoom() < -1) return;
    const b = map.getBounds();
    const x0 = Math.floor(b.getWest() / 16) * 16, x1 = Math.ceil(b.getEast() / 16) * 16;
    const z0 = Math.floor(b.getNorth() / 16) * 16, z1 = Math.ceil(b.getSouth() / 16) * 16;
    const lines = [];
    for (let x = x0; x <= x1; x += 16) lines.push([ll(x, z0), ll(x, z1)]);
    for (let z = z0; z <= z1; z += 16) lines.push([ll(x0, z), ll(x1, z)]);
    G.grid.addLayer(L.polyline(lines, { renderer: R.ui, color: "#000", opacity: 0.3, weight: 1, interactive: false }));
  }
  map.on("moveend zoomend", () => { drawOverlays(); preloadLand(); });

  function iconUrl(nodeName) {
    const r = E.resources[nodeName];
    const icon = (r && r.icon) || nodeName;
    return E.icons[icon] || E.icons[String(icon).toLowerCase()] || null;
  }

  // ---------------------------------------------------------------- tools
  let stroke = null;   // active paint stroke
  let spaceDown = false;
  let shiftDown = false;
  const cursor = L.circle([0, 0], { renderer: R.ui, radius: 8, color: "#fff", weight: 1.5, fill: false, dashArray: "3 3", interactive: false });
  const boxRect = L.rectangle([[0, 0], [0, 0]], { renderer: R.ui, color: "#f3d38d", weight: 1.5, fillOpacity: 0.12, interactive: false });

  const PAINT_TOOLS = new Set(["paint", "new", "erase"]);
  const DRAG_TOOLS = new Set(["paint", "new", "erase", "rect"]);

  function setTool(t) {
    E.tool = t;
    document.body.className = "editor tool-" + t;
    $$("#tools [data-tool]").forEach((b) => b.classList.toggle("active", b.dataset.tool === t));
    $("#brush").classList.toggle("off", !PAINT_TOOLS.has(t));
    syncDragging();
    if (PAINT_TOOLS.has(t)) G.ui.addLayer(cursor); else G.ui.removeLayer(cursor);
    if (t === "paint" && E.sel.size !== 1) toast("Paint adds to the selected territory. Select one first, or use New.");
    preloadLand();
  }
  function syncDragging() {
    const paintMode = DRAG_TOOLS.has(E.tool) || stroke || rectStart || (E.tool === "select" && shiftDown);
    if (paintMode && !spaceDown) map.dragging.disable(); else map.dragging.enable();
    document.body.classList.toggle("panning", spaceDown);
  }
  function setBrush(n) {
    E.brush = Math.max(0, Math.min(12, n));
    store.set("brush", E.brush);
    $("#brush-size").value = E.brush;
    $("#brush-out").textContent = `${E.brush * 2 + 1}×${E.brush * 2 + 1}`;
    cursor.setRadius((E.brush + 0.5) * 16);
  }

  function chunkAt(latlng) { return [Math.floor(latlng.lng) >> 4, Math.floor(latlng.lat) >> 4]; }

  map.on("mousemove", (e) => {
    const x = Math.floor(e.latlng.lng), z = Math.floor(e.latlng.lat);
    const [cx, cz] = [x >> 4, z >> 4];
    $("#coords").textContent = `x ${x}  z ${z}  ·  chunk ${cx}, ${cz}`;
    const id = E.owner.get(K(cx, cz));
    const t = id !== undefined ? E.terr.get(id) : null;
    const claim = t && E.claimed.get(t.id);
    $("#hover-terr").textContent = t ? `#${t.id} ${t.name || ""}${claim ? " · " + claim.town : ""}` : "wilderness";
    if (PAINT_TOOLS.has(E.tool)) cursor.setLatLng(ll(cx * 16 + 8, cz * 16 + 8));
    if (stroke) strokeTo(cx, cz);
    if (rectStart) {
      boxRect.setBounds([ll(Math.min(rectStart[0], cx) * 16, Math.min(rectStart[1], cz) * 16), ll((Math.max(rectStart[0], cx) + 1) * 16, (Math.max(rectStart[1], cz) + 1) * 16)]);
    }
  });

  let rectStart = null;
  let rectMode = null; // "fill" | "select"

  map.on("mousedown", (e) => {
    if (e.originalEvent.button !== 0 || spaceDown) return;
    const [cx, cz] = chunkAt(e.latlng);
    if (E.tool === "select" && e.originalEvent.shiftKey) {
      rectStart = [cx, cz]; rectMode = "select";
      boxRect.setBounds([ll(cx * 16, cz * 16), ll(cx * 16 + 16, cz * 16 + 16)]);
      G.ui.addLayer(boxRect);
      return;
    }
    if (E.tool === "rect") {
      rectStart = [cx, cz]; rectMode = "fill";
      boxRect.setBounds([ll(cx * 16, cz * 16), ll(cx * 16 + 16, cz * 16 + 16)]);
      G.ui.addLayer(boxRect);
      return;
    }
    if (PAINT_TOOLS.has(E.tool)) startStroke(cx, cz);
  });
  document.addEventListener("mouseup", (e) => {
    if (stroke) endStroke();
    if (rectStart) {
      const ll2 = map.mouseEventToLatLng(e);
      const [cx, cz] = chunkAt(ll2);
      finishRect(rectStart, [cx, cz], rectMode);
      rectStart = null;
      G.ui.removeLayer(boxRect);
      syncDragging();
    }
  });

  map.on("click", (e) => {
    const [cx, cz] = chunkAt(e.latlng);
    const k = K(cx, cz);
    const id = E.owner.get(k);
    if (E.tool === "select") {
      if (e.originalEvent.shiftKey) return; // handled by box select
      if (id === undefined) { if (!e.originalEvent.ctrlKey && !e.originalEvent.metaKey) select([]); return; }
      if (e.originalEvent.ctrlKey || e.originalEvent.metaKey) toggleSel(id);
      else select([id]);
    } else if (E.tool === "core") {
      if (id === undefined) return toast("That chunk isn't in a territory");
      if (isLocked(id)) return toast("That territory is claimed and locked (see Options)", true);
      begin("Set core");
      touch(id);
      E.terr.get(id).core = [cx, cz];
      end();
      select([id]);
    } else if (E.tool === "fill") {
      fillFrom(cx, cz);
    }
  });

  function startStroke(cx, cz) {
    let target = null;
    if (E.tool === "paint") {
      if (E.sel.size !== 1) return toast("Select one territory to paint into, or use the New tool", true);
      target = [...E.sel][0];
      if (isLocked(target)) return toast("That territory is claimed and locked (see Options)", true);
    }
    begin(E.tool === "erase" ? "Erase" : E.tool === "new" ? "New territory" : "Paint");
    if (E.tool === "new") { const t = newTerritory(); target = t.id; E.sel.clear(); E.sel.add(target); }
    stroke = { target, last: null, frame: 0, dirty: new Set() };
    syncDragging();
    strokeTo(cx, cz);
  }
  function strokeTo(cx, cz) {
    const s = stroke;
    const pts = s.last ? line(s.last[0], s.last[1], cx, cz) : [[cx, cz]];
    s.last = [cx, cz];
    const erase = E.tool === "erase";
    const opts = { onlyWild: !erase && E.opt.wild, landOnly: !erase && E.opt.land };
    for (const [x, z] of pts) {
      for (const k of brushKeys(x, z, E.brush)) {
        const before = E.owner.get(k);
        if (assign(k, erase ? null : s.target, opts)) {
          s.dirty.add(erase ? before : s.target);
          if (before !== undefined) s.dirty.add(before);
        }
      }
    }
    if (!s.frame) s.frame = requestAnimationFrame(() => { s.frame = 0; const d = [...s.dirty]; s.dirty.clear(); redraw(d); });
  }
  function endStroke() {
    const s = stroke;
    stroke = null;
    if (s.frame) cancelAnimationFrame(s.frame);
    end();
    syncDragging();
  }
  function line(x0, z0, x1, z1) {
    const out = [];
    const dx = Math.abs(x1 - x0), dz = Math.abs(z1 - z0);
    const sx = x0 < x1 ? 1 : -1, sz = z0 < z1 ? 1 : -1;
    let err = dx - dz, x = x0, z = z0;
    for (let i = 0; i < 4096; i++) {
      out.push([x, z]);
      if (x === x1 && z === z1) break;
      const e2 = 2 * err;
      if (e2 > -dz) { err -= dz; x += sx; }
      if (e2 < dx) { err += dx; z += sz; }
    }
    return out;
  }

  function finishRect(a, b, mode) {
    const x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
    const z0 = Math.min(a[1], b[1]), z1 = Math.max(a[1], b[1]);
    const area = (x1 - x0 + 1) * (z1 - z0 + 1);
    if (mode === "select") {
      const ids = new Set();
      if (area <= 400000) {
        for (let z = z0; z <= z1; z++) for (let x = x0; x <= x1; x++) { const id = E.owner.get(K(x, z)); if (id !== undefined) ids.add(id); }
      } else {
        for (const t of E.terr.values()) if (t.core && t.core[0] >= x0 && t.core[0] <= x1 && t.core[1] >= z0 && t.core[1] <= z1) ids.add(t.id);
      }
      select([...new Set([...E.sel, ...ids])]);
      return;
    }
    if (area > 250000) return toast("That box is too big (max 250,000 chunks)", true);
    let target = E.sel.size === 1 ? [...E.sel][0] : null;
    if (target != null && isLocked(target)) return toast("That territory is claimed and locked (see Options)", true);
    begin("Box fill");
    if (target == null) { target = newTerritory().id; }
    let n = 0;
    for (let z = z0; z <= z1; z++) for (let x = x0; x <= x1; x++) if (assign(K(x, z), target, { onlyWild: E.opt.wild, landOnly: E.opt.land })) n++;
    end();
    if (E.terr.has(target)) select([target]);
    toast(n ? `Filled ${n} chunks` : "Nothing to fill there (water, or already claimed by other territories)");
  }

  function fillFrom(cx, cz) {
    const start = K(cx, cz);
    if (E.owner.has(start)) return toast("Fill starts on empty land. Click outside any territory.", true);
    if (!E.opt.land) return toast("Turn on “Land only” in Options to use Fill", true);
    if (isLand(start) !== true) return toast(isLand(start) === null ? "Terrain is still loading, try again in a moment" : "That's water. Fill only spreads over land.", true);
    const limit = 60000;
    const seen = new Set([start]);
    const q = [start];
    let unknown = 0;
    for (let i = 0; i < q.length; i++) {
      if (q.length > limit) return toast("That area is too large (over 60,000 chunks). Use Box or New instead.", true);
      const x = KX(q[i]), z = KZ(q[i]);
      for (const n of [K(x + 1, z), K(x - 1, z), K(x, z + 1), K(x, z - 1)]) {
        if (seen.has(n) || E.owner.has(n)) continue;
        seen.add(n);
        const l = isLand(n);
        if (l === null) { unknown++; continue; }
        if (!l) continue;
        q.push(n);
      }
    }
    let target = E.sel.size === 1 && !isLocked([...E.sel][0]) ? [...E.sel][0] : null;
    begin("Fill");
    if (target == null) target = newTerritory().id;
    for (const k of q) assign(k, target, { onlyWild: true });
    end();
    select([target]);
    toast(`Filled ${q.length} chunks${unknown ? " (zoom out and fill again if part of the area hadn't loaded)" : ""}`);
  }

  // ---------------------------------------------------------------- selection + bulk actions
  function select(ids) {
    E.sel = new Set(ids.filter((id) => E.terr.has(id)));
    restyle();
    drawOverlays();
    refreshUi();
  }
  function toggleSel(id) {
    if (E.sel.has(id)) E.sel.delete(id); else E.sel.add(id);
    select([...E.sel]);
  }

  function lockedInSelection() { return [...E.sel].filter(isLocked); }

  function mergeSelected() {
    const ids = [...E.sel];
    if (ids.length < 2) return;
    if (lockedInSelection().length) return toast("Claimed territories are locked. Turn off the lock in Options to merge them.", true);
    const into = ids.reduce((a, b) => (E.terr.get(b).chunks.size > E.terr.get(a).chunks.size ? b : a));
    begin("Merge");
    const t = E.terr.get(into);
    touch(into);
    for (const id of ids) {
      if (id === into) continue;
      const o = E.terr.get(id);
      touch(id);
      for (const k of [...o.chunks]) { o.chunks.delete(k); t.chunks.add(k); E.owner.set(k, into); }
      for (const n of o.nodes) if (!t.nodes.includes(n)) t.nodes.push(n);
      if (!t.name && o.name) t.name = o.name;
    }
    end();
    select([into]);
    toast(`Merged ${ids.length} territories into #${into}`);
  }

  function deleteSelected() {
    const ids = [...E.sel];
    if (!ids.length) return;
    const locked = lockedInSelection();
    if (locked.length) return toast(`#${locked.join(", #")} ${locked.length > 1 ? "are" : "is"} claimed by a town and can't be deleted`, true);
    begin(ids.length > 1 ? `Delete ${ids.length} territories` : "Delete territory");
    for (const id of ids) {
      const t = E.terr.get(id);
      touch(id);
      for (const k of t.chunks) E.owner.delete(k);
      t.chunks.clear();
    }
    end();
    select([]);
  }

  function splitDisconnected() {
    const ids = [...E.sel];
    begin("Split pieces");
    let made = 0;
    const newSel = [];
    for (const id of ids) {
      if (isLocked(id)) continue;
      const t = E.terr.get(id);
      const comps = components(t.chunks).sort((a, b) => b.length - a.length);
      newSel.push(id);
      for (const comp of comps.slice(1)) {
        const nt = newTerritory();
        for (const k of comp) assign(k, nt.id);
        newSel.push(nt.id);
        made++;
      }
    }
    end();
    select(newSel);
    toast(made ? `Split off ${made} separate pieces into new territories` : "Those territories are already in one piece");
  }

  /**
   * Split the selected territories into random territories of about `avg`
   * chunks: k-means (Lloyd) on chunk centers, then every territory is made
   * one connected piece and tiny leftovers are merged into a neighbor.
   */
  function generate({ avg, seed, iterations, mergeBelow }) {
    const ids = [...E.sel];
    if (!ids.length) return;
    if (lockedInSelection().length) return toast("Claimed territories are locked and can't be regenerated", true);
    const cells = [];
    for (const id of ids) for (const k of E.terr.get(id).chunks) cells.push(k);
    if (cells.length < 2) return;
    const n = Math.max(1, Math.round(cells.length / avg));
    const rnd = mulberry32(seed);
    const xs = new Float64Array(cells.length), zs = new Float64Array(cells.length);
    cells.forEach((k, i) => { xs[i] = KX(k); zs[i] = KZ(k); });
    // pick spread-out starting centers (k-means++ lite)
    let cxs = [], czs = [];
    const first = Math.floor(rnd() * cells.length);
    cxs.push(xs[first]); czs.push(zs[first]);
    const dist = new Float64Array(cells.length).fill(Infinity);
    while (cxs.length < n) {
      const li = cxs.length - 1;
      let total = 0;
      for (let i = 0; i < cells.length; i++) {
        const d = (xs[i] - cxs[li]) ** 2 + (zs[i] - czs[li]) ** 2;
        if (d < dist[i]) dist[i] = d;
        total += dist[i];
      }
      let r = rnd() * total, pick = 0;
      for (let i = 0; i < cells.length; i++) { r -= dist[i]; if (r <= 0) { pick = i; break; } }
      cxs.push(xs[pick]); czs.push(zs[pick]);
    }
    // bucket grid to keep assignment fast for big areas
    const assignTo = new Int32Array(cells.length);
    const runAssign = () => {
      const cell = Math.max(4, Math.sqrt(avg) * 2);
      const grid = new Map();
      for (let j = 0; j < n; j++) {
        const g = Math.floor(cxs[j] / cell) + "," + Math.floor(czs[j] / cell);
        if (!grid.has(g)) grid.set(g, []);
        grid.get(g).push(j);
      }
      for (let i = 0; i < cells.length; i++) {
        const gx = Math.floor(xs[i] / cell), gz = Math.floor(zs[i] / cell);
        let best = -1, bd = Infinity;
        for (let ring = 0; ring < 50 && (best < 0 || ring < 3); ring++) {
          for (let dz = -ring; dz <= ring; dz++) for (let dx = -ring; dx <= ring; dx++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) !== ring) continue;
            const arr = grid.get((gx + dx) + "," + (gz + dz));
            if (!arr) continue;
            for (const j of arr) { const d = (xs[i] - cxs[j]) ** 2 + (zs[i] - czs[j]) ** 2; if (d < bd) { bd = d; best = j; } }
          }
        }
        if (best < 0) for (let j = 0; j < n; j++) { const d = (xs[i] - cxs[j]) ** 2 + (zs[i] - czs[j]) ** 2; if (d < bd) { bd = d; best = j; } }
        assignTo[i] = best;
      }
    };
    for (let it = 0; it <= iterations; it++) {
      runAssign();
      if (it === iterations) break;
      const sx = new Float64Array(n), sz = new Float64Array(n), c = new Int32Array(n);
      for (let i = 0; i < cells.length; i++) { const j = assignTo[i]; sx[j] += xs[i]; sz[j] += zs[i]; c[j]++; }
      for (let j = 0; j < n; j++) if (c[j]) { cxs[j] = sx[j] / c[j]; czs[j] = sz[j] / c[j]; }
    }
    // groups -> connected pieces; stray pieces join the neighbor they touch most
    const groupOf = new Map();
    cells.forEach((k, i) => groupOf.set(k, assignTo[i]));
    const groups = new Map();
    for (const [k, g] of groupOf) { if (!groups.has(g)) groups.set(g, new Set()); groups.get(g).add(k); }
    const reassignPieces = () => {
      let moved = false;
      for (const [g, set] of [...groups]) {
        const comps = components(set).sort((a, b) => b.length - a.length);
        for (const comp of comps.slice(1)) {
          const votes = new Map();
          for (const k of comp) {
            const x = KX(k), z = KZ(k);
            for (const nk of [K(x + 1, z), K(x - 1, z), K(x, z + 1), K(x, z - 1)]) {
              const ng = groupOf.get(nk);
              if (ng !== undefined && ng !== g) votes.set(ng, (votes.get(ng) || 0) + 1);
            }
          }
          if (!votes.size) continue;
          const to = [...votes].sort((a, b) => b[1] - a[1])[0][0];
          for (const k of comp) { set.delete(k); groups.get(to).add(k); groupOf.set(k, to); }
          moved = true;
        }
      }
      return moved;
    };
    for (let i = 0; i < 4 && reassignPieces(); i++) { /* repeat until stable */ }
    if (mergeBelow > 0) {
      for (const [g, set] of [...groups].sort((a, b) => a[1].size - b[1].size)) {
        if (!groups.has(g) || set.size >= mergeBelow || groups.size <= 1) continue;
        const votes = new Map();
        for (const k of set) {
          const x = KX(k), z = KZ(k);
          for (const nk of [K(x + 1, z), K(x - 1, z), K(x, z + 1), K(x, z - 1)]) {
            const ng = groupOf.get(nk);
            if (ng !== undefined && ng !== g) votes.set(ng, (votes.get(ng) || 0) + 1);
          }
        }
        if (!votes.size) continue;
        const to = [...votes].sort((a, b) => groups.get(a[0]).size - groups.get(b[0]).size)[0][0];
        for (const k of set) { groups.get(to).add(k); groupOf.set(k, to); }
        groups.delete(g);
      }
    }
    begin("Generate territories");
    for (const id of ids) {
      const t = E.terr.get(id);
      touch(id);
      for (const k of t.chunks) E.owner.delete(k);
      t.chunks.clear();
    }
    const created = [];
    for (const set of groups.values()) {
      if (!set.size) continue;
      const t = newTerritory();
      for (const k of set) assign(k, t.id);
      created.push(t.id);
    }
    end();
    select(created);
    toast(`Made ${created.length} territories (about ${Math.round(cells.length / Math.max(1, created.length))} chunks each)`);
  }

  function distribute(resource, count) {
    const ids = [...E.sel].filter((id) => !isLocked(id) && !E.terr.get(id).nodes.includes(resource));
    if (!ids.length) return toast("Every selected territory already has it (or is locked)", true);
    const rnd = mulberry32((Date.now() & 0xffff) + ids.length);
    for (let i = ids.length - 1; i > 0; i--) { const j = Math.floor(rnd() * (i + 1)); [ids[i], ids[j]] = [ids[j], ids[i]]; }
    const pick = ids.slice(0, Math.min(count, ids.length));
    begin(`Add ${resource}`);
    for (const id of pick) { touch(id); E.terr.get(id).nodes.push(resource); }
    end();
    toast(`Added ${resource} to ${pick.length} territories`);
  }

  // ---------------------------------------------------------------- serialize
  function serialize() {
    const territories = {};
    const ids = [...E.terr.keys()].sort((a, b) => a - b);
    for (const id of ids) {
      const t = E.terr.get(id);
      if (!t.chunks.size) continue;
      fixCore(t);
      const { neighbors, edge } = neighborsOf(t);
      const chunks = [...t.chunks].sort((a, b) => KZ(a) - KZ(b) || KX(a) - KX(b));
      const flat = new Array(chunks.length * 2);
      chunks.forEach((k, i) => { flat[i * 2] = KX(k); flat[i * 2 + 1] = KZ(k); });
      territories[id] = { ...t.extra, name: t.name, color: ((t.color % 6) + 6) % 6, coreChunk: t.core, chunks: flat, size: chunks.length, neighbors: [...neighbors].sort((a, b) => a - b), isEdge: edge, nodes: t.nodes };
    }
    return { meta: { type: "world" }, ...E.top, nodes: E.resources, territories };
  }

  /** browser-side problems worth showing before the server check */
  function localIssues() {
    const out = [];
    for (const t of E.terr.values()) {
      const pieces = components(t.chunks).length;
      if (pieces > 1) out.push(`#${t.id} ${t.name ? `"${t.name}" ` : ""}is split into ${pieces} separate pieces`);
      for (const n of t.nodes) if (!E.resources[n]) out.push(`#${t.id} uses resource "${n}" which doesn't exist`);
    }
    return out;
  }

  async function check(andApply) {
    if (!E.token || !E.nodesOnline) return toast("Not connected to the server. Use Download instead.", true);
    const body = JSON.stringify(serialize());
    const local = localIssues();
    let res;
    try {
      busy(true);
      res = await api("api/editor/check", { method: "POST", body, headers: { "Content-Type": "application/json" } });
    } catch (e) {
      busy(false);
      return toast(e.message, true);
    }
    busy(false);
    const stats = res.added != null ? `<div class="stats"><div><b>${res.added}</b><span>new</span></div><div><b>${res.changed}</b><span>changed</span></div><div><b>${res.removed}</b><span>removed</span></div></div>` : "";
    const errs = res.errors.length ? `<div class="note err"><b>Can't apply yet</b><ul>${res.errors.map((x) => `<li>${esc(x)}</li>`).join("")}</ul></div>` : "";
    const warns = res.warnings.length ? `<div class="note warn"><b>Claimed land changes</b><ul>${res.warnings.map((x) => `<li>${esc(x)}</li>`).join("")}</ul>Town claim costs are recalculated automatically.</div>` : "";
    const lw = local.length ? `<div class="note warn"><b>Worth a look</b><ul>${local.slice(0, 12).map((x) => `<li>${esc(x)}</li>`).join("")}${local.length > 12 ? `<li>…and ${local.length - 12} more</li>` : ""}</ul></div>` : "";
    const okLine = !res.errors.length && !res.warnings.length ? `<div class="note ok">Everything checks out.</div>` : "";
    const canApply = res.ok && !res.errors.length;
    modal(`<h2>${andApply ? "Apply to server" : "Check"}</h2>
      <p>${andApply ? "This replaces the server's world.json and reloads territories immediately. The current file is backed up first." : "Nothing has been changed on the server."}</p>
      ${stats}${errs}${warns}${lw}${okLine}
      ${canApply && res.warnings.length ? `<label class="tgl"><input type="checkbox" id="ack">I understand claimed territories will change</label>` : ""}
      <div class="foot"><button class="btn" data-close>Close</button>${canApply ? `<button class="btn primary" id="do-apply">${andApply ? "Apply now" : "Apply to server…"}</button>` : ""}</div>`);
    const btn = $("#do-apply");
    if (btn) {
      const ack = $("#ack");
      if (ack) { btn.disabled = true; ack.onchange = () => (btn.disabled = !ack.checked); }
      btn.onclick = () => apply(body, !!ack);
    }
  }

  async function apply(body, force) {
    let res;
    try {
      busy(true);
      res = await api("api/editor/apply" + (force ? "?force=1" : ""), { method: "POST", body, headers: { "Content-Type": "application/json" } });
    } catch (e) {
      busy(false);
      return toast(e.message, true);
    }
    busy(false);
    if (res.applied) {
      E.savedVersion = E.version;
      closeModal();
      setSaveState();
      toast(`Applied on the server. Backup saved as ${res.backup || "a backup file"}.`);
      await loadClaims();
      drawOverlays();
    } else {
      modal(`<h2>Not applied</h2><div class="note err"><ul>${res.errors.map((x) => `<li>${esc(x)}</li>`).join("")}</ul></div><div class="foot"><button class="btn" data-close>Close</button></div>`);
    }
  }

  function download() {
    const blob = new Blob([JSON.stringify(serialize())], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "world.json";
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  }

  // ---------------------------------------------------------------- UI: lists + inspector
  function setSaveState() {
    const el = $("#save-state");
    const dirty = E.version !== E.savedVersion;
    el.className = "pill " + (dirty ? "dirty" : "ok");
    el.textContent = !E.token ? "Offline" : dirty ? "Unsaved changes" : E.nodesOnline ? "In sync with server" : "No changes";
    $("#btn-apply").disabled = !E.token || !E.nodesOnline;
    $("#btn-check").disabled = !E.token || !E.nodesOnline;
    $("#undo").disabled = !E.undo.length;
    $("#redo").disabled = !E.redo.length;
    $("#undo").title = E.undo.length ? `Undo ${E.undo[E.undo.length - 1].label} (Ctrl+Z)` : "Undo (Ctrl+Z)";
  }

  let listTimer = 0;
  function refreshUi() {
    setSaveState();
    renderInspector();
    clearTimeout(listTimer);
    listTimer = setTimeout(() => { renderTerrList(); renderResList(); }, 60);
    let chunks = 0;
    for (const t of E.terr.values()) chunks += t.chunks.size;
    $("#counts").textContent = `${E.terr.size} territories · ${chunks.toLocaleString()} chunks`;
  }

  function renderTerrList() {
    const q = $("#terr-filter").value.trim().toLowerCase();
    const sort = $("#terr-sort").value;
    let list = [...E.terr.values()];
    if (q) list = list.filter((t) => String(t.id) === q || t.name.toLowerCase().includes(q) || t.nodes.some((n) => n.toLowerCase().includes(q)) || (E.claimed.get(t.id)?.town || "").toLowerCase().includes(q));
    const pieces = new Map(list.map((t) => [t.id, components(t.chunks).length]));
    const issues = (t) => (pieces.get(t.id) > 1 ? 1 : 0) + t.nodes.filter((n) => !E.resources[n]).length;
    const sorters = { id: (a, b) => a.id - b.id, size: (a, b) => b.chunks.size - a.chunks.size, name: (a, b) => (a.name || "~").localeCompare(b.name || "~"), issues: (a, b) => issues(b) - issues(a) || a.id - b.id };
    list.sort(sorters[sort] || sorters.id);
    const shown = list.slice(0, 400);
    $("#terr-summary").textContent = q ? `${list.length} match${list.length === 1 ? "" : "es"}` : "";
    $("#terr-list").innerHTML = shown.map((t) => {
      const c = E.claimed.get(t.id);
      const icons = t.nodes.slice(0, 5).map((n) => { const u = iconUrl(n); return u ? `<img src="${esc(u)}" alt="" title="${esc(n)}">` : `<span>${esc(n)}</span>`; }).join("");
      const split = pieces.get(t.id) > 1 ? `<span class="warn" title="split into separate pieces">⚠</span>` : "";
      return `<div class="trow ${E.sel.has(t.id) ? "sel" : ""}" data-id="${t.id}"><span class="sw" style="background:${PALETTE[t.color % 6] || "#777"}"></span><div><div class="nm">${c ? `<span class="lock" title="claimed by ${esc(c.town)}">🔒</span> ` : ""}${esc(t.name || "Unnamed")} <span style="color:var(--faint);font-weight:400">#${t.id}</span> ${split}</div><div class="sub">${c ? esc(c.town) + (icons ? " · " : "") : ""}${icons}</div></div><span class="stat">${t.chunks.size}</span></div>`;
    }).join("") + (list.length > shown.length ? `<div class="hint">…${list.length - shown.length} more, search to narrow down</div>` : "") + (!list.length ? `<div class="empty">${E.terr.size ? "Nothing matches" : "No territories yet. Use New (N) to paint one."}</div>` : "");
  }

  function resCount(name) { let n = 0; for (const t of E.terr.values()) if (t.nodes.includes(name)) n++; return n; }

  let resSel = null;
  function renderResList() {
    const q = $("#res-filter").value.trim().toLowerCase();
    const names = Object.keys(E.resources).filter((n) => !q || n.toLowerCase().includes(q)).sort((a, b) => a.localeCompare(b));
    $("#res-list").innerHTML = names.map((n) => {
      const u = iconUrl(n);
      return `<div class="trow ${n === resSel ? "sel" : ""}" data-res="${esc(n)}">${u ? `<img src="${esc(u)}" alt="" style="width:16px;height:16px;image-rendering:pixelated">` : `<span class="sw" style="background:#555"></span>`}<div class="nm">${esc(n)}</div><span class="stat">${resCount(n)}</span></div>`;
    }).join("") || `<div class="empty">No resource nodes yet</div>`;
    renderResEditor();
  }

  function renderResEditor() {
    const el = $("#res-editor");
    const r = resSel && E.resources[resSel];
    if (!r) { el.innerHTML = ""; return; }
    const iconNames = Object.keys(E.icons).sort();
    const u = iconUrl(resSel);
    const json = JSON.stringify(r, null, 2);
    el.innerHTML = `
      <div class="field"><span>Name</span><input id="re-name" value="${esc(resSel)}"></div>
      <div class="field"><span>Icon</span><div class="inline">${u ? `<img src="${esc(u)}" alt="">` : ""}<input id="re-icon" list="icon-list" value="${esc(r.icon ?? "")}" placeholder="icon name"></div>
        <datalist id="icon-list">${iconNames.map((n) => `<option value="${esc(n)}">`).join("")}</datalist></div>
      <div class="grid2" style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:0 8px">
        <div class="field"><span>Cost +</span><input id="re-cc" type="number" step="1" value="${r.cost?.constant ?? 0}"></div>
        <div class="field"><span>Cost ×</span><input id="re-cs" type="number" step="0.05" value="${r.cost?.scale ?? 1}"></div>
        <div class="field"><span>Priority</span><input id="re-pr" type="number" step="1" value="${r.priority ?? 0}"></div>
      </div>
      <div class="field"><span>All settings (JSON: income, ore, crops, animals…)</span><textarea id="re-json" spellcheck="false">${esc(json)}</textarea></div>
      <div id="re-err"></div>
      <div class="actions"><button class="btn small primary" id="re-save">Save resource</button><button class="btn small" id="re-select">Select territories with it</button><button class="btn small danger" id="re-del">Delete</button></div>`;
    const syncJson = () => {
      try {
        const o = JSON.parse($("#re-json").value);
        o.icon = $("#re-icon").value || null;
        o.cost = { ...(o.cost || {}), constant: +$("#re-cc").value || 0, scale: $("#re-cs").value === "" ? 1 : +$("#re-cs").value };
        o.priority = +$("#re-pr").value || 0;
        $("#re-json").value = JSON.stringify(o, null, 2);
      } catch { /* user is mid-edit in the JSON box */ }
    };
    for (const id of ["#re-icon", "#re-cc", "#re-cs", "#re-pr"]) $(id).addEventListener("change", syncJson);
    $("#re-save").onclick = () => {
      let obj;
      try { obj = JSON.parse($("#re-json").value); } catch (e) { $("#re-err").innerHTML = `<div class="note err">That JSON isn't valid: ${esc(e.message)}</div>`; return; }
      if (!obj || typeof obj !== "object" || Array.isArray(obj)) { $("#re-err").innerHTML = `<div class="note err">Settings must be a JSON object</div>`; return; }
      const newName = $("#re-name").value.trim();
      if (!newName) return;
      if (newName !== resSel && E.resources[newName]) { $("#re-err").innerHTML = `<div class="note err">A resource named "${esc(newName)}" already exists</div>`; return; }
      begin(newName !== resSel ? "Rename resource" : "Edit resource");
      touchRes();
      if (newName !== resSel) {
        const out = {};
        for (const [k, v] of Object.entries(E.resources)) out[k === resSel ? newName : k] = k === resSel ? obj : v;
        E.resources = out;
        for (const t of E.terr.values()) {
          const i = t.nodes.indexOf(resSel);
          if (i >= 0) { touch(t.id); t.nodes[i] = newName; }
        }
        resSel = newName;
      } else {
        E.resources[resSel] = obj;
      }
      end();
      toast("Resource saved");
    };
    $("#re-select").onclick = () => select([...E.terr.values()].filter((t) => t.nodes.includes(resSel)).map((t) => t.id));
    $("#re-del").onclick = () => {
      const n = resCount(resSel);
      if (n && !confirm(`"${resSel}" is used by ${n} territories. Delete it and remove it from all of them?`)) return;
      begin("Delete resource");
      touchRes();
      for (const t of E.terr.values()) { const i = t.nodes.indexOf(resSel); if (i >= 0) { touch(t.id); t.nodes.splice(i, 1); } }
      delete E.resources[resSel];
      resSel = null;
      end();
    };
  }

  function renderInspector() {
    const el = $("#inspector");
    const ids = [...E.sel].filter((id) => E.terr.has(id));
    if (!ids.length) { el.hidden = true; return; }
    el.hidden = false;
    const resOptions = Object.keys(E.resources).sort().map((n) => `<option value="${esc(n)}">${esc(n)}</option>`).join("");
    if (ids.length === 1) {
      const t = E.terr.get(ids[0]);
      const c = E.claimed.get(t.id);
      const locked = isLocked(t.id);
      const { neighbors, edge } = neighborsOf(t);
      const pieces = components(t.chunks).length;
      el.innerHTML = `
        <div class="info-kicker">Territory #${t.id}</div>
        <div class="field"><span>Name</span><input id="in-name" value="${esc(t.name)}" placeholder="Unnamed"></div>
        ${c ? `<div class="note ${locked ? "warn" : ""}">${c.kind === "occupied" ? "Occupied" : "Claimed"} by <b>${esc(c.town)}</b>.${locked ? " Locked: its land and resources can't be changed (Options → Lock claimed)." : " Changes need confirming on apply."}</div>` : ""}
        ${pieces > 1 ? `<div class="note warn">This territory is in ${pieces} separate pieces. <a href="#" id="in-split">Split them up</a></div>` : ""}
        <dl class="kv">
          <dt>Chunks</dt><dd>${t.chunks.size}</dd>
          <dt>Claim cost</dt><dd>${terrCost(t)}</dd>
          <dt>Core chunk</dt><dd>${t.core ? t.core.join(", ") : "—"} <a href="#" id="in-core" title="Pick a new core on the map">change</a></dd>
          <dt>Neighbors</dt><dd>${neighbors.size}${edge ? " · borders wilderness" : ""}</dd>
        </dl>
        <div class="section-h">Resources</div>
        <div class="chips">${t.nodes.map((n) => { const u = iconUrl(n); return `<span class="chip plain">${u ? `<img src="${esc(u)}" alt="">` : ""}${esc(n)}${E.resources[n] ? "" : " ⚠"}${locked ? "" : `<span class="x" data-rm="${esc(n)}" title="Remove">×</span>`}</span>`; }).join("") || `<span class="hint" style="margin:0">None</span>`}</div>
        ${locked ? "" : `<div class="field" style="margin-top:8px"><select id="in-add"><option value="">Add a resource…</option>${resOptions}</select></div>`}
        <div class="actions">${locked ? "" : `<button class="btn small danger" id="in-del">Delete</button>`}<button class="btn small" id="in-zoom">Zoom to</button></div>`;
      const name = $("#in-name");
      name.onchange = () => { begin("Rename"); touch(t.id); t.name = name.value.trim(); end(); };
      $("#in-core").onclick = (e) => { e.preventDefault(); setTool("core"); toast("Click a chunk inside the territory to make it the core"); };
      $("#in-zoom").onclick = () => zoomTo([t.id]);
      if ($("#in-split")) $("#in-split").onclick = (e) => { e.preventDefault(); splitDisconnected(); };
      if (!locked) {
        $("#in-del").onclick = deleteSelected;
        $("#in-add").onchange = (e) => {
          const v = e.target.value;
          if (!v || t.nodes.includes(v)) return;
          begin("Add resource"); touch(t.id); t.nodes.push(v); end();
        };
        $$("[data-rm]", el).forEach((x) => (x.onclick = () => { begin("Remove resource"); touch(t.id); t.nodes = t.nodes.filter((n) => n !== x.dataset.rm); end(); }));
      }
      return;
    }
    let chunks = 0;
    for (const id of ids) chunks += E.terr.get(id).chunks.size;
    const locked = ids.filter(isLocked);
    el.innerHTML = `
      <div class="info-kicker">Selection</div>
      <h2 class="info-title">${ids.length} territories</h2>
      <div class="info-sub">${chunks.toLocaleString()} chunks${locked.length ? ` · ${locked.length} locked` : ""}</div>
      <div class="actions">
        <button class="btn small" id="ms-merge">Merge into one</button>
        <button class="btn small" id="ms-gen">Regenerate…</button>
        <button class="btn small" id="ms-dist">Add resource…</button>
        <button class="btn small" id="ms-split">Split pieces</button>
        <button class="btn small" id="ms-zoom">Zoom to</button>
        <button class="btn small danger" id="ms-del">Delete all</button>
      </div>
      <p class="hint">Ctrl/⌘-click toggles a territory. Shift+drag adds a box of territories.</p>`;
    $("#ms-merge").onclick = mergeSelected;
    $("#ms-del").onclick = deleteSelected;
    $("#ms-split").onclick = splitDisconnected;
    $("#ms-zoom").onclick = () => zoomTo(ids);
    $("#ms-gen").onclick = () => generateDialog();
    $("#ms-dist").onclick = () => distributeDialog();
  }

  function zoomTo(ids) {
    const b = L.latLngBounds([]);
    for (const id of ids) { const t = E.terr.get(id); if (!t) continue; for (const k of t.chunks) { b.extend(ll(KX(k) * 16, KZ(k) * 16)); } }
    if (b.isValid()) map.fitBounds(b.pad(0.2), { maxZoom: 0 });
  }

  function generateDialog() {
    const chunks = [...E.sel].reduce((a, id) => a + E.terr.get(id).chunks.size, 0);
    const p = store.get("gen", { avg: 120, seed: 1, iterations: 4, mergeBelow: 30 });
    modal(`<h2>Regenerate territories</h2>
      <p>Replaces the ${E.sel.size} selected territories (${chunks.toLocaleString()} chunks) with new random ones. Resources on them are cleared. You can undo this.</p>
      <div class="grid2">
        <label class="field"><span>Average size (chunks)</span><input id="g-avg" type="number" min="4" value="${p.avg}"></label>
        <label class="field"><span>Random seed</span><input id="g-seed" type="number" value="${p.seed}"></label>
        <label class="field"><span>Smoothing passes</span><input id="g-it" type="number" min="0" max="20" value="${p.iterations}"></label>
        <label class="field"><span>Merge pieces smaller than</span><input id="g-merge" type="number" min="0" value="${p.mergeBelow}"></label>
      </div>
      <p class="hint" id="g-est"></p>
      <div class="foot"><button class="btn" data-close>Cancel</button><button class="btn primary" id="g-go">Generate</button></div>`);
    const est = () => { $("#g-est").textContent = `About ${Math.max(1, Math.round(chunks / Math.max(4, +$("#g-avg").value || 120)))} territories`; };
    $("#g-avg").oninput = est;
    est();
    $("#g-go").onclick = () => {
      const params = { avg: Math.max(4, +$("#g-avg").value || 120), seed: +$("#g-seed").value || 1, iterations: Math.max(0, Math.min(20, +$("#g-it").value || 0)), mergeBelow: Math.max(0, +$("#g-merge").value || 0) };
      store.set("gen", params);
      closeModal();
      setTimeout(() => generate(params), 20);
    };
  }

  function distributeDialog() {
    const names = Object.keys(E.resources).sort();
    if (!names.length) return toast("Create a resource first (Resources tab)", true);
    modal(`<h2>Add a resource</h2><p>Adds it to randomly chosen territories in the selection.</p>
      <div class="grid2">
        <label class="field"><span>Resource</span><select id="d-res">${names.map((n) => `<option>${esc(n)}</option>`).join("")}</select></label>
        <label class="field"><span>How many territories</span><input id="d-n" type="number" min="1" value="${Math.max(1, Math.round(E.sel.size / 5))}"></label>
      </div>
      <div class="foot"><button class="btn" data-close>Cancel</button><button class="btn primary" id="d-go">Add</button></div>`);
    $("#d-go").onclick = () => { const r = $("#d-res").value; const n = +$("#d-n").value || 1; closeModal(); distribute(r, n); };
  }

  // ---------------------------------------------------------------- toast / modal / busy
  let toastTimer = 0;
  function toast(msg, err = false) {
    const t = $("#toast");
    t.textContent = msg;
    t.className = err ? "err" : "";
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (t.hidden = true), err ? 5000 : 3000);
  }
  function modal(html) {
    $("#modal").innerHTML = html;
    $("#modal-wrap").hidden = false;
    $$("[data-close]", $("#modal")).forEach((b) => (b.onclick = closeModal));
  }
  function closeModal() { $("#modal-wrap").hidden = true; }
  function busy(on) { document.body.style.cursor = on ? "progress" : ""; $("#btn-apply").disabled = on || !E.nodesOnline; $("#btn-check").disabled = on || !E.nodesOnline; }

  // ---------------------------------------------------------------- wiring
  function buildUi() {
    $$("#tools [data-tool]").forEach((b) => (b.onclick = () => setTool(b.dataset.tool)));
    $("#brush-size").oninput = (e) => setBrush(+e.target.value);
    setBrush(E.brush);
    $("#undo").onclick = undo;
    $("#redo").onclick = redo;
    $("#btn-check").onclick = () => check(false);
    $("#btn-apply").onclick = () => check(true);
    $("#btn-menu").onclick = (e) => { e.stopPropagation(); $("#menu").hidden = !$("#menu").hidden; };
    document.addEventListener("click", () => ($("#menu").hidden = true));
    $$("#menu [data-act]").forEach((b) => (b.onclick = async () => {
      $("#menu").hidden = true;
      const a = b.dataset.act;
      if (a === "download") download();
      if (a === "import") $("#import-file").click();
      if (a === "reload") {
        if (E.version !== E.savedVersion && !confirm("Throw away your unsaved changes and reload from the server?")) return;
        try { await loadClaims(); await loadFromServer(false); setSaveState(); } catch (e) { toast(e.message, true); }
      }
      if (a === "help") helpDialog();
    }));
    $("#import-file").onchange = async (e) => {
      const f = e.target.files[0];
      e.target.value = "";
      if (!f) return;
      if (E.version !== E.savedVersion && !confirm("Replace your current edits with this file?")) return;
      try {
        const w = JSON.parse(await f.text());
        if (!w.territories) throw new Error("this doesn't look like a Nodes world.json");
        loadWorld(w);
        E.savedVersion = -1;
        fitWorld();
        setSaveState();
        toast(`Imported ${E.terr.size} territories. Apply to send them to the server.`);
      } catch (err) { toast("Couldn't import: " + err.message, true); }
    };
    $$("#tabs button").forEach((b) => (b.onclick = () => {
      $$("#tabs button").forEach((x) => x.classList.toggle("active", x === b));
      $$(".tab").forEach((t) => t.classList.toggle("active", t.id === "tab-" + b.dataset.tab));
    }));
    $("#terr-filter").oninput = renderTerrList;
    $("#terr-sort").onchange = renderTerrList;
    $("#res-filter").oninput = renderResList;
    $("#terr-list").onclick = (e) => {
      const row = e.target.closest("[data-id]");
      if (!row) return;
      const id = +row.dataset.id;
      if (e.ctrlKey || e.metaKey) toggleSel(id); else { select([id]); zoomTo([id]); }
    };
    $("#res-list").onclick = (e) => {
      const row = e.target.closest("[data-res]");
      if (!row) return;
      resSel = row.dataset.res;
      renderResList();
    };
    $("#res-new").onclick = () => {
      let n = "new_resource", i = 1;
      while (E.resources[n]) n = `new_resource_${i++}`;
      begin("New resource");
      touchRes();
      E.resources[n] = { icon: null, cost: { constant: 0, scale: 1.0 }, priority: 0, income: {}, ore: {}, crops: {}, animals: {} };
      end();
      resSel = n;
      renderResList();
    };
    const opts = { "#opt-land": "land", "#opt-wild": "wild", "#opt-lock": "lock", "#opt-labels": "labels", "#opt-icons": "icons", "#opt-owners": "owners", "#opt-grid": "grid" };
    for (const [sel, key] of Object.entries(opts)) {
      const el = $(sel);
      el.checked = !!E.opt[key];
      el.onchange = () => {
        E.opt[key] = el.checked;
        store.set("opt", E.opt);
        drawOverlays();
        preloadLand();
        refreshUi();
      };
    }
    $("#modal-wrap").addEventListener("mousedown", (e) => { if (e.target.id === "modal-wrap") closeModal(); });

    document.addEventListener("keydown", (e) => {
      const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName);
      if (e.key === "Escape") { closeModal(); if (!typing) select([]); return; }
      if (typing) return;
      const mod = e.ctrlKey || e.metaKey;
      if (mod && e.key.toLowerCase() === "z") { e.preventDefault(); if (e.shiftKey) redo(); else undo(); return; }
      if (mod && e.key.toLowerCase() === "y") { e.preventDefault(); redo(); return; }
      if (mod && e.key.toLowerCase() === "s") { e.preventDefault(); check(true); return; }
      if (mod && e.key.toLowerCase() === "a") { e.preventDefault(); select([...E.terr.keys()]); return; }
      if (mod) return;
      if (e.code === "Space") { if (!spaceDown) { spaceDown = true; syncDragging(); } e.preventDefault(); return; }
      if (e.key === "Shift") { shiftDown = true; syncDragging(); return; }
      const tools = { v: "select", b: "paint", n: "new", e: "erase", r: "rect", f: "fill", c: "core" };
      if (tools[e.key.toLowerCase()]) return setTool(tools[e.key.toLowerCase()]);
      if (e.key === "[") return setBrush(E.brush - 1);
      if (e.key === "]") return setBrush(E.brush + 1);
      if (e.key === "Delete" || e.key === "Backspace") return deleteSelected();
      if (e.key.toLowerCase() === "m") return mergeSelected();
    });
    document.addEventListener("keyup", (e) => {
      if (e.code === "Space") { spaceDown = false; syncDragging(); }
      if (e.key === "Shift") { shiftDown = false; syncDragging(); }
    });
    window.addEventListener("blur", () => { spaceDown = false; shiftDown = false; syncDragging(); });
    window.addEventListener("beforeunload", (e) => { if (E.version !== E.savedVersion) { e.preventDefault(); e.returnValue = ""; } });
    setTool("select");
  }

  function helpDialog() {
    modal(`<h2>Shortcuts</h2><div class="keys">
      <kbd>V</kbd><span>Select: click a territory. Ctrl/⌘-click to add, Shift+drag for a box.</span>
      <kbd>B</kbd><span>Paint chunks into the selected territory</span>
      <kbd>N</kbd><span>Paint a new territory (one per stroke)</span>
      <kbd>E</kbd><span>Erase chunks back to wilderness</span>
      <kbd>R</kbd><span>Drag a box to fill</span>
      <kbd>F</kbd><span>Fill a connected area of empty land (like an island)</span>
      <kbd>C</kbd><span>Set a territory's core chunk</span>
      <kbd>[ ]</kbd><span>Brush smaller / bigger</span>
      <kbd>Space</kbd><span>Hold and drag to pan while painting</span>
      <kbd>M</kbd><span>Merge selected</span>
      <kbd>Del</kbd><span>Delete selected</span>
      <kbd>Ctrl+Z</kbd><span>Undo (Ctrl+Shift+Z to redo)</span>
      <kbd>Ctrl+S</kbd><span>Check &amp; apply to server</span>
    </div>
    <p>Applying never deletes territories that towns own, and won't run during flag attacks or raids. The server backs up world.json every time.</p>
    <div class="foot"><button class="btn" data-close>Got it</button></div>`);
  }

  // handy from the browser console, e.g. NodesEditor.serialize()
  window.NodesEditor = { state: E, map, serialize, select, undo, redo };

  init().catch((e) => { console.error(e); toast("Editor failed to load: " + e.message, true); });
})();
