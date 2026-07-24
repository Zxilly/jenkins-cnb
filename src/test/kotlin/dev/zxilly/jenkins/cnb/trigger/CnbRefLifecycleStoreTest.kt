package dev.zxilly.jenkins.cnb.trigger

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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

    private fun scope(consumer: String) =
        CnbRepositoryEventStateScope("primary", "team/project", "server", consumer)

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
