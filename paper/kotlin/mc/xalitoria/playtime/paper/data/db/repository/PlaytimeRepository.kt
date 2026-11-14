package mc.xalitoria.playtime.paper.data.db.repository

import mc.xalitoria.playtime.paper.data.db.DatabaseManager.dbTransaction
import com.xalitoria.playtime.storage.DailyTable
import com.xalitoria.playtime.storage.PlayerTable
import com.xalitoria.playtime.storage.PlaytimeStorage
import com.xalitoria.playtime.storage.PlaytimeTable
import mc.xalitoria.playtime.paper.data.model.Day
import mc.xalitoria.playtime.paper.data.model.Profile
import mc.xalitoria.playtime.paper.data.model.Ranked
import mc.xalitoria.playtime.paper.data.model.Stats
import net.xalitoria.playtime.PlaytimePeriod
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Everything the plugin keeps. Time is only ever added to (`UPDATE ... SET active = active + ?`), never set, so servers that
 * share the database lose nothing of what the others wrote.
 */
object PlaytimeRepository {
    /** Days since the epoch, in UTC, at [millis]. */
    fun dayOf(millis: Long): Long = Math.floorDiv(millis, MILLIS_PER_DAY)

    /** The first day of [period] when today is [today]; days before it are not in it. */
    fun firstDay(period: PlaytimePeriod, today: Long): Long = when (period) {
        PlaytimePeriod.DAY -> today
        PlaytimePeriod.WEEK -> today - 6
        PlaytimePeriod.MONTH -> today - 29
        PlaytimePeriod.ALL -> Long.MIN_VALUE
    }

    /** What [joined] returns: what the player has already, to carry on from. */
    data class Totals(val active: Long, val connected: Long)

    /** Makes the row of the player [uuid] or brings their name and time up to date, counts a join, and returns their totals. */
    suspend fun joined(uuid: UUID, name: String, now: Long = System.currentTimeMillis()): Totals = dbTransaction {
        val id = uuid.toKotlinUuid()
        val updated = PlayerTable.update({ PlayerTable.uuid eq id }) {
            it[PlayerTable.name] = name
            it[nameLower] = name.lowercase()
            it[lastSeen] = now
        }
        if (updated == 0) {
            val inserted = PlayerTable.insertIgnore {
                it[PlayerTable.uuid] = id
                it[PlayerTable.name] = name
                it[nameLower] = name.lowercase()
                it[firstSeen] = now
                it[lastSeen] = now
            }
            // Another server inserted it between the two statements.
            if (inserted.insertedCount == 0) PlayerTable.update({ PlayerTable.uuid eq id }) { it[lastSeen] = now }
        }
        ensureTotals(id)
        PlaytimeTable.update({ PlaytimeTable.player eq id }) { it[joins] = joins + 1 }
        totals(id)
    }

    /** The row of the player in [PlaytimeTable] exists afterwards. InnoDB: look first, since `INSERT IGNORE` of a row that exists locks it. */
    private fun JdbcTransaction.ensureTotals(id: kotlin.uuid.Uuid) {
        if (PlaytimeTable.selectAll().where { PlaytimeTable.player eq id }.empty()) {
            PlaytimeTable.insertIgnore { it[player] = id }
        }
    }

    private fun JdbcTransaction.totals(id: kotlin.uuid.Uuid): Totals {
        val row = PlaytimeTable.selectAll().where { PlaytimeTable.player eq id }.single()
        return Totals(row[PlaytimeTable.active], row[PlaytimeTable.connected])
    }

