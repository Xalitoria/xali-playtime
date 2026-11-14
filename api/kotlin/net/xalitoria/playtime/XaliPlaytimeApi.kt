package net.xalitoria.playtime

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * The way into XaliPlaytime for other plugins, found in the services manager. List XaliPlaytime as a dependency of your plugin
 * (`depend`, or `softdepend` and a check that the service is there) so it is enabled first.
 *
 * Everything in this package is made of JDK and Bukkit types, and no Kotlin ones (no function types, default arguments or
 * `KClass`), so a plugin that carries its own copy of the Kotlin runtime can use it, and so can one written in Java.
 *
 * What counts as play time is *active* time: a player is active for a while after they did something, and AFK after that;
 * what a machine in a pen earns is thrown away. Times are milliseconds.
 *
 * Reading from the database returns a future that completes off the server's threads: do not wait for one on a region thread.
 * What is held in memory for a player who is online ([cachedActive], [pendingActive], [isAfk], [top]) is answered at once.
 */
interface XaliPlaytimeApi {
    /**
     * Active milliseconds of an online [player] as this server holds them: what the database had, and what was earned since,
     * including the time that is still being judged. -1 for a player who is not online here.
     */
    fun cachedActive(player: UUID): Long

    /** Milliseconds connected (AFK or not) of an online [player], counted up to now. -1 for a player who is not online here. */
    fun cachedConnected(player: UUID): Long

    /**
     * The active time an online [player] has earned that has not been passed to the listeners yet (see [addListener]), counted
     * up to now: what a message such as "3 of 10 minutes" needs to be right between two credits. 0 for a player who is not online here.
     */
    fun pendingActive(player: UUID): Long

    /** Whether an online [player] has done nothing for long enough to be AFK. False for a player who is not online here. */
    fun isAfk(player: UUID): Boolean

    /** What the database has of [player], plus what an online player has earned since; completes with null for a player who never played. */
    fun stats(player: UUID): CompletableFuture<PlaytimeStats?>

    /** The first [limit] places of the leaderboard of [period] as of its last refresh (see `leaderboard.refresh`), most active time first. */
    fun top(period: PlaytimePeriod, limit: Int): List<PlaytimeEntry>

    /** The place of [player] on the leaderboard of [period] as of its last refresh; 0 when they are not on it. */
    fun position(player: UUID, period: PlaytimePeriod): Int

    /**
     * Calls [listener] whenever active time is written to the database for a player: with how much, since the last time. It is
     * called from a thread of XaliPlaytime's own, after the time is saved, at the `save-interval` and when the player leaves. The listener is
     * removed when [plugin] disables, or with [removeListeners].
     */
    fun addListener(plugin: Plugin, listener: PlaytimeListener)

    /** Removes every listener [plugin] added. */
    fun removeListeners(plugin: Plugin)

    companion object {
        /** The API of the XaliPlaytime plugin that is running. */
        @JvmStatic
        fun get(): XaliPlaytimeApi = Bukkit.getServicesManager().load(XaliPlaytimeApi::class.java)
            ?: error("XaliPlaytime is not enabled; add it as a dependency of your plugin")
    }
}

/** Told when active time was saved for a player. */
fun interface PlaytimeListener {
    /** [player] earned [activeMillis] more active time, now saved. Never called with 0. */
    fun credited(player: UUID, activeMillis: Long)
}

/** The stretches of time a leaderboard is made over. A day is a UTC day; the week and the month end today. */
enum class PlaytimePeriod {
    /** Today, from 00:00 UTC. */
    DAY,

    /** The last 7 days, today included. */
    WEEK,

    /** The last 30 days, today included. */
    MONTH,

    /** Since the plugin began to count. */
    ALL,
}

/** One place of a leaderboard. */
interface PlaytimeEntry {
    /** The place from 1; equal times share one. */
    val position: Int
    val player: UUID

    /** The name the player had when they were last seen. */
    val name: String
    val activeMillis: Long
}

/** What XaliPlaytime knows of one player. Times of the periods are of active time. */
interface PlaytimeStats {
    val player: UUID
    val name: String

    /** Active time since counting began. */
    val activeMillis: Long

    /** Time connected, AFK or not. */
    val connectedMillis: Long
    val activeTodayMillis: Long
    val activeWeekMillis: Long
    val activeMonthMillis: Long

    /** How many times they joined since counting began. */
    val joins: Int

    /** Milliseconds since the epoch. */
    val firstSeen: Long
    val lastSeen: Long
}
