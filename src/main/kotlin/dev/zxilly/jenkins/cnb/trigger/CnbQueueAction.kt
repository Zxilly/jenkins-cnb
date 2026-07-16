package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.security.CnbGitObjectId
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import hudson.model.Action
import hudson.model.InvisibleAction
import hudson.model.Job
import hudson.model.Queue
import hudson.model.Result
import jenkins.model.CauseOfInterruption
import java.io.Serializable
import java.util.logging.Level
import java.util.logging.Logger

/** Stable queue identity used to prevent Jenkins from folding unrelated CNB revisions together. */
data class CnbQueueIdentity(
    val serverId: String,
    val repositoryPath: String,
    val ref: String,
    val sha: String,
    val targetSha: String? = null,
) : Serializable {
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
            if (action is CnbQueueAction && action.identity == identity) return false
        }
        return true
    }

    internal fun isSupersededBy(incoming: CnbQueueIdentity): Boolean =
        identity.serverId == incoming.serverId &&
            identity.repositoryPath == incoming.repositoryPath &&
            identity.ref == incoming.ref &&
            (identity.sha != incoming.sha || identity.targetSha != incoming.targetSha)

    companion object {
        private const val serialVersionUID = 1L
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

/** Reads the durable queue/run action stream while the Jenkins queue lock serializes deliveries. */
internal object CnbPullRequestRevisionHistory {
    fun hasSameRevisionAsLastDelivery(
        queue: Queue,
        job: Job<*, *>,
        incoming: CnbQueueIdentity,
    ): Boolean {
        if (!isPullRequestRef(incoming.ref)) return false
        var latestQueueId = Long.MIN_VALUE
        var latest: CnbQueueIdentity? = null
        for (item in queue.items) {
            if (item.task != job) continue
            val identity = item.getAction(CnbQueueAction::class.java)?.identity ?: continue
            if (!samePullRequest(identity, incoming) || item.id < latestQueueId) continue
            latestQueueId = item.id
            latest = identity
        }
        for (run in job.builds) {
            val identity = run.getAction(CnbQueueAction::class.java)?.identity ?: continue
            if (!samePullRequest(identity, incoming) || run.queueId <= latestQueueId) continue
            latestQueueId = run.queueId
            latest = identity
        }
        return latest?.sha == incoming.sha
    }

    private fun samePullRequest(
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
}

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
