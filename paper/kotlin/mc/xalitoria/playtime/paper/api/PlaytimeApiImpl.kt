package mc.xalitoria.playtime.paper.api

import kotlinx.coroutines.future.future
import mc.xalitoria.playtime.paper.XaliPlaytime
import mc.xalitoria.playtime.paper.tracking.PlaytimeTracker
import net.xalitoria.playtime.PlaytimeEntry
import net.xalitoria.playtime.PlaytimeListener
import net.xalitoria.playtime.PlaytimePeriod
import net.xalitoria.playtime.PlaytimeStats
import net.xalitoria.playtime.XaliPlaytimeApi
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture

/** What other plugins reach through the services manager. */
class PlaytimeApiImpl(private val plugin: XaliPlaytime) : XaliPlaytimeApi {
    private val service get() = plugin.service

    override fun cachedActive(player: UUID): Long = service.tracker.session(player)?.estimate(PlaytimeTracker.now()) ?: -1

    override fun cachedConnected(player: UUID): Long = service.tracker.session(player)?.connected(PlaytimeTracker.now()) ?: -1

    override fun pendingActive(player: UUID): Long = service.tracker.session(player)?.pending(PlaytimeTracker.now()) ?: 0

    override fun isAfk(player: UUID): Boolean = service.tracker.session(player)?.isAfk(PlaytimeTracker.now()) ?: false

    override fun stats(player: UUID): CompletableFuture<PlaytimeStats?> = plugin.scope.future { service.stats(player) }

    override fun top(period: PlaytimePeriod, limit: Int): List<PlaytimeEntry> = service.leaderboard(period).take(limit.coerceAtLeast(0))

    override fun position(player: UUID, period: PlaytimePeriod): Int = service.positionOf(player, period)

    override fun addListener(plugin: Plugin, listener: PlaytimeListener) = service.addListener(plugin, listener)

    override fun removeListeners(plugin: Plugin) = service.removeListeners(plugin)
}
