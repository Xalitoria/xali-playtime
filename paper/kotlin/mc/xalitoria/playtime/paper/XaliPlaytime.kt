package mc.xalitoria.playtime.paper

import com.xalitoria.util.bukkit.coroutineScope
import com.xalitoria.util.bukkit.register
import com.xalitoria.util.config.YamlFile
import com.xalitoria.util.message.*
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mc.xalitoria.playtime.paper.api.PlaytimeApiImpl
import mc.xalitoria.playtime.paper.command.PlaytimeCommand
import mc.xalitoria.playtime.paper.data.config.MainConfig
import mc.xalitoria.playtime.paper.data.config.MessageConfig
import mc.xalitoria.playtime.paper.data.constants.Permissions
import mc.xalitoria.playtime.paper.data.db.DatabaseManager
import mc.xalitoria.playtime.paper.external.PlaytimeExpansion
import mc.xalitoria.playtime.paper.external.plan.PlanHook
import mc.xalitoria.playtime.paper.listener.PlayerListener
import mc.xalitoria.playtime.paper.service.PlaytimeService
import mc.xalitoria.playtime.paper.tracking.ActivityListener
import net.xalitoria.playtime.XaliPlaytimeApi
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin

class XaliPlaytime : JavaPlugin() {
    lateinit var config: YamlFile<MainConfig>
        private set

    lateinit var messageConfig: YamlFile<MessageConfig>
        private set

    lateinit var scope: CoroutineScope
        private set

    lateinit var service: PlaytimeService
        private set

    private var expansion: PlaytimeExpansion? = null

    override fun onEnable() {
        scope = coroutineScope()

        try {
            config = loadConfig()
            messageConfig = loadMessages(config.data)
        } catch (e: Exception) {
            logger.fail("The configuration is wrong; disabling.", e)
            server.pluginManager.disablePlugin(this)
            return
        }
        applyDebug()

        val pm = server.pluginManager
        try {
            DatabaseManager.connect(this, config.data.database, config.data.serverName)
        } catch (e: Exception) {
            logger.fail("Failed to connect to the database; disabling.", e)
            pm.disablePlugin(this)
            return
        }
        debug("Connected to the {0} database", config.data.database.type)

        // Everyone may see their play time unless a permissions plugin says otherwise.
        pm.addPermission(Permission(Permissions.USE, PermissionDefault.TRUE))
        pm.addPermission(Permission(Permissions.OTHERS, PermissionDefault.OP))
        pm.addPermission(Permission(Permissions.ADMIN, PermissionDefault.OP))

        service = PlaytimeService(this)
        service.start()
        pm.registerEvents(PlayerListener(this), this)
        pm.registerEvents(ActivityListener(service.tracker), this)
        // Players who are online already (a plugin reload) are counted from now on.
        for (player in server.onlinePlayers) scope.launch { runCatching { service.arrive(player) } }

        server.servicesManager.register(XaliPlaytimeApi::class.java, PlaytimeApiImpl(this), this, ServicePriority.Normal)
        if (pm.isPluginEnabled("PlaceholderAPI")) expansion = PlaytimeExpansion(this).also { it.register() }
        hookPlan()

        val command = PlaytimeCommand(this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(this, command.builder().build(), command.description, command.aliases)
        }
        debug("Enabled")
    }

    override fun onDisable() {
        server.servicesManager.unregisterAll(this)
        expansion?.unregister()
        if (server.pluginManager.getPlugin("Plan") != null) try { PlanHook.unhook() } catch (e: NoClassDefFoundError) { }
        if (::service.isInitialized) {
            // Plugin threads are about to stop, so what the players earned is written now, with a bound on the wait.
            val sessions = service.tracker.stopAll()
            runBlocking {
                withTimeoutOrNull(SHUTDOWN_SAVE_MILLIS) { sessions.forEach { service.save(it, final = true) } }
                    ?: logger.warning("Saving the play time of ${sessions.size} players took too long; the last minutes were not saved")
            }
        }
        if (::scope.isInitialized) scope.cancel("XaliPlaytime is disabling")
        DatabaseManager.close()
    }

    /**
     * Reads the configuration and the language file again, saves what the players online have earned, and connects to the database
     * again with the settings as they are now. If a file is wrong, or the new database can't be reached, this throws saying what is
     * wrong and everything stays as it was. Players already counted keep the rules they started with, and the Plan section and
     * the table prefix are read only at the start.
     */
    suspend fun reload() {
        val newConfig = loadConfig()
        val newMessages = loadMessages(newConfig.data)
        service.whileSaved {
            withContext(Dispatchers.IO) { DatabaseManager.reconnect(this@XaliPlaytime, newConfig.data.database, newConfig.data.serverName) }
        }
        config = newConfig
        messageConfig = newMessages
        applyDebug()
        service.configure()
        service.refreshBoards()
        debug("Reloaded")
    }

    /** Plan can't be ordered against this plugin, so it is hooked whenever it enables. */
    private fun hookPlan() {
        fun hook() = try { PlanHook.hook(this) } catch (e: NoClassDefFoundError) { debug("Plan's API is not available: {0}", e) }
        val plan = server.pluginManager.getPlugin("Plan")
        if (plan != null && plan.isEnabled) {
            hook()
            return
        }
        server.pluginManager.registerEvent(
            PluginEnableEvent::class.java, object : Listener {}, EventPriority.MONITOR,
            { _, event -> if ((event as PluginEnableEvent).plugin.name == "Plan") hook() }, this,
        )
    }

    private fun loadConfig(): YamlFile<MainConfig> {
        val file = YamlFile(dataPath.resolve("config.yml"), MainConfig.serializer(), ::MainConfig)
        file.load()
        file.data.validate()
        // Written again so a setting added by an update shows up in the file.
        file.save()
        return file
    }

    private fun loadMessages(config: MainConfig): YamlFile<MessageConfig> {
        val file = YamlFile(dataPath.resolve("language", config.language + ".yml"), MessageConfig.serializer(), ::MessageConfig)
        file.load()
        file.save()
        return file
    }

    /** Starts from `debug` in the configuration. */
    private fun applyDebug() {
        debugOn = config.data.debug
        updateDebugger()
    }

    /** Turns the debug log on or off until the next reload, and returns whether it is on. */
    fun toggleDebug(): Boolean {
        debugOn = !debugOn
        updateDebugger()
        return debugOn
    }

    private fun updateDebugger() {
        debugger =
            if (debugOn) { message: String, args: Array<out Any?> -> logger.info(message.formatArgs(*args)) }
            else { _: String, _: Array<out Any?> -> }
    }

    private var debugOn = false

    companion object {
        private const val SHUTDOWN_SAVE_MILLIS = 5_000L
        private var debugger: (String, Array<out Any?>) -> Unit = { _, _ -> }
        fun debug(message: String, vararg args: Any?) = debugger(message, args)
    }
}
