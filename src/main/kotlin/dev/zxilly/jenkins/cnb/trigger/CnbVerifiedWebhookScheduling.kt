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
import hudson.plugins.git.RevisionParameterAction
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
    val checkoutAction: RevisionParameterAction? = null,
    val onlyIfNewPullRequestCommits: Boolean = false,
    val deliveryScope: String = CnbDeliveryScope.DIRECT,
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

        val directCandidates = ArrayList<CnbClassicTriggerCandidate>(candidates.size)
        val targetPushCandidates = ArrayList<CnbClassicTriggerCandidate>(candidates.size)
        for (candidate in candidates) {
            if (candidate.trigger.matches(delivery)) directCandidates.add(candidate)
            if (candidate.trigger.expandsOpenPullRequestsFor(delivery)) targetPushCandidates.add(candidate)
        }
        if (directCandidates.isEmpty() && targetPushCandidates.isEmpty()) return emptyList()

        val directRequirements = requirementsFor(delivery, directCandidates)
        val targetPushRequirements = requirementsForTargetPush(targetPushCandidates)
        return try {
            openClient().use { client ->
                val verified = ArrayList<CnbVerifiedQueueCandidate>(candidates.size)
                if (directCandidates.isNotEmpty()) {
                    val snapshot = resolve(delivery, directRequirements, client)
                    if (snapshot.revisionMatches) {
                        val identity = CnbQueueIdentity.from(delivery)
                        if (identity != null) {
                            val liveCandidates = ArrayList<CnbClassicTriggerCandidate>(directCandidates.size)
                            for (candidate in directCandidates) {
                                if (candidate.trigger.matchesLive(delivery, snapshot)) liveCandidates += candidate
                            }
                            val sourceCloneUrl =
                                if (liveCandidates.any { CnbClassicGitRevisionAction.supports(it.job) }) {
                                    directSourceCloneUrl(delivery, snapshot, client)
                                } else {
                                    null
                                }
                            for (candidate in liveCandidates) {
                                val trigger = candidate.trigger
                                val requiresCheckout = CnbClassicGitRevisionAction.supports(candidate.job)
                                val checkout =
                                    sourceCloneUrl?.let { url ->
                                        CnbClassicGitRevisionAction.create(candidate.job, identity.sha, url)
                                    }
                                if (requiresCheckout && checkout == null) continue
                                verified +=
                                    CnbVerifiedQueueCandidate(
                                        job = candidate.job,
                                        delivery = delivery,
                                        identity = identity,
                                        cancelPending = trigger.isCancelPendingBuildsOnUpdate(),
                                        cancelRunning = trigger.shouldCancelRunningBuildsFor(delivery),
                                        checkoutAction = checkout,
                                        onlyIfNewPullRequestCommits =
                                            trigger.isTriggerOnlyIfNewCommitsPushed() &&
                                                delivery.payload.event.pullRequestEvent,
                                    )
                            }
                        }
                    }
                }

                if (targetPushCandidates.isNotEmpty()) {
                    val pullRequests =
                        CnbOpenPullRequestTargetPushResolver.resolve(
                            delivery,
                            targetPushRequirements,
                            client,
                        )
                    for (pullRequest in pullRequests) {
                        if (!pullRequest.snapshot.revisionMatches) continue
                        val identity = CnbQueueIdentity.from(pullRequest.delivery) ?: continue
                        val sourceSha =
                            pullRequest.delivery.payload.pullRequest
                                ?.sourceSha ?: continue
                        val liveCandidates = ArrayList<CnbClassicTriggerCandidate>(targetPushCandidates.size)
                        for (candidate in targetPushCandidates) {
                            if (candidate.trigger.matchesLive(pullRequest.delivery, pullRequest.snapshot)) {
                                liveCandidates += candidate
                            }
                        }
                        val sourceCloneUrl =
                            if (liveCandidates.any { CnbClassicGitRevisionAction.supports(it.job) }) {
                                directSourceCloneUrl(pullRequest.delivery, pullRequest.snapshot, client)
                            } else {
                                null
                            }
                        for (candidate in liveCandidates) {
                            val trigger = candidate.trigger
                            val requiresCheckout = CnbClassicGitRevisionAction.supports(candidate.job)
                            val checkout =
                                sourceCloneUrl?.let { url ->
                                    CnbClassicGitRevisionAction.create(candidate.job, sourceSha, url)
                                }
                            if (requiresCheckout && checkout == null) continue
                            verified +=
                                CnbVerifiedQueueCandidate(
                                    job = candidate.job,
                                    delivery = pullRequest.delivery,
                                    identity = identity,
                                    cancelPending = trigger.isCancelPendingBuildsOnUpdate(),
                                    cancelRunning = trigger.shouldCancelRunningBuildsFor(pullRequest.delivery),
                                    checkoutAction = checkout,
                                    // A target update is a new PR revision even when its source is unchanged.
                                    onlyIfNewPullRequestCommits = false,
                                    deliveryScope = CnbDeliveryScope.pullRequestTarget(identity),
                                )
                        }
                    }
                }
                verified
            }
        } catch (failure: CnbApiException) {
            if (failure.statusCode !in missingOrUnauthorizedStatusCodes) throw failure
            emptyList()
        }
    }

    private fun requirementsFor(
        delivery: CnbWebhookDelivery,
        candidates: List<CnbClassicTriggerCandidate>,
    ): CnbLiveDeliveryRequirements =
        CnbLiveDeliveryRequirements(
            labels = candidates.any { candidate -> candidate.trigger.labelPolicy().configured },
            comment = delivery.payload.event == CnbWebhookEvent.PULL_REQUEST_COMMENT,
            commitMessage = candidates.any { candidate -> candidate.trigger.isCiSkip() },
        )

    private fun requirementsForTargetPush(candidates: List<CnbClassicTriggerCandidate>): CnbLiveDeliveryRequirements =
        CnbLiveDeliveryRequirements(
            labels = candidates.any { candidate -> candidate.trigger.labelPolicy().configured },
            commitMessage = candidates.any { candidate -> candidate.trigger.isCiSkip() },
        )

    private fun directSourceCloneUrl(
        delivery: CnbWebhookDelivery,
        snapshot: CnbLiveDeliverySnapshot,
        client: CnbClient,
    ): String? {
        val sourceRepository = snapshot.pullRequest?.sourceRepo ?: delivery.payload.repository.slug
        val repository =
            try {
                client.getRepository(sourceRepository)
            } catch (failure: CnbApiException) {
                if (failure.statusCode !in missingOrUnauthorizedStatusCodes) throw failure
                return null
            }
        if (repository.path != sourceRepository || !repository.cloneable || repository.cloneUrl.isBlank()) return null
        return CnbOpenPullRequestTargetPushResolver.secureCloneUrl(
            repository.cloneUrl,
            sourceRepository,
            delivery.payload.instance.webUrl,
        )
    }

    fun targetPushPullRequests(
        delivery: CnbWebhookDelivery,
        openClient: (CnbSCMSource, SCMSourceOwner) -> CnbClient = { source, owner ->
            CnbClientFactory.create(source.serverId, source.getApiCredentialsId(), owner)
        },
    ): List<CnbWebhookDelivery> {
        if (delivery.payload.event !in TARGET_PUSH_EVENTS || delivery.payload.ref.tag || delivery.payload.pullRequest != null) {
            return emptyList()
        }
        val verified = linkedMapOf<CnbQueueIdentity, CnbWebhookDelivery>()
        for (owner in SCMSourceOwners.all()) {
            for (candidate in owner.scmSources) {
                val source = candidate as? CnbSCMSource ?: continue
                if (source.owner !== owner || owner.getSCMSource(source.id) !== source) continue
                if (source.serverId != delivery.serverId || source.repositoryPath != delivery.payload.repository.slug) continue
                val context = CnbSCMSourceContext(null, SCMHeadObserver.none()).withTraits(source.traits)
                if (!context.wantsPullRequests) continue

                val pullRequests =
                    try {
                        openClient(source, owner).use { client ->
                            CnbOpenPullRequestTargetPushResolver.resolve(
                                delivery,
                                CnbLiveDeliveryRequirements(),
                                client,
                            )
                        }
                    } catch (failure: CnbApiException) {
                        if (failure.statusCode !in missingOrUnauthorizedStatusCodes) throw failure
                        continue
                    }
                for (pullRequest in pullRequests) {
                    val advertised = pullRequest.delivery.payload.pullRequest ?: continue
                    val fork = advertised.sourceRepository != delivery.payload.repository.slug
                    if (fork && !context.wantsForkPullRequests) continue
                    if (!fork && !context.wantsOriginPullRequests) continue
                    val identity = CnbQueueIdentity.from(pullRequest.delivery) ?: continue
                    verified.putIfAbsent(identity, pullRequest.delivery)
                }
            }
        }
        return verified.values.toList()
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
    private val TARGET_PUSH_EVENTS = setOf(CnbWebhookEvent.PUSH, CnbWebhookEvent.COMMIT_ADD)
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
    fun schedule(
        candidates: List<CnbVerifiedQueueCandidate>,
        loadRunHistory: (CnbDeliveryHistory.Query) -> CnbDeliveryHistory.RunSummary =
            CnbDeliveryHistory::loadRunHistory,
    ): Int {
        val distinct = ArrayList<CnbVerifiedQueueCandidate>(candidates.size)
        val seen = HashSet<CandidateKey>(candidates.size)
        for (candidate in candidates) {
            val key =
                CandidateKey(
                    candidate.job.fullName,
                    candidate.identity,
                    candidate.delivery.payload.deliveryId,
                    candidate.deliveryScope,
                )
            if (seen.add(key)) distinct += candidate
        }
        if (distinct.isEmpty()) return 0

        val queries = ArrayList<CnbDeliveryHistory.Query>(distinct.size)
        for (candidate in distinct) {
            queries +=
                CnbDeliveryHistory.Query(
                    job = candidate.job,
                    incoming = candidate.identity,
                    deliveryId = candidate.delivery.payload.deliveryId,
                    deliveryScope = candidate.deliveryScope,
                    deduplicateRevision = candidate.delivery.payload.event in REVISION_DEDUPLICATED_EVENTS,
                    onlyIfNewPullRequestCommits = candidate.onlyIfNewPullRequestCommits,
                )
        }
        var newlyQueued: List<CnbVerifiedQueueCandidate> = emptyList()
        CnbDeliveryHistory.withStableQueue(queries, loadRunHistory) { queue, histories ->
            val eligible = ArrayList<CnbVerifiedQueueCandidate>(distinct.size)
            for ((index, candidate) in distinct.withIndex()) {
                if (!candidate.job.isBuildable || candidate.job !is Queue.Task) continue
                if (CnbDeliveryHistory.contains(queue, histories[index])) continue
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
                                CnbQueueAction(
                                    candidate.identity,
                                    candidate.delivery.payload.deliveryId,
                                    candidate.deliveryScope,
                                ),
                            ).apply {
                                candidate.revisionAction?.let(::add)
                                candidate.checkoutAction?.let(::add)
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
    private val REVISION_DEDUPLICATED_EVENTS =
        setOf(CnbWebhookEvent.PUSH, CnbWebhookEvent.COMMIT_ADD, CnbWebhookEvent.TAG_PUSH)

    private data class CandidateKey(
        val jobFullName: String,
        val identity: CnbQueueIdentity,
        val deliveryId: String,
        val deliveryScope: String,
    )
}
