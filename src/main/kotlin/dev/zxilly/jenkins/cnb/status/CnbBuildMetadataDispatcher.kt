package dev.zxilly.jenkins.cnb.status

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.health.CnbOperationalHealth
import hudson.model.Item
import hudson.model.Run
import java.util.LinkedHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/** Coalesces and serializes updates per build so stale queued/running writes cannot win. */
internal object CnbBuildMetadataDispatcher {
    private val runtime =
        CnbBuildMetadataDispatchRuntime(
            workers = newMetadataWorkerExecutor(),
            wakeups = newMetadataWakeupExecutor(),
            reporter =
                CnbOperationalHealthMetadataReporter(
                    delegate = CnbMetadataReporter(CnbBuildMetadataReporter::report),
                    health = CnbOperationalHealth.get(),
                ),
        )

    fun schedule(
        run: Run<*, *>,
        action: CnbBuildMetadataAction,
        delaySeconds: Long = 0,
    ): Boolean = runtime.scheduleRun(run, action, delaySeconds)

    fun schedule(
        item: Item?,
        action: CnbBuildMetadataAction,
        delaySeconds: Long = 0,
    ): Boolean = runtime.scheduleItem(item, action, delaySeconds)

    fun shutdown() {
        runtime.shutdown()
    }
}

/** Plugin-owned scheduler for the single drain wakeup; it never executes reporting or network I/O. */
internal class CnbMetadataWakeupExecutor : CnbMetadataWakeupScheduler {
    private val executor =
        ScheduledThreadPoolExecutor(1, CnbMetadataWakeupThreadFactory()).apply {
            removeOnCancelPolicy = true
            setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
            setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
        }

    override fun schedule(
        delayMillis: Long,
        wakeup: Runnable,
    ): CnbMetadataScheduledWakeup {
        val future = executor.schedule(wakeup, delayMillis, TimeUnit.MILLISECONDS)
        return CnbMetadataScheduledWakeup { future.cancel(false) }
    }

    override fun shutdown() {
        executor.shutdownNow()
    }

    internal fun queuedTaskCount(): Int = executor.queue.size

    internal fun isShutdown(): Boolean = executor.isShutdown

    internal fun removesCancelledTasks(): Boolean = executor.removeOnCancelPolicy

    internal fun executesDelayedTasksAfterShutdown(): Boolean = executor.executeExistingDelayedTasksAfterShutdownPolicy

    internal fun continuesPeriodicTasksAfterShutdown(): Boolean = executor.continueExistingPeriodicTasksAfterShutdownPolicy
}

internal fun newMetadataWakeupExecutor(): CnbMetadataWakeupExecutor = CnbMetadataWakeupExecutor()

internal fun interface CnbMetadataScheduledWakeup {
    fun cancel()
}

internal fun interface CnbMetadataWakeupScheduler {
    /** Implementations must enqueue [wakeup], never run it inline. */
    fun schedule(
        delayMillis: Long,
        wakeup: Runnable,
    ): CnbMetadataScheduledWakeup

    /** Test schedulers may keep this default; production owns and shuts down its executor. */
    fun shutdown() {}
}

internal fun interface CnbMetadataReporter {
    fun report(
        snapshot: CnbBuildMetadataSnapshot,
        item: Item?,
    ): CnbBuildMetadataReportResult
}

/** Production reporter decorator; test runtimes remain isolated unless they explicitly opt in. */
internal class CnbOperationalHealthMetadataReporter(
    private val delegate: CnbMetadataReporter,
    private val health: CnbOperationalHealth,
) : CnbMetadataReporter {
    override fun report(
        snapshot: CnbBuildMetadataSnapshot,
        item: Item?,
    ): CnbBuildMetadataReportResult {
        val target = snapshot.target
        return try {
            delegate.report(snapshot, item).also {
                health.recordReporting(
                    target.serverId,
                    target.repository,
                    successful = true,
                    summary = "state=${snapshot.state.wireName} status=success",
                )
            }
        } catch (failure: Exception) {
            health.recordReporting(
                target.serverId,
                target.repository,
                successful = false,
                summary =
                    "state=${snapshot.state.wireName} status=failure " +
                        "class=${failure.javaClass.simpleName.replace(HEALTH_CLASS_BOUNDARY, ".")}",
            )
            throw failure
        }
    }

    private companion object {
        val HEALTH_CLASS_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
    }
}

