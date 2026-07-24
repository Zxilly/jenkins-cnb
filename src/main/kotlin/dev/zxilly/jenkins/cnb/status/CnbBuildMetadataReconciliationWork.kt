package dev.zxilly.jenkins.cnb.status

import hudson.Extension
import hudson.model.AsyncPeriodicWork
import hudson.model.Item
import hudson.model.Items
import hudson.model.Job
import hudson.model.Queue
import hudson.model.Run
import hudson.model.TaskListener
import jenkins.model.Jenkins
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Bounded loss recovery for durable metadata Actions that could not enter the in-memory dispatcher.
 * One startup round is performed; after that, the task sleeps unless an overflow requests a new
 * generation. Lazy item traversal and a single Run cursor avoid materializing all jobs or builds.
 */
@Extension
class CnbBuildMetadataReconciliationWork : AsyncPeriodicWork("CNB build metadata reconciliation") {
    private var roundGeneration: Long? = null
    private var queueIterator: Iterator<Queue.Item>? = null
    private var currentQueueItem: Queue.Item? = null
    private var currentQueueActions: Iterator<CnbBuildMetadataAction>? = null
    private var queueComplete = true
    private var jobIterator: Iterator<Job<*, *>>? = null
    private var currentRun: Run<*, *>? = null
    private var currentRunActionOwner: Run<*, *>? = null
    private var currentRunActions: Iterator<CnbBuildMetadataAction>? = null
    private var jobsComplete = true

    override fun getRecurrencePeriod(): Long = RECURRENCE.toMillis()

    override fun getInitialDelay(): Long = RECURRENCE.toMillis()

    override fun execute(listener: TaskListener) {
        val jenkins = Jenkins.get()
        if (jenkins.isQuietingDown || jenkins.isTerminating) return

        val stats =
            recoverOnce(
                recoveryRequests = CNB_BUILD_METADATA_RECOVERY_REQUESTS,
                queueItems = CNB_BUILD_METADATA_QUEUE_RECOVERY_INDEX::snapshotIterator,
                jobs = { Items.allItems(jenkins, Job::class.java).iterator() },
                scheduleQueue = { item, action ->
                    CnbBuildMetadataDispatcher.schedule(item.task as? Item, action)
                },
                scheduleRun = CnbBuildMetadataDispatcher::schedule,
            )
        if (stats.accepted > 0) {
            listener.logger.printf(
                "CNB metadata recovery generation %d requeued %d durable Action(s); inspected %d queue item(s), %d job(s), and %d run(s)%n",
                stats.generation,
                stats.accepted,
                stats.queueItemsInspected,
                stats.jobsInspected,
                stats.runsInspected,
            )
        }
    }

    @Synchronized
    internal fun recoverOnce(
        recoveryRequests: CnbBuildMetadataRecoveryGate,
        queueItems: () -> Iterator<Queue.Item>,
        jobs: () -> Iterator<Job<*, *>>,
        maximumQueueItems: Int = MAX_QUEUE_ITEMS_PER_EXECUTION,
        maximumJobs: Int = MAX_JOBS_PER_EXECUTION,
        maximumRuns: Int = MAX_RUNS_PER_EXECUTION,
        maximumActions: Int = MAX_ACTIONS_PER_EXECUTION,
        scheduleQueue: (Queue.Item, CnbBuildMetadataAction) -> Boolean,
        scheduleRun: (Run<*, *>, CnbBuildMetadataAction) -> Boolean,
    ): RecoveryTickStats {
        require(maximumQueueItems > 0) { "maximumQueueItems must be positive" }
        require(maximumJobs > 0) { "maximumJobs must be positive" }
        require(maximumRuns > 0) { "maximumRuns must be positive" }
        require(maximumActions > 0) { "maximumActions must be positive" }

        val generation = roundGeneration ?: recoveryRequests.pendingGeneration() ?: return RecoveryTickStats.idle()
        return try {
            if (roundGeneration == null) {
                beginRound(generation, queueItems(), jobs())
            }
            val queueStats =
                if (queueComplete) {
                    ReconciliationStats(0, 0, 0)
                } else {
                    reconcileQueue(maximumQueueItems, maximumActions, scheduleQueue)
                }
            val remainingActions = maximumActions - queueStats.actionsInspected
            val runStats =
                if (remainingActions == 0) {
                    RunReconciliationStats(0, 0, 0, 0)
                } else {
                    reconcileRuns(maximumJobs, maximumRuns, remainingActions, scheduleRun)
                }
            val completed = queueComplete && jobsComplete
            val requestCleared = completed && recoveryRequests.completeIfUnchanged(generation)
            if (completed) resetRound()
            RecoveryTickStats(
                generation = generation,
                queueItemsInspected = queueStats.inspected,
                jobsInspected = runStats.jobsInspected,
                runsInspected = runStats.runsInspected,
                actionsInspected = queueStats.actionsInspected + runStats.actionsInspected,
                accepted = queueStats.accepted + runStats.accepted,
                roundCompleted = completed,
                requestCleared = requestCleared,
            )
        } catch (failure: RuntimeException) {
            // Item trees may change while their lazy iterator is retained between ticks. Keep the
            // request pending and restart the bounded round on the next tick.
            LOGGER.log(Level.FINE, "CNB metadata recovery round will restart", failure)
            recoveryRequests.request()
            resetRound()
            RecoveryTickStats(
                generation = generation,
                roundCompleted = false,
                requestCleared = false,
            )
        }
    }

