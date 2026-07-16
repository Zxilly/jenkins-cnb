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
import hudson.security.ACL
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
        ACL.as2(ACL.SYSTEM2).use {
            dispatchAsSystem(delivery)
        }
    }

    private fun dispatchAsSystem(delivery: CnbWebhookDelivery) {
        val classicTriggers = ArrayList<CnbClassicTriggerCandidate>()
        for (candidate in Jenkins.get().getAllItems(Job::class.java)) {
            try {
                val trigger = ParameterizedJobMixIn.getTrigger(candidate, CnbPushTrigger::class.java)
                if (trigger != null && trigger.isEligible(delivery)) {
                    classicTriggers += CnbClassicTriggerCandidate(candidate, trigger)
                }
            } catch (failure: RuntimeException) {
                // One corrupt, legacy job must not turn every delivery for the repository into a
                // permanent retry loop. Its trigger remains fail-closed and other jobs continue.
                LOGGER.log(
                    Level.WARNING,
                    "Could not inspect the CNB trigger for job {0}",
                    candidate.fullName,
                )
                LOGGER.log(Level.FINE, "CNB trigger inspection failure", failure)
            }
        }

        // Complete every network lookup before the first queue mutation. Classic policies share
        // one immutable API snapshot; each multibranch source uses its item-scoped credential.
        val verified = ArrayList<CnbVerifiedQueueCandidate>()
        verified += CnbVerifiedWebhookPlanner.classic(delivery, classicTriggers)
        if (delivery.payload.event == CnbWebhookEvent.PULL_REQUEST_COMMENT) {
            verified += CnbVerifiedWebhookPlanner.pullRequestComment(delivery)
        } else {
            // Comment builds are an explicit trait/trigger operation. Sending them through the
            // generic SCM event path would bypass that opt-in and may schedule the wrong child.
            SCMHeadEvent.fireNow(CnbSCMHeadEvent(delivery))
        }
        val scheduled = CnbAtomicQueueScheduler.schedule(verified)
        LOGGER.log(
            Level.FINE,
            "CNB delivery {0} produced {1} verified queue entries",
            arrayOf<Any>(delivery.payload.deliveryId, scheduled),
        )
    }

    private val LOGGER = Logger.getLogger(JenkinsCnbWebhookDispatcher::class.java.name)
}

/**
 * A signed CNB webhook represented as an SCM API hint.
 *
 * [SCMHeadEvent.filter] treats [heads] as an untrusted include set. A head is forwarded to Jenkins
 * only after [CnbSCMSource.retrieve] resolves it with the source owner's credentials and passes it
 * through the configured criteria, filters, and authorities. Values here target that lookup and
 * preserve deletion hints; they are never a substitute for CNB API authorization.
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
        if (cnb.serverId != payload.serverId) return false
        if (cnb.isDiscoverAllRepositories()) return true
        val namespace = cnb.namespace.trim('/')
        if (namespace.isEmpty()) return false
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
            CnbWebhookEvent.COMMIT_ADD,
            CnbWebhookEvent.BRANCH_CREATE,
            CnbWebhookEvent.BRANCH_DELETE,
            -> if (discoveryContext(cnb).wantsBranches) branchHeads() else emptyMap()

            CnbWebhookEvent.TAG_PUSH -> if (discoveryContext(cnb).wantsTags) tagHeads() else emptyMap()

            CnbWebhookEvent.PULL_REQUEST,
            CnbWebhookEvent.PULL_REQUEST_UPDATE,
            CnbWebhookEvent.PULL_REQUEST_APPROVED,
            CnbWebhookEvent.PULL_REQUEST_CHANGES_REQUESTED,
            CnbWebhookEvent.PULL_REQUEST_COMMENT,
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

                CnbWebhookEvent.PUSH,
                CnbWebhookEvent.COMMIT_ADD,
                -> {
                    when {
                        !isPresentObjectId(payload.ref.before) -> SCMEvent.Type.CREATED
                        !isPresentObjectId(effectiveObjectId(payload)) -> SCMEvent.Type.REMOVED
                        else -> SCMEvent.Type.UPDATED
                    }
                }

                CnbWebhookEvent.PULL_REQUEST,
                CnbWebhookEvent.PULL_REQUEST_UPDATE,
                CnbWebhookEvent.PULL_REQUEST_TARGET,
                -> {
                    if (payload.pullRequest?.action?.lowercase(Locale.ROOT) in CREATED_ACTIONS) {
                        SCMEvent.Type.CREATED
                    } else {
                        SCMEvent.Type.UPDATED
                    }
                }

                CnbWebhookEvent.PULL_REQUEST_APPROVED,
                CnbWebhookEvent.PULL_REQUEST_CHANGES_REQUESTED,
                CnbWebhookEvent.PULL_REQUEST_COMMENT,
                CnbWebhookEvent.PULL_REQUEST_MERGEABLE,
                -> {
                    SCMEvent.Type.UPDATED
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
