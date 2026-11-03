package phonon.nodes.commands

import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import phonon.nodes.Message
import phonon.nodes.Nodes
import phonon.nodes.economy.Economy
import phonon.nodes.economy.EconomyException
import phonon.nodes.economy.EconomyExtras
import phonon.nodes.economy.EconomyMenu

private fun guarded(sender: CommandSender, block: () -> Unit) {
    try {
        block()
    } catch (e: EconomyException) {
        Message.error(sender, e.message ?: "Economy error")
    } catch (e: Exception) {
        Nodes.logger?.severe("[Economy] Operation stopped: $e")
        Message.error(sender, "Operation stopped, see the server log. No partial transaction was saved")
    }
}

private fun need(args: Array<out String>, count: Int, usage: String) {
    if (args.size != count) {
        throw EconomyException("Usage: $usage")
    }
}

private fun filter(options: Collection<String>, prefix: String): List<String> = options.filter { it.lowercase().startsWith(prefix.lowercase()) }.sorted().take(80)

class EconomyCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!Economy.enabled) {
            Message.error(sender, "The economy is disabled")
            return true
        }
        if (sender !is Player) {
            Message.error(sender, "Players only")
            return true
        }
        if (args.isEmpty()) {
            printHelp(sender)
            return true
        }
        guarded(sender) { run(sender, args) }
        return true
    }

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Economy commands (account: player, town, nation or business-<name>):")
        Message.print(sender, "/economy balance <account>${ChatColor.WHITE}: Balance and your wallet")
        Message.print(sender, "/economy deposit <account> <amount>${ChatColor.WHITE}: Wallet to account")
        Message.print(sender, "/economy withdraw <account> <amount>${ChatColor.WHITE}: Account to wallet")
        Message.print(sender, "/economy pay <account> <player> <amount>${ChatColor.WHITE}: Pay a player's bank")
        Message.print(sender, "/economy history <account>${ChatColor.WHITE}: Latest 20 records")
        Message.print(sender, "/economy business create <name>${ChatColor.WHITE}: Open a business account")
        Message.print(sender, "/economy menu${ChatColor.WHITE}: Open the economy menu")
        Message.print(sender, "/economy prices${ChatColor.WHITE}: What the server buys")
        Message.print(sender, "/economy sell hand|all|town${ChatColor.WHITE}: Sell items to the server (town: stored income, to the treasury)")
        Message.print(sender, "/economy market list|mine|sell|buy|cancel${ChatColor.WHITE}: Player market (sell uses the item in your hand)")
        Message.print(sender, "/economy top [players|nations|towns]${ChatColor.WHITE}: Richest lists")
        Message.print(sender, "/economy stock${ChatColor.WHITE}: Your nation's trade resources")
        Message.print(sender, "/economy route list|offer|accept|cancel${ChatColor.WHITE}: Recurring trade routes")
        Message.print(sender, "/economy loan request|list|repay${ChatColor.WHITE}: Loans from the reserve")
        Message.print(sender, "/economy infra list|pay${ChatColor.WHITE}: Infrastructure upkeep")
        Message.print(sender, "${ChatColor.GRAY}Nation accounts, routes, loans and infrastructure need the capital leader or an officer. Town accounts need the town leader or an officer.")
    }

    private fun run(p: Player, a: Array<out String>) {
        when (a[0].lowercase()) {
            "help" -> printHelp(p)

            "balance" -> {
                need(a, 2, "/economy balance <account>")
                Message.print(p, Economy.balanceOf(p, a[1]))
            }

            "deposit", "withdraw" -> {
                need(a, 3, "/economy ${a[0].lowercase()} <account> <amount>")
                Message.print(p, Economy.transfer(p, a[0].equals("deposit", ignoreCase = true), a[1], a[2]))
            }

            "pay" -> {
                need(a, 4, "/economy pay <account> <player> <amount>")
                Message.print(p, Economy.pay(p, a[1], a[2], a[3]))
            }

            "business" -> {
                need(a, 3, "/economy business create <name>")
                if (!a[1].equals("create", ignoreCase = true)) {
                    throw EconomyException("Usage: /economy business create <name>")
                }
                Message.print(p, Economy.createBusiness(p, a[2]))
            }

            "history" -> {
                need(a, 2, "/economy history <account>")
                Economy.history(p, a[1]).forEach { Message.print(p, it) }
            }

            "stock" -> {
                val lines = Economy.stock(p)
                if (lines.isEmpty()) {
                    Message.print(p, "Your nation has no trade resources")
                }
                lines.forEach { Message.print(p, it) }
            }

            "menu" -> EconomyMenu.openMain(p)

            "prices" -> {
                val lines = EconomyExtras.prices()
                if (lines.isEmpty()) {
                    Message.print(p, "The server does not buy anything")
                }
                lines.forEach { Message.print(p, it) }
            }

            "sell" -> {
                need(a, 2, "/economy sell hand|all|town")
                Message.print(
                    p,
                    when (a[1].lowercase()) {
                        "hand" -> EconomyExtras.sellHand(p)
                        "all" -> EconomyExtras.sellAll(p)
                        "town" -> EconomyExtras.sellTown(p)
                        else -> throw EconomyException("Usage: /economy sell hand|all|town")
                    },
                )
            }

            "market" -> market(p, a)

            "top" -> {
                val lines = when (a.getOrNull(1)?.lowercase() ?: "players") {
                    "players" -> EconomyExtras.topPlayers(10)
                    "nations" -> EconomyExtras.topNations(10)
                    "towns" -> EconomyExtras.topTowns(10)
                    else -> throw EconomyException("Usage: /economy top [players|nations|towns]")
                }
                if (lines.isEmpty()) {
                    Message.print(p, "Nothing to rank yet")
                }
                lines.forEach { Message.print(p, it) }
            }

            "route" -> route(p, a)

            "loan" -> loan(p, a)

            "infra" -> infra(p, a)

            else -> printHelp(p)
        }
    }

    private fun market(p: Player, a: Array<out String>) {
        when (a.getOrNull(1)?.lowercase()) {
            "list" -> EconomyExtras.marketLines(a.getOrNull(2)?.toIntOrNull() ?: 1).forEach { Message.print(p, it) }

            "mine" -> EconomyExtras.marketLines(1, p.uniqueId).forEach { Message.print(p, it) }

            "sell" -> {
                need(a, 3, "/economy market sell <price> (hold the item stack)")
                Message.print(p, EconomyExtras.marketSell(p, a[2]))
            }

            "buy" -> {
                need(a, 3, "/economy market buy <id>")
                Message.print(p, EconomyExtras.marketBuy(p, a[2]))
            }

            "cancel" -> {
                need(a, 3, "/economy market cancel <id>")
                Message.print(p, EconomyExtras.marketCancel(p, a[2], p.hasPermission("nodes.economy.admin")))
            }

            else -> throw EconomyException("Usage: /economy market list|mine|sell|buy|cancel")
        }
    }

    private fun route(p: Player, a: Array<out String>) {
        when (a.getOrNull(1)?.lowercase()) {
            "list" -> {
                val lines = Economy.routeList(p)
                if (lines.isEmpty()) {
                    Message.print(p, "Your nation has no routes")
                }
                lines.forEach { Message.print(p, it) }
            }

            "offer" -> {
                need(a, 7, "/economy route offer <buyer-nation> <material> <quantity> <total-price> <interval-minutes>")
                Message.print(p, Economy.routeOffer(p, a[2], a[3], a[4], a[5], a[6]))
            }

            "accept" -> {
                need(a, 3, "/economy route accept <id>")
                Message.print(p, Economy.routeAccept(p, a[2]))
            }

            "cancel" -> {
                need(a, 3, "/economy route cancel <id>")
                Message.print(p, Economy.routeCancel(p, a[2]))
            }

            else -> throw EconomyException("Usage: /economy route list|offer|accept|cancel")
        }
    }

    private fun loan(p: Player, a: Array<out String>) {
        when (a.getOrNull(1)?.lowercase()) {
            "request" -> {
                need(a, 4, "/economy loan request <account> <amount>")
                Message.print(p, Economy.loanRequest(p, a[2], a[3]))
            }

            "list" -> {
                need(a, 3, "/economy loan list <account>")
                val lines = Economy.loanList(p, a[2])
                if (lines.isEmpty()) {
                    Message.print(p, "No loans")
                }
                lines.forEach { Message.print(p, it) }
            }

            "repay" -> {
                need(a, 5, "/economy loan repay <account> <loan-id> <amount>")
                Message.print(p, Economy.loanRepay(p, a[2], a[3], a[4]))
            }

            else -> throw EconomyException("Usage: /economy loan request|list|repay")
        }
    }

    private fun infra(p: Player, a: Array<out String>) {
        when (a.getOrNull(1)?.lowercase()) {
            "list" -> {
                val lines = Economy.infraList(p)
                if (lines.isEmpty()) {
                    Message.print(p, "Your nation has no registered infrastructure")
                }
                lines.forEach { Message.print(p, it) }
            }

            "pay" -> {
                need(a, 3, "/economy infra pay <id>")
                Message.print(p, Economy.infraPay(p, a[2]))
            }

            else -> throw EconomyException("Usage: /economy infra list|pay")
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val accounts = listOf("player", "town", "nation")
        val options: Collection<String> = when {
            args.size == 1 -> listOf("help", "balance", "deposit", "withdraw", "pay", "history", "business", "menu", "prices", "sell", "market", "top", "stock", "route", "loan", "infra")
            args.size == 2 && args[0].equals("sell", ignoreCase = true) -> listOf("hand", "all", "town")
            args.size == 2 && args[0].equals("market", ignoreCase = true) -> listOf("list", "mine", "sell", "buy", "cancel")
            args.size == 2 && args[0].equals("top", ignoreCase = true) -> listOf("players", "nations", "towns")
            args.size == 2 && args[0].lowercase() in setOf("balance", "deposit", "withdraw", "pay", "history") -> accounts
            args.size == 2 && args[0].equals("business", ignoreCase = true) -> listOf("create")
            args.size == 2 && args[0].equals("route", ignoreCase = true) -> listOf("list", "offer", "accept", "cancel")
            args.size == 2 && args[0].equals("loan", ignoreCase = true) -> listOf("request", "list", "repay")
            args.size == 2 && args[0].equals("infra", ignoreCase = true) -> listOf("list", "pay")
            args.size == 3 && args[0].equals("pay", ignoreCase = true) -> Nodes.residents.values.map { it.name }
            args.size == 3 && args[0].equals("route", ignoreCase = true) && args[1].equals("offer", ignoreCase = true) -> Nodes.nations.values.map { it.name }
            args.size == 3 && args[0].equals("loan", ignoreCase = true) -> accounts
            else -> emptyList()
        }
        return filter(options, args.last())
    }
}

