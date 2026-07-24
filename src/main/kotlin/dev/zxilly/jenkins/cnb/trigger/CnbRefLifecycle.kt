package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.security.CnbGitObjectId
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import hudson.model.Job
import jenkins.model.Jenkins
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.WeakHashMap

internal fun classicJobEventConsumerScope(job: Job<*, *>): String = "1\u0000${job.fullName}"

internal fun classicJobRefLifecycleScope(
    serverId: String,
    repositoryPath: String,
    job: Job<*, *>,
): CnbRepositoryEventStateScope =
    CnbRepositoryEventStateScope(
        serverId,
        repositoryPath,
        SERVER_AUTHORIZATION_SCOPE,
        classicJobEventConsumerScope(job),
    )

/** One lifecycle store instance per live Jenkins controller, shared by webhook and polling paths. */
internal object CnbRefLifecycleStores {
    private val stores = WeakHashMap<Jenkins, CnbRefLifecycleStore>()

    @Synchronized
    fun current(jenkins: Jenkins = Jenkins.get()): CnbRefLifecycleStore =
        stores.getOrPut(jenkins) {
            CnbRefLifecycleStore(
                jenkins.rootDir
                    .toPath()
                    .resolve("cnb")
                    .resolve("classic-ref-lifecycle.properties"),
            )
        }
}

internal object CnbWebhookRefLifecycle {
    fun transition(delivery: CnbWebhookDelivery): CnbRefLifecycleTransition? {
        val payload = delivery.payload
        if (payload.pullRequest != null || payload.event.pullRequestEvent) return null
        val present =
            when (payload.event) {
                CnbWebhookEvent.PUSH,
                CnbWebhookEvent.COMMIT_ADD,
                CnbWebhookEvent.BRANCH_CREATE,
                -> {
                    if (payload.ref.tag || !currentObjectIdIsPresent(delivery)) return null
                    true
                }

                CnbWebhookEvent.BRANCH_DELETE -> {
                    if (payload.ref.tag) return null
                    false
                }

                CnbWebhookEvent.TAG_PUSH -> {
                    if (!payload.ref.tag) return null
                    currentObjectIdIsPresent(delivery)
                }

                else -> return null
            }
        val prefix = if (payload.ref.tag || payload.event == CnbWebhookEvent.TAG_PUSH) "refs/tags/" else "refs/heads/"
        return CnbRefLifecycleTransition(
            qualifiedRef = "$prefix${payload.ref.name}",
            present = present,
            occurredAt = payload.occurredAt,
            stableEventId =
                stableLifecycleEventId(
                    payload.deliveryId,
                    stableKey(delivery.serverId, payload.repository.slug, payload.deliveryId),
                ),
        )
    }

    fun candidateProvesTransition(
        delivery: CnbWebhookDelivery,
        observers: List<CnbClassicTriggerCandidate>,
        candidates: List<CnbVerifiedQueueCandidate>,
    ): Boolean {
        val transition = transition(delivery) ?: return false
        val jobs = observers.mapTo(HashSet()) { it.job }
        return candidates.any { candidate ->
            candidate.job in jobs &&
                candidate.delivery.serverId == delivery.serverId &&
                candidate.delivery.payload.deliveryId == delivery.payload.deliveryId &&
                candidate.identity.ref == transition.qualifiedRef
        }
    }

    /** Persists one verified transition for every observer and attaches its generation to builds. */
    fun applyVerified(
        delivery: CnbWebhookDelivery,
        observers: List<CnbClassicTriggerCandidate>,
        candidates: List<CnbVerifiedQueueCandidate>,
        store: CnbRefLifecycleStore = CnbRefLifecycleStores.current(),
    ): List<CnbVerifiedQueueCandidate> {
        val transition = transition(delivery) ?: return candidates
        if (observers.isEmpty()) return candidates
        val scoped =
            observers.map { observer ->
                CnbScopedRefLifecycleTransition(
                    classicJobRefLifecycleScope(delivery.serverId, delivery.payload.repository.slug, observer.job),
                    transition,
                )
            }
        val results = store.apply(scoped)
        val stateByJob = LinkedHashMap<Job<*, *>, CnbRefLifecycleResult>(observers.size)
        for ((index, observer) in observers.withIndex()) stateByJob[observer.job] = results[index]

        return candidates.mapNotNull { candidate ->
            val state = stateByJob[candidate.job] ?: return@mapNotNull candidate
            if (candidate.delivery.serverId != delivery.serverId ||
                candidate.delivery.payload.deliveryId != delivery.payload.deliveryId ||
                candidate.identity.ref != transition.qualifiedRef
            ) {
                return@mapNotNull candidate
            }
            if (!state.current || state.present != transition.present) return@mapNotNull null
            candidate.copy(identity = candidate.identity.copy(refGeneration = state.generation))
        }
    }

    private fun currentObjectIdIsPresent(delivery: CnbWebhookDelivery): Boolean {
        val ref = delivery.payload.ref
        return CnbGitObjectId.isPresent(ref.commit) || CnbGitObjectId.isPresent(ref.sha)
    }

    private fun stableKey(
        serverId: String,
        repositoryPath: String,
        deliveryId: String,
    ): String =
        HexFormat.of().formatHex(
            MessageDigest
                .getInstance("SHA-256")
                .digest("$serverId\u0000$repositoryPath\u0000$deliveryId".toByteArray(StandardCharsets.UTF_8)),
        )
}
