/*
 * National economy: nation, personal and business accounts, recurring
 * import/export routes, loans from a funded reserve and infrastructure upkeep.
 * Currency is separate from Vault. All balances live in one ledger that is
 * saved atomically, so a failed operation leaves no partial transaction.
 */

package phonon.nodes.economy

import com.google.gson.GsonBuilder
import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.entity.Player
import phonon.nodes.Nodes
import phonon.nodes.objects.Nation
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

class EconomyException(message: String) : RuntimeException(message)

class EcoRecord(
    var time: Long = 0L,
    var action: String = "",
    var from: String = "",
    var to: String = "",
    var amount: Long = 0L,
)

class EcoRoute(
    var seller: String = "",
    var buyer: String = "",
    var material: String = "",
    var quantity: Long = 0L,
    var price: Long = 0L,
    var interval: Long = 0L,
    var status: String = "OFFER",
    var expires: Long = 0L,
    var next: Long = 0L,
    var lastResult: String = "",
    var lastDelivery: Long = 0L,
)

class EcoLoan(
    var borrower: String = "",
    var owed: Long = 0L,
    var status: String = "REQUESTED",
    var period: Long = 0L,
    var bps: Int = 0,
    var remaining: Int = 0,
    var next: Long = 0L,
)

class EcoInfra(
    var nation: String = "",
    var type: String = "",
    var units: Long = 0L,
    var cost: Long = 0L,
    var status: String = "ACTIVE",
    var next: Long = 0L,
    var arrears: Long = 0L,
)

class EcoState(
    var balances: MutableMap<String, Long> = LinkedHashMap(),
    var records: MutableList<EcoRecord> = ArrayList(),
    var businesses: MutableMap<String, String> = LinkedHashMap(),
    var stock: MutableMap<String, MutableMap<String, Long>> = LinkedHashMap(),
    var routes: MutableMap<String, EcoRoute> = LinkedHashMap(),
    var loans: MutableMap<String, EcoLoan> = LinkedHashMap(),
    var infra: MutableMap<String, EcoInfra> = LinkedHashMap(),
)

object Economy {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val path: Path = Paths.get("plugins/nodes/economy.json")

    private const val DAY_MS = 86_400_000L
    private const val MAX_RECORDS = 5000

    // ===================================
    // config (economy: section of config.yml)
    // ===================================
    // stays off until the economy: section exists in config.yml
    var enabled = false
    private var resources: Set<String> = setOf(
        "COAL",
        "IRON_INGOT",
        "COPPER_INGOT",
        "GOLD_INGOT",
        "DIAMOND",
        "EMERALD",
        "REDSTONE",
        "LAPIS_LAZULI",
    )
    private var loanMaximum = "10000"
    private var loanPeriodHours = 24L
    private var loanTermPeriods = 30
    private var loanInterestBps = 100
    private val infraRates: HashMap<String, String> = hashMapOf(
        "railway" to "5.00",
        "port" to "20.00",
        "road" to "1.00",
        "power" to "10.00",
    )

    val INFRA_TYPES: List<String> = listOf("railway", "port", "road", "power")

    private var state = EcoState()
    private var failed = false

    fun loadConfig(config: FileConfiguration) {
        val sec = config.getConfigurationSection("economy")
        if (sec == null) {
            enabled = false
            return
        }
        enabled = sec.getBoolean("enabled", true)
        if (sec.contains("resources")) {
            resources = sec.getStringList("resources")
                .mapNotNull { Material.matchMaterial(it) }
                .filter { it.isItem && !it.isAir }
                .map { it.name }
                .toSet()
        }
        loanMaximum = sec.getString("loans.maximum", loanMaximum)!!
        loanPeriodHours = sec.getLong("loans.periodHours", loanPeriodHours)
        loanTermPeriods = sec.getInt("loans.termPeriods", loanTermPeriods)
        loanInterestBps = sec.getInt("loans.interestBasisPoints", loanInterestBps)
        for (type in INFRA_TYPES) {
            infraRates[type] = sec.getString("infrastructure.$type", infraRates[type] ?: "1.00")!!
        }
    }