    private fun beginRound(
        generation: Long,
        queueItems: Iterator<Queue.Item>,
        jobs: Iterator<Job<*, *>>,
    ) {
        roundGeneration = generation
        queueIterator = queueItems
        currentQueueItem = null
        currentQueueActions = null
        queueComplete = !queueItems.hasNext()
        if (queueComplete) queueIterator = null
        jobIterator = jobs
        currentRun = null
        currentRunActionOwner = null
        currentRunActions = null
        jobsComplete = false
    }

    private fun reconcileQueue(
        maximumItems: Int,
        maximumActions: Int,
        schedule: (Queue.Item, CnbBuildMetadataAction) -> Boolean,
    ): ReconciliationStats {
        val iterator = queueIterator ?: return ReconciliationStats(0, 0, 0)
        var inspected = 0
        var actionsInspected = 0
        var accepted = 0
        while (actionsInspected < maximumActions) {
            var item = currentQueueItem
            var actions = currentQueueActions
            if (item == null || actions == null || !actions.hasNext()) {
                currentQueueItem = null
                currentQueueActions = null
                if (inspected >= maximumItems || !iterator.hasNext()) break
                item = iterator.next()
                actions = item.getActions(CnbBuildMetadataAction::class.java).iterator()
                currentQueueItem = item
                currentQueueActions = actions
                inspected++
                if (!actions.hasNext()) continue
            }

            val action = actions.next()
            actionsInspected++
            if (action.isPending() && schedule(item, action)) accepted++
        }
        if (currentQueueActions?.hasNext() != true) {
            currentQueueItem = null
            currentQueueActions = null
        }
        if (currentQueueActions == null && !iterator.hasNext()) {
            queueComplete = true
            queueIterator = null
        }
        return ReconciliationStats(inspected, actionsInspected, accepted)
    }

    private fun reconcileRuns(
        maximumJobs: Int,
        maximumRuns: Int,
        maximumActions: Int,
        schedule: (Run<*, *>, CnbBuildMetadataAction) -> Boolean,
    ): RunReconciliationStats {
        if (jobsComplete) return RunReconciliationStats(0, 0, 0, 0)
        var jobsInspected = if (currentRun == null && currentRunActionOwner == null) 0 else 1
        var runsInspected = 0
        var actionsInspected = 0
        var accepted = 0
        while (actionsInspected < maximumActions) {
            val actionOwner = currentRunActionOwner
            val actions = currentRunActions
            if (actionOwner != null && actions != null && actions.hasNext()) {
                val action = actions.next()
                actionsInspected++
                if (action.isPending() && schedule(actionOwner, action)) accepted++
                continue
            }
            currentRunActionOwner = null
            currentRunActions = null
            if (runsInspected >= maximumRuns) break

            var run = currentRun
            if (run == null) {
                if (jobsInspected >= maximumJobs) break
                val iterator = jobIterator
                if (iterator == null || !iterator.hasNext()) {
                    jobsComplete = true
                    jobIterator = null
                    break
                }
                val job = iterator.next()
                jobsInspected++
                run = job.lastBuild ?: continue
            }

            currentRun = run.previousBuild
            runsInspected++
            currentRunActionOwner = run
            currentRunActions = run.getActions(CnbBuildMetadataAction::class.java).iterator()
        }
        if (currentRunActions?.hasNext() != true) {
            currentRunActionOwner = null
            currentRunActions = null
        }
        return RunReconciliationStats(jobsInspected, runsInspected, actionsInspected, accepted)
    }

    private fun resetRound() {
        roundGeneration = null
        queueIterator = null
        currentQueueItem = null
        currentQueueActions = null
        queueComplete = true
        jobIterator = null
        currentRun = null
        currentRunActionOwner = null
        currentRunActions = null
        jobsComplete = true
    }

