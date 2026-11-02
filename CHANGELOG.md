# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [Unreleased]

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