/** Runtime separated from the singleton to make overload and interruption behavior testable. */
internal class CnbBuildMetadataDispatchRuntime(
    private val workers: ExecutorService,
    private val wakeups: CnbMetadataWakeupScheduler,
    private val reporter: CnbMetadataReporter,
    private val pendingCapacity: Int = DEFAULT_PENDING_CAPACITY,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val recoveryRequests: CnbBuildMetadataRecoveryGate = CNB_BUILD_METADATA_RECOVERY_REQUESTS,
    private val requestRecovery: () -> Unit = { recoveryRequests.request() },
    private val saveRun: (Run<*, *>) -> Unit = { it.save() },
) {
    private val stateLock = Any()
    private val pending = LinkedHashMap<String, Work>()
    private val active = HashMap<String, Work>()
    private val draining = AtomicBoolean()
    private val shuttingDown = AtomicBoolean()
    private val nextOverflowLogMillis = AtomicLong()

    private var scheduledWakeup: CnbMetadataScheduledWakeup? = null
    private var scheduledWakeupAtMillis = Long.MAX_VALUE
    private var wakeupGeneration = 0L
    private var workerBackpressureUntilMillis = Long.MIN_VALUE

    init {
        require(pendingCapacity > 0) { "CNB metadata pending capacity must be positive" }
    }

    fun scheduleRun(
        run: Run<*, *>,
        action: CnbBuildMetadataAction,
        delaySeconds: Long = 0,
    ): Boolean = enqueue(Work.create(action, run.parent, run, delaySeconds, clockMillis()))

    fun scheduleItem(
        item: Item?,
        action: CnbBuildMetadataAction,
        delaySeconds: Long = 0,
    ): Boolean = enqueue(Work.create(action, item, null, delaySeconds, clockMillis()))

    fun shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return
        synchronized(stateLock) {
            cancelWakeupLocked()
            // The Actions are durable. Dropping controller-memory references during shutdown is
            // safe and prevents plugin-owned Run/Item references from outliving the executor.
            pending.clear()
            active.clear()
        }
        wakeups.shutdown()
        workers.shutdownNow()
    }

    internal fun retryAttempt(action: CnbBuildMetadataAction): Int? =
        synchronized(stateLock) {
            pending[action.dispatchKey()]?.attempt ?: active[action.dispatchKey()]?.attempt
        }

    internal fun pendingCount(): Int = synchronized(stateLock) { pending.size }

    internal fun activeCount(): Int = synchronized(stateLock) { active.size }

    private fun enqueue(candidate: Work?): Boolean {
        if (candidate == null || shuttingDown.get()) return false
        val accepted =
            synchronized(stateLock) {
                if (shuttingDown.get()) return@synchronized false
                val key = candidate.key
                val queued = pending[key]
                val running = active[key]
                when {
                    queued != null -> {
                        pending[key] = queued.coalesce(candidate)
                        true
                    }

                    running != null && candidate.versionHint <= running.versionHint -> {
                        // The active report snapshots the same durable Action. A same-version
                        // periodic reconciliation does not need a duplicate in-memory entry.
                        true
                    }

                    pending.size < pendingCapacity -> {
                        pending[key] = candidate
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
        if (!accepted) {
            logOverflow()
            return false
        }

        if (candidate.notBeforeMillis <= clockMillis()) requestDrain() else ensureWakeup()
        return true
    }

    private fun requestDrain() {
        if (shuttingDown.get() || !draining.compareAndSet(false, true)) return
        while (true) {
            var submissionRejected = false
            while (!shuttingDown.get()) {
                val work = takeEligible() ?: break
                try {
                    workers.execute { executeOne(work) }
                } catch (_: RejectedExecutionException) {
                    deferAfterWorkerRejection(work)
                    submissionRejected = true
                    break
                }
            }

            draining.set(false)
            if (shuttingDown.get()) return
            // Close the enqueue-vs-drain-exit race without creating another Timer task.
            if (!submissionRejected && hasEligible() && draining.compareAndSet(false, true)) continue
            ensureWakeup()
            return
        }
    }

    private fun takeEligible(): Work? =
        synchronized(stateLock) {
            val now = clockMillis()
            if (now < workerBackpressureUntilMillis) return@synchronized null
            workerBackpressureUntilMillis = Long.MIN_VALUE
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key in active || entry.value.notBeforeMillis > now) continue
                iterator.remove()
                active[entry.key] = entry.value
                return@synchronized entry.value
            }
            null
        }

    private fun hasEligible(): Boolean =
        synchronized(stateLock) {
            val now = clockMillis()
            if (now < workerBackpressureUntilMillis) return@synchronized false
            pending.any { (key, work) -> key !in active && work.notBeforeMillis <= now }
        }

    private fun executeOne(work: Work) {
        var snapshot: CnbBuildMetadataSnapshot? = null
        var requestRecoveryAfterActiveRemoval = false
        try {
            snapshot = work.action.snapshot() ?: return
            val result = reporter.report(snapshot, work.item)
            markReportedAndPersist(work, snapshot, result.commentId)
        } catch (e: InterruptedException) {
            handleInterruption(work)
        } catch (e: CnbApiException) {
            if (e.causedByInterruption()) {
                handleInterruption(work)
            } else {
                logFailure(work, snapshot, e)
                if (snapshot != null) {
                    requestRecoveryAfterActiveRemoval = handleApiFailure(work, snapshot, e)
                }
            }
        } catch (e: Exception) {
            logFailure(work, snapshot, e)
            if (snapshot != null) {
                requestRecoveryAfterActiveRemoval = handleUnexpectedFailure(work, snapshot)
            }
        } finally {
            removeActive(work.key)
            try {
                if (requestRecoveryAfterActiveRemoval) requestRecovery()
            } finally {
                requestDrain()
            }
        }
    }

    private fun handleApiFailure(
        work: Work,
        snapshot: CnbBuildMetadataSnapshot,
        failure: CnbApiException,
    ): Boolean {
        if (!failure.retryable) {
            markReportedAndPersist(work, snapshot, null)
            return false
        }
        if (hasQueuedSuccessor(work.key)) return false
        if (work.attempt >= RETRY_DELAYS_SECONDS.size) {
            logRetriesExhausted(work, snapshot)
            return true
        }

        val baseDelay = RETRY_DELAYS_SECONDS[work.attempt]
        val delaySeconds = ThreadLocalRandom.current().nextLong(baseDelay * 4 / 5, baseDelay * 6 / 5 + 1)
        requeue(
            work.copy(
                attempt = work.attempt + 1,
                notBeforeMillis = safeAddMillis(clockMillis(), TimeUnit.SECONDS.toMillis(delaySeconds)),
            ),
            "retry",
        )
        return false
    }

    private fun handleInterruption(work: Work) {
        Thread.currentThread().interrupt()
        // Interruption is lifecycle/cancellation, not a failed delivery attempt. Preserve the exact
        // attempt count so shutdown can never exhaust retries or mark an Action as reported.
        if (!shuttingDown.get()) {
            requeue(work.withDelay(INTERRUPTED_RETRY_DELAY_MILLIS, clockMillis()), "interruption")
        }
        LOGGER.log(
            Level.FINE,
            "CNB build metadata reconciliation interrupted for build={0}; it remains pending",
            work.run?.externalizableId ?: work.item?.fullName ?: "queue-item",
        )
    }

    private fun handleUnexpectedFailure(
        work: Work,
        snapshot: CnbBuildMetadataSnapshot,
    ): Boolean {
        if (hasQueuedSuccessor(work.key)) return false
        if (work.attempt >= RETRY_DELAYS_SECONDS.size) {
            logRetriesExhausted(work, snapshot)
            return true
        }
        val baseDelay = RETRY_DELAYS_SECONDS[work.attempt]
        val delaySeconds = ThreadLocalRandom.current().nextLong(baseDelay * 4 / 5, baseDelay * 6 / 5 + 1)
        requeue(
            work.copy(
                attempt = work.attempt + 1,
                notBeforeMillis = safeAddMillis(clockMillis(), TimeUnit.SECONDS.toMillis(delaySeconds)),
            ),
            "unexpected reporting failure",
        )
        return false
    }

    private fun deferAfterWorkerRejection(work: Work) {
        val now = clockMillis()
        val retryAt = safeAddMillis(now, BACKPRESSURE_RETRY_DELAY_MILLIS)
        val candidate = work.copy(notBeforeMillis = retryAt)
        val accepted =
            synchronized(stateLock) {
                active.remove(work.key)
                if (shuttingDown.get()) return@synchronized false
                workerBackpressureUntilMillis = maxOf(workerBackpressureUntilMillis, retryAt)
                val queued = pending[candidate.key]
                when {
                    queued != null -> {
                        pending[candidate.key] = queued.coalesce(candidate)
                        true
                    }

                    pending.size < pendingCapacity -> {
                        pending[candidate.key] = candidate
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
        if (!accepted && !shuttingDown.get()) {
            logOverflow()
            LOGGER.log(
                Level.FINE,
                "CNB metadata in-memory requeue skipped after worker queue rejection; durable Action remains pending",
            )
        }
    }

    private fun markReportedAndPersist(
        work: Work,
        snapshot: CnbBuildMetadataSnapshot,
        commentId: String?,
    ) {
        val checkpoint = work.action.markReported(snapshot.version, commentId)
        if (persist(work.run)) return

        work.action.restorePending(checkpoint)
        val retryIndex = work.persistenceAttempt.coerceAtMost(PERSISTENCE_RETRY_DELAYS_SECONDS.lastIndex)
        val delayMillis = TimeUnit.SECONDS.toMillis(PERSISTENCE_RETRY_DELAYS_SECONDS[retryIndex])
        requeue(
            work.copy(
                persistenceAttempt = (work.persistenceAttempt + 1).coerceAtMost(PERSISTENCE_RETRY_DELAYS_SECONDS.size),
                notBeforeMillis = safeAddMillis(clockMillis(), delayMillis),
            ),
            "build state persistence failure",
        )
    }

    private fun requeue(
        candidate: Work,
        reason: String,
    ): Boolean {
        if (shuttingDown.get()) return false
        val accepted =
            synchronized(stateLock) {
                if (shuttingDown.get()) return@synchronized false
                val queued = pending[candidate.key]
                when {
                    queued != null -> {
                        pending[candidate.key] = queued.coalesce(candidate)
                        true
                    }

                    pending.size < pendingCapacity -> {
                        pending[candidate.key] = candidate
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
        if (!accepted) {
            logOverflow()
            LOGGER.log(
                Level.FINE,
                "CNB metadata in-memory requeue skipped after {0}; durable Action remains pending",
                reason,
            )
        }
        return accepted
    }

    private fun hasQueuedSuccessor(key: String): Boolean = synchronized(stateLock) { key in pending }

    private fun removeActive(key: String) {
        synchronized(stateLock) { active.remove(key) }
    }

    private fun ensureWakeup() {
        var drainNow = false
        synchronized(stateLock) {
            if (shuttingDown.get()) {
                cancelWakeupLocked()
                return
            }
            val now = clockMillis()
            val earliest =
                pending
                    .asSequence()
                    .filter { (key, _) -> key !in active }
                    .minOfOrNull { (_, work) -> work.notBeforeMillis }
                    ?.let { maxOf(it, workerBackpressureUntilMillis) }
            when {
                earliest == null -> {
                    cancelWakeupLocked()
                }

                earliest <= now -> {
                    cancelWakeupLocked()
                    drainNow = true
                }

                scheduledWakeup != null && scheduledWakeupAtMillis <= earliest -> {
                    // The single existing wakeup is early enough for every queued key.
                }

                else -> {
                    cancelWakeupLocked()
                    val generation = ++wakeupGeneration
                    val delayMillis = (earliest - now).coerceAtLeast(1L)
                    try {
                        scheduledWakeup =
                            wakeups.schedule(
                                delayMillis,
                                Runnable {
                                    val current =
                                        synchronized(stateLock) {
                                            if (generation != wakeupGeneration) {
                                                false
                                            } else {
                                                scheduledWakeup = null
                                                scheduledWakeupAtMillis = Long.MAX_VALUE
                                                true
                                            }
                                        }
                                    if (current) requestDrain()
                                },
                            )
                        scheduledWakeupAtMillis = earliest
                    } catch (_: RejectedExecutionException) {
                        scheduledWakeup = null
                        scheduledWakeupAtMillis = Long.MAX_VALUE
                        // Jenkins is shutting down. Durable Actions reconcile after restart.
                    }
                }
            }
        }
        if (drainNow) requestDrain()
    }

    private fun cancelWakeupLocked() {
        wakeupGeneration++
        scheduledWakeup?.cancel()
        scheduledWakeup = null
        scheduledWakeupAtMillis = Long.MAX_VALUE
    }

    private fun logOverflow() {
        requestRecovery()
        val now = clockMillis()
        val next = nextOverflowLogMillis.get()
        if (now < next || !nextOverflowLogMillis.compareAndSet(next, safeAddMillis(now, OVERFLOW_LOG_INTERVAL_MILLIS))) return
        LOGGER.log(
            Level.WARNING,
            "CNB metadata in-memory capacity ({0}) reached; durable Actions remain pending for bounded periodic reconciliation",
            pendingCapacity,
        )
    }

    private fun logFailure(
        work: Work,
        snapshot: CnbBuildMetadataSnapshot?,
        failure: Exception,
    ) {
        // Deliberately omit exception messages and target details: HTTP failures may contain
        // response data. Reporting failures must never mutate the Jenkins build result.
        LOGGER.log(
            Level.WARNING,
            "CNB build metadata reconciliation failed for build={0}, server={1}, attempt={2} ({3}); Jenkins result is unchanged",
            arrayOf(
                work.run?.externalizableId ?: work.item?.fullName ?: "queue-item",
                snapshot?.target?.serverId ?: "unresolved",
                work.attempt + 1,
                failure.javaClass.simpleName,
            ),
        )
    }

    private fun persist(run: Run<*, *>?): Boolean {
        if (run == null) return true
        return try {
            saveRun(run)
            true
        } catch (e: Exception) {
            LOGGER.log(Level.WARNING, "Unable to persist CNB build metadata state (${e.javaClass.simpleName})")
            false
        }
    }

    private fun logRetriesExhausted(
        work: Work,
        snapshot: CnbBuildMetadataSnapshot,
    ) {
        LOGGER.log(
            Level.WARNING,
            "CNB build metadata retries exhausted for build={0}, server={1}, version={2}; Jenkins result is unchanged",
            arrayOf<Any>(
                work.run?.externalizableId ?: work.item?.fullName ?: "queue-item",
                snapshot.target.serverId,
                snapshot.version,
            ),
        )
    }

    private fun Throwable.causedByInterruption(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is InterruptedException) return true
            current = current.cause
        }
        return false
    }

    private data class Work(
        val action: CnbBuildMetadataAction,
        val item: Item?,
        val run: Run<*, *>?,
        val attempt: Int,
        val persistenceAttempt: Int,
        val versionHint: Long,
        val notBeforeMillis: Long,
    ) {
        val key: String
            get() = action.dispatchKey()

        fun coalesce(candidate: Work): Work =
            if (candidate.versionHint > versionHint) {
                candidate.copy(
                    item = candidate.item ?: item,
                    run = candidate.run ?: run,
                )
            } else {
                copy(
                    item = candidate.item ?: item,
                    run = candidate.run ?: run,
                )
            }

        fun withDelay(
            delayMillis: Long,
            nowMillis: Long,
        ): Work = copy(notBeforeMillis = safeAddMillis(nowMillis, delayMillis))

        companion object {
            fun create(
                action: CnbBuildMetadataAction,
                item: Item?,
                run: Run<*, *>?,
                delaySeconds: Long,
                nowMillis: Long,
            ): Work? {
                val snapshot = action.snapshot() ?: return null
                val safeDelayMillis = TimeUnit.SECONDS.toMillis(delaySeconds.coerceAtLeast(0L))
                return Work(
                    action = action,
                    item = item,
                    run = run,
                    attempt = 0,
                    persistenceAttempt = 0,
                    versionHint = snapshot.version,
                    notBeforeMillis = safeAddMillis(nowMillis, safeDelayMillis),
                )
            }
        }
    }

    internal companion object {
        const val DEFAULT_PENDING_CAPACITY = 1024
        const val BACKPRESSURE_RETRY_DELAY_MILLIS = 1_000L
        const val INTERRUPTED_RETRY_DELAY_MILLIS = 1_000L

        private val LOGGER = Logger.getLogger(CnbBuildMetadataDispatcher::class.java.name)
        private const val OVERFLOW_LOG_INTERVAL_MILLIS = 60_000L
        private val RETRY_DELAYS_SECONDS = longArrayOf(2, 10, 30, 60, 300, 900, 3600)
        private val PERSISTENCE_RETRY_DELAYS_SECONDS = longArrayOf(1, 2, 5, 10, 30, 60, 300)
    }
}

private fun safeAddMillis(
    base: Long,
    increment: Long,
): Long =
    try {
        Math.addExact(base, increment)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

internal fun newMetadataWorkerExecutor(): ThreadPoolExecutor {
    val executor =
        ThreadPoolExecutor(
            METADATA_WORKER_THREADS,
            METADATA_WORKER_THREADS,
            METADATA_WORKER_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(METADATA_WORK_QUEUE_CAPACITY),
            CnbMetadataThreadFactory(),
            ThreadPoolExecutor.AbortPolicy(),
        )
    executor.allowCoreThreadTimeOut(true)
    return executor
}

private class CnbMetadataThreadFactory : ThreadFactory {
    private val sequence = AtomicInteger()

    override fun newThread(task: Runnable): Thread =
        Thread(task, "jenkins-cnb-build-metadata-${sequence.incrementAndGet()}").apply {
            isDaemon = true
            uncaughtExceptionHandler =
                Thread.UncaughtExceptionHandler { thread, failure ->
                    THREAD_LOGGER.log(Level.SEVERE, "Uncaught failure in ${thread.name}", failure)
                }
        }

    private companion object {
        private val THREAD_LOGGER = Logger.getLogger(CnbMetadataThreadFactory::class.java.name)
    }
}

private class CnbMetadataWakeupThreadFactory : ThreadFactory {
    override fun newThread(task: Runnable): Thread =
        Thread(task, "jenkins-cnb-build-metadata-wakeup").apply {
            isDaemon = true
            uncaughtExceptionHandler =
                Thread.UncaughtExceptionHandler { thread, failure ->
                    THREAD_LOGGER.log(Level.SEVERE, "Uncaught failure in ${thread.name}", failure)
                }
        }

    private companion object {
        private val THREAD_LOGGER = Logger.getLogger(CnbMetadataWakeupThreadFactory::class.java.name)
    }
}

private const val METADATA_WORKER_THREADS = 4
private const val METADATA_WORK_QUEUE_CAPACITY = 256
private const val METADATA_WORKER_KEEP_ALIVE_SECONDS = 60L
