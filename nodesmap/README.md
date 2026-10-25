# NodesMap

A lightweight live web map built specifically for Nodes. It replaces Dynmap + the old Nodes Dynmap extension.

- **Terrain renderer**: draws the world top-down into tiles, with water depth and hill shading.
- **Nodes layer**: territories, towns, nations, war, warzones, raids, supply lines, forts, outposts, ports, mining towers, refineries and supply drops, all updating live without a page refresh.
- **Built-in web server**: no Dynmap, no nginx needed (though nginx works too).

## Install

1. Build both plugins (see *Building*), then put `nodes-*.jar` and `nodesmap-*.jar` in `plugins/`.
2. Start the server. Open `http://<server-ip>:8100/`.
3. Run `/nodesmap fullrender` once to draw the existing world. After that, the map keeps itself up to date.

Nodes detects NodesMap automatically and starts exporting map data. You can remove Dynmap.

## Map editor

A full editor for `world.json` (territories and resource nodes), drawn on top of your real terrain.

1. Run `/nodesmap editor` in game (permission `nodesmap.editor`, op by default) and click the link. The link logs you in for 2 hours (`editor.session-minutes`). `/nodesmap editor revoke` ends your sessions early.
2. Edit:
   - **Select (V):** click territories. Ctrl/⌘-click adds to the selection, Shift+drag box-selects.
   - **Paint (B), New (N), Erase (E):** brush painting, with `[` and `]` changing the brush size.
   - **Box (R):** drag a rectangle to fill.
   - **Fill (F):** fills a whole island or valley in one click.
   - **Core (C):** sets a territory's core chunk.
   - **Selection actions:** merge, delete, split disconnected pieces, regenerate into random territories of a chosen size, and add a resource to random territories.
   - **Resources tab:** create, rename and delete resource nodes, pick icons, and set cost, priority and the full JSON (income, ore, crops, animals, neighbor modifiers).
   - **Undo/redo** for everything (Ctrl+Z / Ctrl+Shift+Z).
3. **Check** shows what would change. **Apply to server** writes it live: Nodes backs up the old `world.json` to `plugins/nodes/backup/`, reloads territories, keeps occupation state, and recalculates town claims.

Safety built into the server side (the browser can't bypass it):
- Territories owned or occupied by a town can never be deleted.
- Changes to claimed land need an explicit confirm.
- Nothing applies while flag attacks or raids are running.
- Cores must sit inside their territory, chunks can't overlap, and resources must exist.

In the editor, "Lock claimed territories" (on by default) also stops you from painting over claimed land at all.

"Land only" (on by default) reads the rendered tiles so painting and filling skip water and ungenerated chunks. You can also import, edit and download a `world.json` without being connected to a server.

Set `web.public-url` if players reach the map through a domain or reverse proxy, so the editor link points there. Editing over plain HTTP sends the session token unencrypted, so use HTTPS (a reverse proxy) if your map is public.

## Commands (`nodesmap.admin`, op by default)

| Command | What it does |
|---|---|
| `/nodesmap status` | Render queue, progress, web address |
| `/nodesmap fullrender [radius]` | Draw every generated chunk (or only within `radius` chunks of you) |
| `/nodesmap render [radius]` | Redraw the loaded chunks around you (default 8) |
| `/nodesmap players [on\|off]` | Show or hide players on the web map, live (saved to config) |
| `/nodesmap cancel` | Drop everything queued |
| `/nodesmap reload` | Reload config (web server/world changes need a restart) |
| `/nodesmap editor [revoke]` | Get a login link for the map editor (`nodesmap.editor`) |

Permission `nodesmap.hidden` hides a player from the map. Sneaking, invisible, spectator and vanished players are hidden by default.

## What it costs the server

The design rule: **the main thread never draws, encodes, or writes anything.**

| Work | Where | Cost |
|---|---|---|
| Noticing changes | block events | a heightmap lookup + set insert; changes more than 2 blocks below the surface (mining, caves) are ignored |
| Chunk snapshots | main thread | time-boxed per tick (`render.snapshot-budget-ms`, default 2 ms); unloaded chunks are loaded with Paper's async loader and never generated |
| Drawing tiles, zoom levels, PNG encoding | 1 low-priority background thread | ~2,000+ chunks/s on one core |
| Changed chunks | batched | released every `render.batch-seconds` (15 s), so a chunk edited 500 times is drawn once |
| Players | main thread, every 20 ticks | one pass over online players; served from memory |
| Nodes data | main thread copies small snapshots; JSON + hashing + writing on a background thread | files are only rewritten when their content actually changes |
| Web | 3 HTTP threads | ETags everywhere, so unchanged tiles and data cost a `304`; text is gzipped |

Browsers poll one tiny `version.json` and only download the layers whose hash changed. They swap only the tiles that were redrawn.

## Web map features

- **Color modes:** towns, nations, diplomacy (relative to any town: own, nation, ally, vassal, enemy, neutral), resource finder (highlight every territory with a given resource node), supply lines (connected vs cut off), plain terrain.
- **Live war:** flags with progress rings and countdowns; occupied chunks hatched in the occupier's color; war timer pill; war tab with every attack, warzone scoreboards, raids (prep/live with countdowns), supply drop timer and an occupation leaderboard.
- **Warzones:** gold dashed outline, live countdown and current holder on the map.
- **Click anything:**
  - Territory: owner, nation, occupier, fort level, raid, supply status, resources with icons, claim cost.
  - Town: leader, residents with online status, claims, allies, enemies, vassals, outposts.
  - Nation: towns, allies, enemies, totals.
- **Search** (press `/`) across towns, nations, players, territories, or type `x z` to jump.
- Town/nation lists with sorting; players colored by town; shareable URL (`#x,z,zoom`); settings remembered per browser; works on phones.

## Config

`plugins/NodesMap/config.yml`: world, port, render budget, zoom levels, shading, player privacy. Every option is commented.

`plugins/nodes/config.yml` → `mapExport:`: export path and refresh rates for the Nodes layer (`statePeriodTicks`, `livePeriodTicks`), and whether to include supply lines. Supply connectivity is computed one town per tick to spread the cost out.

### Serving with nginx instead

Set `web.enabled: false`, then serve `plugins/NodesMap/web/` as static files. NodesMap still writes `data/players.json` and `data/tiles.json` there.

## Building

```
cd nodesmap
./gradlew build      # -> build/libs/nodesmap-0.2.0.jar
```

Like Nodes, it needs the Kotlin runtime plugin on the server.

## Files

```
plugins/NodesMap/web/
  index.html, app.js, geometry.js, style.css, vendor/, icons/   viewer (re-extracted on plugin update)
  tiles/<world>/<zoom>/<x>_<z>.png                               terrain tiles
  data/map.json, players.json, tiles.json                        NodesMap (in memory when the built-in server is on)
  data/nodes/version.json, world.json, state.json,
             features.json, live.json                            written by Nodes
```
