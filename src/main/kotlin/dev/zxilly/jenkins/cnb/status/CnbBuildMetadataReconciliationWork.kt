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
import java.io.IOException
import java.lang.ref.WeakReference
import java.time.Duration
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Bounded loss recovery for durable metadata Actions that could not enter the in-memory dispatcher.
 * One startup round is performed; after that, the task sleeps unless an overflow requests a new
 * generation. Lazy traversal with retained cancellation, Job, and Run cursors avoids materializing
 * controller history.
 */
@Extension
class CnbBuildMetadataReconciliationWork : AsyncPeriodicWork("CNB build metadata reconciliation") {
    private var roundGeneration: Long? = null
    private var queueIterator: Iterator<Queue.Item>? = null
    private var currentQueueItem: Queue.Item? = null
    private var currentQueueActions: Iterator<CnbBuildMetadataAction>? = null
    private var queueComplete = true
    private var cancelledIterator: Iterator<CnbCancelledBuildMetadataRecord>? = null
    private var cancelledComplete = true
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
        val cancelledStore = CnbCancelledBuildMetadataStores.current(jenkins)

        val stats =
            recoverOnce(
                recoveryRequests = CNB_BUILD_METADATA_RECOVERY_REQUESTS,
                queueItems = CNB_BUILD_METADATA_QUEUE_RECOVERY_INDEX::snapshotIterator,
                cancelledRecords = cancelledStore::snapshotIterator,
                jobs = { Items.allItems(jenkins, Job::class.java).iterator() },
                scheduleQueue = { item, action ->
                    CnbBuildMetadataDispatcher.schedule(item.task as? Item, action)
                },
                scheduleCancelled = { record ->
                    scheduleCancelledRecord(jenkins, cancelledStore, record)
                },
                scheduleRun = CnbBuildMetadataDispatcher::schedule,
            )
        if (stats.accepted > 0) {
            listener.logger.printf(
                "CNB metadata recovery generation %d requeued %d durable Action(s); inspected %d queue item(s), %d cancelled record(s), %d job(s), and %d run(s)%n",
                stats.generation,
                stats.accepted,
                stats.queueItemsInspected,
                stats.cancelledRecordsInspected,
                stats.jobsInspected,
                stats.runsInspected,
            )
        }
    }

    internal fun scheduleCancelledRecord(
        jenkins: Jenkins,
        store: CnbCancelledBuildMetadataStore,
        record: CnbCancelledBuildMetadataRecord,
        schedule: (Item?, CnbCancelledBuildMetadataRecord, CnbCancelledBuildMetadataStore) -> Boolean =
            CnbBuildMetadataDispatcher::schedulePersisted,
    ): Boolean {
        val resolution = store.resolveCredentialContext(record, jenkins)
        return when (resolution.state) {
            CnbCancelledBuildMetadataCredentialResolution.State.AVAILABLE -> {
                schedule(resolution.item, record, store)
            }

            CnbCancelledBuildMetadataCredentialResolution.State.MISSING -> {
                false
            }

            CnbCancelledBuildMetadataCredentialResolution.State.DELETED -> {
                discardUnresolvableRecord(store, record, "credential context was deleted or invalid")
                false
            }
        }
    }

    private fun discardUnresolvableRecord(
        store: CnbCancelledBuildMetadataStore,
        record: CnbCancelledBuildMetadataRecord,
        reason: String,
    ) {
        try {
            store.remove(record)
            LOGGER.warning("Discarded a pending CNB metadata report because its $reason")
        } catch (failure: IOException) {
            LOGGER.log(Level.WARNING, "Could not discard an unresolvable CNB metadata report", failure)
        }
    }

    @Synchronized
    internal fun recoverOnce(
        recoveryRequests: CnbBuildMetadataRecoveryGate,
        queueItems: () -> Iterator<Queue.Item>,
        cancelledRecords: () -> Iterator<CnbCancelledBuildMetadataRecord> = {
            emptyList<CnbCancelledBuildMetadataRecord>().iterator()
        },
        jobs: () -> Iterator<Job<*, *>>,
        maximumQueueItems: Int = MAX_QUEUE_ITEMS_PER_EXECUTION,
        maximumCancelledRecords: Int = MAX_CANCELLED_RECORDS_PER_EXECUTION,
        maximumJobs: Int = MAX_JOBS_PER_EXECUTION,
        maximumRuns: Int = MAX_RUNS_PER_EXECUTION,
        maximumActions: Int = MAX_ACTIONS_PER_EXECUTION,
        scheduleQueue: (Queue.Item, CnbBuildMetadataAction) -> Boolean,
        scheduleCancelled: (CnbCancelledBuildMetadataRecord) -> Boolean = { false },
        scheduleRun: (Run<*, *>, CnbBuildMetadataAction) -> Boolean,
    ): RecoveryTickStats {
        require(maximumQueueItems > 0) { "maximumQueueItems must be positive" }
        require(maximumCancelledRecords > 0) { "maximumCancelledRecords must be positive" }
        require(maximumJobs > 0) { "maximumJobs must be positive" }
        require(maximumRuns > 0) { "maximumRuns must be positive" }
        require(maximumActions > 0) { "maximumActions must be positive" }

        val generation = roundGeneration ?: recoveryRequests.pendingGeneration() ?: return RecoveryTickStats.idle()
        return try {
            if (roundGeneration == null) {
                beginRound(generation, queueItems(), cancelledRecords(), jobs())
            }
            val queueStats =
                if (queueComplete) {
                    ReconciliationStats(0, 0, 0)
                } else {
                    reconcileQueue(maximumQueueItems, maximumActions, scheduleQueue)
                }
            val remainingActions = maximumActions - queueStats.actionsInspected
            val cancelledStats =
                if (cancelledComplete || remainingActions == 0) {
                    ReconciliationStats(0, 0, 0)
                } else {
                    reconcileCancelled(maximumCancelledRecords, remainingActions, scheduleCancelled)
                }
            val remainingRunActions = remainingActions - cancelledStats.actionsInspected
            val runStats =
                if (remainingRunActions == 0) {
                    RunReconciliationStats(0, 0, 0, 0)
                } else {
                    reconcileRuns(maximumJobs, maximumRuns, remainingRunActions, scheduleRun)
                }
            val completed = queueComplete && cancelledComplete && jobsComplete
            val requestCleared = completed && recoveryRequests.completeIfUnchanged(generation)
            if (completed) resetRound()
            RecoveryTickStats(
                generation = generation,
                queueItemsInspected = queueStats.inspected,
                cancelledRecordsInspected = cancelledStats.inspected,
                jobsInspected = runStats.jobsInspected,
                runsInspected = runStats.runsInspected,
                actionsInspected = queueStats.actionsInspected + cancelledStats.actionsInspected + runStats.actionsInspected,
                accepted = queueStats.accepted + cancelledStats.accepted + runStats.accepted,
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
        cancelledRecords: Iterator<CnbCancelledBuildMetadataRecord>,
        jobs: Iterator<Job<*, *>>,
    ) {
        roundGeneration = generation
        queueIterator = queueItems
        currentQueueItem = null
        currentQueueActions = null
        queueComplete = !queueItems.hasNext()
        if (queueComplete) queueIterator = null
        cancelledIterator = cancelledRecords
        cancelledComplete = !cancelledRecords.hasNext()
        if (cancelledComplete) cancelledIterator = null
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

    private fun reconcileCancelled(
        maximumRecords: Int,
        maximumActions: Int,
        schedule: (CnbCancelledBuildMetadataRecord) -> Boolean,
    ): ReconciliationStats {
        val iterator = cancelledIterator ?: return ReconciliationStats(0, 0, 0)
        var inspected = 0
        var accepted = 0
        while (inspected < maximumRecords && inspected < maximumActions && iterator.hasNext()) {
            val record = iterator.next()
            inspected++
            if (schedule(record)) accepted++
        }
        if (!iterator.hasNext()) {
            cancelledComplete = true
            cancelledIterator = null
        }
        return ReconciliationStats(inspected, inspected, accepted)
    }

    private fun reconcileRuns(
        maximumJobs: Int,
        maximumRuns: Int,
        maximumActions: Int,
        scheduleRun: (Run<*, *>, CnbBuildMetadataAction) -> Boolean,
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
                if (action.isPending() && scheduleRun(actionOwner, action)) accepted++
                continue
            }
            currentRunActionOwner = null
            currentRunActions = null

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
            if (runsInspected >= maximumRuns) break
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
        cancelledIterator = null
        cancelledComplete = true
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
        val cancelledRecordsInspected: Int = 0,
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
        private const val MAX_CANCELLED_RECORDS_PER_EXECUTION = 512
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
