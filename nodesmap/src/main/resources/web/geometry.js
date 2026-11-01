/*
 * Chunk-set geometry. Runs in the browser so the server never builds polygons.
 *
 * outline(cells) turns a set of chunk cells into closed rings of block
 * coordinates (outer borders and holes). Rings are meant to be drawn with
 * fill-rule evenodd, which Leaflet's SVG renderer uses by default.
 */
(function (root) {
  "use strict";

  const K = (x, z) => x * 0x400000 + z; // fast numeric key, chunk coords fit easily

  /** @param cells Array<[cx, cz]> or flat [x1,z1,x2,z2,...] */
  function toSet(cells) {
    const set = new Set();
    if (cells.length && typeof cells[0] === "number") {
      for (let i = 0; i < cells.length; i += 2) set.add(K(cells[i], cells[i + 1]));
    } else {
      for (const c of cells) set.add(K(c[0], c[1]));
    }
    return set;
  }

  function cellsOf(cells) {
    if (cells.length && typeof cells[0] === "number") {
      const out = [];
      for (let i = 0; i < cells.length; i += 2) out.push([cells[i], cells[i + 1]]);
      return out;
    }
    return cells;
  }

  /**
   * Returns rings as arrays of [x, z] block corners (chunk * size).
   * Edges are directed clockwise around filled cells (screen coords: z down),
   * so walking "next edge from this vertex" traces each ring.
   * Collinear points are removed.
   */
  function outline(cells, size) {
    size = size || 16;
    const list = cellsOf(cells);
    const set = toSet(list);
    const has = (x, z) => set.has(K(x, z));
    // vertex key -> list of outgoing edges [toX, toZ]
    const out = new Map();
    const add = (ax, az, bx, bz) => {
      const k = K(ax, az);
      let a = out.get(k);
      if (!a) out.set(k, (a = []));
      a.push([ax, az, bx, bz]);
    };
    for (const [x, z] of list) {
      if (!has(x, z - 1)) add(x, z, x + 1, z);         // top: left -> right
      if (!has(x + 1, z)) add(x + 1, z, x + 1, z + 1); // right: down
      if (!has(x, z + 1)) add(x + 1, z + 1, x, z + 1); // bottom: right -> left
      if (!has(x - 1, z)) add(x, z + 1, x, z);         // left: up
    }
    const rings = [];
    for (const [, edges] of out) {
      while (edges.length) {
        const first = edges.pop();
        const ring = [[first[0], first[1]]];
        let cur = first;
        let guard = 0;
        while (guard++ < 1e6) {
          const nx = cur[2], nz = cur[3];
          if (nx === first[0] && nz === first[1]) break;
          ring.push([nx, nz]);
          const cand = out.get(K(nx, nz));
          if (!cand || !cand.length) break;
          // at a pinch vertex (two cells touching at a corner) prefer turning right,
          // which keeps diagonal-touching cells as separate rings
          let pick = 0;
          if (cand.length > 1) {
            const dx = cur[2] - cur[0], dz = cur[3] - cur[1];
            const rx = -dz, rz = dx; // right turn in z-down coords
            for (let i = 0; i < cand.length; i++) {
              const ex = cand[i][2] - cand[i][0], ez = cand[i][3] - cand[i][1];
              if (ex === rx && ez === rz) { pick = i; break; }
            }
          }
          cur = cand.splice(pick, 1)[0];
        }
        rings.push(simplify(ring).map(([x, z]) => [x * size, z * size]));
      }
    }
    return rings;
  }

  function simplify(ring) {
    if (ring.length < 4) return ring;
    const res = [];
    const n = ring.length;
    for (let i = 0; i < n; i++) {
      const a = ring[(i - 1 + n) % n], b = ring[i], c = ring[(i + 1) % n];
      const collinear = (a[0] === b[0] && b[0] === c[0]) || (a[1] === b[1] && b[1] === c[1]);
      if (!collinear) res.push(b);
    }
    return res;
  }

  /** a cell inside the set nearest its centroid, good for labels: returns [cx, cz] */
  function labelCell(cells) {
    const list = cellsOf(cells);
    if (!list.length) return null;
    let sx = 0, sz = 0;
    for (const [x, z] of list) { sx += x; sz += z; }
    const mx = sx / list.length, mz = sz / list.length;
    let best = list[0], bd = Infinity;
    for (const c of list) {
      const d = (c[0] - mx) ** 2 + (c[1] - mz) ** 2;
      if (d < bd) { bd = d; best = c; }
    }
    return best;
  }

  /**
   * The chunk deepest inside a shape: the one furthest (in chunk steps) from
   * any border, so it's as hard as possible to reach from outside. Ties go to
   * the chunk nearest the shape's average position.
   */
  function centerCell(cells) {
    const list = cellsOf(cells);
    if (!list.length) return null;
    const idx = new Map();
    list.forEach(([x, z], i) => idx.set(K(x, z), i));
    const dist = new Int32Array(list.length).fill(-1);
    const queue = [];
    const N4 = [[1, 0], [-1, 0], [0, 1], [0, -1]];
    const N8 = [...N4, [1, 1], [1, -1], [-1, 1], [-1, -1]];
    list.forEach(([x, z], i) => {
      if (N4.some(([dx, dz]) => !idx.has(K(x + dx, z + dz)))) { dist[i] = 0; queue.push(i); }
    });
    for (let q = 0; q < queue.length; q++) {
      const i = queue[q];
      const [x, z] = list[i];
      for (const [dx, dz] of N8) {
        const j = idx.get(K(x + dx, z + dz));
        if (j !== undefined && dist[j] < 0) { dist[j] = dist[i] + 1; queue.push(j); }
      }
    }
    let sx = 0, sz = 0;
    for (const [x, z] of list) { sx += x; sz += z; }
    const mx = sx / list.length, mz = sz / list.length;
    let best = 0, bestD = -1, bestC = Infinity;
    for (let i = 0; i < list.length; i++) {
      const c = (list[i][0] - mx) ** 2 + (list[i][1] - mz) ** 2;
      if (dist[i] > bestD || (dist[i] === bestD && c < bestC)) { best = i; bestD = dist[i]; bestC = c; }
    }
    return list[best];
  }

  root.NodesGeom = { outline, labelCell, centerCell, toSet, cellsOf, key: K };
  if (typeof module !== "undefined") module.exports = root.NodesGeom;
})(typeof window !== "undefined" ? window : globalThis);
