import com.djrapitops.plan.extension.extractor.ExtensionExtractor
import mc.xalitoria.playtime.paper.XaliPlaytime
import mc.xalitoria.playtime.paper.external.plan.PlanExtension
import kotlin.test.Test
import kotlin.test.assertEquals

/** Plan's own check of an extension: annotations, parameters, return types and conditions. */
class PlanExtension {
    @Test
    fun planAcceptsTheExtension() {
        // The check only reads the class, so a plugin that is never used will do.
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val plugin = (unsafe as sun.misc.Unsafe).allocateInstance(XaliPlaytime::class.java)
        val instance = PlanExtension(plugin as XaliPlaytime)
        ExtensionExtractor(instance).validateAnnotations()
        assertEquals("XaliPlaytime", instance.pluginName)
    }
}
