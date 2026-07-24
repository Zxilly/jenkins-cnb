package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.security.CnbGitObjectId
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import hudson.Extension
import hudson.model.Action
import hudson.model.InvisibleAction
import hudson.model.Job
import hudson.model.Queue
import hudson.model.Result
import hudson.model.queue.QueueListener
import jenkins.model.CauseOfInterruption
import java.io.Serializable
import java.util.WeakHashMap
import java.util.logging.Level
import java.util.logging.Logger

/** Stable queue identity used to prevent Jenkins from folding unrelated CNB revisions together. */
data class CnbQueueIdentity(
    val serverId: String,
    val repositoryPath: String,
    val ref: String,
    val sha: String,
    val targetSha: String? = null,
    val refGeneration: Long = 0L,
) : Serializable {
    init {
        require(refGeneration >= 0L) { "ref generation must not be negative" }
    }

    companion object {
        private const val serialVersionUID = 1L

        fun from(delivery: CnbWebhookDelivery): CnbQueueIdentity? {
            val payload = delivery.payload
            val pullRequest = payload.pullRequest
            val repositoryPath = payload.repository.slug
            val ref =
                pullRequest?.let { "refs/pull/${it.number}/head" }
                    ?: if (payload.ref.tag || payload.event == CnbWebhookEvent.TAG_PUSH) {
                        "refs/tags/${payload.ref.name}"
                    } else {
                        "refs/heads/${payload.ref.name}"
                    }
            val sha = pullRequest?.sourceSha ?: effectiveObjectId(payload.ref.commit, payload.ref.sha, payload.ref.before)
            if (repositoryPath.isEmpty() || ref.isEmpty() || !CnbGitObjectId.isPresent(sha)) return null
            val targetSha =
                pullRequest
                    ?.targetSha
                    ?.takeIf(CnbGitObjectId::isPresent)
                    ?.let(CnbGitObjectId::canonical)
            return CnbQueueIdentity(
                delivery.serverId,
                repositoryPath,
                ref,
                CnbGitObjectId.canonical(sha),
                targetSha,
            )
        }

        private fun effectiveObjectId(
            commit: String,
            sha: String,
            before: String,
        ): String = listOf(commit, sha, before).firstOrNull(CnbGitObjectId::isPresent).orEmpty()
    }
}

class CnbQueueAction(
    val identity: CnbQueueIdentity,
    val deliveryId: String? = null,
    val deliveryScope: String? = null,
) : InvisibleAction(),
    Queue.QueueAction,
    Serializable {
    constructor(
        serverId: String,
        repositoryPath: String,
        ref: String,
        sha: String,
    ) : this(CnbQueueIdentity(serverId, repositoryPath, ref, sha))

    val serverId: String
        get() = identity.serverId
    val repositoryPath: String
        get() = identity.repositoryPath
    val ref: String
        get() = identity.ref
    val sha: String
        get() = identity.sha
    val targetSha: String?
        get() = identity.targetSha

    override fun shouldSchedule(actions: List<Action>): Boolean {
        for (action in actions) {
            if (action !is CnbQueueAction) continue
            if (sameDeliveryReceipt(action, identity, deliveryId, deliveryScope)) return false
            if (action.identity == identity && (deliveryId == null || action.deliveryId == null)) return false
        }
        return true
    }

    internal fun isSupersededBy(incoming: CnbQueueIdentity): Boolean =
        identity.serverId == incoming.serverId &&
            identity.repositoryPath == incoming.repositoryPath &&
            identity.ref == incoming.ref &&
            (identity.sha != incoming.sha ||
                identity.targetSha != incoming.targetSha ||
                identity.refGeneration != incoming.refGeneration)

    companion object {
        private const val serialVersionUID = 1L
    }
}

internal object CnbDeliveryScope {
    const val DIRECT = "direct"

    fun pullRequestTarget(identity: CnbQueueIdentity): String = "pull-request-target:${identity.ref}"
}

