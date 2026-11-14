import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.xalitoria.playtime.storage.NetworkRepository
import kotlinx.coroutines.runBlocking
import mc.xalitoria.playtime.paper.data.config.MainConfig
import mc.xalitoria.playtime.paper.data.db.DatabaseManager
import mc.xalitoria.playtime.paper.data.db.repository.PlaytimeRepository
import net.xalitoria.playtime.PlaytimePeriod
import java.nio.file.Files
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** What is kept, against a real SQLite file. The tables read the prefix once, so one database serves a class. */
class PlaytimeStore {
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()
    private val minute = 60_000L
    private val day = PlaytimeRepository.MILLIS_PER_DAY

    // A fixed "now" in the middle of day 100, so the periods have known edges.
    private val now = 100 * day + day / 2

    @BeforeTest
    fun database() {
        val file = Files.createTempFile("xaliplaytime-test", ".db").also { it.toFile().deleteOnExit() }
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$file"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 1
            connectionInitSql = "PRAGMA foreign_keys = ON"
        }
        DatabaseManager.attach(HikariDataSource(config), MainConfig().database)
    }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    @Test
    fun `a player who joins has a row and nothing counted, and joining again counts the join`() = run {
        val first = PlaytimeRepository.joined(alice, "Alice", now)
        assertEquals(0, first.active)
        assertEquals(0, first.connected)
        PlaytimeRepository.add(alice, 5 * minute, 7 * minute, 100)
        val second = PlaytimeRepository.joined(alice, "Alice", now + 1)
        assertEquals(5 * minute, second.active)
        assertEquals(7 * minute, second.connected)
        assertEquals(2, PlaytimeRepository.stats(alice, now)?.joins)
        assertEquals(now, PlaytimeRepository.stats(alice, now)?.firstSeen)
        assertEquals(now + 1, PlaytimeRepository.stats(alice, now)?.lastSeen)
    }

    @Test
    fun `each server's time is kept apart and the totals are all of it`() = run {
        PlaytimeRepository.joined(alice, "Alice", now)
        PlaytimeRepository.add(alice, 3 * minute, 3 * minute, 100, "survival")
        PlaytimeRepository.add(alice, 4 * minute, 5 * minute, 100, "skyblock")
        val totals = PlaytimeRepository.add(alice, 1 * minute, 1 * minute, 100, "survival")
        assertEquals(8 * minute, totals.active)
        val servers = NetworkRepository.servers(1, now).associateBy { it.server }
        assertEquals(4 * minute, servers.getValue("survival").activeMillis)
        assertEquals(4 * minute, servers.getValue("skyblock").activeMillis)
        assertEquals(8 * minute, NetworkRepository.totalActive(days = 1, now = now))
        assertEquals(PlaytimeRepository.stats(alice, now)?.activeTodayMillis, 8 * minute)
    }

    @Test
    fun `time from before the plugin is brought in once per server, on the day the player first played`() = run {
        PlaytimeRepository.joined(alice, "Alice", now)
        val totals = PlaytimeRepository.importHistory(alice, 90 * minute, 40, "survival")
        assertEquals(90 * minute, totals?.active)
        assertEquals(90 * minute, totals?.connected)
        // Not again, even after more was added; and nothing to bring in is no row at all.
        assertNull(PlaytimeRepository.importHistory(alice, 90 * minute, 40, "survival"))
        assertNull(PlaytimeRepository.importHistory(bob, 0, 40, "survival"))
        // Another server of the same player has its own.
        assertEquals(120 * minute, PlaytimeRepository.importHistory(alice, 30 * minute, 40, "skyblock")?.active)
        assertEquals(0, PlaytimeRepository.stats(alice, now)?.activeMonthMillis)
        assertEquals(90 * minute, PlaytimeRepository.serverActive(alice, "survival"))
    }

    @Test
    fun `an admin adds, takes away and sets the time of a server, no more than it has`() = run {
        PlaytimeRepository.joined(alice, "Alice", now)
        PlaytimeRepository.add(alice, 10 * minute, 12 * minute, 99, "survival")
        PlaytimeRepository.add(alice, 20 * minute, 25 * minute, 100, "survival")
        PlaytimeRepository.add(alice, 50 * minute, 50 * minute, 100, "skyblock")

        assertEquals(PlaytimeRepository.Adjusted(5 * minute, PlaytimeRepository.Totals(85 * minute, 92 * minute)), PlaytimeRepository.adjust(alice, 5 * minute, 100, "survival"))
        // Newest day first: day 100 has 25, so day 99 is untouched by taking 20.
        val taken = PlaytimeRepository.adjust(alice, -20 * minute, 100, "survival")
        assertEquals(-20 * minute, taken.applied)
        assertEquals(65 * minute, taken.totals.active)
        assertEquals(10 * minute, PlaytimeRepository.days(alice, 5, now).single { it.day == 99L }.activeMillis)
        // Only 15 minutes are left on survival; the 50 of skyblock are not touched.
        val less = PlaytimeRepository.adjust(alice, -60 * minute, 100, "survival")
        assertEquals(-15 * minute, less.applied)
        assertEquals(50 * minute, less.totals.active)
        assertEquals(0, PlaytimeRepository.serverActive(alice, "survival"))

        assertEquals(120 * minute, PlaytimeRepository.setServerActive(alice, 70 * minute, 100, "survival").totals.active)
        assertEquals(60 * minute, PlaytimeRepository.setServerActive(alice, 10 * minute, 100, "survival").totals.active)
        assertEquals(10 * minute, PlaytimeRepository.serverActive(alice, "survival"))
    }

    @Test
    fun `a reset takes the time on every server and keeps the player from being imported again`() = run {
        PlaytimeRepository.joined(alice, "Alice", now)
        PlaytimeRepository.add(alice, 10 * minute, 10 * minute, 100, "survival")
        PlaytimeRepository.add(alice, 10 * minute, 10 * minute, 100, "skyblock")
        val totals = PlaytimeRepository.reset(alice, 100, "survival")
        assertEquals(PlaytimeRepository.Totals(0, 0), totals)
        assertEquals(0, PlaytimeRepository.stats(alice, now)?.activeMonthMillis)
        assertEquals(0, NetworkRepository.totalActive())
        assertNull(PlaytimeRepository.importHistory(alice, 99 * minute, 40, "survival"))
        assertEquals(1, PlaytimeRepository.stats(alice, now)?.joins)
    }

    @Test
    fun `a purge deletes the player, and the inactive ones are counted before they are deleted`() = run {
        PlaytimeRepository.joined(alice, "Alice", now)
        PlaytimeRepository.joined(bob, "Bob", now + 10 * day)
        PlaytimeRepository.add(alice, minute, minute, 100)
        PlaytimeRepository.add(bob, minute, minute, 100)
        PlaytimeRepository.joined(carol, "Carol", now + 11 * day)
        val before = now + 5 * day
        assertEquals(1, PlaytimeRepository.purgeInactive(before, delete = false, keep = emptySet()))
        assertNotNull(PlaytimeRepository.stats(alice, now))
        assertEquals(0, PlaytimeRepository.purgeInactive(before, delete = true, keep = setOf(alice)))
        assertEquals(1, PlaytimeRepository.purgeInactive(before, delete = true, keep = emptySet()))
        assertNull(PlaytimeRepository.stats(alice, now))
        assertNotNull(PlaytimeRepository.stats(bob, now))
        assertEquals(true, PlaytimeRepository.purge(bob))
        assertEquals(false, PlaytimeRepository.purge(bob))
        assertEquals(0, NetworkRepository.totalActive())
    }

    @Test
    fun `time is added, never set, to the total and to the day`() = run {
        PlaytimeRepository.joined(alice, "Alice", now)
        assertEquals(3 * minute, PlaytimeRepository.add(alice, 3 * minute, 4 * minute, 100).active)
        val totals = PlaytimeRepository.add(alice, 2 * minute, 2 * minute, 100)
        assertEquals(5 * minute, totals.active)
        assertEquals(6 * minute, totals.connected)
        val days = PlaytimeRepository.days(alice, 7, now)
        assertEquals(1, days.size, "both went to one row")
        assertEquals(5 * minute, days.single().activeMillis)
    }

    @Test
    fun `a player the database has not seen has no stats`() = run {
        assertNull(PlaytimeRepository.stats(alice, now))
    }

    @Test
    fun `the day, week and month hold the days they name`() = run {
        PlaytimeRepository.joined(alice, "Alice", now)
        PlaytimeRepository.add(alice, 1 * minute, 1 * minute, 100)  // today
        PlaytimeRepository.add(alice, 10 * minute, 10 * minute, 94) // 6 days ago: the oldest of the week
        PlaytimeRepository.add(alice, 100 * minute, 100 * minute, 93) // 7 days ago: month, not week
        PlaytimeRepository.add(alice, 1000 * minute, 1000 * minute, 71) // 29 days ago: the oldest of the month
        PlaytimeRepository.add(alice, 10_000 * minute, 10_000 * minute, 70) // 30 days ago: out
        val stats = assertNotNull(PlaytimeRepository.stats(alice, now))
        assertEquals(1 * minute, stats.activeTodayMillis)
        assertEquals(11 * minute, stats.activeWeekMillis)
        assertEquals(1111 * minute, stats.activeMonthMillis)
        assertEquals(11_111 * minute, stats.activeMillis)
        assertEquals(1111 * minute, PlaytimeRepository.totalActive(days = 30, now = now))
        assertEquals(11_111 * minute, PlaytimeRepository.totalActive())
    }

    @Test
    fun `leaderboards are ordered, share places for equal times and leave out those who did not play`() = run {
        for ([id, name] in listOf(alice to "Alice", bob to "Bob", carol to "Carol")) PlaytimeRepository.joined(id, name, now)
        PlaytimeRepository.add(alice, 30 * minute, 30 * minute, 100)
        PlaytimeRepository.add(bob, 30 * minute, 30 * minute, 100)
        PlaytimeRepository.add(carol, 100 * minute, 100 * minute, 90)

        val all = PlaytimeRepository.top(PlaytimePeriod.ALL, 10, now)
        assertEquals(listOf("Carol", "Alice", "Bob").sorted(), all.map { it.name }.sorted())
        assertEquals("Carol", all.first().name)
        assertEquals(listOf(1, 2, 2), all.map { it.position })

        val today = PlaytimeRepository.top(PlaytimePeriod.DAY, 10, now)
        assertEquals(setOf("Alice", "Bob"), today.map { it.name }.toSet(), "Carol played 10 days ago")
        assertEquals(listOf(1, 1), today.map { it.position })

        assertEquals(1, PlaytimeRepository.top(PlaytimePeriod.ALL, 1, now).size)
        assertEquals(setOf("Alice", "Bob", "Carol"), PlaytimeRepository.top(PlaytimePeriod.MONTH, 10, now).map { it.name }.toSet())
        assertEquals(emptyList(), PlaytimeRepository.top(PlaytimePeriod.WEEK, 10, now).filter { it.name == "Carol" })
    }

    @Test
    fun `players are found by name in any case, the latest holder of a name first`() = run {
        PlaytimeRepository.joined(alice, "Alice", now)
        assertEquals(alice, PlaytimeRepository.byName("aLiCe")?.uuid)
        assertNull(PlaytimeRepository.byName("Nobody"))
        PlaytimeRepository.joined(bob, "alice", now + 5)
        assertEquals(bob, PlaytimeRepository.byName("ALICE")?.uuid)
    }

    @Test
    fun `the days of a player are the latest ones, newest first`() = run {
        PlaytimeRepository.joined(alice, "Alice", now)
        for (d in 90L..100L) PlaytimeRepository.add(alice, minute, minute, d)
        val days = PlaytimeRepository.days(alice, 3, now)
        assertEquals(listOf(100L, 99L, 98L), days.map { it.day })
    }

    @Test
    fun `places are shared by equal times`() {
        val ranked = PlaytimeRepository.rank(listOf(Triple(alice, "A", 9L), Triple(bob, "B", 5L), Triple(carol, "C", 5L), Triple(UUID.randomUUID(), "D", 1L)))
        assertEquals(listOf(1, 2, 2, 4), ranked.map { it.position })
    }

    @Test
    fun `days are counted from the epoch in UTC`() {
        assertEquals(0, PlaytimeRepository.dayOf(0))
        assertEquals(0, PlaytimeRepository.dayOf(day - 1))
        assertEquals(1, PlaytimeRepository.dayOf(day))
        assertEquals(-1, PlaytimeRepository.dayOf(-1))
    }
}
