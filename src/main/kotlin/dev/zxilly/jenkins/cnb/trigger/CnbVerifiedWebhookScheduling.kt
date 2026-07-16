package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.CnbClientFactory
import dev.zxilly.jenkins.cnb.scm.CnbPullRequestSCMHead
import dev.zxilly.jenkins.cnb.scm.CnbPullRequestSCMRevision
import dev.zxilly.jenkins.cnb.scm.CnbSCMSource
import dev.zxilly.jenkins.cnb.scm.CnbSCMSourceContext
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import hudson.model.Action
import hudson.model.CauseAction
import hudson.model.Job
import hudson.model.Queue
import hudson.model.TaskListener
import jenkins.branch.Branch
import jenkins.branch.BranchProjectFactory
import jenkins.branch.MultiBranchProject
import jenkins.model.ParameterizedJobMixIn
import jenkins.scm.api.SCMHeadObserver
import jenkins.scm.api.SCMRevisionAction
import jenkins.scm.api.SCMSourceOwner
import jenkins.scm.api.SCMSourceOwners
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.util.logging.Level
import java.util.logging.Logger

internal data class CnbClassicTriggerCandidate(
    val job: Job<*, *>,
    val trigger: CnbPushTrigger,
)

/** A completely verified queue effect. No network operation is permitted after this is created. */
internal data class CnbVerifiedQueueCandidate(
    val job: Job<*, *>,
    val delivery: CnbWebhookDelivery,
    val identity: CnbQueueIdentity,
    val cancelPending: Boolean = false,
    val cancelRunning: Boolean = false,
    val revisionAction: SCMRevisionAction? = null,
    val onlyIfNewPullRequestCommits: Boolean = false,
)

/**
 * Resolves every untrusted webhook field before the dispatcher performs its first queue mutation.
 *
 * Classic jobs for one delivery deliberately share one CNB snapshot, while multibranch sources
 * use their own item-scoped credentials and re-run normal source discovery for the advertised PR.
 */
internal object CnbVerifiedWebhookPlanner {
    fun classic(
        delivery: CnbWebhookDelivery,
        candidates: List<CnbClassicTriggerCandidate>,
        openClient: () -> CnbClient = { CnbClientFactory.create(delivery.serverId) },
    ): List<CnbVerifiedQueueCandidate> {
        if (candidates.isEmpty()) return emptyList()
        check(
            candidates.all { candidate ->
                candidate.trigger.serverId == delivery.serverId &&
                    candidate.trigger.repositoryPath == delivery.payload.repository.slug
            },
        ) { "CNB classic trigger verification scopes diverged" }

        val requirements =
            CnbLiveDeliveryRequirements(
                labels = candidates.any { it.trigger.labelPolicy().configured },
                comment = delivery.payload.event == CnbWebhookEvent.PULL_REQUEST_COMMENT,
                commitMessage = candidates.any { it.trigger.isCiSkip() },
            )
        val snapshot =
            try {
                openClient().use { client -> resolve(delivery, requirements, client) }
            } catch (failure: CnbApiException) {
                if (failure.statusCode !in missingOrUnauthorizedStatusCodes) throw failure
                return emptyList()
            }
        if (!snapshot.revisionMatches) return emptyList()
        // An authorization/not-found response for data required by any policy invalidates this
        // shared verification batch. This avoids selectively scheduling against an incomplete
        // repository snapshot.
        if (requirements.labels && snapshot.labels == null) return emptyList()
        if (requirements.commitMessage && snapshot.commitMessage == null) return emptyList()
        if (requirements.comment &&
            (!snapshot.commentVerified || snapshot.commentBody == null || snapshot.actorAccessLevels == null)
        ) {
            return emptyList()
        }

        val identity = CnbQueueIdentity.from(delivery) ?: return emptyList()
        val verified = ArrayList<CnbVerifiedQueueCandidate>(candidates.size)
        for (candidate in candidates) {
            val trigger = candidate.trigger
            if (trigger.matchesLive(delivery, snapshot)) {
                verified +=
                    CnbVerifiedQueueCandidate(
                        job = candidate.job,
                        delivery = delivery,
                        identity = identity,
                        cancelPending = trigger.isCancelPendingBuildsOnUpdate(),
                        cancelRunning = trigger.shouldCancelRunningBuildsFor(delivery),
                        onlyIfNewPullRequestCommits =
                            trigger.isTriggerOnlyIfNewCommitsPushed() && delivery.payload.event.pullRequestEvent,
                    )
            }
        }
        return verified
    }

