package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.model.CnbLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
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
                loadLabels = { _ ->
                    calls.incrementAndGet()
                    (1..510).map { index -> CnbLabel("label-$index", "label-$index") }
                },
                executor = executor,
                timeout = Duration.ofSeconds(1),
                ttl = Duration.ofSeconds(60),
                nowNanos = { now },
            )

        try {
            val first = assertInstanceOf(CnbRepositoryLabelCatalogResult.Available::class.java, catalog.lookup(request()))
            val cached = assertInstanceOf(CnbRepositoryLabelCatalogResult.Available::class.java, catalog.lookup(request()))

            assertEquals(500, first.labels.size)
            assertEquals(false, first.complete)
            assertEquals(first, cached)
            assertEquals(1, calls.get())

            catalog.lookup(request(credentialsId = "source-api"))
            assertEquals(2, calls.get())

            now = TimeUnit.SECONDS.toNanos(61)
            catalog.lookup(request())
            assertEquals(3, calls.get())
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
                loadLabels = { _ ->
                    if (calls.incrementAndGet() == 1) error("secret response body")
                    blocker.await()
                    emptyList()
                },
                executor = executor,
                timeout = Duration.ofMillis(50),
            )

        try {
            assertEquals(CnbRepositoryLabelCatalogResult.Unavailable, catalog.lookup(request()))
            assertEquals(CnbRepositoryLabelCatalogResult.Unavailable, catalog.lookup(request()))
            assertEquals(2, calls.get())
        } finally {
            blocker.countDown()
            catalog.close()
        }
    }

    @Test
    fun `shared lookup cancellation is unavailable to every waiter`() {
        val loaderStarted = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        val catalogExecutor = Executors.newSingleThreadExecutor()
        val callers = Executors.newFixedThreadPool(2)
        val catalog =
            CnbCachingRepositoryLabelCatalog(
                loadLabels = { _ ->
                    loaderStarted.countDown()
                    releaseLoader.await()
                    emptyList()
                },
                executor = catalogExecutor,
                timeout = Duration.ofMillis(500),
            )

        try {
            val first = callers.submit<CnbRepositoryLabelCatalogResult> { catalog.lookup(request()) }
            assertTrue(loaderStarted.await(1, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse(first.isDone)
            val second = callers.submit<CnbRepositoryLabelCatalogResult> { catalog.lookup(request()) }

            assertEquals(CnbRepositoryLabelCatalogResult.Unavailable, first.get(1, TimeUnit.SECONDS))
            assertEquals(CnbRepositoryLabelCatalogResult.Unavailable, second.get(1, TimeUnit.SECONDS))
        } finally {
            releaseLoader.countDown()
            catalog.close()
            callers.shutdownNow()
        }
    }

    private fun request(credentialsId: String? = null): CnbRepositoryLabelLookupRequest =
        CnbRepositoryLabelLookupRequest("primary", "team/repo", credentialsId)
}