    /**
     * Adds [active] and [connected] milliseconds to the player's total and to their day ([day], see [dayOf]) on this server
     * ([server], the `server-name`) and returns the totals afterwards. One transaction.
     */
    suspend fun add(uuid: UUID, active: Long, connected: Long, day: Long, server: String = PlaytimeStorage.server): Totals = dbTransaction {
        val id = uuid.toKotlinUuid()
        ensureTotals(id)
        PlaytimeTable.update({ PlaytimeTable.player eq id }) {
            it[PlaytimeTable.active] = PlaytimeTable.active + active
            it[PlaytimeTable.connected] = PlaytimeTable.connected + connected
        }
        val today = (DailyTable.player eq id) and (DailyTable.day eq day) and (DailyTable.server eq server)
        var updated = DailyTable.update({ today }) {
            it[DailyTable.active] = DailyTable.active + active
            it[DailyTable.connected] = DailyTable.connected + connected
        }
        if (updated == 0 && DailyTable.insertIgnore {
                it[player] = id
                it[DailyTable.day] = day
                it[DailyTable.server] = server
                it[DailyTable.active] = active
                it[DailyTable.connected] = connected
            }.insertedCount == 0
        ) {
            // Another server made the day's row between the two statements.
            updated = DailyTable.update({ today }) {
                it[DailyTable.active] = DailyTable.active + active
                it[DailyTable.connected] = DailyTable.connected + connected
            }
        }
        totals(id)
    }

    /**
     * Brings in the time a player had on this server before the plugin: [active] milliseconds, put on [day] of [server] as both
     * active and connected time. Only when the player has no time of this server yet, in one transaction, and the player must
     * have been through [joined]. Returns the totals afterwards, or null when there was nothing to bring in.
     */
    suspend fun importHistory(uuid: UUID, active: Long, day: Long, server: String = PlaytimeStorage.server): Totals? = dbTransaction {
        if (active <= 0 || hasServerRows(uuid.toKotlinUuid(), server)) return@dbTransaction null
        add(uuid, active, active, day, server)
    }

    private fun JdbcTransaction.hasServerRows(id: kotlin.uuid.Uuid, server: String): Boolean =
        !DailyTable.selectAll().where { (DailyTable.player eq id) and (DailyTable.server eq server) }.limit(1).empty()

    /** The active milliseconds [uuid] has on [server]. */
    suspend fun serverActive(uuid: UUID, server: String = PlaytimeStorage.server): Long = dbTransaction { serverActive(uuid.toKotlinUuid(), server) }

    private fun JdbcTransaction.serverActive(id: kotlin.uuid.Uuid, server: String): Long {
        val sum = DailyTable.active.sum()
        return DailyTable.select(sum).where { (DailyTable.player eq id) and (DailyTable.server eq server) }.singleOrNull()?.get(sum) ?: 0
    }

    /** What [adjust] did: the milliseconds that were added (positive) or taken away (negative), and the totals afterwards. */
    data class Adjusted(val applied: Long, val totals: Totals)

    /**
     * Adds [delta] milliseconds to the player's time on [server] (on [day]), or takes it away: newest day first, and no more than
     * the server has of it. The player must have been through [joined]. One transaction.
     */
    suspend fun adjust(uuid: UUID, delta: Long, day: Long, server: String = PlaytimeStorage.server): Adjusted = dbTransaction { adjust(uuid.toKotlinUuid(), delta, day, server) }

    /** Sets the player's time on [server] to [target] milliseconds, by [adjust]ing it by the difference. */
    suspend fun setServerActive(uuid: UUID, target: Long, day: Long, server: String = PlaytimeStorage.server): Adjusted = dbTransaction {
        val id = uuid.toKotlinUuid()
        adjust(id, target - serverActive(id, server), day, server)
    }

