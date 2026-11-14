package mc.xalitoria.playtime.paper.external.plan

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
import kotlinx.coroutines.runBlocking
import mc.xalitoria.playtime.paper.XaliPlaytime
import mc.xalitoria.playtime.paper.XaliPlaytime.Companion.debug
import mc.xalitoria.playtime.paper.data.db.repository.PlaytimeRepository
import mc.xalitoria.playtime.paper.tracking.PlaytimeTracker
import net.xalitoria.playtime.PlaytimePeriod
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * What the play time adds to Plan: per player their active time (all of it, the week and the month), the time they were
 * AFK and their place, with a table of their latest days; per server the active time of everyone on it and its leaderboard.
 * The servers added up are on the proxy's page (the `velocity` module). Plan
 * has its own "playtime", which counts every second connected; this is the part of it that was active.
 *
 * Plan calls these methods off the server thread, so they read the database directly. Every Plan class is used only from here
 * and [PlanHook], which the plugin touches only once Plan is known to be installed.
 */
@PluginInfo(name = "XaliPlaytime", iconName = "hourglass-half", iconFamily = Family.SOLID, color = Color.LIGHT_GREEN)
@TabInfo(tab = "Days", iconName = "calendar-days", iconFamily = Family.SOLID, elementOrder = [ElementOrder.TABLE])
class PlanExtension(private val plugin: XaliPlaytime) : DataExtension {
    private val server get() = plugin.config.data.serverName
    private val days get() = plugin.config.data.plan.days.coerceIn(1, 90)

    /** A player's page is refreshed when they join and leave, the server's every so often. */
    override fun callExtensionMethodsOn() = arrayOf(
        CallEvents.PLAYER_JOIN, CallEvents.PLAYER_LEAVE, CallEvents.SERVER_EXTENSION_REGISTER, CallEvents.SERVER_PERIODICAL,
    )

    @NumberProvider(text = "Active time", description = "Time spent playing, not AFK", iconName = "hourglass-half", iconFamily = Family.SOLID, iconColor = Color.LIGHT_GREEN, priority = 20, format = FormatType.TIME_MILLISECONDS)
    fun active(playerUUID: UUID): Long = stats(playerUUID)?.activeMillis ?: 0

    @NumberProvider(text = "Active time (7 days)", description = "Time spent playing in the last 7 days", iconName = "calendar-week", iconFamily = Family.SOLID, iconColor = Color.LIGHT_GREEN, priority = 19, format = FormatType.TIME_MILLISECONDS)
    fun activeWeek(playerUUID: UUID): Long = stats(playerUUID)?.activeWeekMillis ?: 0

    @NumberProvider(text = "Active time (30 days)", description = "Time spent playing in the last 30 days", iconName = "calendar", iconFamily = Family.SOLID, iconColor = Color.LIGHT_GREEN, priority = 18, format = FormatType.TIME_MILLISECONDS)
    fun activeMonth(playerUUID: UUID): Long = stats(playerUUID)?.activeMonthMillis ?: 0

    @NumberProvider(text = "AFK time", description = "Time connected without playing", iconName = "bed", iconFamily = Family.SOLID, iconColor = Color.GREY, priority = 17, format = FormatType.TIME_MILLISECONDS)
    fun afk(playerUUID: UUID): Long = stats(playerUUID)?.let { (it.connectedMillis - it.activeMillis).coerceAtLeast(0) } ?: 0

    @NumberProvider(text = "Active time (this server)", description = "Time spent playing on this server", iconName = "server", iconFamily = Family.SOLID, iconColor = Color.LIGHT_GREEN, priority = 15, format = FormatType.TIME_MILLISECONDS)
    fun activeHere(playerUUID: UUID): Long = query(0) { NetworkRepository.playerServers(playerUUID).firstOrNull { it.server == server }?.activeMillis ?: 0 }

    @NumberProvider(text = "Place", description = "On the leaderboard of all time", iconName = "ranking-star", iconFamily = Family.SOLID, iconColor = Color.AMBER, priority = 14)
    fun place(playerUUID: UUID): Long = plugin.service.positionOf(playerUUID, PlaytimePeriod.ALL).toLong()

    @Tab("Days")
    @TableProvider(tableColor = Color.LIGHT_GREEN)
    fun latestDays(playerUUID: UUID): Table {
        val table = Table.builder()
            .columnOne("Day (UTC)", Icon(Family.SOLID, "calendar-day", Color.NONE))
            .columnTwo("Active", Icon(Family.SOLID, "hourglass-half", Color.NONE))
            .columnThree("Connected", Icon(Family.SOLID, "plug", Color.NONE))
        query(emptyList()) { PlaytimeRepository.days(playerUUID, days) }.forEach {
            table.addRow(DAY.format(Instant.ofEpochMilli(it.day * PlaytimeRepository.MILLIS_PER_DAY)), words(it.activeMillis), words(it.connectedMillis))
        }
        return table.build()
    }

    @NumberProvider(text = "Active time (24 h)", description = "Played on this server today, UTC", iconName = "hourglass-half", iconFamily = Family.SOLID, iconColor = Color.LIGHT_GREEN, priority = 20, format = FormatType.TIME_MILLISECONDS)
    fun activeToday(): Long = query(0) { NetworkRepository.totalActive(days = 1, server = server) }

    @NumberProvider(text = "Active time (total)", description = "Played on this server by everyone since counting began", iconName = "hourglass-half", iconFamily = Family.SOLID, iconColor = Color.LIGHT_GREEN, priority = 19, format = FormatType.TIME_MILLISECONDS)
    fun activeTotal(): Long = query(0) { NetworkRepository.totalActive(server = server) }

    @TableProvider(tableColor = Color.LIGHT_GREEN)
    fun mostActive(): Table {
        val table = Table.builder()
            .columnOne("Player", Icon(Family.SOLID, "user", Color.NONE))
            .columnTwo("Active", Icon(Family.SOLID, "hourglass-half", Color.NONE))
        query(emptyList()) { NetworkRepository.top(TOP, server) }.forEach { table.addRow(it.name, words(it.activeMillis)) }
        return table.build()
    }

    /** What the database has of the player, and what they have earned since it was written when they are online. */
    private fun stats(uuid: UUID) = query(null) { plugin.service.stats(uuid) }

    private fun words(millis: Long) = plugin.service.words(millis / 1000)

    /** Runs a database read; a failure shows [fallback] on the page and a line in the debug log, never an error in Plan. */
    private fun <T> query(fallback: T, read: suspend () -> T): T = try {
        runBlocking { read() }
    } catch (e: Exception) {
        debug("Plan could not read the play time: {0}", e)
        fallback
    }

    private companion object {
        const val TOP = 10
        val DAY: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
    }
}