    fun pullRequestComment(
        delivery: CnbWebhookDelivery,
        openClient: (CnbSCMSource, SCMSourceOwner) -> CnbClient = { source, owner ->
            CnbClientFactory.create(source.serverId, source.getApiCredentialsId(), owner)
        },
    ): List<CnbVerifiedQueueCandidate> {
        if (delivery.payload.event != CnbWebhookEvent.PULL_REQUEST_COMMENT) return emptyList()
        val advertised = delivery.payload.pullRequest ?: return emptyList()
        val identity = CnbQueueIdentity.from(delivery) ?: return emptyList()
        val event = CnbSCMHeadEvent(delivery)
        val result = ArrayList<CnbVerifiedQueueCandidate>()

        for (owner in SCMSourceOwners.all()) {
            val project = owner as? MultiBranchProject<*, *> ?: continue
            for (candidate in owner.scmSources) {
                val source = candidate as? CnbSCMSource ?: continue
                if (source.owner !== owner || owner.getSCMSource(source.id) !== source) continue
                if (source.serverId != delivery.serverId || source.repositoryPath != delivery.payload.repository.slug) continue
                val context = CnbSCMSourceContext(null, SCMHeadObserver.none()).withTraits(source.traits)
                val policy = context.pullRequestCommentPolicy ?: continue

                val snapshot =
                    try {
                        openClient(source, owner).use { client ->
                            resolve(
                                delivery,
                                CnbLiveDeliveryRequirements(comment = true),
                                client,
                            )
                        }
                    } catch (failure: CnbApiException) {
                        if (failure.statusCode !in missingOrUnauthorizedStatusCodes) throw failure
                        continue
                    }
                val current = snapshot.pullRequest ?: continue
                if (!snapshot.revisionMatches ||
                    !snapshot.commentVerified ||
                    !policy.matches(snapshot.commentBody, snapshot.actorAccessLevels)
                ) {
                    continue
                }

                val discovered = SCMHeadObserver.collect()
                try {
                    source.fetch(discovered, event, TaskListener.NULL)
                } catch (failure: CnbApiException) {
                    if (failure.statusCode !in missingOrUnauthorizedStatusCodes) throw failure
                    continue
                }
                for ((head, revision) in discovered.result()) {
                    val pullRequestHead = head as? CnbPullRequestSCMHead ?: continue
                    val pullRequestRevision = revision as? CnbPullRequestSCMRevision ?: continue
                    if (pullRequestHead.number != advertised.number ||
                        !pullRequestRevision.headHash.equals(current.sourceSha, ignoreCase = true)
                    ) {
                        continue
                    }
                    val job = findChildJob(project, source, pullRequestHead) ?: continue
                    if (!job.isBuildable || job !is Queue.Task) continue
                    result +=
                        CnbVerifiedQueueCandidate(
                            job,
                            delivery,
                            identity,
                            revisionAction = SCMRevisionAction(source, pullRequestRevision),
                        )
                }
            }
        }
        return result
    }

    private fun resolve(
        delivery: CnbWebhookDelivery,
        requirements: CnbLiveDeliveryRequirements,
        client: CnbClient,
    ): CnbLiveDeliverySnapshot =
        CnbLiveDeliveryResolver.resolve(
            delivery = delivery,
            requirements = requirements,
            getBranch = client::getBranch,
            getTag = client::getTag,
            getPullRequest = client::getPullRequest,
            listLabels = client::listPullLabels,
            getComment = client::getPullComment,
            listMemberAccess = client::listMemberAccessLevels,
            getCommit = client::getCommit,
        )

    private fun findChildJob(
        project: MultiBranchProject<*, *>,
        source: CnbSCMSource,
        head: CnbPullRequestSCMHead,
    ): Job<*, *>? {
        for (item in project.items) {
            val job = item as? Job<*, *> ?: continue
            val branch = branchFor(project, job) ?: continue
            val childHead = branch.head as? CnbPullRequestSCMHead ?: continue
            if (branch.sourceId == source.id &&
                childHead.name == head.name &&
                childHead.number == head.number &&
                childHead.checkoutStrategy == head.checkoutStrategy
            ) {
                return job
            }
        }
        return null
    }

    private fun branchFor(
        project: MultiBranchProject<*, *>,
        job: Job<*, *>,
    ): Branch? =
        try {
            BRANCH_LOOKUP.invoke(project.projectFactory, job) as? Branch
        } catch (failure: InvocationTargetException) {
            throw IOException("Could not resolve the CNB multibranch child owner", failure.targetException)
        } catch (failure: ReflectiveOperationException) {
            throw IOException("Could not resolve the CNB multibranch child owner", failure)
        }

    private val BRANCH_LOOKUP = BranchProjectFactory::class.java.getMethod("getBranch", Job::class.java)
    private val missingOrUnauthorizedStatusCodes = setOf(401, 403, 404)
}

