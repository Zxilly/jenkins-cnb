package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbClientFactory
import dev.zxilly.jenkins.cnb.api.model.CnbLabel
import hudson.init.Terminator
import java.time.Duration
import java.util.LinkedHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

internal sealed interface CnbRepositoryLabelCatalogResult {
    data class Available(
        val labels: List<String>,
        val complete: Boolean,
    ) : CnbRepositoryLabelCatalogResult

    data object Unavailable : CnbRepositoryLabelCatalogResult
}

internal fun interface CnbRepositoryLabelLookup {
    fun lookup(
        serverId: String,
        repositoryPath: String,
    ): CnbRepositoryLabelCatalogResult
}

/** Bounded, single-flight label catalog for latency-sensitive Jenkins configuration forms. */
internal class CnbCachingRepositoryLabelCatalog(
    private val loadLabels: (String, String) -> List<CnbLabel>,
    private val executor: ExecutorService = newExecutor(),
    private val timeout: Duration = Duration.ofSeconds(5),
    private val ttl: Duration = Duration.ofSeconds(60),
    private val nowNanos: () -> Long = System::nanoTime,
) : CnbRepositoryLabelLookup,
    AutoCloseable {
    private data class Key(
        val serverId: String,
        val repositoryPath: String,
    )

    private data class CacheEntry(
        val value: CnbRepositoryLabelCatalogResult.Available,
        val expiresAtNanos: Long,
    )

    private val lock = Any()
    private val cache = LinkedHashMap<Key, CacheEntry>(16, 0.75f, true)
    private val inFlight = HashMap<Key, FutureTask<CnbRepositoryLabelCatalogResult>>()
    private val closed = AtomicBoolean()

    override fun lookup(
        serverId: String,
        repositoryPath: String,
    ): CnbRepositoryLabelCatalogResult {
        if (closed.get()) return CnbRepositoryLabelCatalogResult.Unavailable
        val key = Key(serverId, repositoryPath)
        val now = nowNanos()
        var created = false
        val future =
            synchronized(lock) {
                cache[key]
                    ?.takeIf { entry -> entry.expiresAtNanos > now }
                    ?.let { entry -> return entry.value }
                cache.remove(key)
                inFlight[key]
                    ?: FutureTask { load(key) }.also { task ->
                        inFlight[key] = task
                        created = true
                    }
            }

        if (created) {
            try {
                executor.execute(future)
            } catch (_: RejectedExecutionException) {
                synchronized(lock) { if (inFlight[key] === future) inFlight.remove(key) }
                return CnbRepositoryLabelCatalogResult.Unavailable
            }
        }
        return try {
            future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            (executor as? ThreadPoolExecutor)?.purge()
            CnbRepositoryLabelCatalogResult.Unavailable
        } catch (_: ExecutionException) {
            CnbRepositoryLabelCatalogResult.Unavailable
        } catch (_: CancellationException) {
            CnbRepositoryLabelCatalogResult.Unavailable
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            CnbRepositoryLabelCatalogResult.Unavailable
        } finally {
            if (future.isDone) {
                synchronized(lock) { if (inFlight[key] === future) inFlight.remove(key) }
            }
        }
    }

    private fun load(key: Key): CnbRepositoryLabelCatalogResult {
        val result =
            try {
                val names = linkedSetOf<String>()
                for (label in loadLabels(key.serverId, key.repositoryPath)) names += label.name
                CnbRepositoryLabelCatalogResult.Available(
                    labels = names.take(MAX_VISIBLE_LABELS),
                    complete = names.size <= MAX_VISIBLE_LABELS,
                )
            } catch (failure: Exception) {
                LOGGER.log(Level.FINE, "CNB repository label catalog is unavailable", failure.javaClass.simpleName)
                return CnbRepositoryLabelCatalogResult.Unavailable
            }
        synchronized(lock) {
            cache[key] = CacheEntry(result, nowNanos() + ttl.toNanos())
            while (cache.size > MAX_CACHE_KEYS) {
                val eldest = cache.entries.iterator()
                eldest.next()
                eldest.remove()
            }
        }
        return result
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            inFlight.values.forEach { future -> future.cancel(true) }
            inFlight.clear()
            cache.clear()
        }
        executor.shutdownNow()
        try {
            executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val MAX_VISIBLE_LABELS = 500
        private const val MAX_CACHE_KEYS = 128
        private const val MAX_QUEUED_LOOKUPS = 16
        private const val WORKER_COUNT = 2
        private const val SHUTDOWN_WAIT_SECONDS = 2L
        private val LOGGER = Logger.getLogger(CnbCachingRepositoryLabelCatalog::class.java.name)

        private fun newExecutor(): ExecutorService =
            ThreadPoolExecutor(
                WORKER_COUNT,
                WORKER_COUNT,
                0L,
                TimeUnit.MILLISECONDS,
                ArrayBlockingQueue(MAX_QUEUED_LOOKUPS),
                CnbRepositoryLabelThreadFactory(),
                ThreadPoolExecutor.AbortPolicy(),
            )
    }
}

internal object CnbRepositoryLabelCatalogRuntime : CnbRepositoryLabelLookup {
    @Volatile
    private var current: CnbCachingRepositoryLabelCatalog? = null

    override fun lookup(
        serverId: String,
        repositoryPath: String,
    ): CnbRepositoryLabelCatalogResult = catalog().lookup(serverId, repositoryPath)

    private fun catalog(): CnbCachingRepositoryLabelCatalog =
        current ?: synchronized(this) {
            current
                ?: CnbCachingRepositoryLabelCatalog(
                    loadLabels = { serverId, repositoryPath ->
                        CnbClientFactory.create(serverId).use { client -> client.listRepositoryLabels(repositoryPath) }
                    },
                ).also { created -> current = created }
        }

    fun shutdown() {
        val closing = synchronized(this) { current.also { current = null } }
        closing?.close()
    }
}

private class CnbRepositoryLabelThreadFactory : ThreadFactory {
    private val sequence = AtomicInteger()

    override fun newThread(task: Runnable): Thread =
        Thread(task, "cnb-label-catalog-${sequence.incrementAndGet()}").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
}

@Terminator
internal fun shutdownCnbRepositoryLabelCatalog() {
    CnbRepositoryLabelCatalogRuntime.shutdown()
}