private fun sameDeliveryReceipt(
    action: CnbQueueAction,
    incoming: CnbQueueIdentity,
    deliveryId: String?,
    deliveryScope: String?,
): Boolean =
    deliveryId != null &&
        action.deliveryId == deliveryId &&
        action.serverId == incoming.serverId &&
        action.repositoryPath == incoming.repositoryPath &&
        (action.deliveryScope ?: CnbDeliveryScope.DIRECT) == (deliveryScope ?: CnbDeliveryScope.DIRECT)

@Extension
class CnbQueueTransitionListener : QueueListener() {
    override fun onLeft(item: Queue.LeftItem) {
        if (item.getAction(CnbQueueAction::class.java) == null) return
        val job = item.task as? Job<*, *> ?: return
        CnbQueueTransitionWatermark.advance(job)
    }
}

internal object CnbQueueTransitionWatermark {
    private val generations = WeakHashMap<Job<*, *>, Long>()

    @Synchronized
    fun current(job: Job<*, *>): Long = generations[job] ?: 0L

    @Synchronized
    fun advance(job: Job<*, *>) {
        generations[job] = current(job) + 1L
    }
}

/** Cancels queued, superseded revisions for one job/ref; running builds are intentionally absent. */
internal object CnbPendingBuilds {
    fun cancelSuperseded(
        queue: Queue,
        task: Queue.Task,
        incoming: CnbQueueIdentity,
    ): Int {
        var cancelled = 0
        for (item in queue.items) {
            if (item.task != task) continue
            val action = item.getAction(CnbQueueAction::class.java) ?: continue
            if (action.isSupersededBy(incoming) && queue.cancel(item)) cancelled++
        }
        return cancelled
    }
}

/** Stops only an older running revision of the same pull request in the supplied job. */
internal object CnbRunningBuilds {
    fun cancelSuperseded(
        job: Job<*, *>,
        incoming: CnbQueueIdentity,
    ): Int {
        if (!isPullRequestRef(incoming.ref)) return 0
        var cancelled = 0
        for (run in job.builds) {
            if (!run.isBuilding) continue
            val previous = run.getAction(CnbQueueAction::class.java)?.identity ?: continue
            if (!isSupersededPullRequestRevision(previous, incoming)) continue
            val executor = run.executor ?: continue
            try {
                executor.interrupt(Result.ABORTED, CnbSupersededByPullRequestUpdate(incoming, previous.sha))
                cancelled++
                LOGGER.log(
                    Level.INFO,
                    "Stopped {0} after a verified CNB pull request revision update for {1}",
                    arrayOf(run.fullDisplayName, incoming.ref),
                )
            } catch (failure: RuntimeException) {
                LOGGER.log(Level.WARNING, "Could not stop a superseded CNB build for ${job.fullName}", failure)
            }
        }
        return cancelled
    }

    internal fun isSupersededPullRequestRevision(
        previous: CnbQueueIdentity,
        incoming: CnbQueueIdentity,
    ): Boolean =
        isPullRequestRef(incoming.ref) &&
            isPullRequestRef(previous.ref) &&
            previous.serverId == incoming.serverId &&
            previous.repositoryPath == incoming.repositoryPath &&
            previous.ref == incoming.ref &&
            (previous.sha != incoming.sha || previous.targetSha != incoming.targetSha)

    private fun isPullRequestRef(value: String): Boolean {
        if (!value.startsWith("refs/pull/") || !value.endsWith("/head")) return false
        val number = value.removePrefix("refs/pull/").removeSuffix("/head")
        return number.isNotEmpty() && number.all { it in '0'..'9' }
    }

    private val LOGGER = Logger.getLogger(CnbRunningBuilds::class.java.name)
}

/**
 * Durable per-job receipt history shared by webhook and polling delivery paths.
 *
 * Run loading stays outside Jenkins' global queue lock. A first locked snapshot retains recent
 * non-cancelled LeftItems as transition receipts and records an onLeft generation per job.
 * A second lock validates that watermark before combining the Run summary with live queue items.
 */
