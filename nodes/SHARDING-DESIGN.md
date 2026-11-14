# Nodes on Reef shards: design

Status: **phases 0, 1 and the first part of 2 are implemented (experimental).** Tested with a leader, a follower and Redis, on plain Paper and on Reef behind Velocity with border handoffs. See "What phase 1 does" and "What phase 2 does so far" below. Decisions so far: one leader shard owns all Nodes state and logic, the other shards keep a mirror of it, and the shards talk over Redis (besides Velocity moving players).

Reef (see its `SHARDING.md`) splits one world across servers: each shard simulates only its own regions and players are handed over at borders. Reef shares player data and nothing else. Nodes keeps everything (towns, nations, territory ownership, wars, economy, skills, duels, POIs, ports) in one server's memory and in local files, so on shards it would diverge. This document says how to fix that.

## Goals

- Every shard sees the same towns, nations, claims, relations and wars.
- A change made on any shard (claim, join, deposit, declare war) is visible on all shards within about a second.
- Nothing is lost if a follower shard restarts; a leader restart pauses writes briefly.
- Reef's own sharding stays off for servers that do not need it, and Nodes behaves exactly as today when sharding is off.

## Architecture

```
Players --> Velocity --> shard A (follower) --\
                     --> shard B (leader)   ---+-- Redis (commands, events, snapshots)
                     --> shard C (follower) --/
```

- **Leader:** exactly one shard, chosen in config (`sharding.leader: true`). It loads and saves `world.json`, `towns.json` and all side stores, runs every periodic task (income, upkeep, war timers, calendar, auto-save) and is the only writer.
- **Followers:** load the same state from a snapshot at start and then apply events. They do not save Nodes files and do not run the periodic tasks. They still run everything that needs a player or a location on their own shard (protection checks, chat, GUIs, flag capture progress), reading from the mirror.
- **Redis:** one stream `nodes:events` (leader to everyone: ordered changes, each with a sequence number), one stream `nodes:commands` (follower to leader: requests to change something), and a key `nodes:snapshot` (full state plus the sequence number it was taken at).

### Reading

Followers answer all reads from their mirror, so commands such as `/town info` and every protection check stay fast and need no Redis round trip.

### Writing

1. A follower turns a player's action into a command: `{id, player, type, args}`.
2. The leader checks it with the same rules Nodes uses today, applies it, and publishes an event with the result.
3. Every shard (including the one that asked) applies the event in sequence order and shows the player the message.
4. A follower never changes the mirror itself; it waits for the event (about 50 ms in the same data centre).

This keeps one source of truth and no merge conflicts. Two shards claiming the same chunk is decided by whichever command reaches the leader first.

### Players across shards

- Resident data (town, nation, permissions, wallet, skills, duel record, discoveries, titles) lives in the leader's state and is mirrored, so a player who is handed over arrives with it already present.
- Reef already shares inventory, health and position through the shared player-data folder.

## What is hard

These are the parts that decide the effort:

1. **Every state change must become a command and an event.** About 45 mutating entry points in `Nodes.kt` plus the setters on `Town`, `Nation`, `Resident`, `Territory` and `Port` (most of `TownCommand` and `NationCommand`) call them directly today. Each needs a serialisable form.
2. **Time-driven logic must run on one shard.** Wars, capture timers, income, upkeep, calendar and supply drops use the periodic tick, so they move to the leader. Flag capture progress is simulated by the shard that owns the flag area and reported to the leader.
3. **Wars cross borders.** A battle near a region border needs players on both shards to count toward one capture. The leader aggregates capture presence from all shards.
4. **Chat and tab.** Town, nation and ally chat need to cross shards through Redis; the tab list needs a merged player list.
5. **Economy.** Money moves become commands on the leader's ledger so balances are never read and written on two shards at once.
6. **Side stores** (`duels.json`, `skills.json`, `pois.json`, `discoveries.json`, `calendar.json`, supply drops, refineries) follow the same rule: leader writes, followers mirror.
7. **GinjerMap** runs on the leader and renders from its mirror; tile re-rendering needs block-change events from followers (a lightweight event).
8. **Failure handling:** leader down means followers keep serving reads, queue commands for a short time, then tell players that changes are paused. A starting follower loads the snapshot, then replays events after its sequence number.

