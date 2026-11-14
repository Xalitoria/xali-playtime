package mc.xalitoria.playtime.velocity

import com.xalitoria.playtime.storage.PlaytimeStorage
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import java.nio.file.Path

/**
 * The connection to the servers' play time database. Read-only in use: the proxy never creates or changes a table, so a user
 * that may only SELECT is enough, and the tables are the servers' to create (a page opened before a server has started shows 0).
 */
class PlaytimeDatabase private constructor(private val source: HikariDataSource) : AutoCloseable {
    override fun close() = source.close()

    companion object {
        private val PREFIX = Regex("[A-Za-z0-9_]*")

        /** Connects with [settings] and points [PlaytimeStorage] at it. Throws when the settings are wrong or the database can't be reached. */
        fun connect(settings: ProxyConfig.Database, folder: Path): PlaytimeDatabase {
            require(PREFIX.matches(settings.tablePrefix)) { "database.table-prefix may only contain letters, digits and underscores" }
            val config = HikariConfig()
            config.poolName = "XaliPlaytime-Network"
            when (settings.type.trim().lowercase()) {
                "sqlite" -> {
                    config.jdbcUrl = "jdbc:sqlite:${folder.resolve(settings.sqlite.filename).toAbsolutePath()}"
                    config.driverClassName = "org.sqlite.JDBC"
                    config.maximumPoolSize = 1
                }
                "mariadb", "mysql" -> {
                    val mysql = settings.mysql
                    val mariadb = settings.type.trim().equals("mariadb", ignoreCase = true)
                    // MariaDB gets its own driver: Connector/J asks a MariaDB server for INFORMATION_SCHEMA columns it doesn't have.
                    config.jdbcUrl = if (mariadb) "jdbc:mariadb://${mysql.host}:${mysql.port}/${mysql.database}?sslMode=${if (mysql.useSsl) "trust" else "disable"}"
                    else "jdbc:mysql://${mysql.host}:${mysql.port}/${mysql.database}?useSSL=${mysql.useSsl}"
                    config.driverClassName = if (mariadb) "org.mariadb.jdbc.Driver" else "com.mysql.cj.jdbc.Driver"
                    config.username = mysql.username
                    config.password = mysql.password
                    config.maximumPoolSize = mysql.poolSize.coerceAtLeast(1)
                    config.isReadOnly = true
                }
                else -> throw IllegalArgumentException("Unknown database type '${settings.type}': use sqlite, mariadb or mysql")
            }
            val source = HikariDataSource(config)
            try {
                PlaytimeStorage.use(Database.connect(source), settings.tablePrefix)
            } catch (e: Throwable) {
                source.close()
                throw e
            }
            return PlaytimeDatabase(source)
        }
    }
}
