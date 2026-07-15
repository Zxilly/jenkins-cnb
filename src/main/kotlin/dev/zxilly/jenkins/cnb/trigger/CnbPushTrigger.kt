package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClientFactory
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbTag
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import hudson.Extension
import hudson.model.CauseAction
import hudson.model.Item
import hudson.model.Job
import hudson.model.Queue
import hudson.triggers.Trigger
import hudson.triggers.TriggerDescriptor
import hudson.util.FormValidation
import hudson.util.ListBoxModel
import jenkins.model.ParameterizedJobMixIn
import org.jenkinsci.Symbol
import org.kohsuke.stapler.AncestorInPath
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

class CnbPushTrigger
    @DataBoundConstructor
    constructor(
        serverId: String,
        repositoryPath: String,
        branchFilter: String,
    ) : Trigger<Job<*, *>>() {
        val serverId: String = serverId.trim()
        val repositoryPath: String = repositoryPath.trim().trim('/')
        val branchFilter: String = branchFilter.trim()

        init {
            require(SERVER_ID_PATTERN.matches(this.serverId)) { "Invalid CNB server ID" }
            require(isRepositoryPath(this.repositoryPath)) { "Invalid CNB repository path" }
            CnbRefGlob.compile(this.branchFilter.ifBlank { DEFAULT_BRANCH_FILTER })
        }

        fun matches(delivery: CnbWebhookDelivery): Boolean {
            val payload = delivery.payload
            return delivery.serverId == serverId &&
                payload.repository.slug == repositoryPath &&
                payload.event in BUILD_EVENTS &&
                payload.ref.commit
                    .ifEmpty { payload.ref.sha }
                    .any { it != '0' } &&
                CnbRefGlob.matches(branchFilter.ifBlank { DEFAULT_BRANCH_FILTER }, payload.ref.name)
        }

        internal fun isEligible(delivery: CnbWebhookDelivery): Boolean {
            val target = job ?: return false
            return target.isBuildable && matches(delivery)
        }

        internal fun scheduleVerified(delivery: CnbWebhookDelivery): Boolean {
            val target = job ?: return false
            // Recheck mutable Jenkins state after the dispatcher's API preflight, but never perform
            // network I/O here: all matching jobs must be validated before any one is scheduled.
            if (!target.isBuildable || !matches(delivery)) return false
            val queued =
                ParameterizedJobMixIn.scheduleBuild2(
                    target,
                    0,
                    CauseAction(CnbPushCause.from(delivery)),
                )
            if (queued != null) {
                LOGGER.log(
                    Level.FINE,
                    "Scheduled {0} for CNB delivery {1}",
                    arrayOf(target.fullName, delivery.payload.deliveryId),
                )
            }
            return queued != null
        }

        /**
         * Re-resolves a signed delivery through the configured CNB API before a classic job can be
         * scheduled. The bridge signature authenticates the sender, but is not authoritative for
         * repository state.
         */
        internal fun liveRevisionMatches(delivery: CnbWebhookDelivery): Boolean =
            try {
                // A traditional trigger has no item-scoped API credential setting. Resolve the
                // credential from its global CNB server profile once per delivery preflight.
                CnbClientFactory.create(serverId).use { client ->
                    revisionMatches(delivery, client::getBranch, client::listTags)
                }
            } catch (failure: CnbApiException) {
                throw failure
            } catch (failure: IOException) {
                throw failure
            } catch (failure: Exception) {
                // Make credential/configuration failures follow the webhook's retryable dispatch
                // path instead of being mistaken for a successfully handled delivery.
                throw IOException("CNB live ref verification failed", failure)
            }

        @Extension
        @Symbol("cnbPush")
        class DescriptorImpl : TriggerDescriptor() {
            override fun getDisplayName(): String = "Build when CNB pushes a ref"

            override fun isApplicable(item: Item): Boolean = item is Job<*, *> && item is Queue.Task

            fun doFillServerIdItems(
                @AncestorInPath item: Item?,
                @QueryParameter serverId: String?,
            ): ListBoxModel =
                ListBoxModel().apply {
                    if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                        serverId?.takeIf(String::isNotBlank)?.let { add(it, it) }
                        return@apply
                    }
                    CnbGlobalConfiguration.get().getServers().forEach { add(it.name, it.id) }
                    if (!serverId.isNullOrBlank() && none { it.value == serverId }) add(serverId, serverId)
                }

            @POST
            fun doCheckServerId(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation =
                if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                    FormValidation.ok()
                } else if (value?.matches(SERVER_ID_PATTERN) == true) {
                    FormValidation.ok()
                } else {
                    FormValidation.error("Select a configured CNB server")
                }

            @POST
            fun doCheckRepositoryPath(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation =
                if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                    FormValidation.ok()
                } else if (value != null && isRepositoryPath(value.trim().trim('/'))) {
                    FormValidation.ok()
                } else {
                    FormValidation.error("Use a full repository path such as namespace/project")
                }

            @POST
            fun doCheckBranchFilter(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation =
                if (item == null || !item.hasPermission(Item.CONFIGURE)) {
                    FormValidation.ok()
                } else {
                    try {
                        CnbRefGlob.compile(value.orEmpty().ifBlank { DEFAULT_BRANCH_FILTER })
                        FormValidation.ok()
                    } catch (e: IllegalArgumentException) {
                        FormValidation.error(e.message)
                    }
                }
        }

        companion object {
            private const val DEFAULT_BRANCH_FILTER = "**"
            private val SERVER_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
            private val BUILD_EVENTS =
                setOf(
                    CnbWebhookEvent.PUSH,
                    CnbWebhookEvent.TAG_PUSH,
                )
            private val LOGGER = Logger.getLogger(CnbPushTrigger::class.java.name)

            internal fun revisionMatches(
                delivery: CnbWebhookDelivery,
                getBranch: (String, String) -> CnbBranch,
                listTags: (String) -> List<CnbTag>,
            ): Boolean {
                val payload = delivery.payload
                val expected = payload.ref.commit.ifEmpty { payload.ref.sha }
                if (expected.isEmpty() || expected.all { it == '0' }) return false

                return try {
                    when (payload.event) {
                        CnbWebhookEvent.PUSH -> {
                            if (payload.ref.tag) return false
                            val branch = getBranch(payload.repository.slug, payload.ref.name)
                            branch.name == payload.ref.name && branch.sha.equals(expected, ignoreCase = true)
                        }

                        CnbWebhookEvent.TAG_PUSH -> {
                            if (!payload.ref.tag) return false
                            listTags(payload.repository.slug).any { tag ->
                                tag.name == payload.ref.name && tag.sha.equals(expected, ignoreCase = true)
                            }
                        }

                        else -> {
                            false
                        }
                    }
                } catch (failure: CnbApiException) {
                    if (failure.statusCode == 404) false else throw failure
                }
            }

            private fun isRepositoryPath(value: String): Boolean {
                if (value.length !in 3..1024 || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) {
                    return false
                }
                val segments = value.split('/')
                return segments.size >= 2 && segments.all { it.isNotEmpty() && it != "." && it != ".." }
            }
        }
    }

internal object CnbRefGlob {
    fun matches(
        pattern: String,
        value: String,
    ): Boolean = compile(pattern).matches(value)

    fun compile(pattern: String): Regex {
        require(pattern.isNotEmpty()) { "Branch filter must not be empty" }
        require(pattern.length <= 512 && pattern.none { it.code < 0x20 || it.code == 0x7f }) {
            "Branch filter is invalid"
        }
        val expression = StringBuilder("^")
        var index = 0
        while (index < pattern.length) {
            val character = pattern[index]
            when {
                character == '*' && index + 1 < pattern.length && pattern[index + 1] == '*' -> {
                    expression.append(".*")
                    index += 2
                }

                character == '*' -> {
                    expression.append("[^/]*")
                    index++
                }

                character == '?' -> {
                    expression.append("[^/]")
                    index++
                }

                else -> {
                    if (character in "\\.[]{}()+-^$|") expression.append('\\')
                    expression.append(character)
                    index++
                }
            }
        }
        expression.append('$')
        return Regex(expression.toString())
    }
}
