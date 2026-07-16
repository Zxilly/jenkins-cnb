package dev.zxilly.jenkins.cnb.webhook

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class CnbReplayCacheTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `distinguishes an in-flight delivery from a completed duplicate`() {
        val cache = CnbReplayCache(10)
        val claim = claimed(cache.claim(SCOPE, "delivery", NOW, Duration.ofMinutes(1)))

        assertEquals(
            CnbReplayClaimResult.InFlight,
            cache.claim(SCOPE, "delivery", NOW.plusSeconds(1), Duration.ofMinutes(1)),
        )

        cache.complete(claim, NOW.plusSeconds(2), Duration.ofMinutes(15))

        assertEquals(
            CnbReplayClaimResult.Completed,
            cache.claim(SCOPE, "delivery", NOW.plusSeconds(3), Duration.ofMinutes(1)),
        )
    }

    @Test
    fun `a live local owner remains fenced after its durable lease expires`() {
        val cache = CnbReplayCache(10)
        val first = claimed(cache.claim(SCOPE, "delivery", NOW, Duration.ofSeconds(30)))

        assertEquals(
            CnbReplayClaimResult.InFlight,
            cache.claim(SCOPE, "delivery", NOW.plusSeconds(31), Duration.ofSeconds(30)),
        )

        cache.complete(first, NOW.plusSeconds(32), Duration.ofMinutes(15))
        assertEquals(CnbReplayClaimResult.Completed, cache.claim(SCOPE, "delivery", NOW.plusSeconds(33)))
    }

    @Test
    fun `an abandoned lease is recoverable and stale owners are fenced`() {
        val cache = CnbReplayCache(10)
        val first = claimed(cache.claim(SCOPE, "delivery", NOW, Duration.ofSeconds(30)))
        cache.abandon(first)
        val recovered = claimed(cache.claim(SCOPE, "delivery", NOW.plusSeconds(31), Duration.ofSeconds(30)))

        cache.release(first)

        assertEquals(
            CnbReplayClaimResult.InFlight,
            cache.claim(SCOPE, "delivery", NOW.plusSeconds(32), Duration.ofSeconds(30)),
        )

        assertThrows(CnbReplayOwnershipException::class.java) {
            cache.complete(first, NOW.plusSeconds(32), Duration.ofMinutes(15))
        }
        cache.complete(recovered, NOW.plusSeconds(32), Duration.ofMinutes(15))
        assertEquals(CnbReplayClaimResult.Completed, cache.claim(SCOPE, "delivery", NOW.plusSeconds(33)))
    }

    @Test
    fun `repository quotas are independent`() {
        val cache = CnbReplayCache(capacity = 2, perScopeCapacity = 1)
        claimed(cache.claim("server:team/one", "one", NOW))

        assertThrows(CnbReplayCapacityException::class.java) {
            cache.claim("server:team/one", "two", NOW)
        }
        assertTrue(cache.claim("server:team/two", "one", NOW) is CnbReplayClaimResult.Claimed)
        assertThrows(CnbReplayCapacityException::class.java) {
            cache.claim("server:team/three", "one", NOW)
        }
        assertEquals(2, cache.size())
    }

    @Test
    fun `restart preserves in-flight and completed states and leases still recover`() {
        val path = temporaryDirectory.resolve("replays.journal")
        val cache = CnbReplayCache(path = path, clock = fixedClock())
        claimed(cache.claim(SCOPE, "in-flight", NOW, Duration.ofSeconds(30)))
        val completed = claimed(cache.claim(SCOPE, "completed", NOW, Duration.ofSeconds(30)))
        cache.complete(completed, NOW, Duration.ofMinutes(15))

        val restarted = CnbReplayCache(path = path, clock = fixedClock())

        assertEquals(CnbReplayClaimResult.InFlight, restarted.claim(SCOPE, "in-flight", NOW.plusSeconds(1)))
        assertEquals(CnbReplayClaimResult.Completed, restarted.claim(SCOPE, "completed", NOW.plusSeconds(1)))
        assertTrue(
            restarted.claim(SCOPE, "in-flight", NOW.plusSeconds(31), Duration.ofSeconds(30)) is
                CnbReplayClaimResult.Claimed,
        )
    }

    @Test
    fun `journal stores only hashes for repository scope and delivery key`() {
        val path = temporaryDirectory.resolve("replays.journal")
        val scope = "server:private/repository"
        val key = "sensitive-delivery-id"
        val cache = CnbReplayCache(path = path, clock = fixedClock())
        val claim = claimed(cache.claim(scope, key, NOW))
        cache.complete(claim, NOW, Duration.ofMinutes(15))

        val journal = Files.readString(path)

        assertFalse(journal.contains(scope))
        assertFalse(journal.contains("private/repository"))
        assertFalse(journal.contains(key))
        assertTrue(journal.lineSequence().filter(String::isNotBlank).all { it.split('\t').size == 7 })
    }

    @Test
    fun `periodic compaction bounds an append-only journal`() {
        val path = temporaryDirectory.resolve("replays.journal")
        val cache = CnbReplayCache(path = path, compactionThreshold = 2, clock = fixedClock())

        repeat(20) { index ->
            val claim = claimed(cache.claim(SCOPE, "delivery-$index", NOW.plusSeconds(index.toLong())))
            cache.release(claim)
        }

        val lines = Files.readAllLines(path).filter(String::isNotBlank)
        assertTrue(lines.size <= 2, "expected a compact journal, got ${lines.size} records")
        assertEquals(0, CnbReplayCache(path = path, clock = fixedClock()).size())
    }

    @Test
    fun `loader ignores a damaged journal tail`() {
        val path = temporaryDirectory.resolve("replays.journal")
        val cache = CnbReplayCache(path = path, clock = fixedClock())
        val claim = claimed(cache.claim(SCOPE, "completed", NOW))
        cache.complete(claim, NOW, Duration.ofMinutes(15))
        Files.writeString(
            path,
            "v2\tC\ttruncated-tail",
            StandardCharsets.US_ASCII,
            StandardOpenOption.APPEND,
        )

        val restarted = CnbReplayCache(path = path, clock = fixedClock())

        assertEquals(CnbReplayClaimResult.Completed, restarted.claim(SCOPE, "completed", NOW.plusSeconds(1)))
    }

    @Test
    fun `loads legacy properties and migrates them during compaction`() {
        val path = temporaryDirectory.resolve("webhook-replay.properties")
        val legacyHash = sha256("$SCOPE:legacy-delivery")
        Files.writeString(path, "$legacyHash=${NOW.plusSeconds(900).toEpochMilli()}\n")
        val cache = CnbReplayCache(path = path, compactionThreshold = 1, clock = fixedClock())

        assertEquals(CnbReplayClaimResult.Completed, cache.claim(SCOPE, "legacy-delivery", NOW.plusSeconds(1)))
        claimed(cache.claim(SCOPE, "new-delivery", NOW.plusSeconds(1)))

        val migrated = Files.readString(path)
        assertTrue(migrated.startsWith("v2\t"))
        assertFalse(migrated.contains("legacy-delivery"))
        assertEquals(
            CnbReplayClaimResult.Completed,
            CnbReplayCache(path = path, clock = fixedClock()).claim(SCOPE, "legacy-delivery", NOW.plusSeconds(2)),
        )
    }

    @Test
    fun `legacy entries count toward the global hard limit`() {
        val path = temporaryDirectory.resolve("legacy-capacity.properties")
        val firstHash = sha256("$SCOPE:legacy-one")
        val secondHash = sha256("$SCOPE:legacy-two")
        val expiry = NOW.plusSeconds(900).toEpochMilli()
        Files.writeString(path, "$firstHash=$expiry\n$secondHash=$expiry\n")
        val cache = CnbReplayCache(capacity = 2, path = path, perScopeCapacity = 1, clock = fixedClock())

        assertEquals(CnbReplayClaimResult.Completed, cache.claim(SCOPE, "legacy-one", NOW))
        assertThrows(CnbReplayCapacityException::class.java) {
            cache.claim("server:another/repository", "new-delivery", NOW)
        }
        assertEquals(2, cache.size())
    }

    @Test
    fun `expired journal history cannot hide a later live completion on restart`() {
        val path = temporaryDirectory.resolve("expired-prefix.journal")
        val cache =
            CnbReplayCache(
                capacity = 2,
                path = path,
                perScopeCapacity = 1,
                clock = fixedClock(),
            )
        val expired = claimed(cache.claim(SCOPE, "expired", NOW))
        cache.complete(expired, NOW, Duration.ofSeconds(10))
        val live = claimed(cache.claim(SCOPE, "live", NOW.plusSeconds(11)))
        cache.complete(live, NOW.plusSeconds(11), Duration.ofMinutes(15))

        val restarted =
            CnbReplayCache(
                capacity = 2,
                path = path,
                perScopeCapacity = 1,
                clock = fixedClock(NOW.plusSeconds(12)),
            )

        assertEquals(CnbReplayClaimResult.Completed, restarted.claim(SCOPE, "live", NOW.plusSeconds(12)))
        assertEquals(1, restarted.size())
    }

    @Test
    fun `journal capacity failure rolls back the proposed claim in memory and on restart`() {
        val path = temporaryDirectory.resolve("small-replays.journal")
        val cache = CnbReplayCache(path = path, capacity = 100, maxJournalBytes = 1_024, clock = fixedClock())
        var accepted = 0

        while (true) {
            try {
                claimed(cache.claim(SCOPE, "delivery-$accepted", NOW))
                accepted++
            } catch (_: CnbReplayCapacityException) {
                break
            }
        }

        assertTrue(accepted > 0)
        assertEquals(accepted, cache.size())
        assertEquals(
            accepted,
            CnbReplayCache(path = path, capacity = 100, maxJournalBytes = 1_024, clock = fixedClock()).size(),
        )
    }

    @Test
    fun `failed durable append never exposes the claim in memory`() {
        val nonDirectory = temporaryDirectory.resolve("not-a-directory")
        Files.writeString(nonDirectory, "file")
        val cache = CnbReplayCache(path = nonDirectory.resolve("replays.journal"), clock = fixedClock())

        assertThrows(Exception::class.java) {
            cache.claim(SCOPE, "delivery", NOW)
        }

        assertEquals(0, cache.size())
    }

    @Test
    fun `journal symbolic link is never read or followed for append`() {
        val outside = temporaryDirectory.resolve("outside.txt")
        Files.writeString(outside, "must-stay-unchanged", StandardCharsets.UTF_8)
        val journal = temporaryDirectory.resolve("replays.journal")
        assumeTrue(runCatching { Files.createSymbolicLink(journal, outside) }.isSuccess, "symbolic links unavailable")

        val cache = CnbReplayCache(path = journal, clock = fixedClock())

        assertEquals(0, cache.size())
        assertThrows(Exception::class.java) { cache.claim(SCOPE, "delivery", NOW) }
        assertEquals("must-stay-unchanged", Files.readString(outside, StandardCharsets.UTF_8))
    }

    private fun claimed(result: CnbReplayClaimResult): CnbReplayClaimToken {
        assertTrue(result is CnbReplayClaimResult.Claimed, "expected a new claim, got $result")
        return (result as CnbReplayClaimResult.Claimed).token
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun fixedClock(at: Instant = NOW): Clock = Clock.fixed(at, ZoneOffset.UTC)

    companion object {
        private const val SCOPE = "server:team/repository"
        private val NOW = Instant.parse("2026-07-15T10:00:00Z")
    }
}