class EconomyAdminCommand :
    CommandExecutor,
    TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!Economy.enabled) {
            Message.error(sender, "The economy is disabled")
            return true
        }
        if (args.isEmpty()) {
            printHelp(sender)
            return true
        }
        guarded(sender) { run(sender, args) }
        return true
    }

    private fun printHelp(sender: CommandSender) {
        Message.print(sender, "${ChatColor.BOLD}[Nodes] Economy admin commands:")
        Message.print(sender, "/economyadmin issue <player|reserve> <amount>${ChatColor.WHITE}: Create currency")
        Message.print(sender, "/economyadmin vote <player> [service]${ChatColor.WHITE}: Pay the vote reward (for vote plugins that run commands)")
        Message.print(sender, "/economyadmin stock <nation> <material> <quantity>${ChatColor.WHITE}: Issue trade resources")
        Message.print(sender, "/economyadmin loan approve|reject <id>${ChatColor.WHITE}: Decide a loan request")
        Message.print(sender, "/economyadmin infra <nation> <railway|port|road|power> <units> <name>${ChatColor.WHITE}: Register infrastructure")
        Message.print(sender, "/economyadmin retire <name>${ChatColor.WHITE}: Retire infrastructure once its arrears are paid")
    }

    private fun run(sender: CommandSender, a: Array<out String>) {
        when (a[0].lowercase()) {
            "issue" -> {
                need(a, 3, "/economyadmin issue <player|reserve> <amount>")
                Message.print(sender, Economy.issue(a[1], a[2], sender.name))
            }

            "vote" -> {
                if (a.size < 2 || a.size > 3) {
                    throw EconomyException("Usage: /economyadmin vote <player> [service]")
                }
                Message.print(sender, EconomyExtras.vote(a[1], a.getOrNull(2) ?: "console"))
            }

            "stock" -> {
                need(a, 4, "/economyadmin stock <nation> <material> <quantity>")
                Message.print(sender, Economy.issueStock(a[1], a[2], a[3], sender.name))
            }

            "loan" -> {
                need(a, 3, "/economyadmin loan approve|reject <id>")
                when (a[1].lowercase()) {
                    "approve" -> Message.print(sender, Economy.loanApprove(a[2]))
                    "reject" -> Message.print(sender, Economy.loanReject(a[2]))
                    else -> throw EconomyException("Usage: /economyadmin loan approve|reject <id>")
                }
            }

            "infra" -> {
                need(a, 5, "/economyadmin infra <nation> <railway|port|road|power> <units> <name>")
                Message.print(sender, Economy.registerInfra(a[1], a[2], a[3], a[4]))
            }

            "retire" -> {
                need(a, 2, "/economyadmin retire <name>")
                Message.print(sender, Economy.retireInfra(a[1]))
            }

            else -> printHelp(sender)
        }
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val options: Collection<String> = when {
            args.size == 1 -> listOf("issue", "vote", "stock", "loan", "infra", "retire")
            args.size == 2 && args[0].equals("vote", ignoreCase = true) -> Nodes.residents.values.map { it.name }
            args.size == 2 && args[0].equals("issue", ignoreCase = true) -> Nodes.residents.values.map { it.name } + "reserve"
            args.size == 2 && args[0].lowercase() in setOf("stock", "infra") -> Nodes.nations.values.map { it.name }
            args.size == 2 && args[0].equals("loan", ignoreCase = true) -> listOf("approve", "reject")
            args.size == 3 && args[0].equals("infra", ignoreCase = true) -> Economy.INFRA_TYPES
            else -> emptyList()
        }
        return filter(options, args.last())
    }
}
