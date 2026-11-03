package phonon.nodes.commands

import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.bounty.Bounties
import phonon.nodes.bounty.BountyItems
import phonon.nodes.economy.Economy
import phonon.nodes.economy.EconomyExtras
import java.util.UUID

private const val PERM_ADD = "nodes.bounty.add"
private const val PERM_ADMIN = "nodes.bounty.admin"
private const val PAGE_SIZE = 10

class BountyCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            printHelp(sender)
            return true
        }
        when (args[0].lowercase()) {
            "help" -> printHelp(sender)
            "list" -> cmdList(sender, args)
            "info" -> cmdInfo(sender, args)
            "add" -> cmdAdd(sender, args)
            "additem" -> cmdAddItem(sender, args)
            "addmoney" -> cmdAddMoney(sender, args)
            "addreward" -> cmdAddReward(sender, args)
            "materials" -> cmdMaterials(sender)
            "rewards" -> cmdRewards(sender)
            "collect" -> cmdCollect(sender)
            "wallet" -> cmdWallet(sender)
            "status" -> cmdStatus(sender)
            "audit" -> cmdAudit(sender, args)
            "refund" -> cmdRefund(sender, args)
            "return" -> cmdReturn(sender, args)
            else -> printHelp(sender)
        }
        return true
    }

    private fun isAdmin(sender: CommandSender): Boolean = sender.hasPermission(PERM_ADMIN)

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Bounty commands:")
        Message.print(sender, "/bounty list [page]${ChatColor.WHITE}: Players with ore bounties")
        Message.print(sender, "/bounty info <player> [page]${ChatColor.WHITE}: Rewards, funders and reasons")
        Message.print(sender, "/bounty add <player> <ore> <amount> [reason]${ChatColor.WHITE}: Fund a bounty from your inventory")
        Message.print(sender, "/bounty additem <player> [reason]${ChatColor.WHITE}: Fund with the stack in your hand")
        if (Economy.enabled) {
            Message.print(sender, "/bounty addmoney <player> <amount> [reason]${ChatColor.WHITE}: Fund with money from your wallet")
        }
        Message.print(sender, "/bounty addreward <player> <package> [reason]${ChatColor.WHITE}: Fund a preset package")
        Message.print(sender, "/bounty materials${ChatColor.WHITE}: Allowed reward ores")
        Message.print(sender, "/bounty rewards${ChatColor.WHITE}: Preset packages")
        Message.print(sender, "/bounty collect${ChatColor.WHITE}: Receive rewards waiting for inventory space")
        Message.print(sender, "/bounty wallet${ChatColor.WHITE}: Rewards waiting for you")
        if (isAdmin(sender)) {
            Message.print(sender, "${ChatColor.GRAY}Admin: /bounty status, audit [page], refund <id>, return <delivery-id>")
        }
    }

    private fun player(sender: CommandSender): Player? {
        if (sender !is Player) {
            Message.error(sender, "Players only")
            return null
        }
        return sender
    }

    private fun page(args: Array<out String>, index: Int): Int {
        val raw = args.getOrNull(index) ?: return 0
        return ((raw.toIntOrNull() ?: 1).coerceIn(1, 100000)) - 1
    }

    private fun name(uuid: UUID): String = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()

    // only players loaded in Nodes (joined this server) can be targeted
    private fun resolveTarget(sender: CommandSender, name: String): Pair<OfflinePlayer, String>? {
        val resident = Nodes.getResidentFromName(name)
        if (resident == null) {
            Message.error(sender, "Unknown player. Targets must have joined this server before")
            return null
        }
        return Pair(Bukkit.getOfflinePlayer(resident.uuid), resident.name)
    }

    private fun reason(sender: CommandSender, args: Array<out String>, offset: Int): String? {
        val text = if (args.size > offset) args.drop(offset).joinToString(" ") else "Player bounty"
        if (text.length > 120 || text.any { it.isISOControl() || it == '§' }) {
            Message.error(sender, "Reason must be plain text, up to 120 characters")
            return null
        }
        return text
    }

    private fun place(player: Player, target: Pair<OfflinePlayer, String>, items: List<ItemStack>, reason: String) {
        Bounties.fund(player, target.first, target.second, items, reason)?.let { Message.error(player, it) }
    }

    private fun cmdAdd(sender: CommandSender, args: Array<out String>) {
        val player = player(sender) ?: return
        if (!player.hasPermission(PERM_ADD)) {
            Message.error(player, "You do not have permission to fund bounties")
            return
        }
        if (args.size < 4) {
            Message.error(player, "Usage: /bounty add <player> <ore> <amount> [reason]  e.g. /bounty add Steve DIAMOND 32")
            return
        }
        val target = resolveTarget(player, args[1]) ?: return
        val material = Material.matchMaterial(args[2])
        if (material == null || material !in Bounties.allowed) {
            Message.error(player, "Unknown ore reward. Use /bounty materials")
            return
        }
        val amount = args[3].toIntOrNull()
        if (amount == null || amount < 1 || amount.toLong() > Bounties.maxStacks.toLong() * material.maxStackSize) {
            Message.error(player, "Ore amount must be a positive whole number within the stack limit")
            return
        }
        val reason = reason(player, args, 4) ?: return
        place(player, target, BountyItems.stacks(material, amount), reason)
    }

    private fun cmdAddMoney(sender: CommandSender, args: Array<out String>) {
        val player = player(sender) ?: return
        if (!player.hasPermission(PERM_ADD)) {
            Message.error(player, "You do not have permission to fund bounties")
            return
        }
        if (args.size < 3) {
            Message.error(player, "Usage: /bounty addmoney <player> <amount> [reason]  (paid from your wallet)")
            return
        }
        val target = resolveTarget(player, args[1]) ?: return
        val reason = reason(player, args, 3) ?: return
        Bounties.fundMoney(player, target.first, target.second, args[2], reason)?.let { Message.error(player, it) }
    }

    private fun cmdAddItem(sender: CommandSender, args: Array<out String>) {
        val player = player(sender) ?: return
        if (!player.hasPermission(PERM_ADD)) {
            Message.error(player, "You do not have permission to fund bounties")
            return
        }
        if (args.size < 2) {
            Message.error(player, "Usage: /bounty additem <player> [reason]")
            return
        }
        val target = resolveTarget(player, args[1]) ?: return
        val held = player.inventory.itemInMainHand
        if (held.type.isAir || held.amount <= 0) {
            Message.error(player, "Hold the reward stack in your main hand")
            return
        }
        val reason = reason(player, args, 2) ?: return
        place(player, target, listOf(held.clone()), reason)
    }

    private fun cmdAddReward(sender: CommandSender, args: Array<out String>) {
        val player = player(sender) ?: return
        if (!player.hasPermission(PERM_ADD)) {
            Message.error(player, "You do not have permission to fund bounties")
            return
        }
        if (args.size < 3) {
            Message.error(player, "Usage: /bounty addreward <player> <package> [reason]")
            return
        }
        val target = resolveTarget(player, args[1]) ?: return
        val items = Bounties.packageItems(args[2])
        if (items == null) {
            Message.error(player, "Unknown reward package. Use /bounty rewards")
            return
        }
        val reason = reason(player, args, 3) ?: return
        place(player, target, items, reason)
    }

    private fun cmdMaterials(sender: CommandSender) {
        Message.print(sender, "Allowed ore rewards: ${Bounties.allowed.map { it.name }.sorted().joinToString(", ")}")
    }

    private fun cmdRewards(sender: CommandSender) {
        val where = if (Bounties.rewardToTown) "hunter's town income" else "hunter's inventory"
        Message.print(sender, "Ore rewards go to the $where. Packages are taken from your inventory:")
        for ((id, specs) in Bounties.packages) {
            Message.print(sender, "$id${ChatColor.WHITE}: ${specs.joinToString(", ") { "${it.second}x ${it.first.name}" }}")
        }
    }

    private fun cmdList(sender: CommandSender, args: Array<out String>) {
        val page = page(args, 1)
        val rows = Bounties.targets().drop(page * PAGE_SIZE).take(PAGE_SIZE)
        Message.print(sender, "${ChatColor.BOLD}Ore bounties - page ${page + 1}")
        if (rows.isEmpty()) {
            Message.print(sender, "No bounties on this page")
        }
        for ((bounty, count) in rows) {
            Message.print(sender, "${bounty.targetName}${ChatColor.WHITE}: $count contribution(s). /bounty info ${bounty.targetName}")
        }
        if (Economy.enabled && page == 0) {
            for ((_, targetName, total) in EconomyExtras.moneyTargets().take(PAGE_SIZE)) {
                Message.print(sender, "$targetName${ChatColor.WHITE}: ${Economy.show(total)} in money. /bounty info $targetName")
            }
        }
    }

    private fun cmdInfo(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            Message.error(sender, "Usage: /bounty info <player> [page]")
            return
        }
        val target = resolveTarget(sender, args[1]) ?: return
        val page = page(args, 2)
        val rows = Bounties.active(target.first.uniqueId)
        val moneyRows = if (Economy.enabled) EconomyExtras.activeMoney(target.first.uniqueId) else emptyList()
        Message.print(sender, "${ChatColor.BOLD}${target.second}${ChatColor.RESET}: ${rows.size + moneyRows.size} contribution(s)")
        if (page == 0) {
            for ((id, money) in moneyRows.take(PAGE_SIZE)) {
                val tag = if (isAdmin(sender)) " ${ChatColor.GRAY}[$id]" else ""
                Message.print(sender, "${Economy.show(money.cents)} money${ChatColor.WHITE} - ${money.reason} (funded by ${name(java.util.UUID.fromString(money.sponsor))})$tag")
            }
        }
        for (bounty in rows.drop(page * PAGE_SIZE).take(PAGE_SIZE)) {
            val id = if (isAdmin(sender)) " ${ChatColor.GRAY}[${bounty.id}]" else ""
            Message.print(sender, "${bounty.summary}${ChatColor.WHITE} - ${bounty.reason} (funded by ${name(bounty.sponsor)})$id")
        }
        if (rows.size > (page + 1) * PAGE_SIZE) {
            Message.print(sender, "More: /bounty info ${target.second} ${page + 2}")
        }
    }

    private fun cmdCollect(sender: CommandSender) {
        val player = player(sender) ?: return
        Bounties.deliverPending(player)
        Message.print(player, "Checked your queued rewards. Make inventory space if anything is still waiting")
    }

    private fun cmdWallet(sender: CommandSender) {
        val player = player(sender) ?: return
        val rows = Bounties.queuedFor(player.uniqueId)
        Message.print(player, "Rewards waiting for you: ${rows.size}. Use /bounty collect")
        for (d in rows.take(5)) {
            Message.print(player, d.summary)
        }
    }

    private fun requireAdmin(sender: CommandSender): Boolean {
        if (!isAdmin(sender)) {
            printHelp(sender)
            return false
        }
        return true
    }

    private fun cmdStatus(sender: CommandSender) {
        if (!requireAdmin(sender)) {
            return
        }
        val where = if (Bounties.rewardToTown) "TOWN_INCOME" else "PLAYER"
        Message.print(sender, "Bounties enabled=${Bounties.enabled}, destination=$where, queued deliveries=${Bounties.queuedCount()}")
    }

    private fun cmdAudit(sender: CommandSender, args: Array<out String>) {
        if (!requireAdmin(sender)) {
            return
        }
        val page = page(args, 1)
        val rows = Bounties.queued()
        Message.print(sender, "Queued deliveries: ${rows.size} (page ${page + 1}). Missing towns can be returned with /bounty return <id>")
        for (d in rows.drop(page * 5).take(5)) {
            val kind = if (d.toTown) "TOWN" else "PLAYER ${name(d.recipient)}"
            Message.print(sender, "${d.id} | $kind | ${d.summary}")
        }
    }

    private fun cmdRefund(sender: CommandSender, args: Array<out String>) {
        if (!requireAdmin(sender)) {
            return
        }
        if (args.size != 2) {
            Message.error(sender, "Usage: /bounty refund <contribution-id>")
            return
        }
        if (EconomyExtras.hasMoneyContribution(args[1])) {
            val moneyError = EconomyExtras.refundMoney(args[1])
            if (moneyError != null) {
                Message.error(sender, moneyError)
            } else {
                Message.print(sender, "Money returned to the original funder's wallet")
            }
            return
        }
        val error = Bounties.refund(args[1])
        if (error != null) {
            Message.error(sender, error)
            return
        }
        Message.print(sender, "Ores queued for return to the original funder")
    }

    private fun cmdReturn(sender: CommandSender, args: Array<out String>) {
        if (!requireAdmin(sender)) {
            return
        }
        if (args.size != 2) {
            Message.error(sender, "Usage: /bounty return <delivery-id>")
            return
        }
        val error = Bounties.returnQueued(args[1])
        if (error != null) {
            Message.error(sender, error)
            return
        }
        Message.print(sender, "Delivery returned to its original funder")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val options: Collection<String> = when {
            args.size == 1 -> {
                val subs = mutableListOf("help", "list", "info", "add", "additem", "addmoney", "addreward", "materials", "rewards", "collect", "wallet")
                if (isAdmin(sender)) {
                    subs.addAll(listOf("status", "audit", "refund", "return"))
                }
                subs
            }

            args.size == 2 && args[0].lowercase() in setOf("add", "additem", "addmoney", "addreward", "info") -> Nodes.residents.values.map { it.name }

            args.size == 3 && args[0].equals("add", ignoreCase = true) -> Bounties.allowed.map { it.name }

            args.size == 3 && args[0].equals("addreward", ignoreCase = true) -> Bounties.packages.keys

            else -> emptyList()
        }
        val prefix = args.last().lowercase()
        return options.filter { it.lowercase().startsWith(prefix) }.sorted().take(80)
    }
}
