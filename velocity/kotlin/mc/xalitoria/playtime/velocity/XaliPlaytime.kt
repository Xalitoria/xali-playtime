package mc.xalitoria.playtime.velocity

import com.velocitypowered.api.proxy.ProxyServer
import org.slf4j.Logger
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * The proxy end of XaliPlaytime: it counts nothing and relays nothing. The servers write the play time to a shared database,
 * and when Plan's web server runs on this proxy this adds it up for Plan's network pages: the time of the whole network, what
 * each server contributes, the most active players, and on a player's page the time over every server (see [ProxyPlanExtension]).
 *
 * Without Plan there is nothing to do, and the plugin says so and stays idle. A database that can't be reached leaves the pages
 * off, with the reason in the log.
 */
class XaliPlaytime(
    private val proxy: ProxyServer,
    private val logger: Logger,
    private val dataDirectory: Path,
    /** The loader Velocity knows as the plugin. */
    @Suppress("unused") private val plugin: Any,
) : Runnable, AutoCloseable {
    private var config = ProxyConfig()
    private var database: PlaytimeDatabase? = null
    private var hook: PlanHook? = null

    override fun run() {
        dataDirectory.createDirectories()
        config = try {
            ProxyConfig.load(dataDirectory.resolve("config.yml"))
        } catch (e: Exception) {
            logger.error("Could not read config.yml; the network's play time is off until it is fixed", e)
            return
        }
        if (!config.plan.enable) return logger.info("plan.enable is off, so there is nothing for this plugin to do")
        if (!proxy.pluginManager.isLoaded("plan")) return logger.info("Plan is not on this proxy, so there is nothing for this plugin to do")
        try {
            database = PlaytimeDatabase.connect(config.database, dataDirectory)
        } catch (e: Exception) {
            val why = generateSequence<Throwable>(e) { it.cause }.last().message ?: e.javaClass.simpleName
            return logger.warn("Plan is on this proxy, but the play time database can't be reached ({}), so its pages are off: check `database` in config.yml", why)
        }
        try {
            hook = PlanHook(logger, { config.plan }, ::debug).also { it.hook() }
        } catch (e: NoClassDefFoundError) {
            logger.warn("Plan is loaded but its API couldn't be used, so its pages are off", e)
        }
    }

    override fun close() {
        try { hook?.unhook() } catch (e: NoClassDefFoundError) { /* Plan is gone */ }
        database?.close()
    }

    private fun debug(message: String) {
        if (config.debug) logger.info("[debug] {}", message)
    }
}
