package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.model.CnbLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CnbRepositoryLabelCatalogTest {
    @Test
    fun `caches a bounded partial catalog by server and repository`() {
        val calls = AtomicInteger()
        var now = 0L
        val executor = Executors.newSingleThreadExecutor()
        val catalog =
            CnbCachingRepositoryLabelCatalog(
                loadLabels = { _, _ ->
                    calls.incrementAndGet()
                    (1..510).map { index -> CnbLabel("label-$index", "label-$index") }
                },
                executor = executor,
                timeout = Duration.ofSeconds(1),
                ttl = Duration.ofSeconds(60),
                nowNanos = { now },
            )

        try {
            val first = assertInstanceOf(CnbRepositoryLabelCatalogResult.Available::class.java, catalog.lookup("primary", "team/repo"))
            val cached = assertInstanceOf(CnbRepositoryLabelCatalogResult.Available::class.java, catalog.lookup("primary", "team/repo"))

            assertEquals(500, first.labels.size)
            assertEquals(false, first.complete)
            assertEquals(first, cached)
            assertEquals(1, calls.get())

            now = TimeUnit.SECONDS.toNanos(61)
            catalog.lookup("primary", "team/repo")
            assertEquals(2, calls.get())
        } finally {
            catalog.close()
        }
    }

    @Test
    fun `returns unavailable without caching failures or leaking worker timeouts`() {
        val calls = AtomicInteger()
        val blocker = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val catalog =
            CnbCachingRepositoryLabelCatalog(
                loadLabels = { _, _ ->
                    if (calls.incrementAndGet() == 1) error("secret response body")
                    blocker.await()
                    emptyList()
                },
                executor = executor,
                timeout = Duration.ofMillis(50),
            )

        try {
            assertEquals(CnbRepositoryLabelCatalogResult.Unavailable, catalog.lookup("primary", "team/repo"))
            assertEquals(CnbRepositoryLabelCatalogResult.Unavailable, catalog.lookup("primary", "team/repo"))
            assertEquals(2, calls.get())
        } finally {
            blocker.countDown()
            catalog.close()
        }
    }
}
