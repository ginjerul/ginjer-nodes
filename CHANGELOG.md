# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added

- Sharding, phase 1 (experimental, off by default, `sharding:` in the config): Nodes can run on several servers that share one world (Reef sharding). One server is the leader; followers copy its towns, nations, claims, ports and war state through Redis, so protection and info work on every shard. Commands that change things, and starting war attacks, only work on the leader for now. `/nda shards` shows the role and the other shards. See `nodes/SHARDING-DESIGN.md`.
- Sharding, phase 2 (first part): town, nation, claim, join, diplomacy and economy commands typed on a follower now run on the leader, and the player gets the answer once their shard has the new state. Leader broadcasts and messages to players on other shards (applications, invites, alliance and peace offers) reach them on their shard. Changes are published right away instead of after the next save. If the leader is down the player is told nothing changed. Commands that need items, teleports or menus still only work on the leader.
- Sharding: followers only fetch and rebuild the parts of the state that changed. A town, nation, claim or diplomacy change now takes 10-20 ms on a follower instead of a 85-130 ms full reload (1,764 territories); territories are only rebuilt after a map edit. The leader only sends the parts that changed.
- Sharding: `/town income`, `/economy sell hand|all`, `/economy shop buy|sell` and chest protection now work on followers; the leader keeps the income, ledger and protected chests and every action is applied exactly once (items are given back if it fails). Items for a player are delivered on whichever shard they are on. `/town spawn`, `/nation spawn`, `/town fly`, `/town supply`, `/economy sell town` and the shop and market listings work on followers too. A follower keeps chest-protect mode and spawn countdowns across its updates.
- Sharding: `/economy market sell|buy|cancel` work on followers; listed items keep their names, enchantments and other metadata.

### Fixed

- Sharding: players who had never joined a follower shard were dropped from their towns there, so towns showed no leader or residents and players arrived from the leader without a town. Nodes now uses the name saved in `towns.json` when sharding is on.
- Sharding: turning war on is saved right away, so follower shards see it (before, nothing was written until the first attack).
- Sharding: a follower no longer announces "Nodes war enabled" on every update, no longer runs attack progress itself, and removes the previous flags and beacons before showing the leader's current ones. Turning war off on the leader now turns it off on followers too.
- Sharding: placing a war flag on a follower now says why it was refused.
- Sharding: followers fetch the leader's state off the main thread, keep each player's minimap and chat channel across updates, and log how long each update took. The leader publishes again if Redis lost its copy (for example after a Redis restart). A Redis outage is logged once when it starts and once when it ends.

## [0.1.1] – 2026-11-12

### Added

