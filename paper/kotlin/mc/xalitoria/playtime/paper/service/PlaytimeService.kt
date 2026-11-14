package mc.xalitoria.playtime.paper.service

import com.xalitoria.util.message.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mc.xalitoria.playtime.paper.XaliPlaytime
import mc.xalitoria.playtime.paper.XaliPlaytime.Companion.debug
import mc.xalitoria.playtime.paper.data.db.repository.PlaytimeRepository
import mc.xalitoria.playtime.paper.data.model.Profile
import mc.xalitoria.playtime.paper.data.model.Ranked
import mc.xalitoria.playtime.paper.data.model.Stats
import mc.xalitoria.playtime.paper.tracking.PlaytimeTracker
import mc.xalitoria.playtime.paper.tracking.Rules
import mc.xalitoria.playtime.paper.tracking.Session
import net.xalitoria.playtime.PlaytimeListener
import net.xalitoria.playtime.PlaytimePeriod
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * What play time goes through: a player joins ([arrive]), the [PlaytimeTracker] counts their active time, [save] writes it
 * down and tells the listeners of other plugins, and they leave ([leave]). The leaderboards are kept in memory and brought
 * up to date now and then, so that a placeholder never waits for the database.
 */
class PlaytimeService(private val plugin: XaliPlaytime) {
    val tracker = PlaytimeTracker(plugin, Rules.of(plugin.config.data.counting))

    private val boards = AtomicReference<Map<PlaytimePeriod, List<Ranked>>>(emptyMap())
    private val listeners = CopyOnWriteArrayList<Pair<Plugin, PlaytimeListener>>()

    /** Set while the database is being replaced: saves wait, or are skipped when the next one will do. */
    @Volatile
    private var paused = false

    private val config get() = plugin.config.data
    private val msg get() = plugin.messageConfig.data

    /** Brings the rules up to date after the configuration changed. Players being watched keep the rules they started with. */
    fun configure() {
        tracker.rules = Rules.of(config.counting)
    }

    fun start() {
        configure()
        // Both loops read their interval anew each time, so a reload changes them without restarting anything.
        plugin.scope.launch {
            while (isActive) {
                delay(config.counting.saveInterval())
                tracker.tracked.forEach { session -> plugin.scope.launch { save(session) } }
            }
        }
        plugin.scope.launch {
            while (isActive) {
                refreshBoards()
                delay(config.leaderboard.refresh())
            }
        }
    }

    // --- Playing ---------------------------------------------------------------------------------------------

    /** A player comes in: their row, their totals so far, and from now on their time is counted. */
    suspend fun arrive(player: Player, history: History = History.NONE) {
        var totals = PlaytimeRepository.joined(player.uniqueId, player.name)
        if (history.millis > 0 && config.importing.enable) {
            val day = if (history.firstPlayed > 0) PlaytimeRepository.dayOf(history.firstPlayed) else 0
            PlaytimeRepository.importHistory(player.uniqueId, history.millis, day)?.let {
                totals = it
                plugin.logger.info("Brought in ${words(history.millis / 1000)} that ${player.name} played on this server before XaliPlaytime")
            }
        }
        if (tracker.start(player, totals.active, totals.connected) != null) {
            debug("{0} is counted from {1} ms active", player.name, totals.active)
        }
    }

    /** What a player had played on this server before the plugin: [millis] since [firstPlayed] (milliseconds since the epoch, 0 if unknown). */
    data class History(val millis: Long, val firstPlayed: Long) {
        companion object {
            val NONE = History(0, 0)
        }
    }

    /** The player leaves: their time is judged one last time and saved. Runs on the player's thread. */
    fun leave(player: Player) {
        val session = tracker.stop(player.uniqueId, player) ?: return
        plugin.scope.launch { save(session, final = true) }
    }

    /**
     * Writes down the time [session] has earned and tells the listeners. One save at a time per player. A second one asked for
     * meanwhile is skipped, as the next one carries what it would have; but a [final] one (the player left, the server
     * stops) has no next one, so it waits its turn.
     */
    suspend fun save(session: Session, final: Boolean = false) {
        while (paused) {
            if (!final) return
            delay(FINAL_SAVE_WAIT_MILLIS)
        }
        if (final) {
            while (!session.saving.compareAndSet(false, true)) delay(FINAL_SAVE_WAIT_MILLIS)
        } else if (!session.saving.compareAndSet(false, true)) return
        try {
            val now = PlaytimeTracker.now()
            val active = session.take()
            val connected = session.takeConnected(now)
            if (active == 0L && connected == 0L) return
            val totals = try {
                PlaytimeRepository.add(session.player, active, connected, PlaytimeRepository.dayOf(System.currentTimeMillis()))
            } catch (e: Exception) {
                session.giveBack(active)
                session.giveBackConnected(connected)
                plugin.logger.fail("Failed to save the play time of ${session.player}", e)
                return
            }
            session.synced(totals.active)
            if (active > 0) notify(session.player, active)
        } finally {
            session.saving.set(false)
        }
    }

