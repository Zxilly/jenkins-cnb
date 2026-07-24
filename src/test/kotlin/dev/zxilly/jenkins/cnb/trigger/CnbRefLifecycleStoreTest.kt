package dev.zxilly.jenkins.cnb.trigger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import java.time.Instant

class CnbRefLifecycleStoreTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `delete and same revision recreation survive store restart`() {
        val path = temporaryDirectory.resolve("lifecycle.journal")
        val scope = scope("job")
        var store = CnbRefLifecycleStore(path)

        assertEquals(0L, store.apply(listOf(request(scope, "main", true, 1, "1"))).single().generation)
        val deleted = store.apply(listOf(request(scope, "main", false, 2, "2"))).single()
        assertFalse(deleted.present)

        store = CnbRefLifecycleStore(path)
        val recreated = store.apply(listOf(request(scope, "main", true, 3, "3"))).single()
        assertTrue(recreated.current)
        assertTrue(recreated.present)
        assertEquals(1L, recreated.generation)

        store = CnbRefLifecycleStore(path)
        assertEquals(1L, store.apply(listOf(request(scope, "main", true, 3, "3"))).single().generation)
    }

    @Test
    fun `late delete advances an already observed recreation across restart`() {
        val path = temporaryDirectory.resolve("late-delete.journal")
        val scope = scope("job")
        var store = CnbRefLifecycleStore(path, compactionThreshold = 1)

        assertEquals(0L, store.apply(listOf(request(scope, "main", true, 1, "1"))).single().generation)
        assertEquals(0L, store.apply(listOf(request(scope, "main", true, 3, "3"))).single().generation)

        val lateDelete = store.apply(listOf(request(scope, "main", false, 2, "2"))).single()
        assertFalse(lateDelete.current)
        assertTrue(lateDelete.present)
        assertEquals(1L, lateDelete.generation)

        val replayedRecreation = store.apply(listOf(request(scope, "main", true, 3, "3"))).single()
        assertTrue(replayedRecreation.current)
        assertTrue(replayedRecreation.present)
        assertEquals(1L, replayedRecreation.generation)

        store = CnbRefLifecycleStore(path, compactionThreshold = 1)
        val restartedRecreation = store.apply(listOf(request(scope, "main", true, 3, "3"))).single()
        assertTrue(restartedRecreation.current)
        assertTrue(restartedRecreation.present)
        assertEquals(1L, restartedRecreation.generation)

        val repeatedDelete = store.apply(listOf(request(scope, "main", false, 2, "2"))).single()
        assertFalse(repeatedDelete.current)
        assertTrue(repeatedDelete.present)
        assertEquals(1L, repeatedDelete.generation)

        val reversedBatch = CnbRefLifecycleStore(temporaryDirectory.resolve("reversed-batch.journal"))
        reversedBatch.apply(
            listOf(
                request(scope, "main", true, 3, "3"),
                request(scope, "main", false, 2, "2"),
                request(scope, "main", true, 1, "1"),
            ),
        )
        val reversedCurrent = reversedBatch.apply(listOf(request(scope, "main", true, 3, "3"))).single()
        assertTrue(reversedCurrent.current)
        assertTrue(reversedCurrent.present)
        assertEquals(1L, reversedCurrent.generation)
    }

    @Test
    fun `alternating timeline pressure evicts the whole ref above a persisted floor`() {
        val path = temporaryDirectory.resolve("per-ref-capacity.journal")
        val scope = scope("job")
        var store = CnbRefLifecycleStore(path, compactionThreshold = 1, perRefMarkerCapacity = 3)

        store.apply(listOf(request(scope, "main", true, 1, "1")))
        store.apply(listOf(request(scope, "main", false, 2, "2")))
        assertEquals(1L, store.apply(listOf(request(scope, "main", true, 3, "3"))).single().generation)
        store.apply(listOf(request(scope, "main", false, 4, "4")))

        store = CnbRefLifecycleStore(path, compactionThreshold = 1, perRefMarkerCapacity = 3)
        val reappeared = store.apply(listOf(request(scope, "main", true, 5, "5"))).single()
        assertTrue(reappeared.current)
        assertTrue(reappeared.present)
        assertEquals(2L, reappeared.generation)

        store = CnbRefLifecycleStore(path, compactionThreshold = 1, perRefMarkerCapacity = 3)
        assertEquals(2L, store.apply(listOf(request(scope, "main", true, 5, "5"))).single().generation)
    }

    @Test
    fun `numeric event IDs order 10 after 9 at the same timestamp`() {
        val store = CnbRefLifecycleStore(temporaryDirectory.resolve("numeric-order.journal"))
        val scope = scope("job")
        val at = Instant.parse("2026-07-24T10:00:00Z")

        val results =
            store.apply(
                listOf(
                    CnbScopedRefLifecycleTransition(scope, transition("main", false, at, "10")),
                    CnbScopedRefLifecycleTransition(scope, transition("main", true, at, "9")),
                ),
            )

        assertFalse(results.last().current, "ID 9 must not overwrite the already-observed ID 10")
        assertFalse(results.last().present)
    }

    @Test
    fun `evicted ref reappears above persisted generation floor`() {
        val path = temporaryDirectory.resolve("bounded.journal")
        val scope = scope("job")
        var store = CnbRefLifecycleStore(path, capacity = 1)

        assertEquals(0L, store.apply(listOf(request(scope, "main", true, 1, "1"))).single().generation)
        store.apply(listOf(request(scope, "release", true, 2, "2")))

        store = CnbRefLifecycleStore(path, capacity = 1)
        val reappeared = store.apply(listOf(request(scope, "main", true, 3, "3"))).single()
        assertTrue(reappeared.generation > 0L)
    }

    @Test
    fun `persistence failure rolls back the in-memory ref lifecycle state`() {
        val path = temporaryDirectory.resolve("failed-lifecycle-append.journal")
        val scope = scope("job")
        var failPersistence = false
        val store =
            CnbRefLifecycleStore(
                path,
                beforePersistence = {
                    if (failPersistence) throw IOException("injected lifecycle persistence failure")
                },
            )
        store.apply(listOf(request(scope, "main", true, 1, "1")))
        failPersistence = true

        assertThrows(IOException::class.java) {
            store.apply(listOf(request(scope, "main", false, 2, "2")))
        }

        failPersistence = false
        val replay = store.apply(listOf(request(scope, "main", true, 1, "1"))).single()
        assertTrue(replay.current)
        assertTrue(replay.present)
        assertEquals(0L, replay.generation)
    }

    private fun scope(consumer: String) = CnbRepositoryEventStateScope("primary", "team/project", "server", consumer)

    private fun request(
        scope: CnbRepositoryEventStateScope,
        ref: String,
        present: Boolean,
        second: Long,
        id: String,
    ) = CnbScopedRefLifecycleTransition(
        scope,
        transition(ref, present, Instant.EPOCH.plusSeconds(second), id),
    )

    private fun transition(
        ref: String,
        present: Boolean,
        at: Instant,
        id: String,
    ) = CnbRefLifecycleTransition("refs/heads/$ref", present, at, id)
}
