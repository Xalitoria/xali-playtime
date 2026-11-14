import com.djrapitops.plan.extension.extractor.ExtensionExtractor
import mc.xalitoria.playtime.velocity.ProxyConfig
import mc.xalitoria.playtime.velocity.ProxyPlanExtension
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The extension as Plan checks it, and the file the proxy reads. */
class ProxyPlanExtension {
    @Test
    fun planAcceptsTheExtension() {
        val extension = ProxyPlanExtension({ ProxyConfig.Plan() }) {}
        ExtensionExtractor(extension).validateAnnotations()
        assertEquals("XaliPlaytime", extension.pluginName)
    }

    @Test
    fun `times read the way Plan's tables are`() {
        assertEquals("0s", ProxyPlanExtension.words(0))
        assertEquals("59s", ProxyPlanExtension.words(59_999))
        assertEquals("1h 1m", ProxyPlanExtension.words((3600 + 60 + 59) * 1000L))
        assertEquals("2d 3h", ProxyPlanExtension.words(((2 * 24 + 3) * 3600 + 5 * 60) * 1000L))
    }

    @Test
    fun `a first start writes the defaults and a bad setting is named`() {
        val dir = Files.createTempDirectory("xaliplaytime-proxy")
        val file = dir.resolve("config.yml")
        assertEquals(7, ProxyConfig.load(file).plan.days)
        assertTrue(Files.readString(file).contains("table-prefix: \"xaliplaytime_\""))
        Files.writeString(file, "plan:\n  days: 400\n")
        assertTrue(assertFailsWith<IllegalArgumentException> { ProxyConfig.load(file) }.message!!.contains("plan.days"))
    }
}