    /** Saves every player being watched, for [XaliPlaytime.onDisable] and `/xaliplaytime admin save`. Returns how many there were. */
    suspend fun saveAll(): Int {
        val sessions = tracker.tracked.toList()
        sessions.forEach { save(it, final = true) }
        return sessions.size
    }

    /**
     * Saves everyone now, then runs [block] while no save starts and none is under way; for what replaces the database. Saves
     * that are asked for meanwhile wait ([save] with `final`) or are left to the next one.
     */
    suspend fun <T> whileSaved(block: suspend () -> T): T {
        saveAll()
        paused = true
        try {
            withTimeoutOrNull(PAUSE_WAIT_MILLIS) { while (tracker.tracked.any { it.saving.get() }) delay(FINAL_SAVE_WAIT_MILLIS) }
            return block()
        } finally {
            paused = false
        }
    }

    // --- Changes by an admin ---------------------------------------------------------------------------------

    /** What an admin command changed: the amount, and what the player has afterwards (all servers). */
    data class Changed(val applied: Long, val activeMillis: Long)

    /** Adds [delta] milliseconds (or takes them away, when negative) from the player's time on this server. */
    suspend fun give(profile: Profile, delta: Long): Changed = settled(profile.uuid) {
        PlaytimeRepository.adjust(profile.uuid, delta, today()).let { Changed(it.applied, it.totals.active) }
    }

    /** Sets the player's time on this server to [target] milliseconds. */
    suspend fun set(profile: Profile, target: Long): Changed = settled(profile.uuid) {
        PlaytimeRepository.setServerActive(profile.uuid, target, today()).let { Changed(it.applied, it.totals.active) }
    }

    /** Takes all the time the player has, on every server. */
    suspend fun reset(profile: Profile): Changed = settled(profile.uuid) {
        Changed(0, PlaytimeRepository.reset(profile.uuid, today()).active)
    }

    /** Deletes the player from the database. Returns whether they were in it; a player who is online starts again from nothing. */
    suspend fun purge(profile: Profile): Boolean {
        val session = tracker.session(profile.uuid)
        // What was earned and not saved is theirs no longer.
        session?.take()
        session?.takeConnected(PlaytimeTracker.now())
        val found = PlaytimeRepository.purge(profile.uuid)
        if (session != null) {
            PlaytimeRepository.joined(profile.uuid, profile.name)
            session.synced(0)
        }
        return found
    }

    /** How many players have not been seen for [days] days, and with [delete] they are deleted. Players online are never. */
    suspend fun purgeInactive(days: Int, delete: Boolean): Int {
        val before = System.currentTimeMillis() - days * PlaytimeRepository.MILLIS_PER_DAY
        return PlaytimeRepository.purgeInactive(before, delete, plugin.server.onlinePlayers.map { it.uniqueId }.toSet())
    }

    private fun today() = PlaytimeRepository.dayOf(System.currentTimeMillis())

    /** Runs [change] with what an online player has earned saved first, and brings their session in line with what it left. */
    private suspend fun settled(uuid: UUID, change: suspend () -> Changed): Changed {
        val session = tracker.session(uuid)
        if (session != null) save(session, final = true)
        val changed = change()
        session?.synced(changed.activeMillis)
        return changed
    }

    // --- What others ask -------------------------------------------------------------------------------------

    fun addListener(owner: Plugin, listener: PlaytimeListener) {
        listeners += owner to listener
    }

    fun removeListeners(owner: Plugin) {
        listeners.removeIf { it.first == owner }
    }

    private fun notify(player: UUID, active: Long) {
        for ([owner, listener] in listeners) {
            if (!owner.isEnabled) continue
            try {
                listener.credited(player, active)
            } catch (e: Throwable) {
                plugin.logger.fail("${owner.name} failed to take the play time of $player", e)
            }
        }
    }

    /** What the database has of [uuid], with what an online player has earned since it was written; null for a player not seen. */
    suspend fun stats(uuid: UUID): Stats? {
        val stats = PlaytimeRepository.stats(uuid) ?: return null
        val session = tracker.session(uuid) ?: return stats
        val now = PlaytimeTracker.now()
        return stats.plus(session.pending(now), session.unsavedConnected(now))
    }

    // --- The leaderboards ------------------------------------------------------------------------------------

    /** The places of [period] as of the last refresh. */
    fun leaderboard(period: PlaytimePeriod): List<Ranked> = boards.get()[period].orEmpty()

    fun positionOf(uuid: UUID, period: PlaytimePeriod): Int = leaderboard(period).firstOrNull { it.player == uuid }?.position ?: 0

    suspend fun refreshBoards() {
        try {
            boards.set(PlaytimePeriod.entries.associateWith { PlaytimeRepository.top(it, config.leaderboard.size) })
        } catch (e: Exception) {
            plugin.logger.fail("Failed to refresh the leaderboards", e)
        }
    }

    /** [seconds] in the words of the language. */
    fun words(seconds: Long): String = msg.words.duration(seconds)

    private companion object {
        const val FINAL_SAVE_WAIT_MILLIS = 10L
        const val PAUSE_WAIT_MILLIS = 5_000L
    }
}
