import com.xalitoria.util.message.TimeWords
import com.xalitoria.util.config.DatabaseType
import com.xalitoria.util.config.YamlFile
import com.xalitoria.util.config.databaseType
import mc.xalitoria.playtime.paper.data.config.MainConfig
import mc.xalitoria.playtime.paper.data.config.MessageConfig
import mc.xalitoria.playtime.paper.tracking.Rules
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigFile {
    private fun MainConfig.counting(counting: MainConfig.Counting) = copy(counting = counting)

    @Test
    fun `the defaults are valid and read as times`() {
        val config = MainConfig()
        config.validate()
        assertEquals(30, config.counting.afkThreshold().inWholeSeconds)
        assertEquals(60, config.counting.saveInterval().inWholeSeconds)
        assertEquals(DatabaseType.SQLITE, config.database.databaseType)
        assertEquals("xaliplaytime_", config.database.tablePrefix)
        assertEquals(true, config.importing.enable)
        assertEquals("server", config.serverName)
        val rules = Rules.of(config.counting)
        assertEquals(30_000, rules.afkThreshold)
        assertEquals(120_000, rules.window)
    }

    @Test
    fun `a wrong time names the setting`() {
        val error = assertFailsWith<IllegalArgumentException> { MainConfig().counting(MainConfig.Counting(afkThreshold = "half a minute")).validate() }
        assertTrue("counting.afk-threshold" in error.message!!, error.message)
        assertFailsWith<IllegalArgumentException> { MainConfig().counting(MainConfig.Counting(saveInterval = "0s")).validate() }
    }

    @Test
    fun `a check can't be rarer than the AFK threshold`() {
        val error = assertFailsWith<IllegalArgumentException> {
            MainConfig().counting(MainConfig.Counting(afkThreshold = "10s", checkInterval = "1m")).validate()
        }
        assertTrue("check-interval" in error.message!!, error.message)
    }

    @Test
    fun `the machine, leaderboard and Plan settings are bounded`() {
        val machines = MainConfig.Counting.Machines()
        assertFailsWith<IllegalArgumentException> { MainConfig().counting(MainConfig.Counting(machines = machines.copy(radius = 0))).validate() }
        assertFailsWith<IllegalArgumentException> { MainConfig().counting(MainConfig.Counting(machines = machines.copy(minVariety = 99))).validate() }
        assertFailsWith<IllegalArgumentException> { MainConfig(leaderboard = MainConfig.Leaderboard(size = 0)).validate() }
        assertFailsWith<IllegalArgumentException> { MainConfig(plan = MainConfig.Plan(days = 91)).validate() }
    }

    @Test
    fun `a file is written with the defaults and read back`() {
        val dir = Files.createTempDirectory("xaliplaytime-config")
        val file = YamlFile(dir.resolve("config.yml"), MainConfig.serializer(), ::MainConfig)
        file.load()
        val text = Files.readString(dir.resolve("config.yml"))
        assertTrue("table-prefix: \"xaliplaytime_\"" in text || "table-prefix: xaliplaytime_" in text, text)
        assertTrue("afk-threshold" in text, text)

        Files.writeString(dir.resolve("config.yml"), text.replace("save-interval: \"1m\"", "save-interval: \"30s\"").replace("save-interval: 1m", "save-interval: 30s"))
        val again = YamlFile(dir.resolve("config.yml"), MainConfig.serializer(), ::MainConfig)
        again.load()
        assertEquals(30, again.data.counting.saveInterval().inWholeSeconds)

        val messages = YamlFile(dir.resolve("en-us.yml"), MessageConfig.serializer(), ::MessageConfig)
        messages.load()
        assertEquals("1 hour 5 minutes", messages.data.words.duration(3900))
    }

    @Test
    fun `times are written in words`() {
        val words = TimeWords()
        assertEquals("0 seconds", words.duration(0))
        assertEquals("1 second", words.duration(1))
        assertEquals("2 hours", words.duration(7200))
        assertEquals("1 day 1 hour 1 minute 1 second", words.duration(90_061))
        assertEquals("0 seconds", words.duration(-5))
    }
}