internal data class CnbStagedEffect<T>(
    val value: T,
    val created: Boolean,
)

internal data class CnbStagedEntry<I, O>(
    val input: I,
    val effect: CnbStagedEffect<O>,
)

/** Stages an all-or-nothing set of effects and rolls newly created effects back in reverse order. */
internal fun <I, O> stageAtomically(
    inputs: List<I>,
    stage: (I) -> CnbStagedEffect<O>,
    rollback: (O) -> Unit,
): List<CnbStagedEntry<I, O>> {
    val staged = ArrayList<CnbStagedEntry<I, O>>(inputs.size)
    try {
        for (input in inputs) staged += CnbStagedEntry(input, stage(input))
        return staged
    } catch (failure: Throwable) {
        for (index in staged.lastIndex downTo 0) {
            val entry = staged[index]
            if (!entry.effect.created) continue
            try {
                rollback(entry.effect.value)
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
        }
        throw failure
    }
}

/** Commits all verified Jenkins queue entries under the queue lock, or commits none of them. */
internal object CnbAtomicQueueScheduler {
    fun schedule(candidates: List<CnbVerifiedQueueCandidate>): Int {
        val distinct = ArrayList<CnbVerifiedQueueCandidate>(candidates.size)
        val seen = HashSet<Pair<String, CnbQueueIdentity>>(candidates.size)
        for (candidate in candidates) {
            if (seen.add(candidate.job.fullName to candidate.identity)) distinct += candidate
        }
        if (distinct.isEmpty()) return 0

        var newlyQueued: List<CnbVerifiedQueueCandidate> = emptyList()
        Queue.withLock {
            val queue = Queue.getInstance()
            val eligible = ArrayList<CnbVerifiedQueueCandidate>(distinct.size)
            for (candidate in distinct) {
                if (!candidate.job.isBuildable || candidate.job !is Queue.Task) continue
                if (candidate.onlyIfNewPullRequestCommits &&
                    CnbPullRequestRevisionHistory.hasSameRevisionAsLastDelivery(queue, candidate.job, candidate.identity)
                ) {
                    continue
                }
                eligible += candidate
            }
            val existingIds = HashSet<Long>(queue.items.size)
            for (item in queue.items) existingIds += item.id
            val staged =
                stageAtomically(
                    eligible,
                    stage = { candidate ->
                        val actions =
                            arrayListOf<Action>(
                                CauseAction(CnbPushCause.from(candidate.delivery)),
                                CnbQueueAction(candidate.identity),
                            ).apply {
                                candidate.revisionAction?.let(::add)
                            }
                        val item =
                            ParameterizedJobMixIn.scheduleBuild2(
                                candidate.job,
                                0,
                                *actions.toTypedArray(),
                            ) ?: throw IOException("Jenkins refused a verified CNB queue entry")
                        CnbStagedEffect(item, item.id !in existingIds)
                    },
                    rollback = { item ->
                        if (!queue.cancel(item)) throw IOException("Could not roll back a CNB queue entry")
                    },
                )
            val committed = ArrayList<CnbVerifiedQueueCandidate>(staged.size)
            for (entry in staged) {
                if (entry.effect.created) committed += entry.input
            }
            newlyQueued = committed

            for (candidate in committed) {
                if (!candidate.cancelPending) continue
                try {
                    CnbPendingBuilds.cancelSuperseded(
                        queue,
                        candidate.job as Queue.Task,
                        candidate.identity,
                    )
                } catch (failure: RuntimeException) {
                    LOGGER.log(
                        Level.WARNING,
                        "Could not cancel a superseded CNB queue entry for ${candidate.job.fullName}",
                        failure,
                    )
                }
            }
        }

        for (candidate in newlyQueued) {
            if (!candidate.cancelRunning) continue
            try {
                CnbRunningBuilds.cancelSuperseded(candidate.job, candidate.identity)
            } catch (failure: RuntimeException) {
                // Queue commit has already succeeded. Supersession is best-effort and must not
                // turn the accepted delivery into a retry that could create another build.
                LOGGER.log(
                    Level.WARNING,
                    "Could not inspect superseded CNB builds for ${candidate.job.fullName}",
                    failure,
                )
            }
        }
        for (candidate in newlyQueued) {
            LOGGER.log(
                Level.FINE,
                "Scheduled {0} for CNB delivery {1}",
                arrayOf(candidate.job.fullName, candidate.delivery.payload.deliveryId),
            )
        }
        return newlyQueued.size
    }

    private val LOGGER = Logger.getLogger(CnbAtomicQueueScheduler::class.java.name)
}
