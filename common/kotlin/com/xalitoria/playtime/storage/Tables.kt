package com.xalitoria.playtime.storage

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

/** Every player seen on a server that shares the database. Times are milliseconds since the epoch, in UTC. */
object PlayerTable : Table(PlaytimeStorage.tableName("player")) {
    val uuid = uuid("uuid")
    val name = varchar("name", 24)
    val nameLower = varchar("name_lower", 24).index()
    val firstSeen = long("first_seen")
    val lastSeen = long("last_seen")

    override val primaryKey = PrimaryKey(uuid)
}

/** What a player has in all servers: active milliseconds, milliseconds connected, and how many times they joined. */
object PlaytimeTable : Table(PlaytimeStorage.tableName("playtime")) {
    val player = reference("player", PlayerTable.uuid, onDelete = ReferenceOption.CASCADE)
    val active = long("active").default(0)
    val connected = long("connected").default(0)
    val joins = integer("joins").default(0)

    // The leaderboard of all time sorts by this.
    init {
        index(false, active)
    }

    override val primaryKey = PrimaryKey(player)
}

/**
 * What a player has per UTC day ([day] counts days from the epoch) on each server, for the day, week and month leaderboards,
 * for graphs and for what each server contributes to the network's total.
 */
object DailyTable : Table(PlaytimeStorage.tableName("daily")) {
    val player = reference("player", PlayerTable.uuid, onDelete = ReferenceOption.CASCADE)
    val day = long("day").index()
    val server = varchar("server", 64).index()
    val active = long("active").default(0)
    val connected = long("connected").default(0)

    override val primaryKey = PrimaryKey(player, day, server)
}
