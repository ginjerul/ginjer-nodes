# Configuration Guide

All configuration options for the Nodes plugin live in `plugins/nodes/config.yml`. Options are listed in the order they appear in the default configuration file.

---

## Engine Settings

### disableWorldWhenLoadFails

**Type:** boolean  
**Default:** `true`  
**Description:** Disables world interactions (block placing, breaking, etc.) when `world.json` or `towns.json` fails to load. This prevents corruption from partially loaded data.

**Example:**
```yaml
disableWorldWhenLoadFails: true
```

---

### savePeriod

**Type:** integer (ticks)  
**Default:** `600`  
**Description:** How often plugin data is saved to disk, in server ticks (20 ticks = 1 second).

---

### backupPeriod

**Type:** integer (milliseconds)  
**Default:** `3600000` (1 hour)  
**Description:** How often plugin data is backed up, in milliseconds.

---

### mainPeriodicTick

**Type:** integer (ticks)  
**Default:** `1200`  
**Description:** Interval for the main background task, in ticks.

---

### overMaxClaimsReminderPeriod

**Type:** integer (ticks)  
**Default:** `24000`  
**Description:** How often players are reminded that their town is over its maximum claims, in ticks.

---

### nametagUpdatePeriod

**Type:** integer (ticks)  
**Default:** `80`  
**Description:** How often player nametags are refreshed, in ticks.

---

### nametagPipelineTicks

**Type:** integer  
**Default:** `16`  
**Description:** Number of ticks over which nametag updates are spread to reduce server load.

---

### dynmapCopyTowns

**Type:** boolean  
**Default:** `false`  
**Description:** When enabled, forces a copy of town data to the Dynmap integration on each save cycle.

---

## Nametag Settings

### useNametags

**Type:** boolean  
**Default:** `true`  
**Description:** Enables the internal armorstand-based nametag system for displaying player town/nation info above their heads.

---

## AFK Settings

### afkKickTime

**Type:** integer (milliseconds)  
**Default:** `900000` (15 minutes)  
**Description:** Time in milliseconds before an idle player is penalised. This reduces claim power progress.

---

## General Permissions

### canInteractInEmpty

**Type:** boolean  
**Default:** `false`  
**Description:** Whether players can build, destroy, and interact in areas with no territories defined.

---

### canInteractInUnclaimed

**Type:** boolean  
**Default:** `true`  
**Description:** Whether players can build, destroy, and interact in territories that have not been claimed by any town.

---

### requireSheepNodeToShear

**Type:** boolean  
**Default:** `true`  
**Description:** When enabled, players can only shear sheep in territories that have the sheep resource node.

---

## Town Settings

### townCreateCooldown

**Type:** integer (milliseconds)  
**Default:** `172800000` (48 hours)  
**Description:** Cooldown after a player leaves or disbands a town before they can create a new one.

---

### townMoveHomeCooldown

**Type:** integer (milliseconds)  
**Default:** `172800000` (48 hours)  
**Description:** Cooldown between town home relocations.

---

### townCooldownUpdateTick

**Type:** integer (ticks)  
**Default:** `2400`  
**Description:** How often town cooldown states are updated, in ticks.

---

### townSpawnTime

**Type:** integer (seconds)  
**Default:** `10`  
**Description:** Warm-up time in seconds before a player is teleported to their town home.

---

### outpostTeleportCost

**Type:** map (item: quantity)  
**Default:** `diamond: 1`  
**Description:** Items consumed when a player teleports to an outpost.

**Example:**
```yaml
outpostTeleportCost:
  diamond: 1
```

---

## Nation Settings

### allowNationTownSpawn

**Type:** boolean  
**Default:** `false`  
**Description:** Whether players can teleport to other towns within their nation.

---

### nationTownTeleportCost

**Type:** map (item: quantity)  
**Default:** `diamond: 1`  
**Description:** Items consumed when teleporting to another town in the same nation.

---

### allowNationFriendlyFire

**Type:** boolean  
**Default:** `false`  
**Description:** Whether members of the same nation can attack each other.

---

## Alliance Settings

### allowAllyFriendlyFire

**Type:** boolean  
**Default:** `true`  
**Description:** Whether members of allied towns/nations can attack each other.

---

## Town Claim Settings

### territoryCostBase

**Type:** integer  
**Default:** `10`  
**Description:** Base claim cost for a territory (`cost = base + scale * chunks`).

