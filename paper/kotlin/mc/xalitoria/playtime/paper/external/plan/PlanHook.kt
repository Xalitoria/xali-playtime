package mc.xalitoria.playtime.paper.external.plan

import com.djrapitops.plan.capability.CapabilityService
import com.djrapitops.plan.extension.ExtensionService
import com.xalitoria.util.message.fail
import mc.xalitoria.playtime.paper.XaliPlaytime
import mc.xalitoria.playtime.paper.XaliPlaytime.Companion.debug

/**
 * Registers [PlanExtension] with Plan when it is installed and `plan.enable` is on. Everything that names a Plan class is in
 * this package, and this object is only used after the plugin manager has said Plan is there, so a server without Plan never
 * loads them.
 */
object PlanHook {
    private var extension: PlanExtension? = null

    /** Hooks in if Plan is present and wanted; returns whether it did. Plan may enable later and reload, which registers again. */
    fun hook(plugin: XaliPlaytime): Boolean {
        if (!plugin.config.data.plan.enable || plugin.server.pluginManager.getPlugin("Plan") == null) return false
        try {
            val capabilities = CapabilityService.getInstance()
            if (!capabilities.hasCapability("DATA_EXTENSION_VALUES") || !capabilities.hasCapability("DATA_EXTENSION_TABLES")) {
                plugin.logger.warning("This version of Plan can't take extension values and tables; not adding the play time to its pages.")
                return false
            }
            val extension = PlanExtension(plugin).also { this.extension = it }
            capabilities.registerEnableListener { enabled -> if (enabled) register(plugin, extension) }
            register(plugin, extension)
            return true
        } catch (e: NoClassDefFoundError) {
            debug("Plan's API is not available: {0}", e)
        } catch (e: Exception) {
            plugin.logger.fail("Could not hook into Plan.", e)
        }
        return false
    }

    /** Takes the extension out of Plan again; for disabling. */
    fun unhook() {
        val extension = extension ?: return
        this.extension = null
        try {
            ExtensionService.getInstance().unregister(extension)
        } catch (e: Throwable) {
            // Plan went first, or was never there: nothing left to take it out of.
        }
    }

    private fun register(plugin: XaliPlaytime, extension: PlanExtension) {
        try {
            val caller = ExtensionService.getInstance().register(extension)
            if (caller.isPresent) plugin.logger.info("Added the play time to Plan.")
            else debug("Plan has XaliPlaytime's extension switched off in its own config")
        } catch (e: IllegalStateException) {
            debug("Plan is not enabled yet; waiting for it: {0}", e)
        } catch (e: IllegalArgumentException) {
            plugin.logger.fail("Plan rejected XaliPlaytime.", e)
        }
    }
}
