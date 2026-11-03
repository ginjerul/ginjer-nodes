# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

### Added

- Ore bounties (`/bounty`): players fund bounties on other players with real ores or items from their inventory. The killer's reward goes to their town income or their inventory. Options are under `bounties:` in the config.
- Economy (`/economy`, `/economyadmin`): nation, town, personal and business accounts, recurring trade routes between nations, loans from a funded reserve and infrastructure upkeep. Options are under `economy:` in the config. Based on the NodesEconomy prototype, rewritten as part of Nodes with no extra dependencies.
- Economy: sell items to the server (`/economy sell`, `/economy prices`), a player market (`/economy market`), richest lists (`/economy top`), a menu (`/economy menu`), vote rewards (NuVotifier/Votifier or `/economyadmin vote`), and optional transfer/market fees and a daily town tax.
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
