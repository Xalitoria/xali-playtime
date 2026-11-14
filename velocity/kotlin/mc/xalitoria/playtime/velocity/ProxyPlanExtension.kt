package mc.xalitoria.playtime.velocity

import com.djrapitops.plan.extension.CallEvents
import com.djrapitops.plan.extension.DataExtension
import com.djrapitops.plan.extension.ElementOrder
import com.djrapitops.plan.extension.FormatType
import com.djrapitops.plan.extension.annotation.NumberProvider
import com.djrapitops.plan.extension.annotation.PluginInfo
import com.djrapitops.plan.extension.annotation.Tab
import com.djrapitops.plan.extension.annotation.TabInfo
import com.djrapitops.plan.extension.annotation.TableProvider
import com.djrapitops.plan.extension.icon.Color
import com.djrapitops.plan.extension.icon.Family
import com.djrapitops.plan.extension.icon.Icon
import com.djrapitops.plan.extension.table.Table
import com.xalitoria.playtime.storage.NetworkRepository
import java.util.UUID

/**
 * XaliPlaytime's card on Plan's network pages: the active time of every server that shares the database, added up, and what
 * each server and each of the most active players has contributed. On a player's page: their time over the whole network and
 * on each server. The servers' own XaliPlaytime adds the same for itself on its server page.
 *
 * Plan calls these off the proxy's threads, so they read the database directly; a read that fails shows 0 and a line in the
 * debug log.
 */
@PluginInfo(name = "XaliPlaytime", iconName = "hourglass-half", iconFamily = Family.SOLID, color = Color.LIGHT_GREEN)
@TabInfo(tab = "Servers", iconName = "server", iconFamily = Family.SOLID, elementOrder = [ElementOrder.TABLE])
class ProxyPlanExtension(
    private val config: () -> ProxyConfig.Plan,
    private val debug: (String) -> Unit,
) : DataExtension {
    override fun callExtensionMethodsOn() = arrayOf(
        CallEvents.PLAYER_JOIN, CallEvents.PLAYER_LEAVE, CallEvents.SERVER_EXTENSION_REGISTER, CallEvents.SERVER_PERIODICAL,
    )

    @NumberProvider(text = "Active time (24 h)", description = "Played on every server today, UTC", iconName = "hourglass-half", iconFamily = Family.SOLID, iconColor = Color.LIGHT_GREEN, priority = 20, format = FormatType.TIME_MILLISECONDS)
    fun activeToday(): Long = read(0) { NetworkRepository.totalActive(days = 1) }

    @NumberProvider(text = "Active time (7 days)", description = "Played on every server in the last 7 days", iconName = "calendar-week", iconFamily = Family.SOLID, iconColor = Color.LIGHT_GREEN, priority = 19, format = FormatType.TIME_MILLISECONDS)
    fun activeWeek(): Long = read(0) { NetworkRepository.totalActive(days = 7) }

    @NumberProvider(text = "Active time (total)", description = "Played on every server since counting began", iconName = "hourglass-half", iconFamily = Family.SOLID, iconColor = Color.LIGHT_GREEN, priority = 18, format = FormatType.TIME_MILLISECONDS)
    fun activeTotal(): Long = read(0) { NetworkRepository.totalActive() }

    @NumberProvider(text = "Servers counting", description = "Servers that have added play time", iconName = "server", iconFamily = Family.SOLID, iconColor = Color.GREY, priority = 17)
    fun serversCounting(): Long = read(0L) { NetworkRepository.servers(1).size.toLong() }

    @Tab("Servers")
    @TableProvider(tableColor = Color.LIGHT_GREEN)
    fun servers(): Table {
        val days = config().days
        val table = Table.builder()
            .columnOne("Server", Icon(Family.SOLID, "server", Color.NONE))
            .columnTwo("Active", Icon(Family.SOLID, "hourglass-half", Color.NONE))
            .columnThree("Active ($days days)", Icon(Family.SOLID, "calendar-week", Color.NONE))
            .columnFour("Players", Icon(Family.SOLID, "users", Color.NONE))
        read(emptyList()) { NetworkRepository.servers(days) }.forEach {
            table.addRow(it.server, words(it.activeMillis), words(it.recentMillis), it.players)
        }
        return table.build()
    }

    @TableProvider(tableColor = Color.LIGHT_GREEN)
    fun mostActive(): Table {
        val table = Table.builder()
            .columnOne("Player", Icon(Family.SOLID, "user", Color.NONE))
            .columnTwo("Active", Icon(Family.SOLID, "hourglass-half", Color.NONE))
        read(emptyList()) { NetworkRepository.top(config().top) }.forEach { table.addRow(it.name, words(it.activeMillis)) }
        return table.build()
    }

    @NumberProvider(text = "Active time (network)", description = "Played on every server", iconName = "hourglass-half", iconFamily = Family.SOLID, iconColor = Color.LIGHT_GREEN, priority = 20, format = FormatType.TIME_MILLISECONDS)
    fun playerActive(playerUUID: UUID): Long = read(0) { NetworkRepository.playerServers(playerUUID).sumOf { it.activeMillis } }

    @NumberProvider(text = "Active time (network, 7 days)", description = "Played on every server in the last 7 days", iconName = "calendar-week", iconFamily = Family.SOLID, iconColor = Color.LIGHT_GREEN, priority = 19, format = FormatType.TIME_MILLISECONDS)
    fun playerActiveWeek(playerUUID: UUID): Long = read(0) { NetworkRepository.playerActive(playerUUID, 7) }

    @NumberProvider(text = "AFK time (network)", description = "Connected to a server without playing", iconName = "bed", iconFamily = Family.SOLID, iconColor = Color.GREY, priority = 18, format = FormatType.TIME_MILLISECONDS)
    fun playerAfk(playerUUID: UUID): Long = read(0) {
        NetworkRepository.playerServers(playerUUID).sumOf { (it.connectedMillis - it.activeMillis).coerceAtLeast(0) }
    }

    @Tab("Servers")
    @TableProvider(tableColor = Color.LIGHT_GREEN)
    fun playerServers(playerUUID: UUID): Table {
        val table = Table.builder()
            .columnOne("Server", Icon(Family.SOLID, "server", Color.NONE))
            .columnTwo("Active", Icon(Family.SOLID, "hourglass-half", Color.NONE))
            .columnThree("Connected", Icon(Family.SOLID, "plug", Color.NONE))
        read(emptyList()) { NetworkRepository.playerServers(playerUUID) }.forEach {
            table.addRow(it.server, words(it.activeMillis), words(it.connectedMillis))
        }
        return table.build()
    }

    private fun <T> read(fallback: T, query: () -> T): T = try {
        query()
    } catch (e: Exception) {
        debug("Plan could not read the play time: $e")
        fallback
    }

    companion object {
        /** `1d 2h 3m` for [millis]: the two largest units that are not zero, the way Plan's tables are read. */
        fun words(millis: Long): String {
            var seconds = millis / 1000
            if (seconds < 60) return "${seconds}s"
            val days = seconds / 86_400
            seconds %= 86_400
            val hours = seconds / 3600
            seconds %= 3600
            val minutes = seconds / 60
            return buildList {
                if (days > 0) add("${days}d")
                if (hours > 0) add("${hours}h")
                if (minutes > 0) add("${minutes}m")
            }.take(2).joinToString(" ")
        }
    }
}