    private data class ReconciliationStats(
        val inspected: Int,
        val actionsInspected: Int,
        val accepted: Int,
    )

    private data class RunReconciliationStats(
        val jobsInspected: Int,
        val runsInspected: Int,
        val actionsInspected: Int,
        val accepted: Int,
    )

    internal data class RecoveryTickStats(
        val generation: Long = 0,
        val queueItemsInspected: Int = 0,
        val jobsInspected: Int = 0,
        val runsInspected: Int = 0,
        val actionsInspected: Int = 0,
        val accepted: Int = 0,
        val roundCompleted: Boolean = false,
        val requestCleared: Boolean = false,
    ) {
        companion object {
            fun idle(): RecoveryTickStats = RecoveryTickStats()
        }
    }

    companion object {
        private val RECURRENCE = Duration.ofMinutes(1)
        private const val MAX_QUEUE_ITEMS_PER_EXECUTION = 512
        private const val MAX_JOBS_PER_EXECUTION = 256
        private const val MAX_RUNS_PER_EXECUTION = 1024
        private const val MAX_ACTIONS_PER_EXECUTION = 1024
        private val LOGGER = Logger.getLogger(CnbBuildMetadataReconciliationWork::class.java.name)
    }
}

/** Atomic generation handshake between bounded dispatcher overflow and the periodic scanner. */
internal class CnbBuildMetadataRecoveryGate(
    startupRecovery: Boolean = true,
) {
    private val requestedGeneration = AtomicLong(if (startupRecovery) 1 else 0)
    private val completedGeneration = AtomicLong()

    fun request(): Long = requestedGeneration.incrementAndGet()

    fun pendingGeneration(): Long? {
        val requested = requestedGeneration.get()
        return requested.takeIf { it > completedGeneration.get() }
    }

    fun completeIfUnchanged(generation: Long): Boolean {
        if (requestedGeneration.get() != generation) return false
        while (true) {
            val completed = completedGeneration.get()
            if (completed >= generation) break
            if (completedGeneration.compareAndSet(completed, generation)) break
        }
        return requestedGeneration.get() == generation
    }
}

internal val CNB_BUILD_METADATA_RECOVERY_REQUESTS = CnbBuildMetadataRecoveryGate(startupRecovery = true)

/**
 * Weak, ordered index of queue items carrying durable CNB Actions. Queue listeners populate the
 * index while Jenkins loads and transitions its queue, so recovery can retain a lazy generation
 * cursor without copying or rescanning the controller's entire queue on every tick.
 */
internal class CnbBuildMetadataQueueRecoveryIndex {
    private val items = ConcurrentSkipListMap<Long, WeakReference<Queue.Item>>()

    fun observe(item: Queue.Item) {
        if (item.hasPendingMetadata()) {
            items[item.id] = WeakReference(item)
        } else {
            items.remove(item.id)
        }
    }

    fun remove(item: Queue.Item) {
        items.remove(item.id)
    }

    /** Captures only the generation's upper key; entries themselves are consumed lazily. */
    fun snapshotIterator(): Iterator<Queue.Item> {
        val upperBound = items.lastEntry()?.key ?: return emptyList<Queue.Item>().iterator()
        return object : Iterator<Queue.Item> {
            private var cursor = 0L
            private var cursorInitialized = false
            private var prepared = false
            private var nextItem: Queue.Item? = null

            override fun hasNext(): Boolean {
                prepare()
                return nextItem != null
            }

            override fun next(): Queue.Item {
                prepare()
                val result = nextItem ?: throw NoSuchElementException()
                prepared = false
                nextItem = null
                return result
            }

            private fun prepare() {
                if (prepared) return
                prepared = true
                while (true) {
                    val entry = if (cursorInitialized) items.higherEntry(cursor) else items.firstEntry()
                    if (entry == null || entry.key > upperBound) return
                    cursor = entry.key
                    cursorInitialized = true
                    val item = entry.value.get()
                    val pending = item?.hasPendingMetadata() == true
                    if (item == null || !pending) {
                        items.remove(entry.key, entry.value)
                        continue
                    }
                    nextItem = item
                    return
                }
            }
        }
    }

    internal fun size(): Int = items.size

    private fun Queue.Item.hasPendingMetadata(): Boolean =
        getActions(CnbBuildMetadataAction::class.java).any(CnbBuildMetadataAction::isPending)
}

internal val CNB_BUILD_METADATA_QUEUE_RECOVERY_INDEX = CnbBuildMetadataQueueRecoveryIndex()
