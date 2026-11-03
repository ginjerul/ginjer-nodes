# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added

- Ore bounties (`/bounty`): players fund bounties on other players with real ores or items from their inventory. The killer's reward goes to their town income or their inventory. Options are under `bounties:` in the config.
- Economy (`/economy`, `/economyadmin`): nation, town, personal and business accounts, recurring trade routes between nations, loans from a funded reserve and infrastructure upkeep. Options are under `economy:` in the config. Based on the NodesEconomy prototype, rewritten as part of Nodes with no extra dependencies.
- Economy: sell items to the server (`/economy sell`, `/economy prices`), a player market (`/economy market`), richest lists (`/economy top`), a menu (`/economy menu`), vote rewards (NuVotifier/Votifier or `/economyadmin vote`), and optional transfer/market fees and a daily town tax.
- `/nda socialspy` lets an admin see town, nation and ally chat. Optional proximity alliances (`allyProximity`, `allyProximityDistance`): `/ally` is refused when the closest towns of the two sides are farther apart than that distance.
- `/nda stats reset all confirm` resets the kills and deaths of every player (the old player-stats.json is kept as a backup file).
- Ports: a town's leader or officers set who may warp to ALL of the town's ports (`/port allow ally|neutral|enemy true|false`) and a money fee per group (`/port fee`), paid into the town treasury. One setting covers every port the town owns, including ones it gains later. Replaces the per-port settings. Warping without a ship takes `portNotInShipMultiplier` times longer, and optional beacon pillars mark ports (`portBeacon:`). Replaces the separate ports addon: do not run it next to Nodes.
- Port requests: a town leader or officer files `/port request <name>` where the port should be. Admins use `/nda port requests` (a menu that teleports to each request, then `approve` or `deny`), `/nda port list` and `/nda port group add|remove <group>` to create or delete a port group (replaces `/nda portgroup create|delete`).
- Economy: a server shop of preset items the server sells (`/economy shop`, `shop:` in the config), next to the player market. Every `/economy` and `/economyadmin` command is now available from a menu (`/economy` or `/economyadmin` with no arguments), with chat prompts for amounts and names.
- GinjerMap: port pins now show the owner town, who may warp and the fee for allies, neutrals and enemies, and a new "Port requests" layer (off by default) shows pending requests.
- Ports: the beacon pillar of an approved port request starts at the height the request was made at.
- Economy: stock market (`economy.exchange` in the config). Listed items are bought and sold by the server at a price that follows its stock: low stock raises the price, high stock lowers it. Players sell into the stock and buy out of it, big trades move the price as they go, and the stock moves back toward its target on a timer. `/economy shop list|buy|sell`, a buy/sell mode in the menu, and `/economyadmin exchange reset`.
- Bounties can be paid in money: `/bounty addmoney <player> <amount>`. The money is held in escrow until the target is killed.

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
