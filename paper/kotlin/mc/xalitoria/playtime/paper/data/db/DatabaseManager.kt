package mc.xalitoria.playtime.paper.data.db

import com.xalitoria.playtime.storage.DailyTable
import com.xalitoria.playtime.storage.PlayerTable
import com.xalitoria.playtime.storage.PlaytimeStorage
import com.xalitoria.playtime.storage.PlaytimeTable
import com.xalitoria.util.config.DatabaseConfig
import com.xalitoria.util.config.databaseType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mc.xalitoria.playtime.paper.XaliPlaytime
import mc.xalitoria.playtime.paper.XaliPlaytime.Companion.debug
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

/** HikariCP pool and Exposed [Database] for the plugin's tables (see [com.xalitoria.playtime.storage]). */
object DatabaseManager {
    lateinit var dataSource: HikariDataSource
        private set
    lateinit var database: Database
        private set

    val tablePrefix get() = PlaytimeStorage.prefix

    fun connect(plugin: XaliPlaytime, config: DatabaseConfig, server: String) {
        attach(open(plugin, config), config, server)
    }

    /**
     * Opens the database from [config] again, after the one in use: the new connection is made and its schema checked first, and
     * only then does it replace the old, which is closed. If it can't be made this throws and the old one stays in use. Table
     * names are fixed when the plugin starts, so a new `table-prefix` is ignored until a restart (the old one is kept).
     */
    fun reconnect(plugin: XaliPlaytime, config: DatabaseConfig, server: String) {
        val oldSource = dataSource
        val oldDatabase = database
        val oldServer = PlaytimeStorage.server
        if (config.tablePrefix != tablePrefix) {
            plugin.logger.warning("database.table-prefix changed, which needs a restart; keeping '$tablePrefix' until then")
        }
        val source = open(plugin, config)
        try {
            attach(source, config.copy(tablePrefix = tablePrefix), server)
        } catch (e: Exception) {
            PlaytimeStorage.use(oldDatabase, tablePrefix, oldServer)
            dataSource = oldSource
            database = oldDatabase
            source.close()
            throw e
        }
        oldSource.close()
    }

    private fun open(plugin: XaliPlaytime, config: DatabaseConfig): HikariDataSource {
        val hikariConfig = HikariConfig()
        val type = config.databaseType
        if (type.isRemote) {
            val mysql = config.mysql
            hikariConfig.jdbcUrl = mysql.jdbcUrl(type)
            hikariConfig.driverClassName = mysql.driverClass(type)
            hikariConfig.username = mysql.username
            hikariConfig.password = mysql.password
            hikariConfig.maximumPoolSize = mysql.poolSize.coerceAtLeast(1)
        } else {
            val file = plugin.dataFolder.resolve(config.sqlite.filename)
            file.parentFile.mkdirs()
            hikariConfig.jdbcUrl = "jdbc:sqlite:${file.absolutePath}"
            hikariConfig.driverClassName = "org.sqlite.JDBC"
            // Single-writer: SQLite does not support real concurrent writes.
            hikariConfig.maximumPoolSize = 1
            // SQLite leaves foreign keys (and their cascades) off unless asked, on every connection.
            hikariConfig.connectionInitSql = "PRAGMA foreign_keys = ON"
        }
        hikariConfig.poolName = "XaliPlaytime-DB"
        return HikariDataSource(hikariConfig)
    }

    /** Sets up the schema on [source]. The prefix is set first, as table objects read it once. */
    internal fun attach(source: HikariDataSource, config: DatabaseConfig, server: String = "server") {
        dataSource = source
        database = Database.connect(source)
        PlaytimeStorage.use(database, DatabaseConfig.checkPrefix(config.tablePrefix), server)
        debug("Attaching database: prefix='{0}'", tablePrefix)

        transaction(database) {
            MigrationUtils.statementsRequiredForDatabaseMigration(PlayerTable, PlaytimeTable, DailyTable, withLogs = false)
                .onEach { debug("Schema statement: {0}", it) }
                .forEach { exec(it) }
        }
    }

    fun close() {
        if (::dataSource.isInitialized) dataSource.close()
    }

    /** Runs [statement] in a suspending transaction on [Dispatchers.IO]; nested calls join the outer one. */
    suspend fun <T> dbTransaction(statement: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = database, statement = statement) }
}
