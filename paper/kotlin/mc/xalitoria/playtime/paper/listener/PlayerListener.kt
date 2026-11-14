package mc.xalitoria.playtime.paper.listener

import com.xalitoria.util.message.fail
import kotlinx.coroutines.launch
import mc.xalitoria.playtime.paper.XaliPlaytime
import mc.xalitoria.playtime.paper.service.PlaytimeService
import org.bukkit.Statistic
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

/** Brings a player in and out of the count. */
class PlayerListener(private val plugin: XaliPlaytime) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        // The statistic is the player's own, so it is read here, on their thread, before the database is asked anything.
        val history = if (plugin.config.data.importing.enable) {
            PlaytimeService.History(player.getStatistic(Statistic.PLAY_ONE_MINUTE) * MILLIS_PER_TICK, player.firstPlayed)
        } else PlaytimeService.History.NONE
        plugin.scope.launch {
            try {
                plugin.service.arrive(player, history)
            } catch (e: Exception) {
                plugin.logger.fail("Failed to start counting ${player.name}", e)
            }
        }
    }

    private companion object {
        const val MILLIS_PER_TICK = 50L
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) = plugin.service.leave(event.player)
}
