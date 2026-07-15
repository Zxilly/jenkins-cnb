package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.scm.CnbBranchSCMHead
import dev.zxilly.jenkins.cnb.scm.CnbBranchSCMRevision
import dev.zxilly.jenkins.cnb.scm.CnbPullRequestSCMHead
import dev.zxilly.jenkins.cnb.scm.CnbPullRequestSCMRevision
import dev.zxilly.jenkins.cnb.scm.CnbSCMNavigator
import dev.zxilly.jenkins.cnb.scm.CnbSCMSource
import dev.zxilly.jenkins.cnb.scm.CnbSCMSourceContext
import dev.zxilly.jenkins.cnb.scm.CnbTagSCMHead
import dev.zxilly.jenkins.cnb.scm.CnbTagSCMRevision
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDispatcher
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPayload
import hudson.model.Cause
import hudson.model.Job
import hudson.scm.SCM
import jenkins.model.Jenkins
import jenkins.model.ParameterizedJobMixIn
import jenkins.scm.api.SCMEvent
import jenkins.scm.api.SCMHead
import jenkins.scm.api.SCMHeadEvent
import jenkins.scm.api.SCMHeadObserver
import jenkins.scm.api.SCMHeadOrigin
import jenkins.scm.api.SCMNavigator
import jenkins.scm.api.SCMRevision
import jenkins.scm.api.SCMSource
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

/** Bridges a validated CNB delivery into Jenkins SCM API and classic-job trigger APIs. */
internal object JenkinsCnbWebhookDispatcher : CnbWebhookDispatcher {
    override fun dispatch(delivery: CnbWebhookDelivery) {
        val classicTriggers = ArrayList<Pair<Job<*, *>, CnbPushTrigger>>()
        for (candidate in Jenkins.get().getAllItems(Job::class.java)) {
            try {
                val trigger = ParameterizedJobMixIn.getTrigger(candidate, CnbPushTrigger::class.java)
                if (trigger != null && trigger.isEligible(delivery)) {
                    classicTriggers += candidate to trigger
                }
            } catch (failure: RuntimeException) {
                logJobFailure(delivery, candidate, "inspect", failure)
            }
        }

        val scheduleActions = ArrayList<() -> Unit>(classicTriggers.size)
        for ((candidate, trigger) in classicTriggers) {
            scheduleActions.add(
                {
                    try {
                        trigger.scheduleVerified(delivery)
                    } catch (failure: RuntimeException) {
                        // Queue failures are isolated after successful verification. They do not
                        // make the bridge retry jobs that were already scheduled; repository
                        // polling remains the consistency fallback for this individual job.
                        logJobFailure(delivery, candidate, "schedule", failure)
                    }
                },
            )
        }
        val dispatched =
            dispatchAfterRefPreflight(
                requiresVerification = classicTriggers.isNotEmpty(),
                verify = {
                    // Every eligible trigger matched this one immutable delivery scope. Keep the
                    // invariant explicit before sharing its single server-profile API lookup.
                    check(
                        classicTriggers.all { (_, trigger) ->
                            trigger.serverId == delivery.serverId &&
                                trigger.repositoryPath == delivery.payload.repository.slug
                        },
                    ) { "CNB classic trigger verification scopes diverged" }
                    classicTriggers.first().second.liveRevisionMatches(delivery)
                },
                fireScmEvent = { SCMHeadEvent.fireNow(CnbSCMHeadEvent(delivery)) },
                scheduleActions = scheduleActions,
            )
        if (!dispatched) {
            LOGGER.log(
                Level.FINE,
                "Ignoring CNB delivery {0} because its ref revision is no longer current",
                delivery.payload.deliveryId,
            )
        }
    }

    private fun logJobFailure(
        delivery: CnbWebhookDelivery,
        candidate: Job<*, *>,
        operation: String,
        failure: RuntimeException,
    ) {
        LOGGER.log(
            Level.WARNING,
            "Could not {0} CNB delivery {1} for job {2}",
            arrayOf<Any>(operation, delivery.payload.deliveryId, candidate.fullName),
        )
        LOGGER.log(Level.FINE, "CNB job dispatch failure", failure)
    }

