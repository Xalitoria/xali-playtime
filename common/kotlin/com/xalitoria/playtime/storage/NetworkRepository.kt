package com.xalitoria.playtime.storage

import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * The play time added up over the servers that share the database, for Plan: a server writes its time under its own name
 * (`server-name`), so the network's time is the sum of the rows and each server's is the sum of its own. Only reads, and each
 * call blocks: Plan asks off the platform's threads.
 */
object NetworkRepository {
    const val MILLIS_PER_DAY = 86_400_000L

    /** What one server has added up: all of it, the last [days] days of it, and how many different players. */
    data class ServerTotal(val server: String, val activeMillis: Long, val recentMillis: Long, val players: Int)

    data class Entry(val player: UUID, val name: String, val activeMillis: Long)

    /** A player's time on one server. */
    data class PlayerServer(val server: String, val activeMillis: Long, val connectedMillis: Long)

    fun dayOf(millis: Long): Long = Math.floorDiv(millis, MILLIS_PER_DAY)

    /**
     * Active milliseconds of everyone: on every server, or only on [server]; since counting began, or in the last [days] days
     * (today counts as the first), as of [now].
     */
    fun totalActive(days: Int? = null, server: String? = null, now: Long = System.currentTimeMillis()): Long = transaction(PlaytimeStorage.database) {
        if (days == null && server == null) {
            val sum = PlaytimeTable.active.sum()
            return@transaction PlaytimeTable.select(sum).singleOrNull()?.get(sum) ?: 0
        }
        val sum = DailyTable.active.sum()
        var where: Op<Boolean> = DailyTable.day greaterEq (if (days == null) Long.MIN_VALUE else dayOf(now) - days + 1)
        if (server != null) where = where and (DailyTable.server eq server)
        DailyTable.select(sum).where { where }.singleOrNull()?.get(sum) ?: 0
    }

    /** Every server that has added time, the one with the most first. [days] is how far back `recentMillis` goes. */
    fun servers(days: Int = 1, now: Long = System.currentTimeMillis()): List<ServerTotal> = transaction(PlaytimeStorage.database) {
        val sum = DailyTable.active.sum()
        val players = DailyTable.player.countDistinct()
        val recent = DailyTable.select(DailyTable.server, sum)
            .where { DailyTable.day greaterEq dayOf(now) - days + 1 }
            .groupBy(DailyTable.server)
            .associate { it[DailyTable.server] to (it[sum] ?: 0) }
        DailyTable.select(DailyTable.server, sum, players)
            .groupBy(DailyTable.server)
            .map { ServerTotal(it[DailyTable.server], it[sum] ?: 0, recent[it[DailyTable.server]] ?: 0, it[players].toInt()) }
            .sortedWith(compareByDescending<ServerTotal> { it.activeMillis }.thenBy { it.server })
    }

    /** The [limit] players with the most active time, on every server or only on [server]. Players with none are left out. */
    fun top(limit: Int, server: String? = null): List<Entry> = transaction(PlaytimeStorage.database) {
        val sum = DailyTable.active.sum()
        val rows = if (server == null) {
            PlaytimeTable.selectAll()
                .where { PlaytimeTable.active greaterEq 1 }
                .orderBy(PlaytimeTable.active, SortOrder.DESC)
                .limit(limit)
                .map { it[PlaytimeTable.player] to it[PlaytimeTable.active] }
        } else {
            DailyTable.select(DailyTable.player, sum)
                .where { DailyTable.server eq server }
                .groupBy(DailyTable.player)
                .orderBy(sum, SortOrder.DESC)
                .limit(limit)
                .mapNotNull { row -> row[sum]?.takeIf { it > 0 }?.let { row[DailyTable.player] to it } }
        }
        val names = PlayerTable.selectAll().where { PlayerTable.uuid inList rows.map { it.first } }
            .associate { it[PlayerTable.uuid] to it[PlayerTable.name] }
        rows.map { Entry(it.first.toJavaUuid(), names[it.first] ?: "?", it.second) }
    }

    /** Where [player] has played, the server with their longest time first. */
    fun playerServers(player: UUID): List<PlayerServer> = transaction(PlaytimeStorage.database) {
        val active = DailyTable.active.sum()
        val connected = DailyTable.connected.sum()
        DailyTable.select(DailyTable.server, active, connected)
            .where { DailyTable.player eq player.toKotlinUuid() }
            .groupBy(DailyTable.server)
            .map { PlayerServer(it[DailyTable.server], it[active] ?: 0, it[connected] ?: 0) }
            .sortedWith(compareByDescending<PlayerServer> { it.activeMillis }.thenBy { it.server })
    }

    /** How long [player] was active over every server in the last [days] days (today counts as the first), as of [now]. */
    fun playerActive(player: UUID, days: Int, now: Long = System.currentTimeMillis()): Long = transaction(PlaytimeStorage.database) {
        val sum = DailyTable.active.sum()
        DailyTable.select(sum)
            .where { (DailyTable.player eq player.toKotlinUuid()) and (DailyTable.day greaterEq dayOf(now) - days + 1) }
            .singleOrNull()?.get(sum) ?: 0
    }
}