## Phases

| Phase | Result | Rough size |
| --- | --- | --- |
| 0 | Config (`sharding:` in config.yml), Redis client, leader/follower role, health and `/nda shards` status. Nodes unchanged when disabled. | small |
| 1 | Snapshot plus event stream for the read side: followers mirror towns, nations, territories and diplomacy; protection works everywhere; leader is still the only place to run commands. | medium |
| 2 | Commands from followers for the common actions (town, nation, claim, join, deposit, trust) with events back. | large |
| 3 | War and flag capture across shards, cross-shard chat and tab, economy and side stores. | large |
| 4 | Failover, load tests with 500 bots on a war weekend, docs. | medium |

Phases 0 and 1 are usable on their own: a player on any shard can be protected and see the right nation tags while all commands still run on the leader shard. They deliver value early and prove the event stream before the large rewrite.

## What phase 1 does

- `sharding:` in config.yml sets the role (`leader` or `follower`), this server's name and the Redis address. No extra library is needed; Nodes talks to Redis itself.
- The leader works as before. Every `syncSeconds` it checks its saved files (`world.json`, `towns.json`, `ports.json`, `war.json`) and, when they changed, publishes them to Redis. So a change reaches followers after the leader's next save (`savePeriod`) plus up to `syncSeconds`.
- A follower fetches those files before it loads the world, and again whenever the leader publishes. It then reloads its towns and claims. It never saves the shared state and does not run income, cooldowns, the economy tick or bounty payouts.
- On a follower, Nodes commands that change things are refused with a message, and war flags cannot be placed. Info commands, chat and protection work.
- `/nda shards` shows this shard's role, the last sync and every shard seen in Redis.

Not shared yet (each shard still keeps its own copy): skills xp, POI discoveries, kill and death stats, duels, the economy, bounties, truces, calendar, supply drops, refineries, chat and the tab list. Do not rely on those on follower shards until phases 2 and 3.

