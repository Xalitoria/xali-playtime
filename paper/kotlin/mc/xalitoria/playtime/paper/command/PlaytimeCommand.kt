package mc.xalitoria.playtime.paper.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.xalitoria.util.kotlin.parseDuration
import com.xalitoria.util.message.fail
import com.xalitoria.util.message.pass
import com.xalitoria.util.message.send
import com.xalitoria.util.message.warn
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import kotlinx.coroutines.launch
import mc.xalitoria.playtime.paper.XaliPlaytime
import mc.xalitoria.playtime.paper.data.constants.Permissions
import mc.xalitoria.playtime.paper.data.db.repository.PlaytimeRepository
import mc.xalitoria.playtime.paper.data.model.Profile
import net.xalitoria.playtime.PlaytimePeriod
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * `/xaliplaytime`, also `/playtime`: how long a player has played, the leaderboards, and the admin commands. Everything that
 * reads the database runs off the region thread and answers when it has the result.
 */
class PlaytimeCommand(private val plugin: XaliPlaytime) {
    val label = "xaliplaytime"
    val aliases = listOf("playtime", "xplaytime", "ptime", "xptime")
    val description = "Play time: how long you have really played, and who played the most"

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())
    }

    private val msg get() = plugin.messageConfig.data
    private val service get() = plugin.service

    fun builder(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(label)
        .requires { it.sender.hasPermission(Permissions.USE) }
        .executes { run(it) { sender -> self(sender) } }
        .then(Commands.literal("help").executes { run(it) { sender -> sender send msg.commands.help(label, sender.hasPermission(Permissions.ADMIN)) } })
        .then(Commands.literal("info")
            .executes { run(it) { sender -> self(sender) } }
            .then(playerArgument()
                .requires { it.sender.hasPermission(Permissions.OTHERS) }
                .executes { run(it) { sender -> info(sender, it.getArgument("player", String::class.java)) } })
        )
        .then(Commands.literal("top")
            .executes { run(it) { sender -> top(sender, PlaytimePeriod.ALL, 1) } }
            .then(period()
                .executes { run(it) { sender -> top(sender, periodOf(it), 1) } }
                .then(page().executes { run(it) { sender -> top(sender, periodOf(it), it.getArgument("page", Int::class.java)) } })
            )
        )
        .then(admin())

    private fun admin(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("admin")
        .requires { it.sender.hasPermission(Permissions.ADMIN) }
        .executes { run(it) { sender -> sender send msg.commands.help(label, true) } }
        .then(Commands.literal("save").executes { run(it) { sender -> async(sender) { sender pass msg.admin.saved(service.saveAll()) } } })
        .then(Commands.literal("reload").executes { run(it) { sender -> reload(sender) } })
        .then(change("add") { sender, profile, millis -> give(sender, profile, millis) })
        .then(change("remove") { sender, profile, millis -> give(sender, profile, -millis) })
        .then(change("set") { sender, profile, millis -> set(sender, profile, millis) })
        .then(Commands.literal("reset").then(playerArgument().executes { run(it) { sender -> reset(sender, it.getArgument("player", String::class.java)) } }))
        .then(Commands.literal("purge").then(playerArgument().executes { run(it) { sender -> purge(sender, it.getArgument("player", String::class.java)) } }))
        .then(Commands.literal("purge-inactive").then(
            Commands.argument("days", IntegerArgumentType.integer(1))
                .executes { run(it) { sender -> purgeInactive(sender, it.getArgument("days", Int::class.java), false) } }
                .then(Commands.literal("confirm").executes { run(it) { sender -> purgeInactive(sender, it.getArgument("days", Int::class.java), true) } })
        ))
        .then(Commands.literal("debug").executes {
            run(it) { sender -> sender send msg.commands.debug.get(plugin.toggleDebug()) }
        })

    /** `admin <name> <player> <time>`: the time is the rest of the line, so that it can have spaces (`1h 30m`). */
    private fun change(name: String, block: suspend (CommandSender, Profile, Long) -> Unit): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal(name).then(playerArgument().then(
            Commands.argument("time", StringArgumentType.greedyString()).executes { context ->
                run(context) { sender ->
                    val text = context.getArgument("time", String::class.java)
                    val millis = try {
                        parseDuration("time", text).inWholeMilliseconds.also { require(it > 0) }
                    } catch (e: Exception) {
                        return@run sender fail msg.admin.invalidTime(text)
                    }
                    withProfile(sender, context.getArgument("player", String::class.java)) { block(sender, it, millis) }
                }
            }
        ))

    // --- Arguments ------------------------------------------------------------------------------------------

    private fun playerArgument(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("player", StringArgumentType.word()).suggests { _, builder ->
            plugin.server.onlinePlayers.map { it.name }.filter { it.startsWith(builder.remaining, ignoreCase = true) }.forEach(builder::suggest)
            builder.buildFuture()
        }

    private fun period(): RequiredArgumentBuilder<CommandSourceStack, String> =
        Commands.argument("period", StringArgumentType.word()).suggests { _, builder ->
            PlaytimePeriod.entries.map { it.name.lowercase() }.filter { it.startsWith(builder.remaining, ignoreCase = true) }.forEach(builder::suggest)
            builder.buildFuture()
        }

    private fun page(): RequiredArgumentBuilder<CommandSourceStack, Int> =
        Commands.argument("page", IntegerArgumentType.integer(1))

    private fun periodOf(context: CommandContext<CommandSourceStack>): PlaytimePeriod =
        PlaytimePeriod.entries.firstOrNull { it.name.equals(context.getArgument("period", String::class.java), ignoreCase = true) } ?: PlaytimePeriod.ALL

    /** Runs [block] for the sender of [context], and tells them if it goes wrong; Brigadier is told it went well either way. */
    private fun run(context: CommandContext<CommandSourceStack>, block: (CommandSender) -> Unit): Int {
        val sender = context.source.sender
        try {
            block(sender)
        } catch (e: Exception) {
            plugin.logger.fail("Failed to run a command of ${sender.name}", e)
            sender fail msg.commands.unexpectedError
        }
        return Command.SINGLE_SUCCESS
    }

    /** Runs [block] off the region thread, and reports what goes wrong to [sender]. */
    private fun async(sender: CommandSender, block: suspend () -> Unit) {
        plugin.scope.launch {
            try {
                block()
            } catch (e: Exception) {
                plugin.logger.fail("Failed to run a command of ${sender.name}", e)
                sender fail msg.commands.unexpectedError
            }
        }
    }

    private fun self(sender: CommandSender) {
        if (sender is Player) info(sender, sender.name) else sender fail msg.commands.playerOnly
    }

    // --- What a player sees ---------------------------------------------------------------------------------

    private fun info(sender: CommandSender, name: String) = async(sender) {
        val profile = PlaytimeRepository.byName(name)
        if (profile == null) {
            sender fail msg.commands.playerNotFound(name)
            return@async
        }
        val stats = service.stats(profile.uuid)
        if (stats == null) {
            sender fail msg.info.notPlayed(profile.name)
            return@async
        }
        val position = service.positionOf(profile.uuid, PlaytimePeriod.ALL)
        sender send msg.info.header(stats.name)
        sender send msg.info.active(words(stats.activeMillis))
        sender send msg.info.connected(words(stats.connectedMillis), words(stats.connectedMillis - stats.activeMillis))
        sender send msg.info.periods(words(stats.activeTodayMillis), words(stats.activeWeekMillis), words(stats.activeMonthMillis))
        sender send msg.info.joins(stats.joins, TIME.format(Instant.ofEpochMilli(stats.firstSeen)))
        if (position > 0) sender send msg.info.position(position)
        if (service.tracker.session(profile.uuid)?.isAfk(mc.xalitoria.playtime.paper.tracking.PlaytimeTracker.now()) == true) sender send msg.info.afk
    }

    private fun top(sender: CommandSender, period: PlaytimePeriod, page: Int) {
        val board = service.leaderboard(period)
        val perPage = plugin.config.data.leaderboard.perPage
        val pages = max(1, (board.size + perPage - 1) / perPage)
        val shown = page.coerceIn(1, pages)
        sender send msg.info.topHeader(periodName(period), shown, pages)
        if (board.isEmpty()) sender send msg.info.topEmpty
        board.drop((shown - 1) * perPage).take(perPage).forEach {
            sender send msg.info.topEntry(it.position, it.name, words(it.activeMillis))
        }
    }

    private fun periodName(period: PlaytimePeriod): String = when (period) {
        PlaytimePeriod.DAY -> msg.info.periodDay
        PlaytimePeriod.WEEK -> msg.info.periodWeek
        PlaytimePeriod.MONTH -> msg.info.periodMonth
        PlaytimePeriod.ALL -> msg.info.periodAll
    }

    private fun words(millis: Long): String = service.words(millis / 1000)

    private fun reload(sender: CommandSender) {
        plugin.scope.launch {
            try {
                plugin.reload()
                sender pass msg.commands.reload
            } catch (e: Exception) {
                plugin.logger.fail("Failed to reload the configuration", e)
                sender fail msg.commands.reloadFailed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // --- What an admin changes ------------------------------------------------------------------------------

    /** Finds the player [name] in the database off the region thread and runs [block] with them; says so if they are not there. */
    private fun withProfile(sender: CommandSender, name: String, block: suspend (Profile) -> Unit) = async(sender) {
        val profile = PlaytimeRepository.byName(name)
        if (profile == null) sender fail msg.commands.playerNotFound(name) else block(profile)
    }

    private suspend fun give(sender: CommandSender, profile: Profile, delta: Long) {
        val changed = service.give(profile, delta)
        val total = words(changed.activeMillis)
        when {
            delta >= 0 -> sender pass msg.admin.added(profile.name, words(delta), total)
            -changed.applied < -delta -> sender warn msg.admin.removedLess(profile.name, words(-changed.applied), total)
            else -> sender pass msg.admin.removed(profile.name, words(-delta), total)
        }
    }

    private suspend fun set(sender: CommandSender, profile: Profile, target: Long) {
        val changed = service.set(profile, target)
        sender pass msg.admin.set(profile.name, words(target), words(changed.activeMillis))
    }

    private fun reset(sender: CommandSender, name: String) = withProfile(sender, name) {
        service.reset(it)
        sender pass msg.admin.reset(it.name)
    }

    private fun purge(sender: CommandSender, name: String) = withProfile(sender, name) {
        if (service.purge(it)) sender pass msg.admin.purged(it.name) else sender fail msg.admin.notInDatabase(it.name)
    }

    private fun purgeInactive(sender: CommandSender, days: Int, confirmed: Boolean) = async(sender) {
        val count = service.purgeInactive(days, confirmed)
        if (confirmed) sender pass msg.admin.purgeInactiveDone(count, days) else sender warn msg.admin.purgeInactiveAsk(count, days, label)
    }
}
