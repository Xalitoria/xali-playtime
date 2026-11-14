package mc.xalitoria.playtime.paper.data.config

import com.charleskorn.kaml.YamlComment
import com.xalitoria.util.config.DatabaseConfig
import com.xalitoria.util.kotlin.parseDuration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
data class MainConfig(
    val debug: Boolean = false,
    val language: String = "en-us",
    @SerialName("server-name")
    @YamlComment(
        "The name this server's play time is kept under. Servers that share a database each need their own; the name in velocity.toml is",
        "easiest to follow. The proxy's Plan page adds the servers up and shows each one's part. Needs a restart.",
    )
    val serverName: String = "server",
    @YamlComment("How play time is measured.")
    val counting: Counting = Counting(),
    val leaderboard: Leaderboard = Leaderboard(),
    @SerialName("import")
    @YamlComment(
        "The time a player had on this server before this plugin, from the statistic that EssentialsX's /playtime shows.",
        "It is brought in once per player, the first time they join while this server has no time of theirs.",
    )
    val importing: Import = Import(),
    @YamlComment(
        "Adds the play time to Plan (Player Analytics) when it is installed: a XaliPlaytime tab on the player and server pages.",
        "Both are registered at startup, so a change here needs a restart.",
    )
    val plan: Plan = Plan(),
    val database: DatabaseConfig = DatabaseConfig(
        tablePrefix = "xaliplaytime_",
        sqlite = DatabaseConfig.Sqlite("xaliplaytime.db"),
        mysql = DatabaseConfig.Mysql(database = "xaliplaytime"),
    ),
) {
    @Serializable
    data class Counting(
        @YamlComment(
            "Time counts only while the player did something less than this long ago (moved, looked around, chatted, built, clicked",
            "an inventory...). Longer without anything and they are AFK, and the time stops counting until they act again.",
            "Units: d, h, m, s, and mixes such as 1h 30m.",
        )
        @SerialName("afk-threshold")
        val afkThreshold: String = "30s",
        @YamlComment("How often players are checked. Not longer than afk-threshold. Short checks cost little: they read a few numbers.")
        @SerialName("check-interval")
        val checkInterval: String = "5s",
        @YamlComment("How often the time earned is written to the database, and passed to the plugins that listen for it.")
        @SerialName("save-interval")
        val saveInterval: String = "1m",
        val machines: Machines = Machines(),
    ) {
        /**
         * AFK machines (a water stream, a minecart loop, a jump bot, an auto-clicker) keep a player "active" without anyone
         * playing. Time is held back until a window of play has been judged: a window counts only if it shows several
         * kinds of activity and, when the player stayed in a small area, a deliberate one such as chatting or building.
         */
        @Serializable
        data class Machines(
            val enabled: Boolean = true,
            @YamlComment("Length of a window. Time earned in a window that fails is lost, so a longer window is stricter and slower to credit.")
            val window: String = "2m",
            @YamlComment("A player who stays within this many blocks (east-west and north-south) for the whole window is judged as standing still.")
            val radius: Int = 6,
            @YamlComment("How many kinds of activity a window needs: moving keys, looking around, chat, commands, building, clicking, inventory.")
            @SerialName("min-variety")
            val minVariety: Int = 2,
        )

        fun afkThreshold(): Duration = parseDuration("counting.afk-threshold", afkThreshold)
        fun checkInterval(): Duration = parseDuration("counting.check-interval", checkInterval)
        fun saveInterval(): Duration = parseDuration("counting.save-interval", saveInterval)
        fun machineWindow(): Duration = parseDuration("counting.machines.window", machines.window)
    }

    @Serializable
    data class Leaderboard(
        @YamlComment("How often the leaderboards (/xaliplaytime top, the placeholders and the API) are brought up to date from the database.")
        val refresh: String = "5m",
        @YamlComment("Lines per page of /xaliplaytime top.")
        @SerialName("per-page")
        val perPage: Int = 10,
        @YamlComment("How many places each leaderboard keeps, for the pages, the placeholders and the API.")
        val size: Int = 100,
    ) {
        fun refresh(): Duration = parseDuration("leaderboard.refresh", refresh)
    }

    @Serializable
    data class Import(
        @YamlComment("Minecraft does not know AFK, so the time brought in counts as all active.")
        val enable: Boolean = true,
    )

    @Serializable
    data class Plan(
        val enable: Boolean = true,
        @YamlComment("Days shown in the table of a player's page (1 to 90).")
        val days: Int = 14,
    )

    /** Fails with the key named when a time is unreadable or negative, so a typo is found when the file loads. */
    fun validate() {
        val afk = counting.afkThreshold()
        val check = counting.checkInterval()
        counting.saveInterval()
        counting.machineWindow()
        require(afk > Duration.ZERO) { "counting.afk-threshold must be more than 0s" }
        require(check >= 1.seconds / 4) { "counting.check-interval must be at least a quarter of a second" }
        require(check <= afk) { "counting.check-interval must not be longer than counting.afk-threshold, or AFK would go unseen" }
        require(counting.saveInterval() > Duration.ZERO) { "counting.save-interval must be more than 0s" }
        require(counting.machines.radius >= 1) { "counting.machines.radius must be at least 1" }
        require(counting.machines.minVariety in 1..MAX_VARIETY) { "counting.machines.min-variety must be from 1 to $MAX_VARIETY" }
        leaderboard.refresh()
        require(leaderboard.perPage >= 1) { "leaderboard.per-page must be at least 1" }
        require(leaderboard.size >= 1) { "leaderboard.size must be at least 1" }
        require(serverName.isNotBlank() && serverName.length <= MAX_SERVER_NAME) { "server-name must be from 1 to $MAX_SERVER_NAME characters" }
        require(plan.days in 1..90) { "plan.days must be from 1 to 90" }
    }

    companion object {
        /** Kinds of activity there are. */
        const val MAX_VARIETY = 7

        /** The length of the server column. */
        const val MAX_SERVER_NAME = 64
    }
}