Known cost: a follower reloads all towns and territories each time the leader publishes. With 1,764 territories, 350 towns and 1,750 residents that took 95–300 ms on the main thread in testing (one long tick, after the leader's next save). This goes away when phase 2 replaces full reloads with small events.

## What phase 2 does so far

Commands typed on a follower that change Nodes state run on the leader with the normal command code, so every rule stays in one place:

- The follower sends the player's id, name, position, Nodes permissions and the command line to the Redis list `<prefix>:commands` (`shard/RemoteCommands.kt`).
- The leader runs the real command for a stand-in player (`shard/RemotePlayer.kt`) that answers who and where the player is and collects every message sent to it. It then saves and publishes at once and sends the messages back on `<prefix>:events`, tagged with the published snapshot.
- The follower shows the answer only once it has loaded that snapshot, so `/town info` right after `/town claim` is already up to date. Leader broadcasts ("X has created the town") are relayed to every shard the same way.
- Followers keep the shards' online players in the hash `<prefix>:presence`. On the leader, `Shards.player(...)` and `Resident.onlinePlayer()` also find players online on other shards for messages only (applications, invites, alliance and peace offers), so "no officers online" and invitations work across shards.
- A change made by a player on the leader is published right away too (about 0.2-0.3 s to a follower, reload included), not after the next save.
- If the leader does not answer within 6 s the player is told nothing changed; the leader drops requests older than 10 s, so a command is never applied late.

Followers only rebuild what changed. The leader stores a hash for each part of the state (`world`, `towns`, `ports`, `war`) next to it in Redis and sends only the parts that changed; a follower fetches only parts whose hash differs from its own. If `world.json` is unchanged (it only changes with map edits) the follower keeps its resource nodes and territories and rebuilds towns, nations, residents, ports and war (`Nodes.reloadTownState()`). Measured with 1,764 territories, 352 towns and 1,750 residents:

| Change | Before | Now |
| --- | --- | --- |
| A town, nation, claim or diplomacy change | 85-130 ms (full reload) | 10-20 ms (41 ms the first time after start) |
| A war flag placed (beacon blocks are built) | | about 130 ms, once per attack |
| A map edit (`world.json`) | 85-130 ms | 99 ms (full reload, as before) |

Rebuilding territories from `world.json` was 70-105 ms of the old full reload; towns, nations and residents together are 10-20 ms, under one tick. Per-mutation events (one message per claim, join, ...) would bring that to about 1 ms, but would need a serialisable form for each of the ~45 state-changing functions and their setters; with the partial reload this is not needed for the hitch.

Which commands go where on a follower:

| | Run on the follower (mirror) | Forwarded to the leader | Actions (items or clicks, see below) | Refused for now |
| --- | --- | --- | --- | --- |
| `/town` | help, list, info, online, onlinetop, map, minimap, spawn, fly, supply, protect | create, delete, officer, promote, demote, leader, apply/join, invite, accept, deny, leave, kick, setspawn, color, claim, unclaim, prefix, suffix, rename, pvp, perms, trust, untrust, capital, annex, outpost | income; chest clicks while protecting | fortify |
| `/nation` | help, list, info, online, onlinetop, spawn | announce, create, delete, leave, capital, invite, accept, deny, color, rename | | |
| `/economy` | help, prices | balance, deposit, withdraw, pay, business, history, stock, top, sell town, shop list, market list/mine and the rest | sell hand, sell all, shop buy, shop sell, market sell, market buy, market cancel | menu |
| `/ally`, `/unally`, `/war`, `/peace`, `/truce` | | everything | | |

`/town spawn` and `/nation spawn` run on the follower because they only read the mirror and move the player; a destination in another shard's region is a normal Reef handoff. `/town protect` only turns chest-protect mode on; each chest click is sent to the leader.

### Actions: items and clicks (`shard/RemoteActions.kt`)

The leader keeps the state (town income, the economy ledger, protected chests); the follower has the player and their inventory.

- **Town income:** the follower opens its own "Town Income" view filled from the mirror. A click asks the leader for that stack; the leader checks the income permission, removes what is still there and puts it in the player's mailbox. The view refreshes when the new state arrives.
- **Selling** (`sell hand`, `sell all`, `shop sell`): the follower takes the sellable items first and holds them; the leader prices and pays the wallet. If the sale fails, or the leader does not answer, the items go back to the player.
- **Shop buy:** the follower checks for inventory space, the leader charges the wallet and puts the items in the mailbox.
- **Market:** for `market sell` the follower takes the held stack (with its name, enchantments and other metadata) and the leader creates the listing, or the stack goes back if it fails. For `market buy` and `market cancel` the follower checks for a free slot, the leader pays or removes the listing and puts the item in the mailbox. Mailbox entries carry items with metadata encoded whole.
- **Chest protection:** `Nodes.protectTownChest` on a follower works out the chest's blocks (the leader's copy of that region may not have the chest), updates the mirror at once and sends the blocks to the leader, which keeps only blocks in that town's own land. This also covers protected chests that get destroyed.

Each action is applied exactly once. The leader claims `<prefix>:txn:<id>` with SET NX before applying it; a follower that gets no answer within 8 s claims the same key as "cancelled": if that works the leader never applied it and anything taken from the player is given back; if not, the stored result says what happened.

Items for a player go to their mailbox `<prefix>:mail:<uuid>`. Whichever shard the player is on takes them out with LPOP (when the answer arrives and when they join any shard), so nothing is given twice and nothing is lost if they move to another shard or log off first.

A follower also keeps players' chat channel, minimap, chest-protect mode and spawn countdown across its reloads.

Still to do in phase 2:

- `/economy menu`: it shows balances, listings and loans from the economy store, which followers do not mirror yet (phase 3 side stores).
- `/town fortify`, plots and port commands use side stores (fortifications, plots, port requests and policies) that followers do not mirror yet, so they stay leader-only until the side stores in phase 3. The ports themselves (`ports.json`) are mirrored.
- Messages Nodes sends to players by looping over a town's `playersOnline` (and chat) only reach players on the leader; that is the chat part of phase 3.

## Open questions

- Which Redis library (Jedis is a small dependency; shading it into the plugin jar is needed).
- Whether the snapshot reuses the existing JSON serialisers (`serdes/`) or a smaller binary format.
- Whether followers may run `/nda` at all (suggested: forward it to the leader).
- How to treat the Nether and End: Reef shards only one world, so Nodes stays leader-only there.
