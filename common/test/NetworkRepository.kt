import com.xalitoria.playtime.storage.DailyTable
import com.xalitoria.playtime.storage.NetworkRepository
import com.xalitoria.playtime.storage.PlayerTable
import com.xalitoria.playtime.storage.PlaytimeStorage
import com.xalitoria.playtime.storage.PlaytimeTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Files
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.toKotlinUuid

/** What the proxy adds up, against a real SQLite file with the servers' rows written by hand. */
class NetworkRepository {
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val minute = 60_000L
    private val day = NetworkRepository.MILLIS_PER_DAY

    // The middle of day 100, so "today" and "the last 7 days" have known edges.
    private val now = 100 * day + day / 2

    @BeforeTest
    fun database() {
        val file = Files.createTempFile("xaliplaytime-network", ".db").also { it.toFile().deleteOnExit() }
        val database = Database.connect("jdbc:sqlite:$file", "org.sqlite.JDBC")
        PlaytimeStorage.use(database, "xaliplaytime_")
        transaction(database) {
            SchemaUtils.create(PlayerTable, PlaytimeTable, DailyTable)
            for ([id, name] in listOf(alice to "Alice", bob to "Bob")) PlayerTable.insert {
                it[uuid] = id.toKotlinUuid()
                it[PlayerTable.name] = name
                it[nameLower] = name.lowercase()
                it[firstSeen] = 0
                it[lastSeen] = 0
            }
            // Alice: 10 on survival today, 20 on survival 3 days ago, 40 on skyblock today, 80 on skyblock 20 days ago. Bob: 5 on survival today.
            fun add(player: UUID, server: String, day: Long, minutes: Long) = DailyTable.insert {
                it[DailyTable.player] = player.toKotlinUuid()
                it[DailyTable.server] = server
                it[DailyTable.day] = day
                it[active] = minutes * minute
                it[connected] = minutes * minute * 2
            }
            add(alice, "survival", 100, 10)
            add(alice, "survival", 97, 20)
            add(alice, "skyblock", 100, 40)
            add(alice, "skyblock", 80, 80)
            add(bob, "survival", 100, 5)
            PlaytimeTable.insert { it[player] = alice.toKotlinUuid(); it[active] = 150 * minute }
            PlaytimeTable.insert { it[player] = bob.toKotlinUuid(); it[active] = 5 * minute }
        }
    }

    @Test
    fun `the network is every server added up, the day and the week included`() {
        assertEquals(155 * minute, NetworkRepository.totalActive())
        assertEquals(55 * minute, NetworkRepository.totalActive(days = 1, now = now))
        assertEquals(75 * minute, NetworkRepository.totalActive(days = 7, now = now))
    }

    @Test
    fun `a server is only its own rows`() {
        assertEquals(35 * minute, NetworkRepository.totalActive(server = "survival"))
        assertEquals(15 * minute, NetworkRepository.totalActive(days = 1, server = "survival", now = now))
        assertEquals(0, NetworkRepository.totalActive(server = "creative"))
    }

    @Test
    fun `each server shows its total, its recent time and its players, the busiest first`() {
        val servers = NetworkRepository.servers(days = 7, now = now)
        assertEquals(listOf("skyblock", "survival"), servers.map { it.server })
        assertEquals(listOf(120 * minute, 35 * minute), servers.map { it.activeMillis })
        assertEquals(listOf(40 * minute, 35 * minute), servers.map { it.recentMillis })
        assertEquals(listOf(1, 2), servers.map { it.players })
    }

    @Test
    fun `the most active players are the network's or one server's`() {
        assertEquals(listOf("Alice" to 150 * minute, "Bob" to 5 * minute), NetworkRepository.top(10).map { it.name to it.activeMillis })
        assertEquals(listOf("Alice" to 30 * minute, "Bob" to 5 * minute), NetworkRepository.top(10, "survival").map { it.name to it.activeMillis })
        assertEquals(1, NetworkRepository.top(1).size)
    }

    @Test
    fun `a player has their time on every server`() {
        val servers = NetworkRepository.playerServers(alice)
        assertEquals(listOf("skyblock" to 120 * minute, "survival" to 30 * minute), servers.map { it.server to it.activeMillis })
        assertEquals(listOf(240 * minute, 60 * minute), servers.map { it.connectedMillis })
        assertEquals(70 * minute, NetworkRepository.playerActive(alice, 7, now))
        assertEquals(emptyList(), NetworkRepository.playerServers(UUID.randomUUID()))
    }
}