---

### territoryCostScale

**Type:** float  
**Default:** `0.25`  
**Description:** Per-chunk scaling factor for territory claim cost.

---

### townInitialClaims

**Type:** integer  
**Default:** `25`  
**Description:** Claim power granted to a player when they first create a town.

---

### initialOverClaimsAmountScale

**Type:** float  
**Default:** `2`  
**Description:** Penalty scale applied when a town's territory cost exceeds its initial claim allowance.

---

### townClaimsBase

**Type:** integer  
**Default:** `20`  
**Description:** Base claim power given to every town.

---

### townClaimsMax

**Type:** integer  
**Default:** `-1` (unlimited)  
**Description:** Maximum total claim power a town can have. Set to `-1` for no limit.

---

### playerClaimsInitial / playerClaimsMax / playerClaimsIncrease

**Type:** integer  
**Defaults:** `1` / `25` / `2`  
**Description:** Claim power granted on town join (`playerClaimsInitial`), the per-player cap (`playerClaimsMax`), and how much claim power increases per period (`playerClaimsIncrease`).

---

### townPenaltyDecay

**Type:** integer  
**Default:** `2`  
**Description:** Amount by which a town's overclaim penalty decreases each decay period.

---

### townClaimsPenaltyDecayPeriod / playerClaimsIncreasePeriod

**Type:** integer (milliseconds)  
**Default:** `3600000` (1 hour)  
**Description:** Period between town penalty decay ticks and player claim power gain ticks.

---

### overClaimsPenalty

**Type:** boolean  
**Default:** `true`  
**Description:** Enables the overclaim penalty system, reducing resource yields when a town exceeds its maximum claims.

---

### overClaimsMaxPenalty

**Type:** float  
**Default:** `0.5`  
**Description:** Resource yield rate when a town is over its maximum claims (e.g. `0.5` = 50% chance of receiving a resource).

---

### overClaimsAllowClaim

**Type:** boolean  
**Default:** `false`  
**Description:** Whether towns can continue claiming territories even after exceeding their maximum claim power.

---

## Resource Settings

### incomeEnabled

**Type:** boolean  
**Default:** `true`  
**Description:** Enables the periodic territory income system.

---

### incomePeriod

**Type:** integer (milliseconds)  
**Default:** `3600000` (1 hour)  
**Description:** How often territory income is distributed.

---

### incomeScaleByClaimPower

**Type:** boolean  
**Default:** `true`  
**Description:** Scales income by the ratio of player claim power to total territory claim cost.

---

### incomeScaleMin / incomeScaleMax

**Type:** float  
**Default:** `0.1` / `1.0`  
**Description:** Minimum and maximum income scaling factors.

---

### globalResources

**Type:** map  
**Description:** Default resources present in every territory. Supports `income`, `ore`, `crops`, and `animals` sub-sections.

**Example:**
```yaml
globalResources:
  income:
    gold_ingot: 1
  ore:
    coal: [0.03, 1, 3]
    iron_ore: [0.015, 1, 2]
```

---

### allowOreInWilderness / allowCropsInWilderness / allowBreedingInWilderness

**Type:** boolean  
**Default:** `false`  
**Description:** Whether mining, harvesting, and breeding are allowed in unclaimed wilderness territories.

---

### allowOreInCaptured

**Type:** boolean  
**Default:** `true`  
**Description:** Whether mining is allowed in captured (occupied) territories.

---

### allowOreInNationTowns

**Type:** boolean  
**Default:** `true`  
**Description:** Whether mining and harvesting are allowed in other towns belonging to the same nation.

---

### cropsMinSkyLight / breedingMinSkyLight

**Type:** integer  
**Default:** `14`  
**Description:** Minimum sky-light level required for crop growth events and animal breeding. Set to `0` to disable the check.

---

### cropsMinYHeight / cropsMaxYHeight / breedingMinYHeight / breedingMaxYHeight

**Type:** integer  
**Default:** `16` / `255` / `16` / `255`  
**Description:** Y-level range within which crop and breeding resource events can fire.

---

## Tax Settings

### taxIncomeRate

**Type:** float  
**Default:** `0.2`  
**Description:** Fraction of territory income that is redirected to the occupying town.

---

### taxMineRate / taxFarmRate / taxAnimalRate

