package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEvent
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEventType
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Properties

class CnbRepositoryEventDedupStoreTest {
    @TempDir
    lateinit var directory: Path

    private val now = Instant.parse("2026-07-15T10:00:00Z")

    @Test
    fun `persists only hashed event identities and expires old entries`() {
        val path = directory.resolve("events.properties")
        val event = CnbRepositoryEvent("private-event-id", CnbRepositoryEventType("push"), "team/project", now.toString())
        val key = CnbRepositoryEventPollingWork.eventKey("cnb-cool", "team/project", event)

        store(path, ttl = Duration.ofHours(1)).mark("cnb-cool", "team/project", listOf(key), now)

        val reloaded = store(path, ttl = Duration.ofHours(1))
        assertTrue(reloaded.contains("cnb-cool", "team/project", key, now.plusSeconds(30)))
        assertFalse(reloaded.contains("cnb-cool", "team/project", key, now.plusSeconds(3601)))
        assertEquals(64, key.length)
        assertFalse(path.toFile().readText().contains("private-event-id"))
        assertFalse(path.toFile().readText().contains("team/project"))
    }

    @Test
    fun `high cardinality batches append without rewriting earlier records`() {
        val path = directory.resolve("append.journal")
        val store = store(path, capacity = 1_000, perRepositoryCapacity = 1_000, compactionThreshold = 10_000)
        val firstKeys = (0 until 250).map(::key)
        val secondKeys = (250 until 500).map(::key)

        store.mark("primary", "team/project", firstKeys, now)
        val firstBytes = Files.readAllBytes(path)
        store.mark("primary", "team/project", secondKeys, now.plusSeconds(1))
        val secondBytes = Files.readAllBytes(path)

        assertArrayEquals(firstBytes, secondBytes.copyOfRange(0, firstBytes.size))
        assertEquals(500, Files.readAllLines(path).size)
        assertEquals(0, store.journalCompactionCount())
    }

    @Test
    fun `repository quota evicts only that repository oldest events`() {
        val path = directory.resolve("quota.journal")
        val store = store(path, capacity = 10, perRepositoryCapacity = 2)

        store.mark("primary", "team/noisy", listOf(key(0), key(1), key(2)), now)
        store.mark("primary", "team/quiet", listOf(key(3), key(4)), now.plusSeconds(1))

        assertFalse(store.contains("primary", "team/noisy", key(0), now.plusSeconds(2)))
        assertTrue(store.contains("primary", "team/noisy", key(1), now.plusSeconds(2)))
        assertTrue(store.contains("primary", "team/noisy", key(2), now.plusSeconds(2)))
        assertTrue(store.contains("primary", "team/quiet", key(3), now.plusSeconds(2)))
        assertTrue(store.contains("primary", "team/quiet", key(4), now.plusSeconds(2)))
        assertEquals(2, store.sizeByRepository("primary", "team/noisy"))
    }

    @Test
    fun `global pressure evicts oldest entries from the largest scope fairly`() {
        val path = directory.resolve("fair.journal")
        val store = store(path, capacity = 4, perRepositoryCapacity = 4)

        store.mark("primary", "team/a", listOf(key(0), key(1)), now)
        store.mark("primary", "team/b", listOf(key(2), key(3)), now.plusSeconds(1))
        store.mark("primary", "team/c", listOf(key(4)), now.plusSeconds(2))
        store.mark("primary", "team/c", listOf(key(5)), now.plusSeconds(3))

        assertFalse(store.contains("primary", "team/a", key(0), now.plusSeconds(4)))
        assertTrue(store.contains("primary", "team/a", key(1), now.plusSeconds(4)))
        assertFalse(store.contains("primary", "team/b", key(2), now.plusSeconds(4)))
        assertTrue(store.contains("primary", "team/b", key(3), now.plusSeconds(4)))
        assertTrue(store.contains("primary", "team/c", key(4), now.plusSeconds(4)))
        assertTrue(store.contains("primary", "team/c", key(5), now.plusSeconds(4)))
    }