- Duels have an Elo rating (start and swing set by `duel.eloStart` and `duel.eloK`). It shows in `/duel stats`, the result message and `/duel top`, which is now ordered by rating.
- Ore bounties (`/bounty`): players fund bounties on other players with real ores or items from their inventory. The killer's reward goes to their town income or their inventory. Options are under `bounties:` in the config.
- Economy (`/economy`, `/economyadmin`): nation, town, personal and business accounts, recurring trade routes between nations, loans from a funded reserve and infrastructure upkeep. Options are under `economy:` in the config. Based on the NodesEconomy prototype, rewritten as part of Nodes with no extra dependencies.
- Economy: sell items to the server (`/economy sell`, `/economy prices`), a player market (`/economy market`), richest lists (`/economy top`), a menu (`/economy menu`), vote rewards (NuVotifier/Votifier or `/economyadmin vote`), and optional transfer/market fees and a daily town tax.
- `/nda socialspy` lets an admin see town, nation and ally chat. Optional proximity alliances (`allyProximity`, `allyProximityDistance`): `/ally` is refused when the closest towns of the two sides are farther apart than that distance.
- `/nda stats reset all confirm` resets the kills and deaths of every player (the old player-stats.json is kept as a backup file).
- Ports: a town's leader or officers set who may warp to ALL of the town's ports (`/port allow ally|neutral|enemy true|false`) and a money fee per group (`/port fee`), paid into the town treasury. One setting covers every port the town owns, including ones it gains later. Replaces the per-port settings. Warping without a ship takes `portNotInShipMultiplier` times longer, and optional beacon pillars mark ports (`portBeacon:`). Replaces the separate ports addon: do not run it next to Nodes.
- Port requests: a town leader or officer files `/port request <name>` where the port should be. Admins use `/nda port requests` (a menu that teleports to each request, then `approve` or `deny`), `/nda port list` and `/nda port group add|remove <group>` to create or delete a port group (replaces `/nda portgroup create|delete`).
- Economy: a server shop of preset items the server sells (`/economy shop`, `shop:` in the config), next to the player market. Every `/economy` and `/economyadmin` command is now available from a menu (`/economy` or `/economyadmin` with no arguments), with chat prompts for amounts and names.
- GinjerMap: the browser now reuses map tiles for 30 seconds instead of asking the server about every tile on each pan, which makes the web map much smoother over a domain or slower connections. Redrawn tiles still update live.
- GinjerMap: the README now has a guide for using your own domain or port with a Caddy or nginx reverse proxy, including `web.public-url`, HTTPS and troubleshooting.
- GinjerMap: port pins now show the owner town, who may warp and the fee for allies, neutrals and enemies, and a new "Port requests" layer (off by default) shows pending requests.
- Ports: the beacon pillar of an approved port request starts at the height the request was made at.
- Economy: stock market (`economy.exchange` in the config). Listed items are bought and sold by the server at a price that follows its stock: low stock raises the price, high stock lowers it. Players sell into the stock and buy out of it, big trades move the price as they go, and the stock moves back toward its target on a timer. `/economy shop list|buy|sell`, a buy/sell mode in the menu, and `/economyadmin exchange reset`.
- Bounties can be paid in money: `/bounty addmoney <player> <amount>`. The money is held in escrow until the target is killed.
- Town rules (all off by default, see the "Town rules" block in the config): a town PvP toggle (`/town pvp on|off`, `togglePVPMechanic`, `canToggleTownPVP`, `pvpToggleCooldown`, admin `/nda pvp <town> <on|off>`), mob protection (`mobProtection`, `protectedMobs`, `protectedMobsIfPVPOff`), `invulnerabilityTowns`, and war sickness (`warSickness`).
- War safeguards: `hoursRequiredToJoinWars` keeps new players out of flag attacks until they have played that long.
- Economy: optional costs paid in the economy (`economy.costs`: `townCreation` from the wallet, `territoryClaimPerPower` from the town treasury) and daily upkeep (`economy.upkeep`: `townPerClaimPerDay`, `nationPerTownPerDay`). An account that cannot pay is skipped and nothing is deleted.
- Chat: `/localchat` (`/lc`) and `/rpchat` (`/rp`) channels limited by `localChatDistance` and `rpChatDistance`, and `chatPrefixMode` to choose the global chat prefix.
- Small options: `townRenameCooldown`, `maxClaimsPerTown`, `maxDaysForInactivity` with `/nda inactive [days]`, and `dailyEventTime` to run daily tax and upkeep at a set time.
- Capturing towns: `instantAnnexOnCapture` annexes a town the moment its home is captured (off = use `/town annex`; normal territories always need `/town annex`), and `capturedTownJoinsNation` keeps a captured town (home taken) alive and adds it to the attacker's nation instead of destroying it. Both respect the war and annex blacklists and whitelists.
- Capturing towns: `economy.plunder` takes money from a captured town's treasury, and `onlyKeepCapital` makes a town that joins the attacker's nation keep only its home territory.
- `warProximity`/`warProximityDistance` limit war declarations by distance, `minimumDistanceForNewTown` keeps new towns apart, `canClaimDuringWar`, `canSetCapitalDuringWar` and `canSetCapitalIfEnemies` add war rules, `explodeChests` can protect chests from explosions, `hiddenOreMechanic`/`hiddenOreSilktouch` control hidden ore, and `greenText`/`chatPrefix` tune chat.
- Tab list (`tabList:` in the config, off by default): four columns Staff | Nation | Allies | Enemies with each town's online players and icons for nation leaders, town leaders and officers. Needs the PacketEvents plugin (optional).
- Admin permissions: `/nda` can be split between staff. `nodes.admin` is still everything, `nodes.admin.<command>` gives one command (e.g. `nodes.admin.town`) and `nodes.admin.<command>.<action>` one action (e.g. `nodes.admin.town.addplayer`, `nodes.admin.resident.towncooldown`). Help and tab completion only show what a player can use. Social spy needs `nodes.admin.socialspy`, port request alerts and the request menu `nodes.admin.port.requests`.
- Calendar: `/calendar` opens a month view of the server's daily events, with today highlighted and the day's events shown to players when they join. Admins use `/nda calendar add|edit|remove|list|show` to schedule events (one date, daily, weekly or monthly, with an optional time and type), can make an event start a war (`war:2h`) or the mining boost (`boost:1h`) at its time, and send announcements with `/nda calendar announce`. Options are under `calendar:` in the config; permissions are `nodes.command.calendar` and `nodes.admin.calendar[.action]`.
- `/captimes [node id]` shows how long it takes to capture one chunk of a node (the one you stand in without an id): for enemies attacking and for the owner and allies liberating, with the home, wilderness and node multipliers listed.
- Exploration: admins place points of interest with `/nda poi add <type> <name>` (villages, ruins, caves, shrines, shipwrecks, dungeons, camps) and set radius, description and rewards (money, items, a title). Players discover them by walking close, get the reward once, and see only what they found in `/map`. Finding POIs earns milestone titles that can be shown in chat with `/map title`. Options are under `poi:` in the config; permissions are `nodes.command.map` and `nodes.admin.poi[.action]`. POIs are not shown on GinjerMap.
- Duels (`/duel <player> [kit|own] [wager]`, `accept`, `deny`, `cancel`, `spectate <player>`, `leave`, `top`, `stats [player]`, `kits`): fights in admin-set arenas with a countdown and time limit. Nobody dies and players are put back with their position, health and inventory (also after a crash). Only the two duelists can hurt each other, overriding town PvP and friendly fire rules. Optional kits (everyone gets the same loadout), money wagers held in escrow and paid to the winner, win/loss records, and spectating. Admins use `/nda duel arena|kit|end|resetstats`. Options are under `duel:` in the config.
- Skills (`/skills`, `/skills top <skill>`): mining, farming, woodcutting and hunting gain xp as players do them. Each level gives a small chance of one extra drop, and set levels earn titles that can be shown in chat with `/map title`. Blocks players placed, unripe crops and bred or spawner mobs give no xp. Admins use `/nda skills show|set|addxp|reset`. Options, xp values and titles are under `skills:` in the config.

