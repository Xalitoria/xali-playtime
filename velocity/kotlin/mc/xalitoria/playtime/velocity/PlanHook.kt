package mc.xalitoria.playtime.velocity

import com.djrapitops.plan.capability.CapabilityService
import com.djrapitops.plan.extension.ExtensionService
import org.slf4j.Logger

/**
 * Puts the play time of the network on the Plan whose web server runs on this proxy. Everything that names a Plan class is
 * here and in [ProxyPlanExtension], and this is only used after the plugin manager has said Plan is loaded.
 */
class PlanHook(
    private val logger: Logger,
    config: () -> ProxyConfig.Plan,
    private val debug: (String) -> Unit,
) {
    private val extension = ProxyPlanExtension(config, debug)

    /** Registers the extension now if Plan is up, and again whenever Plan enables (it may reload). Returns false if this Plan can't take it. */
    fun hook(): Boolean {
        val capabilities = CapabilityService.getInstance()
        if (!capabilities.hasCapability("DATA_EXTENSION_VALUES") || !capabilities.hasCapability("DATA_EXTENSION_TABLES")) {
            logger.warn("This version of Plan can't take extension values and tables; not adding the network's play time to its pages.")
            return false
        }
        capabilities.registerEnableListener { enabled -> if (enabled) register() }
        register()
        return true
    }

    /** Takes the extension out of Plan again; for closing. */
    fun unhook() {
        try {
            ExtensionService.getInstance().unregister(extension)
        } catch (e: Throwable) {
            // Plan went first, or was never there: nothing left to take it out of.
        }
    }

    private fun register() {
        try {
            val caller = ExtensionService.getInstance().register(extension)
            if (caller.isPresent) logger.info("Added the network's play time to Plan.")
            else debug("Plan has XaliPlaytime's extension switched off in its own config")
        } catch (e: IllegalStateException) {
            debug("Plan is not enabled yet; waiting for it: $e")
        } catch (e: IllegalArgumentException) {
            logger.warn("Plan rejected XaliPlaytime's extension", e)
        }
    }
}