    @Test
    fun `threshold compaction atomically snapshots live state`() {
        val path = directory.resolve("compact.journal")
        val store = store(path, capacity = 10, perRepositoryCapacity = 10, compactionThreshold = 3)

        store.mark("primary", "team/project", listOf(key(0), key(1)), now)
        assertEquals(0, store.journalCompactionCount())
        store.mark("primary", "team/project", listOf(key(2)), now.plusSeconds(1))

        assertEquals(1, store.journalCompactionCount())
        assertEquals(4, Files.readAllLines(path).size)
        val reloaded = store(path, capacity = 10, perRepositoryCapacity = 10, compactionThreshold = 3)
        (0..2).forEach { assertTrue(reloaded.contains("primary", "team/project", key(it), now.plusSeconds(2))) }
    }

    @Test
    fun `restart truncates a corrupt tail and accepts subsequent appends`() {
        val path = directory.resolve("corrupt-tail.journal")
        store(path).mark("primary", "team/project", listOf(key(0)), now)
        Files.writeString(
            path,
            "CNB_EVENT_DEDUP_V1\tP\tbroken-tail",
            StandardOpenOption.APPEND,
        )

        val recovered = store(path)
        assertTrue(recovered.contains("primary", "team/project", key(0), now.plusSeconds(1)))
        assertFalse(Files.readString(path).contains("broken-tail"))
        recovered.mark("primary", "team/project", listOf(key(1)), now.plusSeconds(1))

        val restarted = store(path)
        assertTrue(restarted.contains("primary", "team/project", key(0), now.plusSeconds(2)))
        assertTrue(restarted.contains("primary", "team/project", key(1), now.plusSeconds(2)))
    }

    @Test
    fun `legacy properties migrate to a hash-only compatibility scope`() {
        val path = directory.resolve("legacy.properties")
        Properties()
            .apply {
                setProperty(key(0), now.toEpochMilli().toString())
                setProperty("not-a-hash", now.toEpochMilli().toString())
            }.also { properties -> Files.newOutputStream(path).use { properties.store(it, "legacy private state") } }

        val migrated = store(path)

        assertTrue(migrated.contains("any-server", "any/repository", key(0), now.plusSeconds(1)))
        assertTrue(Files.readString(path).startsWith("CNB_EVENT_DEDUP_V1"))
        assertFalse(Files.readString(path).contains("legacy private state"))
        assertEquals(1, migrated.journalCompactionCount())
    }

    @Test
    fun `restart restores independent repository scopes`() {
        val path = directory.resolve("restart.journal")
        val initial = store(path)
        initial.mark("primary", "team/a", listOf(key(0)), now)
        initial.mark("secondary", "team/b", listOf(key(1)), now.plusSeconds(1))

        val restarted = store(path)

        assertTrue(restarted.contains("primary", "team/a", key(0), now.plusSeconds(2)))
        assertTrue(restarted.contains("secondary", "team/b", key(1), now.plusSeconds(2)))
        assertFalse(restarted.contains("primary", "team/b", key(1), now.plusSeconds(2)))
    }

    @Test
    fun `production repository quota retains more than the former 4096 event limit`() {
        val path = directory.resolve("busy-current-hour.journal")
        val keys = (0..4_096).map(::key)
        val store =
            CnbRepositoryEventDedupStore(
                path = path,
                clock = Clock.fixed(now, ZoneOffset.UTC),
                compactionThreshold = 20_000,
            )

        store.mark("primary", "team/busy", keys, now)

        assertTrue(store.unseenKeys("primary", "team/busy", keys, now.plusSeconds(1)).isEmpty())
        assertEquals(keys.size, store.sizeByRepository("primary", "team/busy"))
    }

