package mc.xalitoria.playtime.paper.data.model

import net.xalitoria.playtime.PlaytimeEntry
import net.xalitoria.playtime.PlaytimeStats
import java.util.UUID

/** A player the plugin has seen. */
data class Profile(val uuid: UUID, val name: String)

/** What the database holds of one player; the periods are of active time. */
data class Stats(
    override val player: UUID,
    override val name: String,
    override val activeMillis: Long,
    override val connectedMillis: Long,
    override val activeTodayMillis: Long,
    override val activeWeekMillis: Long,
    override val activeMonthMillis: Long,
    override val joins: Int,
    override val firstSeen: Long,
    override val lastSeen: Long,
) : PlaytimeStats {
    /** The same with what an online player has earned that is not written yet. */
    fun plus(active: Long, connected: Long) = copy(
        activeMillis = activeMillis + active,
        connectedMillis = connectedMillis + connected,
        activeTodayMillis = activeTodayMillis + active,
        activeWeekMillis = activeWeekMillis + active,
        activeMonthMillis = activeMonthMillis + active,
    )
}

/** A place on a leaderboard; equal times share a place. */
data class Ranked(
    override val position: Int,
    override val player: UUID,
    override val name: String,
    override val activeMillis: Long,
) : PlaytimeEntry

/** One day of a player: [day] is days since the epoch, in UTC. */
data class Day(val day: Long, val activeMillis: Long, val connectedMillis: Long)