**Type:** float  
**Default:** `0.2`  
**Description:** Probability that resources from mining, farming, or breeding go to the occupier instead of the territory owner.

---

## War Settings

### restrictExplosions

**Type:** boolean  
**Default:** `true`  
**Description:** Restricts explosion block damage according to war state.

---

### onlyAllowExplosionsDuringWar

**Type:** boolean  
**Default:** `true`  
**Description:** Allows explosion block damage only while a war is active.

---

### flagNoBuildDistance

**Type:** integer  
**Default:** `1`  
**Description:** Square radius around a war flag in which building is prohibited.

---

### flagNoBuildYOffset

**Type:** integer  
**Default:** `-1`  
**Description:** Building is prohibited above `flagBaseY + flagNoBuildYOffset`.

---

### chunkAttackTime

**Type:** integer (ticks)  
**Default:** `1200`  
**Description:** Time in ticks to capture a chunk with a war flag.

---

### chunkAttackFromWastelandMultiplier / chunkAttackHomeMultiplier

**Type:** float  
**Default:** `2.0`  
**Description:** Multipliers applied to `chunkAttackTime` when attacking from wasteland or attacking the enemy's home territory.

---

### maxPlayerChunkAttacks

**Type:** integer  
**Default:** `1`  
**Description:** Maximum number of chunks a single player can be attacking simultaneously.

---

### allowBreakingAlliesFlags

**Type:** boolean  
**Default:** `false`  
**Description:** Whether players can break war flags belonging to their allies.

---

### nationJoinCooldown

**Type:** integer (minutes)  
**Default:** `-1` (disabled)  
**Description:** After a player leaves a town in a nation, or is kicked from one, they cannot join a town in a *different* nation for this many minutes. Rejoining their old nation is always allowed, and admin commands ignore the cooldown.

---

### flagBeaconSize / flagBeaconMinSkyLevel / flagBeaconSkyLevel

**Type:** integer  
**Default:** `6` / `100` / `50`  
**Description:** Visual beacon displayed above war flags. `flagBeaconSize` must be in `[2, 16]`.

---

### allowDestructionDuringSkirmish

**Type:** boolean  
**Default:** `false`  
**Description:** Whether war permissions (building/destroying) are allowed during skirmish mode.

---

### warPermissions

**Type:** boolean  
**Default:** `true`  
**Description:** Bypasses normal permissions to allow extended ally interactions in towns during war.

---

### canLeaveTownDuringWar / canCreateTownDuringWar / canDestroyTownDuringWar / canLeaveNationDuringWar

**Type:** boolean  
**Defaults:** `true` / `false` / `false` / `false`  
**Description:** Controls which town/nation management actions are allowed while a war is active.

---

### annexDisabled

**Type:** boolean  
**Default:** `false`  
**Description:** Globally disables the territory annexation mechanic.

---

### canOnlyAnnexDuringWar

**Type:** boolean  
**Default:** `true`  
**Description:** Restricts territory annexation to war time only.

---

### warWhitelist / warBlacklist / annexBlacklist

**Type:** list of town UUIDs  
**Default:** (none)  
**Description:** Lists of town UUIDs that are specifically allowed or protected in war/annexation. Commented out by default.

---

### onlyWhitelistCanAnnex / onlyWhitelistCanClaim

**Type:** boolean  
**Default:** `true`  
**Description:** Restricts annexation and claiming to towns on the war whitelist.

---

### occupiedHomeTeleportMultiplier

**Type:** float  
**Default:** `12.0`  
**Description:** Multiplier applied to the home teleport warm-up time when the town's home territory is occupied.

---

### allowControlInOccupiedTownList

**Type:** list of town UUIDs  
**Default:** (none)  
**Description:** Towns on this list allow building/interaction in their occupied territories even outside war time.

---

## Truce Settings

### trucePeriod

**Type:** integer (milliseconds)  
**Default:** `259200000` (72 hours)  
**Description:** How long a truce lasts after a war ends.

---

## Port Settings

### seaLevel

**Type:** float  
**Default:** `62.0`  
**Description:** Y-level used as the sea level for port warp eligibility checks.

---

### portWarpTime

**Type:** float (seconds)  
**Default:** `5.0`  
**Description:** Warm-up time in seconds before a port warp completes.

---

### allowPortWarpWithoutBoat

**Type:** boolean  
**Default:** `false`  
**Description:** Whether players can use port warps without being in a boat.