    @Test
    fun `one fetched event batch performs only one global expiry pass`() {
        val path = directory.resolve("batch-prune.journal")
        val store = store(path, capacity = 2_000, perRepositoryCapacity = 2_000)
        val existing = (0 until 500).map(::key)
        val unseen = (500 until 1_000).map(::key)
        store.mark("primary", "team/project", existing, now)
        val before = store.prunePassCount()
        val batchTime = now.plusSeconds(1)

        assertEquals(
            unseen.toSet(),
            store.unseenKeys("primary", "team/project", existing + unseen, batchTime),
        )
        store.mark("primary", "team/project", unseen, batchTime)

        assertEquals(before + 1, store.prunePassCount())
    }

    @Test
    fun `persistence failure rolls back memory and leaves the durable prefix unchanged`() {
        val path = directory.resolve("failed-append.journal")
        var failPersistence = false
        val store =
            store(
                path,
                beforePersistence = {
                    if (failPersistence) throw IOException("injected persistence failure")
                },
            )
        store.mark("primary", "team/project", listOf(key(0)), now)
        val durablePrefix = Files.readAllBytes(path)
        failPersistence = true

        assertThrows(IOException::class.java) {
            store.mark("primary", "team/project", listOf(key(1)), now.plusSeconds(1))
        }

        assertArrayEquals(durablePrefix, Files.readAllBytes(path))
        assertTrue(store.contains("primary", "team/project", key(0), now.plusSeconds(2)))
        assertFalse(store.contains("primary", "team/project", key(1), now.plusSeconds(2)))
        val restarted = store(path)
        assertTrue(restarted.contains("primary", "team/project", key(0), now.plusSeconds(2)))
        assertFalse(restarted.contains("primary", "team/project", key(1), now.plusSeconds(2)))
    }

    @Test
    fun `non-atomic compaction commits a validated snapshot through the backup protocol`() {
        val path = directory.resolve("non-atomic.journal")
        val store =
            store(
                path = path,
                capacity = 10,
                perRepositoryCapacity = 10,
                compactionThreshold = 2,
                forceNonAtomicMove = true,
            )
        store.mark("primary", "team/project", listOf(key(0)), now)

        store.mark("primary", "team/project", listOf(key(1)), now.plusSeconds(1))

        assertFalse(Files.exists(path.resolveSibling("${path.fileName}.backup")))
        assertTrue(Files.readString(path).contains("\tC\t"))
        val restarted = store(path, capacity = 10, perRepositoryCapacity = 10)
        assertTrue(restarted.contains("primary", "team/project", key(0), now.plusSeconds(2)))
        assertTrue(restarted.contains("primary", "team/project", key(1), now.plusSeconds(2)))
    }

    @Test
    fun `startup restores the previous journal after an interrupted fallback replacement`() {
        val path = directory.resolve("interrupted-replacement.journal")
        val backup = path.resolveSibling("${path.fileName}.backup")
        store(path).mark("primary", "team/project", listOf(key(0)), now)
        Files.move(path, backup, StandardCopyOption.REPLACE_EXISTING)

        val missingTargetRecovered = store(path)
        assertTrue(missingTargetRecovered.contains("primary", "team/project", key(0), now.plusSeconds(1)))

        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(path, "CNB_EVENT_DEDUP_V1\tP\tcorrupt")
        val corruptTargetRecovered = store(path)
        assertTrue(corruptTargetRecovered.contains("primary", "team/project", key(0), now.plusSeconds(2)))
        assertFalse(Files.exists(backup))
    }

    private fun store(
        path: Path,
        ttl: Duration = Duration.ofDays(1),
        capacity: Int = 100,
        perRepositoryCapacity: Int = capacity,
        compactionThreshold: Int = 1_000,
        beforePersistence: () -> Unit = {},
        forceNonAtomicMove: Boolean = false,
    ): CnbRepositoryEventDedupStore =
        CnbRepositoryEventDedupStore(
            path = path,
            ttl = ttl,
            capacity = capacity,
            perRepositoryCapacity = perRepositoryCapacity,
            compactionThreshold = compactionThreshold,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            beforePersistence = beforePersistence,
            forceNonAtomicMove = forceNonAtomicMove,
        )

    private fun key(index: Int): String = index.toString(16).padStart(64, '0')
}