    private suspend fun JdbcTransaction.adjust(id: kotlin.uuid.Uuid, delta: Long, day: Long, server: String): Adjusted {
        if (delta >= 0) return Adjusted(delta, add(id.toJavaUuid(), delta, delta, day, server))
        var left = -delta
        val rows = DailyTable.selectAll()
            .where { (DailyTable.player eq id) and (DailyTable.server eq server) and (DailyTable.active greaterEq 1) }
            .orderBy(DailyTable.day, SortOrder.DESC)
            .map { Triple(it[DailyTable.day], it[DailyTable.active], it[DailyTable.connected]) }
        var connectedTaken = 0L
        for ([rowDay, rowActive, rowConnected] in rows) {
            if (left == 0L) break
            val take = minOf(left, rowActive)
            val takeConnected = minOf(take, rowConnected)
            DailyTable.update({ (DailyTable.player eq id) and (DailyTable.day eq rowDay) and (DailyTable.server eq server) }) {
                it[DailyTable.active] = DailyTable.active - take
                it[DailyTable.connected] = DailyTable.connected - takeConnected
            }
            left -= take
            connectedTaken += takeConnected
        }
        val removed = -delta - left
        ensureTotals(id)
        val row = PlaytimeTable.selectAll().where { PlaytimeTable.player eq id }.single()
        PlaytimeTable.update({ PlaytimeTable.player eq id }) {
            it[PlaytimeTable.active] = minOf(row[PlaytimeTable.active], removed).let { gone -> PlaytimeTable.active - gone }
            it[PlaytimeTable.connected] = minOf(row[PlaytimeTable.connected], connectedTaken).let { gone -> PlaytimeTable.connected - gone }
        }
        return Adjusted(-removed, totals(id))
    }

    /**
     * Takes all the time of the player, on every server, and puts a row of nothing for [server] on [day] in its place, so that the
     * time they had on this server before the plugin is not brought in again when they next join. Joins are kept.
     */
    suspend fun reset(uuid: UUID, day: Long, server: String = PlaytimeStorage.server): Totals = dbTransaction {
        val id = uuid.toKotlinUuid()
        ensureTotals(id)
        DailyTable.deleteWhere { DailyTable.player eq id }
        PlaytimeTable.update({ PlaytimeTable.player eq id }) {
            it[active] = 0
            it[connected] = 0
        }
        DailyTable.insertIgnore {
            it[player] = id
            it[DailyTable.day] = day
            it[DailyTable.server] = server
        }
        totals(id)
    }

    /** Deletes everything about the player, on every server. Returns whether they were there. */
    suspend fun purge(uuid: UUID): Boolean = dbTransaction { purgeIds(listOf(uuid.toKotlinUuid())) > 0 }

    /** How many players were last seen before [before] (milliseconds since the epoch), and with [delete] they are deleted. */
    suspend fun purgeInactive(before: Long, delete: Boolean, keep: Set<UUID>): Int = dbTransaction {
        val ids = PlayerTable.selectAll().where { PlayerTable.lastSeen less before }.map { it[PlayerTable.uuid] }
            .filter { it.toJavaUuid() !in keep }
        if (delete) purgeIds(ids) else ids.size
    }

    private fun JdbcTransaction.purgeIds(ids: List<kotlin.uuid.Uuid>): Int {
        var gone = 0
        for (chunk in ids.chunked(500)) {
            DailyTable.deleteWhere { DailyTable.player inList chunk }
            PlaytimeTable.deleteWhere { PlaytimeTable.player inList chunk }
            gone += PlayerTable.deleteWhere { PlayerTable.uuid inList chunk }
        }
        return gone
    }

    /** What the database has of [uuid], or null for a player it has not seen. [now] fixes which day is today. */
    suspend fun stats(uuid: UUID, now: Long = System.currentTimeMillis()): Stats? = dbTransaction {
        val id = uuid.toKotlinUuid()
        val player = PlayerTable.selectAll().where { PlayerTable.uuid eq id }.singleOrNull() ?: return@dbTransaction null
        val total = PlaytimeTable.selectAll().where { PlaytimeTable.player eq id }.singleOrNull()
        val today = dayOf(now)
        fun since(period: PlaytimePeriod): Long {
            val sum = DailyTable.active.sum()
            return DailyTable.select(sum).where { (DailyTable.player eq id) and (DailyTable.day greaterEq firstDay(period, today)) }
                .singleOrNull()?.get(sum) ?: 0
        }
        Stats(
            player = uuid,
            name = player[PlayerTable.name],
            activeMillis = total?.get(PlaytimeTable.active) ?: 0,
            connectedMillis = total?.get(PlaytimeTable.connected) ?: 0,
            activeTodayMillis = since(PlaytimePeriod.DAY),
            activeWeekMillis = since(PlaytimePeriod.WEEK),
            activeMonthMillis = since(PlaytimePeriod.MONTH),
            joins = total?.get(PlaytimeTable.joins) ?: 0,
            firstSeen = player[PlayerTable.firstSeen],
            lastSeen = player[PlayerTable.lastSeen],
        )
    }