internal object CnbDeliveryHistory {
    internal data class Query(
        val job: Job<*, *>,
        val incoming: CnbQueueIdentity,
        val deliveryId: String?,
        val deliveryScope: String?,
        val deduplicateRevision: Boolean,
        val onlyIfNewPullRequestCommits: Boolean = false,
    )

    internal data class Entry(
        val queueId: Long,
        val action: CnbQueueAction,
    )

    internal data class RunSummary(
        val exactReceipt: Boolean = false,
        val latestRef: Entry? = null,
        val latestPullRequest: Entry? = null,
    ) {
        companion object {
            val EMPTY = RunSummary()
        }
    }

    internal data class StableHistory(
        val query: Query,
        val runs: RunSummary,
        val transitions: List<Entry>,
    )

    private data class TransitionSnapshot(
        val watermark: Long = Long.MIN_VALUE,
        val entries: List<Entry> = emptyList(),
    )

    internal fun loadRunHistory(query: Query): RunSummary {
        var exactReceipt = false
        var latestRef: Entry? = null
        var latestPullRequest: Entry? = null
        val trackPullRequest = query.onlyIfNewPullRequestCommits && isPullRequestRef(query.incoming.ref)
        for (run in query.job.builds) {
            val action = run.getAction(CnbQueueAction::class.java) ?: continue
            if (!exactReceipt && sameDeliveryReceipt(action, query.incoming, query.deliveryId, query.deliveryScope)) {
                exactReceipt = true
            }
            if (query.deduplicateRevision && sameRef(action.identity, query.incoming)) {
                latestRef = newer(latestRef, Entry(run.queueId, action))
            }
            if (trackPullRequest && sameRef(action.identity, query.incoming)) {
                latestPullRequest = newer(latestPullRequest, Entry(run.queueId, action))
            }
        }
        return RunSummary(exactReceipt, latestRef, latestPullRequest)
    }

