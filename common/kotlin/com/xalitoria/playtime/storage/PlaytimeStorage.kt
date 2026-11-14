package com.xalitoria.playtime.storage

import org.jetbrains.exposed.v1.jdbc.Database

/**
 * Where the tables are: the table prefix, the name this server writes its time under, and the database the queries of
 * [NetworkRepository] read. Set once, before a table object is first used, as a table reads its name once.
 */
object PlaytimeStorage {
    var prefix = "xaliplaytime_"
        private set

    /** The server whose time is added; a proxy only reads, so it never needs one. */
    var server = "server"
        private set

    lateinit var database: Database
        private set

    fun use(database: Database, prefix: String, server: String = this.server) {
        this.database = database
        this.prefix = prefix
        this.server = server
    }

    fun tableName(name: String): String = prefix + name
}
