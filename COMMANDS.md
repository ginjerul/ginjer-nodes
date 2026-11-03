# Commands Reference

All commands provided by the Nodes plugin are listed below. Use `/command help` for in-game sub-command help.

## General Commands

### /nodes \[subcommand\]

**Description:** Display general Nodes information.  
**Aliases:** `/nd`  
**Permission:** `nodes.command.nodes`  
**Usage:** `/nodes help`

### /player \[name\]

**Description:** View info about yourself or another player (town, nation, claim power).  
**Aliases:** `/p`  
**Permission:** `nodes.command.player`  
**Usage:** `/player` or `/player <name>`

### /territory \[id\]

**Description:** View information about the territory you are standing in, or by ID.  
**Permission:** `nodes.command.territory`  
**Usage:** `/territory` or `/territory <id>`

---

## Town Commands

### /town \<subcommand\>

**Description:** Manage your town.  
**Aliases:** `/t`  
**Permission:** `nodes.command.town`  
**Usage:** `/town help`

Common sub-commands:

| Sub-command | Description |
|-------------|-------------|
| `create <name>` | Create a new town |
| `disband` | Disband your town |
| `info [name]` | Show town info |
| `list` | List all towns |
| `invite <player>` | Invite a player to join |
| `join <town>` | Join a town you've been invited to |
| `leave` | Leave your current town |
| `kick <player>` | Kick a player from your town |
| `sethome` | Set the town's home point |
| `home` | Teleport to your town's home |
| `claim` | Claim the chunk you're standing in |
| `unclaim` | Unclaim the chunk you're standing in |
| `annex <territory>` | Annex a captured territory |
| `setleader <player>` | Transfer town leadership |
| `fly` | Toggle fly mode inside your town |

---

## Nation Commands

### /nation \<subcommand\>

**Description:** Manage your nation.  
**Aliases:** `/n`  
**Permission:** `nodes.command.nation`  
**Usage:** `/nation help`

Common sub-commands:

| Sub-command | Description |
|-------------|-------------|
| `create <name>` | Create a new nation |
| `disband` | Disband your nation |
| `info [name]` | Show nation info |
| `list` | List all nations |
| `invite <town>` | Invite a town to join |
| `join <nation>` | Join a nation your town has been invited to |
| `leave` | Leave your current nation |
| `kick <town>` | Remove a town from your nation |
| `setleader <town>` | Transfer nation leadership |

---

## Diplomacy Commands

### /ally \<subcommand\>

**Description:** Form or manage alliances with another town or nation.  
**Permission:** `nodes.command.ally`  
**Usage:** `/ally help`

### /unally \<subcommand\>

**Description:** Break an alliance with another town or nation.  
**Permission:** `nodes.command.unally`  
**Usage:** `/unally help`

### /war \<subcommand\>

**Description:** Declare or manage a war with another town or nation.  
**Permission:** `nodes.command.war`  
**Usage:** `/war help`

### /peace \<subcommand\>

**Description:** Request peace with another town or nation.  
**Permission:** `nodes.command.peace`  
**Usage:** `/peace help`

### /truce

**Description:** View active truces and the time remaining on each.  
**Permission:** `nodes.command.truce`  
**Usage:** `/truce`

---

## Chat Commands

### /globalchat

**Description:** Switch your chat to global (all players).  
**Aliases:** `/gc`  
**Permission:** `nodes.command.chat.global`

### /townchat

**Description:** Switch your chat to town-only.  
**Aliases:** `/tc`  
**Permission:** `nodes.command.chat.town`

### /nationchat

**Description:** Switch your chat to nation-only.  
**Aliases:** `/nc`  
**Permission:** `nodes.command.chat.nation`

### /allychat

**Description:** Switch your chat to ally-only.  
**Aliases:** `/ac`  
**Permission:** `nodes.command.chat.ally`

---

## Port Commands

### /port \<subcommand\>

**Description:** Use port warps to travel between coastal territories.  
**Permission:** `nodes.command.port`  
**Usage:** `/port help`

Common sub-commands:

| Sub-command | Description |
|-------------|-------------|
| `list` | List all available ports |
| `warp <name>` | Warp to a port |

---

## Admin Commands

### /nodesadmin \<subcommand\>

**Description:** Administrative commands for managing the Nodes plugin.  
**Aliases:** `/nda`  
**Permission:** `nodes.admin` for everything. Lower staff can get `nodes.admin.<command>` (all of one command, e.g. `nodes.admin.town`) or `nodes.admin.<command>.<action>` (one action, e.g. `nodes.admin.town.addplayer`) instead. `/nda help` and tab completion only show what the sender may use.  
**Usage:** `/nodesadmin help`
