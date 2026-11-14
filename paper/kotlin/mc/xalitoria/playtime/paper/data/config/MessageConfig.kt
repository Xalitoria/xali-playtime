package mc.xalitoria.playtime.paper.data.config

import com.charleskorn.kaml.YamlComment
import com.xalitoria.util.message.TimeWords
import com.xalitoria.util.message.formatArgs
import com.xalitoria.util.serial.message.State
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What every heading starts with: the same colors in every Xali plugin. */
private const val HEAD = "{#a0a>}&lXALI{#fff<}&f&lPLAYTIME &7>"

/** Every message the plugin sends, in `language/<language>.yml`. `{0}`, `{1}`... are filled in by the plugin. */
@Serializable
data class MessageConfig(
    val commands: Commands = Commands(),
    val info: Info = Info(),
    val admin: Admin = Admin(),
    @YamlComment("The units of a time such as \"1 hour 5 minutes\": the words of the language, and what goes between the parts.")
    val words: TimeWords = TimeWords(),
) {
    @Serializable
    data class Commands(
        @SerialName("no-permission")
        val noPermission: String = "You don't have permission to use this command.",
        @SerialName("player-only")
        val playerOnly: String = "You must be a player to use this command.",
        @SerialName("player-not-found")
        private val playerNotFound: String = "No player called {0} has joined.",
        private val help: String = """
            $HEAD &dHELP
            &d/{0} &r- how long you have played
            &d/{0} &rinfo <player> &7- the same for another player
            &d/{0} &rtop [day|week|month|all] [page] &7- who played the most
        """.trimIndent(),
        @SerialName("admin-help")
        private val adminHelp: String = """
            &d/{0} &radmin save &7- write the time of the players online to the database
            &d/{0} &radmin add|remove|set <player> <time> &7- change a player's time on this server, such as 1h 30m
            &d/{0} &radmin reset <player> &7- take all the time of a player, on every server
            &d/{0} &radmin purge <player> &7- delete a player from the database
            &d/{0} &radmin purge-inactive <days> [confirm] &7- delete the players not seen for that long
            &d/{0} &radmin reload &7- save, read the configuration and connect to the database again
            &d/{0} &radmin debug &7- turn the debug log on or off
        """.trimIndent(),
        @SerialName("unexpected-error")
        val unexpectedError: String = "An unexpected error occurred. Please try again.",
        val reload: String = "Configuration reloaded; the time of the players online was saved and the database connected again.",
        @SerialName("reload-failed")
        private val reloadFailed: String = "The configuration was not reloaded: {0}",
        @YamlComment("Said when /<command> admin debug turns the debug log on or off.")
        val debug: State = State("Debug logging is on.", "Debug logging is off."),
    ) {
        fun playerNotFound(name: String): String = playerNotFound.formatArgs(name)
        fun help(label: String, admin: Boolean): String = help.formatArgs(label) + if (admin) "\n" + adminHelp.formatArgs(label) else ""
        fun reloadFailed(reason: String): String = reloadFailed.formatArgs(reason)
    }

    @Serializable
    data class Info(
        private val header: String = "$HEAD &d{0}",
        private val active: String = "&dPlayed: &f{0}",
        private val connected: String = "&dConnected: &f{0} &7({1} of it AFK)",
        private val periods: String = "&dToday: &f{0} &7- &dthis week: &f{1} &7- &dthis month: &f{2}",
        private val joins: String = "&dJoined: &f{0} times &7- first seen {1}",
        private val position: String = "&dPosition: &f#{0}",
        val afk: String = "&7Away from keyboard right now.",
        @SerialName("not-played")
        private val notPlayed: String = "{0} has no play time yet.",
        @SerialName("top-header")
        private val topHeader: String = "$HEAD &dMost active, {0} &7({1}/{2})",
        @SerialName("top-entry")
        private val topEntry: String = "&d#{0} &f{1} &7- {2}",
        @SerialName("top-empty")
        val topEmpty: String = "&7Nobody has played yet.",
        @SerialName("period-day")
        val periodDay: String = "today",
        @SerialName("period-week")
        val periodWeek: String = "this week",
        @SerialName("period-month")
        val periodMonth: String = "this month",
        @SerialName("period-all")
        val periodAll: String = "of all time",
    ) {
        fun header(name: String): String = header.formatArgs(name)
        fun active(time: String): String = active.formatArgs(time)
        fun connected(time: String, afk: String): String = connected.formatArgs(time, afk)
        fun periods(day: String, week: String, month: String): String = periods.formatArgs(day, week, month)
        fun joins(count: Int, first: String): String = joins.formatArgs(count, first)
        fun position(position: Int): String = this.position.formatArgs(position)
        fun notPlayed(name: String): String = notPlayed.formatArgs(name)
        fun topHeader(period: String, page: Int, pages: Int): String = topHeader.formatArgs(period, page, pages)
        fun topEntry(position: Int, name: String, time: String): String = topEntry.formatArgs(position, name, time)
    }

    @Serializable
    data class Admin(
        private val saved: String = "Saved the time of {0} players.",
        private val added: String = "Added {1} to {0}, who has played {2} now.",
        private val removed: String = "Took {1} from {0}, who has played {2} now.",
        @SerialName("removed-less")
        private val removedLess: String = "There was only {1} of {0}'s time on this server to take; they have played {2} now.",
        private val set: String = "{0} has {1} on this server now, and has played {2} in all.",
        private val reset: String = "Took all the time of {0}.",
        private val purged: String = "Deleted {0} from the database.",
        @SerialName("not-in-database")
        private val notInDatabase: String = "{0} is not in the database.",
        @SerialName("purge-inactive-ask")
        private val purgeInactiveAsk: String = "{0} players were not seen for {1} days. Run /{2} admin purge-inactive {1} confirm to delete them.",
        @SerialName("purge-inactive-done")
        private val purgeInactiveDone: String = "Deleted {0} players not seen for {1} days.",
        @SerialName("invalid-time")
        private val invalidTime: String = "{0} is not a time. Write it like 30m or 1h 30m, with more than 0 of it.",
    ) {
        fun saved(count: Int): String = saved.formatArgs(count)
        fun added(name: String, amount: String, total: String): String = added.formatArgs(name, amount, total)
        fun removed(name: String, amount: String, total: String): String = removed.formatArgs(name, amount, total)
        fun removedLess(name: String, amount: String, total: String): String = removedLess.formatArgs(name, amount, total)
        fun set(name: String, here: String, total: String): String = set.formatArgs(name, here, total)
        fun reset(name: String): String = reset.formatArgs(name)
        fun purged(name: String): String = purged.formatArgs(name)
        fun notInDatabase(name: String): String = notInDatabase.formatArgs(name)
        fun purgeInactiveAsk(count: Int, days: Int, label: String): String = purgeInactiveAsk.formatArgs(count, days, label)
        fun purgeInactiveDone(count: Int, days: Int): String = purgeInactiveDone.formatArgs(count, days)
        fun invalidTime(text: String): String = invalidTime.formatArgs(text)
    }
}
