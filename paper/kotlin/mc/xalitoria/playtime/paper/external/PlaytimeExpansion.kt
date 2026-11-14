package mc.xalitoria.playtime.paper.external

import mc.xalitoria.playtime.paper.XaliPlaytime
import mc.xalitoria.playtime.paper.tracking.PlaytimeTracker
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import net.xalitoria.playtime.PlaytimePeriod
import org.bukkit.OfflinePlayer
import java.util.Locale

/**
 * `%xaliplaytime_...%`. Everything is answered from memory (the leaderboards as of their last refresh, and what the server
 * has counted for a player who is online), never from the database, since placeholders are asked for on a region thread.
 * The ones about a player are empty for a player who is not online here.
 *
 * - `active`, `active_formatted`: active seconds, and the same in words. `connected`, `connected_formatted`: the same for the time connected.
 * - `afk`: `true` or `false`.
 * - `position`, `position_<period>`: the place on the leaderboard (`day`, `week`, `month`, `all`); empty when not on it.
 * - `top_<period>_<n>_name`, `_time` (seconds), `_time_formatted`, `_position`: place n (from 1) of that leaderboard. `top_<n>_...` is of all time.
 */
class PlaytimeExpansion(private val plugin: XaliPlaytime) : PlaceholderExpansion() {
    override fun getIdentifier() = "xaliplaytime"
    override fun getAuthor() = "audizian"
    override fun getVersion() = plugin.pluginMeta.version
    override fun persist() = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        val service = plugin.service
        val key = params.lowercase(Locale.ROOT)
        if (key.startsWith("top_")) return top(key.removePrefix("top_"))

        val uuid = player?.uniqueId ?: return ""
        val session = service.tracker.session(uuid)
        val now = PlaytimeTracker.now()
        return when (key) {
            "active" -> session?.let { (it.estimate(now) / 1000).toString() }.orEmpty()
            "active_formatted" -> session?.let { service.words(it.estimate(now) / 1000) }.orEmpty()
            "connected" -> session?.let { (it.connected(now) / 1000).toString() }.orEmpty()
            "connected_formatted" -> session?.let { service.words(it.connected(now) / 1000) }.orEmpty()
            "afk" -> (session?.isAfk(now) ?: false).toString()
            "position" -> position(uuid, PlaytimePeriod.ALL)
            else -> if (key.startsWith("position_")) period(key.removePrefix("position_"))?.let { position(uuid, it) } else null
        }
    }

    private fun position(uuid: java.util.UUID, period: PlaytimePeriod): String =
        plugin.service.positionOf(uuid, period).takeIf { it > 0 }?.toString().orEmpty()

    private fun period(name: String): PlaytimePeriod? = PlaytimePeriod.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun top(rest: String): String? {
        val parts = rest.split('_', limit = 3)
        // `top_<n>_...` is of all time; `top_<period>_<n>_...` names the period.
        val period = period(parts[0])
        val after = if (period != null) rest.removePrefix(parts[0] + "_") else rest
        val place = after.substringBefore('_').toIntOrNull() ?: return null
        val entry = plugin.service.leaderboard(period ?: PlaytimePeriod.ALL).getOrNull(place - 1)
        return when (after.substringAfter('_', "")) {
            "name" -> entry?.name.orEmpty()
            "time" -> entry?.let { (it.activeMillis / 1000).toString() }.orEmpty()
            "time_formatted" -> entry?.let { plugin.service.words(it.activeMillis / 1000) }.orEmpty()
            "position" -> entry?.position?.toString().orEmpty()
            else -> null
        }
    }
}