    /** The last [days] days of [uuid] that have anything, newest first. */
    suspend fun days(uuid: UUID, days: Int, now: Long = System.currentTimeMillis()): List<Day> = dbTransaction {
        DailyTable.selectAll()
            .where { (DailyTable.player eq uuid.toKotlinUuid()) and (DailyTable.day greaterEq dayOf(now) - days + 1) }
            .orderBy(DailyTable.day, SortOrder.DESC)
            .map { Day(it[DailyTable.day], it[DailyTable.active], it[DailyTable.connected]) }
    }

    /** The first [limit] places of [period] with something to show, most active time first; equal times share a place. */
    suspend fun top(period: PlaytimePeriod, limit: Int, now: Long = System.currentTimeMillis()): List<Ranked> = dbTransaction {
        val rows: List<Pair<kotlin.uuid.Uuid, Long>> = if (period == PlaytimePeriod.ALL) {
            PlaytimeTable.selectAll()
                .where { PlaytimeTable.active greaterEq 1 }
                .orderBy(PlaytimeTable.active, SortOrder.DESC)
                .limit(limit)
                .map { it[PlaytimeTable.player] to it[PlaytimeTable.active] }
        } else {
            val sum = DailyTable.active.sum()
            DailyTable.select(DailyTable.player, sum)
                .where { DailyTable.day greaterEq firstDay(period, dayOf(now)) }
                .groupBy(DailyTable.player)
                .orderBy(sum, SortOrder.DESC)
                .limit(limit)
                .mapNotNull { row -> row[sum]?.takeIf { it > 0 }?.let { row[DailyTable.player] to it } }
        }
        val names = names(rows.map { it.first.toJavaUuid() })
        rank(rows.map { Triple(it.first.toJavaUuid(), names[it.first.toJavaUuid()] ?: "?", it.second) })
    }

    /** Active milliseconds of everyone, since counting began; or of the last [days] days when that is given. */
    suspend fun totalActive(days: Int? = null, now: Long = System.currentTimeMillis()): Long = dbTransaction {
        if (days == null) {
            val sum = PlaytimeTable.active.sum()
            PlaytimeTable.select(sum).singleOrNull()?.get(sum) ?: 0
        } else {
            val sum = DailyTable.active.sum()
            DailyTable.select(sum).where { DailyTable.day greaterEq dayOf(now) - days + 1 }.singleOrNull()?.get(sum) ?: 0
        }
    }

    private fun JdbcTransaction.names(uuids: Collection<UUID>): Map<UUID, String> {
        if (uuids.isEmpty()) return emptyMap()
        return PlayerTable.selectAll().where { PlayerTable.uuid inList uuids.map { it.toKotlinUuid() } }
            .associate { it[PlayerTable.uuid].toJavaUuid() to it[PlayerTable.name] }
    }

    /** A player by name in any case. The one seen last, if a name has changed hands. */
    suspend fun byName(name: String): Profile? = dbTransaction {
        PlayerTable.selectAll()
            .where { PlayerTable.nameLower eq name.lowercase() }
            .orderBy(PlayerTable.lastSeen, SortOrder.DESC)
            .limit(1)
            .firstOrNull()?.let { mc.xalitoria.playtime.paper.data.model.Profile(it[PlayerTable.uuid].toJavaUuid(), it[PlayerTable.name]) }
    }

    /** The places of [times], already in order, with equal times on one place (1, 2, 2, 4). */
    fun rank(times: List<Triple<UUID, String, Long>>): List<Ranked> {
        var position = 0
        var previous = -1L
        return times.mapIndexed { index, entry ->
            if (entry.third != previous) position = index + 1
            previous = entry.third
            Ranked(position, entry.first, entry.second, entry.third)
        }
    }

    const val MILLIS_PER_DAY = 86_400_000L
}
