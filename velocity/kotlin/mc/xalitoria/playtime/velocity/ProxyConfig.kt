package mc.xalitoria.playtime.velocity

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlComment
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** `plugins/xaliplaytime/config.yml` on the proxy. */
@Serializable
data class ProxyConfig(
    @YamlComment("Log what the plugin does in more detail.")
    val debug: Boolean = false,
    @YamlComment("The network's play time on Plan's pages, when Plan's web server runs on this proxy.")
    val plan: Plan = Plan(),
    @YamlComment(
        "The database the servers write their play time to, which the proxy adds up. The same settings as the servers' config.yml.",
        "It is only read from, so a user that may only SELECT will do. Needs a restart.",
    )
    val database: Database = Database(),
) {
    @Serializable
    data class Plan(
        val enable: Boolean = true,
        @YamlComment("Days shown in the tables of a player's page (1 to 90) and for the servers' recent time.")
        val days: Int = 7,
        @YamlComment("Places in the table of the most active players of the network.")
        val top: Int = 10,
    )

    @Serializable
    data class Database(
        @YamlComment("Database types: sqlite, mariadb or mysql. Use mariadb for MariaDB: it has its own driver. Servers of one network need a shared mariadb or mysql database; sqlite is a file next to the proxy.")
        val type: String = "mariadb",
        @SerialName("table-prefix")
        @YamlComment("As on the servers: letters, digits and underscores.")
        val tablePrefix: String = "xaliplaytime_",
        val sqlite: Sqlite = Sqlite(),
        @YamlComment("MariaDB/MySQL connection settings")
        val mysql: Mysql = Mysql(),
    ) {
        @Serializable
        data class Sqlite(val filename: String = "xaliplaytime.db")

        @Serializable
        data class Mysql(
            val host: String = "localhost",
            val port: Int = 3306,
            val database: String = "xaliplaytime",
            val username: String = "root",
            val password: String = "",
            @SerialName("use-ssl")
            val useSsl: Boolean = false,
            @SerialName("pool-size")
            @YamlComment("Connections kept open for Plan's pages; a few are plenty.")
            val poolSize: Int = 3,
        )
    }

    fun validate() {
        require(plan.days in 1..90) { "plan.days must be from 1 to 90" }
        require(plan.top in 1..100) { "plan.top must be from 1 to 100" }
    }

    companion object {
        private val yaml = Yaml(configuration = YamlConfiguration(strictMode = false, encodeDefaults = true))

        /**
         * Reads [file], then writes it back so a first start has the defaults in it and an older file gains new keys.
         * A file that doesn't parse is left as it is and fails.
         */
        fun load(file: Path): ProxyConfig {
            val config = try {
                if (file.exists()) yaml.decodeFromString(serializer(), file.readText()) else ProxyConfig()
            } catch (e: Exception) {
                throw IllegalStateException("config.yml doesn't parse", e)
            }
            config.validate()
            file.writeText(yaml.encodeToString(serializer(), config))
            return config
        }
    }
}