    // ===================================
    // persistence
    // ===================================
    fun load() {
        failed = false
        state = EcoState()
        if (!Files.exists(path)) {
            return
        }
        try {
            val loaded = gson.fromJson(String(Files.readAllBytes(path), StandardCharsets.UTF_8), EcoState::class.java)
            if (loaded != null) {
                state = loaded
            }
            Nodes.logger?.info("[Economy] Loaded ${state.balances.size} accounts, ${state.routes.size} routes, ${state.loans.size} loans")
        } catch (e: Exception) {
            failed = true
            Nodes.logger?.severe("[Economy] Could not read economy.json, operations are frozen: ${e.message}")
        }
    }

    // writes via a temp file and an atomic move so a crash never leaves a half-written ledger
    private fun write() {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling("economy.tmp")
        val bytes = gson.toJson(state).toByteArray(StandardCharsets.UTF_8)
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    // runs the work, saves, and rolls everything back if any step fails
    fun <T> transaction(work: () -> T): T {
        if (failed) {
            throw EconomyException("Economy storage is unavailable; restart after repair")
        }
        val before = gson.toJson(state)
        try {
            val result = work()
            write()
            return result
        } catch (e: Exception) {
            state = gson.fromJson(before, EcoState::class.java)
            if (e is IOException) {
                failed = true
            }
            throw e
        }
    }

    // ===================================
    // ledger primitives
    // ===================================
    fun parseMoney(text: String): Long {
        try {
            val cents = BigDecimal(text).movePointRight(2).longValueExact()
            if (cents <= 0 || cents > 100_000_000_000L) {
                throw ArithmeticException()
            }
            return cents
        } catch (_: Exception) {
            throw EconomyException("Use a positive amount with at most two decimals, up to 1 billion")
        }
    }

    fun show(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    fun balance(account: String): Long = state.balances[account] ?: 0L

    private fun credit(account: String, cents: Long) {
        val value = Math.addExact(balance(account), cents)
        if (value < 0) {
            throw EconomyException("Insufficient funds in ${label(account)}")
        }
        state.balances[account] = value
    }

    private fun requireAvailable(account: String) {
        if (state.loans.values.any { it.borrower == account && it.status == "DEFAULT" }) {
            throw EconomyException("${label(account)} is in default; repay the debt before spending")
        }
    }

    private fun log(action: String, from: String, to: String, amount: Long) {
        state.records.add(EcoRecord(System.currentTimeMillis(), action, from, to, amount))
        if (state.records.size > MAX_RECORDS) {
            state.records.subList(0, state.records.size - MAX_RECORDS).clear()
        }
    }

    private fun move(from: String, to: String, cents: Long, reason: String) {
        if (cents <= 0 || from == to) {
            throw EconomyException("Invalid transfer")
        }
        credit(from, -cents)
        credit(to, cents)
        log(reason, from, to, cents)
    }

    private fun newId(existing: Map<String, Any>): String {
        while (true) {
            val id = UUID.randomUUID().toString().substring(0, 8)
            if (!existing.containsKey(id)) {
                return id
            }
        }
    }

    // readable account name for messages
    private fun label(account: String): String {
        if (account.startsWith("nation-")) {
            val nation = nationByAccount(account)
            return nation?.name ?: "a deleted nation"
        }
        if (account.startsWith("player-") || account.startsWith("wallet-")) {
            val uuid = runCatching { UUID.fromString(account.substringAfter('-')) }.getOrNull()
            val name = uuid?.let { Nodes.getResidentFromUUID(it)?.name }
            return name ?: account
        }
        return account
    }

    private fun nationByAccount(account: String): Nation? = Nodes.nations.values.firstOrNull { "nation-${it.uuid}" == account }

    private fun nationExists(account: String): Boolean = nationByAccount(account) != null

    private fun token(text: String): String {
        if (!Regex("[a-z0-9_-]{1,32}").matches(text)) {
            throw EconomyException("Use 1-32 lowercase letters, numbers, underscores or hyphens")
        }
        return text
    }

    private fun positive(text: String): Long {
        val n = text.toLongOrNull()
        if (n == null || n < 1 || n > 1_000_000) {
            throw EconomyException("Quantity must be 1-1000000")
        }
        return n
    }

    private fun material(name: String): String {
        val m = Material.matchMaterial(name)
        if (m == null || m.name !in resources) {
            throw EconomyException("That resource is not enabled for trade")
        }
        return m.name
    }

    // ===================================
    // account lookup
    // ===================================
    private fun nationOf(player: Player): Nation = Nodes.getResident(player)?.nation ?: throw EconomyException("Join a nation first")

    // manage = true needs the capital leader or an officer
    private fun nationAccount(player: Player, manage: Boolean): String {
        val nation = nationOf(player)
        if (manage) {
            val resident = Nodes.getResident(player)
            val capital = nation.capital
            if (capital.leader !== resident && !capital.officers.contains(resident)) {
                throw EconomyException("Only the capital leader or an officer can do that")
            }
        }
        return "nation-${nation.uuid}"
    }

    private fun account(player: Player, name: String, manage: Boolean): String = when {
        name == "player" -> "player-${player.uniqueId}"

        name == "nation" -> nationAccount(player, manage)

        name.startsWith("business-") -> {
            if (state.businesses[name] != player.uniqueId.toString()) {
                throw EconomyException("Only the business owner can do that")
            }
            name
        }

        else -> throw EconomyException("Use player, nation, or business-<name>")
    }

    private fun resolvePlayerId(text: String): UUID {
        Nodes.getResidentFromName(text)?.let { return it.uuid }
        return runCatching { UUID.fromString(text) }.getOrNull()
            ?: throw EconomyException("Unknown player")
    }

    // ===================================
    // player operations
    // ===================================
    fun balanceOf(player: Player, name: String): String {
        val account = account(player, name, false)
        return "$name: ${show(balance(account))}; wallet: ${show(balance("wallet-${player.uniqueId}"))}"
    }

    fun transfer(player: Player, deposit: Boolean, name: String, amount: String): String {
        val account = account(player, name, !deposit)
        val cents = parseMoney(amount)
        val wallet = "wallet-${player.uniqueId}"
        val from = if (deposit) wallet else account
        val to = if (deposit) account else wallet
        transaction {
            requireAvailable(from)
            move(from, to, cents, "${if (deposit) "deposit" else "withdraw"} by ${player.name}")
        }
        return "Transferred ${show(cents)}"
    }

    fun pay(player: Player, name: String, who: String, amount: String): String {
        val account = account(player, name, true)
        val dest = "player-${resolvePlayerId(who)}"
        val cents = parseMoney(amount)
        transaction {
            requireAvailable(account)
            move(account, dest, cents, "payment by ${player.name}")
        }
        return "Paid ${show(cents)}"
    }

    fun createBusiness(player: Player, name: String): String {
        val account = "business-${token(name)}"
        transaction {
            if (state.businesses.containsKey(account)) {
                throw EconomyException("That business name is taken")
            }
            state.businesses[account] = player.uniqueId.toString()
            log("business-created", "player-${player.uniqueId}", account, 0)
        }
        return "Business account: $account"
    }

    fun history(player: Player, name: String): List<String> {
        val account = account(player, name, false)
        return state.records.asReversed()
            .filter { it.from == account || it.to == account }
            .take(20)
            .map { "${Instant.ofEpochMilli(it.time)} ${it.action} ${show(it.amount)} ${label(it.from)} -> ${label(it.to)}" }
    }

    fun stock(player: Player): List<String> {
        val account = nationAccount(player, false)
        return state.stock[account]?.map { "${it.key}: ${it.value}" } ?: listOf()
    }

    fun routeList(player: Player): List<String> {
        val own = nationAccount(player, false)
        return state.routes
            .filter { it.value.seller == own || it.value.buyer == own }
            .map { (id, r) -> "$id ${r.status} ${r.material} x${r.quantity} for ${show(r.price)} ${label(r.seller)} -> ${label(r.buyer)}" }
    }

    fun routeOffer(player: Player, buyerName: String, materialName: String, quantity: String, price: String, intervalMinutes: String): String {
        val own = nationAccount(player, true)
        val buyerNation = Nodes.getNationFromName(buyerName) ?: throw EconomyException("Unknown nation")
        val buyer = "nation-${buyerNation.uuid}"
        if (own == buyer) {
            throw EconomyException("Choose another nation")
        }
        val mat = material(materialName)
        val qty = positive(quantity)
        val cents = parseMoney(price)
        val interval = Math.multiplyExact(positive(intervalMinutes), 60_000L)
        val id = newId(state.routes)
        transaction {
            state.routes[id] = EcoRoute(
                seller = own,
                buyer = buyer,
                material = mat,
                quantity = qty,
                price = cents,
                interval = interval,
                status = "OFFER",
                expires = System.currentTimeMillis() + DAY_MS,
            )
        }
        return "Route offered: $id. The buyer must accept within 24 hours"
    }

    fun routeAccept(player: Player, id: String): String {
        val own = nationAccount(player, true)
        transaction {
            val route = state.routes[id] ?: throw EconomyException("Unknown route")
            val now = System.currentTimeMillis()
            if (route.buyer != own || route.status != "OFFER" || now > route.expires) {
                throw EconomyException("No valid offer for your nation")
            }
            route.status = "ACTIVE"
            route.next = now + route.interval
        }
        return "Route accepted"
    }

    fun routeCancel(player: Player, id: String): String {
        val own = nationAccount(player, true)
        transaction {
            val route = state.routes[id] ?: throw EconomyException("Unknown route")
            if (route.buyer != own && route.seller != own) {
                throw EconomyException("Not your route")
            }
            route.status = "CANCELLED"
        }
        return "Route cancelled"
    }

    fun loanRequest(player: Player, name: String, amount: String): String {
        val account = account(player, name, true)
        val cents = parseMoney(amount)
        if (cents > parseMoney(loanMaximum)) {
            throw EconomyException("Loan exceeds the limit of $loanMaximum")
        }
        val id = newId(state.loans)
        transaction {
            requireAvailable(account)
            if (state.loans.values.any { it.borrower == account && it.status != "PAID" && it.status != "REJECTED" }) {
                throw EconomyException("Resolve your existing loan or request first")
            }
            state.loans[id] = EcoLoan(borrower = account, owed = cents, status = "REQUESTED")
        }
        return "Loan request $id sent; staff approval required"
    }

    fun loanList(player: Player, name: String): List<String> {
        val account = account(player, name, false)
        return state.loans
            .filter { it.value.borrower == account }
            .map { (id, l) -> "$id ${l.status} owed=${show(l.owed)}" }
    }

    fun loanRepay(player: Player, name: String, id: String, amount: String): String {
        val account = account(player, name, true)
        val cents = parseMoney(amount)
        transaction {
            val loan = state.loans[id]
            if (loan == null || loan.borrower != account || (loan.status != "ACTIVE" && loan.status != "DEFAULT")) {
                throw EconomyException("That is not your active debt")
            }
            if (cents > loan.owed) {
                throw EconomyException("Repayment exceeds the debt")
            }
            move(account, "reserve", cents, "loan-repayment")
            loan.owed -= cents
            if (loan.owed == 0L) {
                loan.status = "PAID"
            }
        }
        return "Repaid ${show(cents)}"
    }

    fun infraList(player: Player): List<String> {
        val own = nationAccount(player, false)
        return state.infra
            .filter { it.value.nation == own }
            .map { (name, i) -> "$name ${i.type} ${i.status} arrears=${show(i.arrears)}" }
    }

    fun infraPay(player: Player, name: String): String {
        val own = nationAccount(player, true)
        transaction {
            val infra = state.infra[name]
            if (infra == null || infra.nation != own) {
                throw EconomyException("Not your infrastructure")
            }
            if (infra.arrears <= 0) {
                throw EconomyException("No arrears to pay")
            }
            requireAvailable(own)
            move(own, "upkeep", infra.arrears, "infrastructure-arrears $name")
            infra.arrears = 0
            infra.status = "ACTIVE"
        }
        return "Arrears paid; $name is active"
    }

    // ===================================
    // admin operations
    // ===================================
    fun issue(who: String, amount: String, by: String): String {
        val account = if (who.equals("reserve", ignoreCase = true)) "reserve" else "wallet-${resolvePlayerId(who)}"
        val cents = parseMoney(amount)
        transaction {
            credit(account, cents)
            log("currency-issued by $by", "issuance", account, cents)
        }
        return "Issued ${show(cents)} to ${label(account)}"
    }

    fun issueStock(nationName: String, materialName: String, quantity: String, by: String): String {
        val nation = Nodes.getNationFromName(nationName) ?: throw EconomyException("Unknown nation")
        val account = "nation-${nation.uuid}"
        val mat = material(materialName)
        val qty = positive(quantity)
        transaction {
            val stock = state.stock.getOrPut(account) { LinkedHashMap() }
            stock[mat] = Math.addExact(stock[mat] ?: 0L, qty)
            log("resource-issued $mat by $by", "issuance", account, qty)
        }
        return "Issued $qty $mat to ${nation.name}"
    }

    fun loanApprove(id: String): String {
        transaction {
            val loan = state.loans[id]
            if (loan == null || loan.status != "REQUESTED") {
                throw EconomyException("No pending request with that ID")
            }
            val period = Math.multiplyExact(loanPeriodHours, 3_600_000L)
            if (period <= 0 || loanTermPeriods < 1 || loanTermPeriods > 365 || loanInterestBps < 0 || loanInterestBps > 10_000) {
                throw EconomyException("Invalid loan settings in config")
            }
            requireAvailable(loan.borrower)
            move("reserve", loan.borrower, loan.owed, "loan-issued $id")
            loan.status = "ACTIVE"
            loan.period = period
            loan.bps = loanInterestBps
            loan.remaining = loanTermPeriods
            loan.next = System.currentTimeMillis() + period
        }
        return "Loan $id approved"
    }

    fun loanReject(id: String): String {
        transaction {
            val loan = state.loans[id]
            if (loan == null || loan.status != "REQUESTED") {
                throw EconomyException("No pending request with that ID")
            }
            loan.status = "REJECTED"
        }
        return "Loan $id rejected"
    }

    fun registerInfra(nationName: String, type: String, units: String, name: String): String {
        val nation = Nodes.getNationFromName(nationName) ?: throw EconomyException("Unknown nation")
        val kind = type.lowercase()
        if (kind !in INFRA_TYPES) {
            throw EconomyException("Type must be one of ${INFRA_TYPES.joinToString(", ")}")
        }
        val id = token(name)
        val count = positive(units)
        val cost = Math.multiplyExact(parseMoney(infraRates[kind] ?: "1.00"), count)
        transaction {
            if (state.infra.containsKey(id)) {
                throw EconomyException("That name is already registered")
            }
            state.infra[id] = EcoInfra(
                nation = "nation-${nation.uuid}",
                type = kind,
                units = count,
                cost = cost,
                status = "ACTIVE",
                next = System.currentTimeMillis() + DAY_MS,
            )
            log("infrastructure-registered", "nation-${nation.uuid}", id, cost)
        }
        return "Registered $id for ${nation.name}; upkeep ${show(cost)} per day"
    }

    fun retireInfra(name: String): String {
        transaction {
            val infra = state.infra[name] ?: throw EconomyException("Unknown infrastructure")
            if (infra.arrears > 0) {
                throw EconomyException("Pay the arrears first")
            }
            infra.status = "RETIRED"
        }
        return "$name retired"
    }

    // ===================================
    // scheduled settlement (loan interest, upkeep, route shipments)
    // ===================================
    fun tick() {
        if (!enabled || failed) {
            return
        }
        val now = System.currentTimeMillis()
        try {
            transaction {
                settleLoans(now)
                settleInfrastructure(now)
                settleRoutes(now)
            }
        } catch (e: Exception) {
            Nodes.logger?.severe("[Economy] Scheduled settlement rolled back: ${e.message}")
        }
    }

    private fun settleLoans(now: Long) {
        for (loan in state.loans.values) {
            if (loan.status != "ACTIVE") {
                continue
            }
            var steps = 0
            while (now >= loan.next && loan.remaining > 0 && steps++ < 365) {
                val interest = BigDecimal.valueOf(loan.owed)
                    .multiply(BigDecimal.valueOf(loan.bps.toLong()))
                    .divide(BigDecimal.valueOf(10_000L), 0, RoundingMode.CEILING)
                    .longValueExact()
                loan.owed = Math.addExact(loan.owed, interest)
                loan.remaining -= 1
                loan.next = Math.addExact(loan.next, loan.period)
                log("interest-accrued", loan.borrower, "debt", interest)
            }
            if (loan.remaining == 0) {
                loan.status = "DEFAULT"
            }
        }
    }

    private fun settleInfrastructure(now: Long) {
        for ((name, infra) in state.infra) {
            if (infra.status == "RETIRED" || now < infra.next) {
                continue
            }
            val days = (now - infra.next) / DAY_MS + 1
            infra.next = Math.addExact(infra.next, Math.multiplyExact(days, DAY_MS))
            infra.arrears = Math.addExact(infra.arrears, Math.multiplyExact(days, infra.cost))
            if (balance(infra.nation) >= infra.arrears) {
                move(infra.nation, "upkeep", infra.arrears, "infrastructure-upkeep $name")
                infra.arrears = 0
                infra.status = "ACTIVE"
            } else {
                infra.status = "SUSPENDED"
            }
        }
    }

    private fun settleRoutes(now: Long) {
        for ((id, route) in state.routes) {
            if (route.status != "ACTIVE" || now < route.next) {
                continue
            }
            route.next = now + route.interval
            if (!nationExists(route.seller) || !nationExists(route.buyer)) {
                route.status = "CANCELLED"
                continue
            }
            var blocked = state.infra.values.any {
                (it.nation == route.seller || it.nation == route.buyer) && it.status == "SUSPENDED"
            }
            try {
                requireAvailable(route.seller)
                requireAvailable(route.buyer)
            } catch (_: EconomyException) {
                blocked = true
            }
            val sellerStock = state.stock.getOrPut(route.seller) { LinkedHashMap() }
            val have = sellerStock[route.material] ?: 0L
            if (blocked || balance(route.buyer) < route.price || have < route.quantity) {
                route.lastResult = "BLOCKED: funds, stock, default or suspended infrastructure"
                continue
            }
            move(route.buyer, route.seller, route.price, "shipment $id ${route.material} x${route.quantity}")
            sellerStock[route.material] = have - route.quantity
            val buyerStock = state.stock.getOrPut(route.buyer) { LinkedHashMap() }
            buyerStock[route.material] = Math.addExact(buyerStock[route.material] ?: 0L, route.quantity)
            route.lastResult = "DELIVERED"
            route.lastDelivery = now
        }
    }
}