    internal fun <T> withStableQueue(
        queries: List<Query>,
        loadRunHistory: (Query) -> RunSummary = ::loadRunHistory,
        action: (Queue, List<StableHistory>) -> T,
    ): T {
        require(queries.isNotEmpty()) { "At least one CNB delivery history query is required" }
        val jobs = LinkedHashSet<Job<*, *>>(queries.size)
        for (query in queries) jobs.add(query.job)
        repeat(MAX_STABILITY_ATTEMPTS) {
            lateinit var transitions: Map<Job<*, *>, TransitionSnapshot>
            Queue.withLock {
                transitions = captureTransitions(Queue.getInstance(), jobs)
            }

            val histories = ArrayList<StableHistory>(queries.size)
            for (query in queries) {
                histories +=
                    StableHistory(
                        query,
                        loadRunHistory(query),
                        transitions.getValue(query.job).entries,
                    )
            }

            var stable = false
            var result: T? = null
            Queue.withLock {
                val queue = Queue.getInstance()
                val current = captureTransitions(queue, jobs)
                if (jobs.all { job -> current.getValue(job).watermark == transitions.getValue(job).watermark }) {
                    result = action(queue, histories)
                    stable = true
                }
            }
            if (stable) {
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
        }
        throw CnbDeliveryHistoryUnstableException(MAX_STABILITY_ATTEMPTS)
    }

    internal fun contains(
        queue: Queue,
        history: StableHistory,
    ): Boolean {
        val query = history.query
        if (history.runs.exactReceipt) return true
        var latestRef = history.runs.latestRef
        var latestPullRequest = history.runs.latestPullRequest

        fun observe(entry: Entry): Boolean {
            val action = entry.action
            if (sameDeliveryReceipt(action, query.incoming, query.deliveryId, query.deliveryScope)) return true
            if (query.deduplicateRevision && sameRef(action.identity, query.incoming)) {
                latestRef = newer(latestRef, entry)
            }
            if (query.onlyIfNewPullRequestCommits &&
                isPullRequestRef(query.incoming.ref) &&
                sameRef(action.identity, query.incoming)
            ) {
                latestPullRequest = newer(latestPullRequest, entry)
            }
            return false
        }

        for (entry in history.transitions) {
            if (observe(entry)) return true
        }
        for (item in queue.items) {
            if (item.task != query.job) continue
            val queued = item.getAction(CnbQueueAction::class.java) ?: continue
            if (observe(Entry(item.id, queued))) return true
        }
        if (query.deduplicateRevision && latestRef?.action?.identity == query.incoming) return true
        return query.onlyIfNewPullRequestCommits && latestPullRequest?.action?.sha == query.incoming.sha
    }

    private fun captureTransitions(
        queue: Queue,
        jobs: Set<Job<*, *>>,
    ): Map<Job<*, *>, TransitionSnapshot> {
        val entries = jobs.associateWithTo(linkedMapOf()) { ArrayList<Entry>() }
        for (item in queue.leftItems) {
            val job = item.task as? Job<*, *> ?: continue
            if (job !in jobs) continue
            if (item.isCancelled) continue
            val action = item.getAction(CnbQueueAction::class.java) ?: continue
            entries.getValue(job) += Entry(item.id, action)
        }
        return jobs.associateWithTo(linkedMapOf()) { job ->
            TransitionSnapshot(CnbQueueTransitionWatermark.current(job), entries.getValue(job))
        }
    }

    private fun newer(
        previous: Entry?,
        candidate: Entry,
    ): Entry = if (previous == null || candidate.queueId > previous.queueId) candidate else previous

    private fun sameRef(
        previous: CnbQueueIdentity,
        incoming: CnbQueueIdentity,
    ): Boolean =
        previous.serverId == incoming.serverId &&
            previous.repositoryPath == incoming.repositoryPath &&
            previous.ref == incoming.ref

    private fun isPullRequestRef(value: String): Boolean {
        if (!value.startsWith("refs/pull/") || !value.endsWith("/head")) return false
        val number = value.removePrefix("refs/pull/").removeSuffix("/head")
        return number.isNotEmpty() && number.all { it in '0'..'9' }
    }

    private const val MAX_STABILITY_ATTEMPTS = 8
}

internal class CnbDeliveryHistoryUnstableException(
    val attempts: Int,
) : RuntimeException("CNB queue kept changing during $attempts delivery history scans")

/** Persisted on an aborted run so the exact, non-secret supersession scope remains auditable. */
class CnbSupersededByPullRequestUpdate(
    incoming: CnbQueueIdentity,
    previousSha: String,
) : CauseOfInterruption(),
    Serializable {
    val serverId: String = safeAuditValue(incoming.serverId, MAX_SERVER_ID_LENGTH)
    val repositoryPath: String = safeAuditValue(incoming.repositoryPath, MAX_REPOSITORY_PATH_LENGTH)
    val pullRequestRef: String = safeAuditValue(incoming.ref, MAX_REF_LENGTH)
    val oldRevision: String = safeRevision(previousSha)
    val newRevision: String = safeRevision(incoming.sha)

    override fun getShortDescription(): String =
        "Superseded by CNB pull request update for $repositoryPath at $pullRequestRef ($oldRevision -> $newRevision)"

    companion object {
        private const val serialVersionUID = 1L
        private const val MAX_SERVER_ID_LENGTH = 64
        private const val MAX_REPOSITORY_PATH_LENGTH = 1024
        private const val MAX_REF_LENGTH = 128
        private const val MAX_REVISION_LENGTH = 12

        private fun safeRevision(value: String): String = safeAuditValue(value, MAX_REVISION_LENGTH)

        private fun safeAuditValue(
            value: String,
            maximumLength: Int,
        ): String =
            value
                .asSequence()
                .filter { character -> character.code >= 0x20 && character.code != 0x7f }
                .take(maximumLength)
                .joinToString("")
    }
}