    private val LOGGER = Logger.getLogger(JenkinsCnbWebhookDispatcher::class.java.name)
}

/**
 * Delivers the SCM hint independently, then gates classic-job queue effects on their shared
 * server-profile ref verification. SCM sources re-resolve with their own item-scoped credentials.
 */
internal fun dispatchAfterRefPreflight(
    requiresVerification: Boolean,
    verify: () -> Boolean,
    fireScmEvent: () -> Unit,
    scheduleActions: List<() -> Unit>,
): Boolean {
    fireScmEvent()
    if (requiresVerification && !verify()) return false
    scheduleActions.forEach { it() }
    return true
}

/**
 * A signed CNB webhook represented as an SCM API hint.
 *
 * Consumers must re-fetch the advertised head. Values here are sufficient to target the exact
 * source and head, but are never a substitute for CNB API authorization or discovery filters.
 */
internal class CnbSCMHeadEvent(
    delivery: CnbWebhookDelivery,
) : SCMHeadEvent<CnbWebhookDelivery>(
        typeOf(delivery.payload),
        delivery.payload.occurredAt.toEpochMilli(),
        delivery,
        delivery.origin,
    ) {
    private val data: CnbWebhookPayload
        get() = payload.payload

    internal val repositoryPath: String
        get() = data.repository.slug

    override fun isMatch(navigator: SCMNavigator): Boolean {
        val cnb = navigator as? CnbSCMNavigator ?: return false
        val namespace = cnb.namespace.trim('/')
        if (cnb.serverId != payload.serverId || namespace.isEmpty()) return false
        val prefix = "$namespace/"
        if (!data.repository.slug.startsWith(prefix)) return false
        val relative = data.repository.slug.removePrefix(prefix)
        return cnb.isIncludeDescendants() || !relative.contains('/')
    }

    override fun isMatch(scm: SCM): Boolean = false

    override fun isMatch(source: SCMSource): Boolean = source is CnbSCMSource && repositoryMatches(source)

    override fun getSourceName(): String = CnbSCMNavigator.projectNameFor(data.repository.slug)

    override fun description(): String = "CNB ${data.event.wireName} event for ${data.repository.slug} at ${data.ref.name}"

    override fun descriptionFor(source: SCMSource): String = "CNB ${data.event.wireName} event at ${data.ref.name}"

    override fun heads(source: SCMSource): Map<SCMHead, SCMRevision> {
        val cnb = source as? CnbSCMSource ?: return emptyMap()
        if (!repositoryMatches(cnb)) return emptyMap()
        return when (data.event) {
            CnbWebhookEvent.PUSH,
            CnbWebhookEvent.BRANCH_CREATE,
            CnbWebhookEvent.BRANCH_DELETE,
            -> if (discoveryContext(cnb).wantsBranches) branchHeads() else emptyMap()

            CnbWebhookEvent.TAG_PUSH -> if (discoveryContext(cnb).wantsTags) tagHeads() else emptyMap()

            CnbWebhookEvent.PULL_REQUEST_TARGET,
            CnbWebhookEvent.PULL_REQUEST_MERGEABLE,
            CnbWebhookEvent.PULL_REQUEST_MERGED,
            -> pullRequestHeads(cnb)
        }
    }

    override fun asCauses(): Array<Cause> = arrayOf(CnbPushCause.from(payload))

    private fun discoveryContext(source: CnbSCMSource): CnbSCMSourceContext =
        CnbSCMSourceContext(null, SCMHeadObserver.none()).withTraits(source.traits)

    private fun repositoryMatches(source: CnbSCMSource): Boolean =
        source.serverId == payload.serverId && source.repositoryPath == data.repository.slug

    private fun branchHeads(): Map<SCMHead, SCMRevision> {
        val head = CnbBranchSCMHead(data.ref.name)
        val revision =
            effectiveObjectId(data)
                .takeIf { type != SCMEvent.Type.REMOVED }
                ?.let { CnbBranchSCMRevision(head, it) }
        return singletonHead(head, revision)
    }

    private fun tagHeads(): Map<SCMHead, SCMRevision> {
        val head = CnbTagSCMHead(data.ref.name, timestamp)
        val revision =
            effectiveObjectId(data)
                .takeIf { type != SCMEvent.Type.REMOVED }
                ?.let { CnbTagSCMRevision(head, it) }
        return singletonHead(head, revision)
    }

    private fun pullRequestHeads(source: CnbSCMSource): Map<SCMHead, SCMRevision> {
        val pullRequest = data.pullRequest ?: return emptyMap()
        val fork = pullRequest.sourceRepository != data.repository.slug
        val result = linkedMapOf<SCMHead, SCMRevision?>()
        CnbSCMSourceContext(null, SCMHeadObserver.none())
            .withTraits(source.traits)
            .newRequest(source, null)
            .use { request ->
                val strategies = request.strategiesFor(fork)
                for (strategy in strategies) {
                    val suffix = if (strategies.size > 1) "-${strategy.name.lowercase(Locale.ROOT)}" else ""
                    val head =
                        CnbPullRequestSCMHead(
                            "PR-${pullRequest.number}$suffix",
                            pullRequest.number,
                            CnbBranchSCMHead(pullRequest.targetBranch),
                            strategy,
                            if (fork) SCMHeadOrigin.Fork(pullRequest.sourceRepository) else SCMHeadOrigin.DEFAULT,
                            pullRequest.sourceRepository,
                            pullRequest.sourceBranch,
                            pullRequest.proposer,
                            pullRequest.title,
                        )
                    result[head] =
                        if (type == SCMEvent.Type.REMOVED) {
                            null
                        } else {
                            CnbPullRequestSCMRevision(
                                head,
                                pullRequest.targetSha,
                                pullRequest.sourceSha,
                                pullRequest.mergeSha,
                            )
                        }
                }
            }
        return nullableRevisionMap(result)
    }

    companion object {
        internal fun typeOf(payload: CnbWebhookPayload): SCMEvent.Type =
            when (payload.event) {
                CnbWebhookEvent.BRANCH_CREATE -> {
                    SCMEvent.Type.CREATED
                }

                CnbWebhookEvent.TAG_PUSH -> {
                    when {
                        !isPresentObjectId(payload.ref.before) -> SCMEvent.Type.CREATED
                        !isPresentObjectId(effectiveObjectId(payload)) -> SCMEvent.Type.REMOVED
                        else -> SCMEvent.Type.UPDATED
                    }
                }

                CnbWebhookEvent.BRANCH_DELETE,
                CnbWebhookEvent.PULL_REQUEST_MERGED,
                -> {
                    SCMEvent.Type.REMOVED
                }

                CnbWebhookEvent.PUSH -> {
                    when {
                        !isPresentObjectId(payload.ref.before) -> SCMEvent.Type.CREATED
                        !isPresentObjectId(effectiveObjectId(payload)) -> SCMEvent.Type.REMOVED
                        else -> SCMEvent.Type.UPDATED
                    }
                }

                CnbWebhookEvent.PULL_REQUEST_TARGET,
                CnbWebhookEvent.PULL_REQUEST_MERGEABLE,
                -> {
                    if (payload.pullRequest?.action?.lowercase(Locale.ROOT) in CREATED_ACTIONS) {
                        SCMEvent.Type.CREATED
                    } else {
                        SCMEvent.Type.UPDATED
                    }
                }
            }

        private fun effectiveObjectId(payload: CnbWebhookPayload): String = payload.ref.commit.ifEmpty { payload.ref.sha }

        private fun isPresentObjectId(value: String): Boolean = value.isNotEmpty() && value.any { it != '0' }

        private val CREATED_ACTIONS = setOf("open", "opened", "reopen", "reopened")

        @Suppress("UNCHECKED_CAST")
        private fun singletonHead(
            head: SCMHead,
            revision: SCMRevision?,
        ): Map<SCMHead, SCMRevision> = mapOf(head to revision) as Map<SCMHead, SCMRevision>

        @Suppress("UNCHECKED_CAST")
        private fun nullableRevisionMap(value: Map<SCMHead, SCMRevision?>): Map<SCMHead, SCMRevision> = value as Map<SCMHead, SCMRevision>
    }
}