### Changed

- Help lists for duels, skills, calendar, map, capture times and the `/nda` duel, skills, calendar and POI sections now use the same `[Nodes] <feature> commands:` style as the other features.

### Fixed

- config.yml now fills in every missing option on startup, including ones inside existing sections (before, only top-level options were added). Existing values are never changed.
- POI rewards can now be the exact item in your hand (`/nda poi reward <id> hand`), so custom named, enchanted or plugin items work, like supply drop loot.
- POI: a new or moved POI cannot be found for `poi.activationDelaySeconds` (default 120), so the admin placing it no longer discovers it by standing there. `/nda poi activate <id>` skips the wait, and `/nda poi add <type> radius=N <name>` sets the discovery radius on creation.
- Duels: XC gun hits (muskets etc.) are now handled too, so a killing shot ends the duel like a melee one instead of killing the player. Anyone who still dies in a duel is put back on respawn.

## [0.1.0] – 2026-11-02

### Added

- GinjerMap (formerly NodesMap): live web map and in-browser territory editor, included in `ginjermap/`.
- `/surrender`: a town joins another nation and inherits its wars and alliances. `/nda surrender reset <town>` clears the cooldown.
- Default `world.json` and `towns.json` are created on first start.
- Default config lists every option; new options are added to existing configs. `discordWebhookWar` and `discordWebhookFlagWar` added.
- `nationJoinCooldown` (duration, e.g. `2h`, `1d12h`).
- bStats usage statistics (disable in `plugins/bStats/config.yml`).

### Changed

- Flag capture labels use a TextDisplay instead of armor stands.
- NodesMap was renamed to GinjerMap: `/ginjermap` (the `/nodesmap` alias still works), permissions are now `ginjermap.*`, and the `plugins/NodesMap` folder is moved to `plugins/GinjerMap` automatically.

## [0.0.14] – 2025-01-01

### Changed

- Updated to Paper 1.21.10 API.
- Updated Kotlin to 2.3.0-RC.

## [0.0.13] – 2024-01-01

### Changed

- Internal maintenance and dependency updates.
