package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClientFactory
import dev.zxilly.jenkins.cnb.api.model.CnbBranch
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestState
import dev.zxilly.jenkins.cnb.api.model.CnbTag
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.scm.CnbRepositoryRole
import dev.zxilly.jenkins.cnb.security.CnbGitObjectId
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookDelivery
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookPullRequest
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import hudson.Extension
import hudson.model.AutoCompletionCandidates
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
import org.kohsuke.stapler.DataBoundSetter
import org.kohsuke.stapler.QueryParameter
import org.kohsuke.stapler.verb.POST
import java.io.IOException
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

internal enum class CnbOpenPullRequestPushMode(
    val wireName: String,
) {
    NEVER("never"),
    SOURCE("source"),
    BOTH("both"),
    ;

    companion object {
        fun parse(value: String?): CnbOpenPullRequestPushMode {
            val normalized = value.orEmpty().trim().lowercase(Locale.ROOT)
            return entries.firstOrNull { it.wireName == normalized }
                ?: throw IllegalArgumentException("Unsupported open pull request push mode")
        }

        fun parseOrNever(value: String?): CnbOpenPullRequestPushMode = runCatching { parse(value) }.getOrDefault(NEVER)
    }
}

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
        private var configuredEventFilter: String? = DEFAULT_EVENT_FILTER
        private var configuredSourceBranchFilter: String? = DEFAULT_REF_FILTER
        private var configuredTargetBranchFilter: String? = DEFAULT_REF_FILTER
        private var configuredIncludeDraftPullRequests: Boolean = false
        private var configuredRequiredPullRequestLabels: String? = ""
        private var configuredExcludedPullRequestLabels: String? = ""
        private var configuredCommentPattern: String? = ""
        private var configuredCommentMinimumRole: String? = CnbPullRequestCommentPolicy.DEFAULT_MINIMUM_ROLE
        private var configuredCancelPendingBuildsOnUpdate: Boolean = false
        private var configuredCancelRunningBuildsOnUpdate: Boolean = false
        private var configuredCiSkip: Boolean? = true
        private var configuredTriggerOnlyIfNewCommitsPushed: Boolean = false
        private var configuredTriggerOpenPullRequestOnPush: String? = CnbOpenPullRequestPushMode.NEVER.wireName

        init {
            require(SERVER_ID_PATTERN.matches(this.serverId)) { "Invalid CNB server ID" }
            require(isRepositoryPath(this.repositoryPath)) { "Invalid CNB repository path" }
            CnbRefGlob.compile(this.branchFilter.ifBlank { DEFAULT_BRANCH_FILTER })
        }

        fun getEventFilter(): String = configuredEventFilter.orEmpty().ifBlank { DEFAULT_EVENT_FILTER }

        @DataBoundSetter
        fun setEventFilter(value: String?) {
            configuredEventFilter = CnbEventFilter.normalize(value.orEmpty().ifBlank { DEFAULT_EVENT_FILTER })
        }

        fun getSourceBranchFilter(): String = configuredSourceBranchFilter.orEmpty().ifBlank { DEFAULT_REF_FILTER }

        @DataBoundSetter
        fun setSourceBranchFilter(value: String?) {
            configuredSourceBranchFilter = normalizeRefFilter(value)
        }

        fun getTargetBranchFilter(): String = configuredTargetBranchFilter.orEmpty().ifBlank { DEFAULT_REF_FILTER }

        @DataBoundSetter
        fun setTargetBranchFilter(value: String?) {
            configuredTargetBranchFilter = normalizeRefFilter(value)
        }

        fun isIncludeDraftPullRequests(): Boolean = configuredIncludeDraftPullRequests

        @DataBoundSetter
        fun setIncludeDraftPullRequests(value: Boolean) {
            configuredIncludeDraftPullRequests = value
        }

        fun getRequiredPullRequestLabels(): String = labelPolicy().requiredConfiguration

        @DataBoundSetter
        fun setRequiredPullRequestLabels(value: String?) {
            configuredRequiredPullRequestLabels =
                CnbPullRequestLabelPolicy(value.orEmpty(), configuredExcludedPullRequestLabels.orEmpty())
                    .requiredConfiguration
        }

        fun getExcludedPullRequestLabels(): String = labelPolicy().excludedConfiguration

        @DataBoundSetter
        fun setExcludedPullRequestLabels(value: String?) {
            configuredExcludedPullRequestLabels =
                CnbPullRequestLabelPolicy(configuredRequiredPullRequestLabels.orEmpty(), value.orEmpty())
                    .excludedConfiguration
        }

        fun getCommentPattern(): String = configuredCommentPattern.orEmpty().trim()

        @DataBoundSetter
        fun setCommentPattern(value: String?) {
            configuredCommentPattern =
                CnbPullRequestCommentPolicy.optional(value, getCommentMinimumRole())?.pattern.orEmpty()
        }

        fun getCommentMinimumRole(): String =
            CnbRepositoryRole
                .parseMinimum(configuredCommentMinimumRole.orEmpty().ifBlank { CnbPullRequestCommentPolicy.DEFAULT_MINIMUM_ROLE })
                .wireName

        @DataBoundSetter
        fun setCommentMinimumRole(value: String?) {
            val normalized =
                CnbRepositoryRole
                    .parseMinimum(value.orEmpty().ifBlank { CnbPullRequestCommentPolicy.DEFAULT_MINIMUM_ROLE })
                    .wireName
            configuredCommentPattern
                ?.takeIf(String::isNotBlank)
                ?.let { CnbPullRequestCommentPolicy(it, normalized) }
            configuredCommentMinimumRole = normalized
        }

        fun isCancelPendingBuildsOnUpdate(): Boolean = configuredCancelPendingBuildsOnUpdate

        @DataBoundSetter
        fun setCancelPendingBuildsOnUpdate(value: Boolean) {
            configuredCancelPendingBuildsOnUpdate = value
        }

        fun isCancelRunningBuildsOnUpdate(): Boolean = configuredCancelRunningBuildsOnUpdate

        @DataBoundSetter
        fun setCancelRunningBuildsOnUpdate(value: Boolean) {
            configuredCancelRunningBuildsOnUpdate = value
        }

        fun isCiSkip(): Boolean = configuredCiSkip ?: true

        @DataBoundSetter
        fun setCiSkip(value: Boolean) {
            configuredCiSkip = value
        }

        fun isTriggerOnlyIfNewCommitsPushed(): Boolean = configuredTriggerOnlyIfNewCommitsPushed

        @DataBoundSetter
        fun setTriggerOnlyIfNewCommitsPushed(value: Boolean) {
            configuredTriggerOnlyIfNewCommitsPushed = value
        }

        fun getTriggerOpenPullRequestOnPush(): String =
            CnbOpenPullRequestPushMode.parseOrNever(configuredTriggerOpenPullRequestOnPush).wireName

        internal fun openPullRequestPushMode(): CnbOpenPullRequestPushMode =
            CnbOpenPullRequestPushMode.parseOrNever(configuredTriggerOpenPullRequestOnPush)

        @DataBoundSetter
        fun setTriggerOpenPullRequestOnPush(value: String?) {
            configuredTriggerOpenPullRequestOnPush =
                CnbOpenPullRequestPushMode
                    .parse(value.orEmpty().ifBlank { CnbOpenPullRequestPushMode.NEVER.wireName })
                    .wireName
        }

        /** Backwards-compatible constructor field, exposed as the general ref glob. */
        fun getRefFilter(): String = branchFilter.ifBlank { DEFAULT_REF_FILTER }

        fun matches(delivery: CnbWebhookDelivery): Boolean {
            val payload = delivery.payload
            if (delivery.serverId != serverId || payload.repository.slug != repositoryPath) return false
            if (!matchesConfiguredEvent(payload.event)) return false
            if (!CnbRefGlob.matches(getRefFilter(), payload.ref.name)) return false
            val pullRequest = payload.pullRequest
            if (payload.event.pullRequestEvent) {
                if (pullRequest == null || (pullRequest.wip && !isIncludeDraftPullRequests())) return false
                if (payload.event == CnbWebhookEvent.PULL_REQUEST_COMMENT && commentPolicy() == null) return false
                if (!CnbRefGlob.matches(getSourceBranchFilter(), pullRequest.sourceBranch)) return false
                if (!CnbRefGlob.matches(getTargetBranchFilter(), pullRequest.targetBranch)) return false
            } else if (payload.event !in DELETION_CAPABLE_EVENTS &&
                !isPresentObjectId(payload.ref.commit.ifEmpty { payload.ref.sha })
            ) {
                return false
            }
            return CnbQueueIdentity.from(delivery) != null
        }

        private fun matchesConfiguredEvent(event: CnbWebhookEvent): Boolean =
            CnbEventFilter.matches(getEventFilter(), event) ||
                (event == CnbWebhookEvent.PULL_REQUEST_TARGET && openPullRequestPushMode() != CnbOpenPullRequestPushMode.NEVER)

        internal fun expandsOpenPullRequestsFor(delivery: CnbWebhookDelivery): Boolean {
            val payload = delivery.payload
            if (openPullRequestPushMode() != CnbOpenPullRequestPushMode.BOTH) return false
            if (delivery.serverId != serverId || payload.repository.slug != repositoryPath) return false
            if (payload.event !in TARGET_PUSH_EVENTS || payload.ref.tag || payload.pullRequest != null) return false
            if (!CnbRefGlob.matches(getRefFilter(), payload.ref.name)) return false
            if (!CnbRefGlob.matches(getTargetBranchFilter(), payload.ref.name)) return false
            return CnbQueueIdentity.from(delivery) != null
        }

        internal fun labelPolicy(): CnbPullRequestLabelPolicy =
            CnbPullRequestLabelPolicy(
                configuredRequiredPullRequestLabels.orEmpty(),
                configuredExcludedPullRequestLabels.orEmpty(),
            )

        internal fun commentPolicy(): CnbPullRequestCommentPolicy? =
            CnbPullRequestCommentPolicy.optional(getCommentPattern(), getCommentMinimumRole())

        internal fun matchesLive(
            delivery: CnbWebhookDelivery,
            snapshot: CnbLiveDeliverySnapshot,
        ): Boolean {
            if (!snapshot.revisionMatches || !matches(delivery)) return false
            if (isCiSkip()) {
                val commitMessage = snapshot.commitMessage ?: return false
                val pullRequestDescription = snapshot.pullRequest?.body.orEmpty()
                if (CnbCiSkip.matches(commitMessage) ||
                    (delivery.payload.event.pullRequestEvent && CnbCiSkip.matches(pullRequestDescription))
                ) {
                    return false
                }
            }
            if (!delivery.payload.event.pullRequestEvent) return true
            if (!labelPolicy().matches(snapshot.labels)) return false
            if (delivery.payload.event != CnbWebhookEvent.PULL_REQUEST_COMMENT) return true
            val policy = commentPolicy() ?: return false
            return snapshot.commentVerified && policy.matches(snapshot.commentBody, snapshot.actorAccessLevels)
        }

        internal fun isEligible(delivery: CnbWebhookDelivery): Boolean {
            val target = job ?: return false
            return target.isBuildable && (matches(delivery) || expandsOpenPullRequestsFor(delivery))
        }

        internal fun shouldCancelRunningBuildsFor(delivery: CnbWebhookDelivery): Boolean {
            if (!isCancelRunningBuildsOnUpdate()) return false
            val pullRequest = delivery.payload.pullRequest ?: return false
            if (CnbQueueIdentity.from(delivery) == null) return false
            return when (delivery.payload.event) {
                CnbWebhookEvent.PULL_REQUEST_UPDATE,
                CnbWebhookEvent.PULL_REQUEST_TARGET,
                -> {
                    true
                }

                CnbWebhookEvent.PULL_REQUEST -> {
                    pullRequest.action.trim().lowercase(Locale.ROOT) in EXPLICIT_PULL_REQUEST_UPDATE_ACTIONS
                }

                else -> {
                    false
                }
            }
        }

        internal fun scheduleVerified(delivery: CnbWebhookDelivery): Boolean {
            val target = job ?: return false
            val queueIdentity = CnbQueueIdentity.from(delivery) ?: return false
            var queued = false
            Queue.withLock(
                Runnable {
                    // Recheck mutable Jenkins state after the dispatcher's API preflight, but never
                    // perform network I/O here: all matching jobs must be validated before any one
                    // is scheduled. The lock also makes supersession and replacement atomic with
                    // respect to Jenkins queue maintenance.
                    if (!target.isBuildable || !matches(delivery)) return@Runnable
                    if (isCancelPendingBuildsOnUpdate()) {
                        val queueTask = target as? Queue.Task ?: return@Runnable
                        CnbPendingBuilds.cancelSuperseded(Queue.getInstance(), queueTask, queueIdentity)
                    }
                    queued =
                        ParameterizedJobMixIn.scheduleBuild2(
                            target,
                            0,
                            CauseAction(CnbPushCause.from(delivery)),
                            CnbQueueAction(queueIdentity),
                        ) != null
                },
            )
            if (queued && shouldCancelRunningBuildsFor(delivery)) {
                CnbRunningBuilds.cancelSuperseded(target, queueIdentity)
            }
            if (queued) {
                LOGGER.log(
                    Level.FINE,
                    "Scheduled {0} for CNB delivery {1}",
                    arrayOf(target.fullName, delivery.payload.deliveryId),
                )
            }
            return queued
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
                    revisionMatches(delivery, client::getBranch, client::getTag, client::getPullRequest)
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

        /** Restores older streams and disables unsafe PR policies that bypassed data-bound setters. */
        @SuppressFBWarnings(
            value = ["RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE"],
            justification = "XStream may restore null into Kotlin non-null fields while bypassing the constructor.",
        )
        protected override fun readResolve(): Any {
            configuredEventFilter =
                runCatching {
                    CnbEventFilter.normalize(configuredEventFilter.orEmpty().ifBlank { DEFAULT_EVENT_FILTER })
                }.getOrDefault(DEFAULT_EVENT_FILTER)
            configuredTriggerOpenPullRequestOnPush =
                CnbOpenPullRequestPushMode.parseOrNever(configuredTriggerOpenPullRequestOnPush).wireName
            var pullRequestPoliciesValid = true
            configuredSourceBranchFilter =
                runCatching { normalizeRefFilter(configuredSourceBranchFilter) }
                    .getOrElse {
                        pullRequestPoliciesValid = false
                        DEFAULT_REF_FILTER
                    }
            configuredTargetBranchFilter =
                runCatching { normalizeRefFilter(configuredTargetBranchFilter) }
                    .getOrElse {
                        pullRequestPoliciesValid = false
                        DEFAULT_REF_FILTER
                    }

            try {
                val labels =
                    CnbPullRequestLabelPolicy(
                        configuredRequiredPullRequestLabels.orEmpty(),
                        configuredExcludedPullRequestLabels.orEmpty(),
                    )
                configuredRequiredPullRequestLabels = labels.requiredConfiguration
                configuredExcludedPullRequestLabels = labels.excludedConfiguration
            } catch (_: IllegalArgumentException) {
                configuredRequiredPullRequestLabels = ""
                configuredExcludedPullRequestLabels = ""
                pullRequestPoliciesValid = false
            }
            if (!pullRequestPoliciesValid) {
                configuredEventFilter = withoutPullRequestEvents(requireNotNull(configuredEventFilter))
            }

            val restoredCommentPolicy =
                runCatching {
                    CnbPullRequestCommentPolicy.optional(
                        configuredCommentPattern,
                        configuredCommentMinimumRole.orEmpty().ifBlank {
                            CnbPullRequestCommentPolicy.DEFAULT_MINIMUM_ROLE
                        },
                    )
                }.getOrNull()
            configuredCommentPattern = restoredCommentPolicy?.pattern.orEmpty()
            configuredCommentMinimumRole =
                restoredCommentPolicy?.minimumRole ?: CnbPullRequestCommentPolicy.DEFAULT_MINIMUM_ROLE
            return super.readResolve()
        }

        @Extension
        @Symbol("cnbPush")
        class DescriptorImpl : TriggerDescriptor {
            private val repositoryLabelLookup: CnbRepositoryLabelLookup

            constructor() : super() {
                repositoryLabelLookup = CnbRepositoryLabelCatalogRuntime
            }

            internal constructor(repositoryLabelLookup: CnbRepositoryLabelLookup) : super() {
                this.repositoryLabelLookup = repositoryLabelLookup
            }

            override fun getDisplayName(): String = "Build on CNB code or pull request events"

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
            fun doCheckEventFilter(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation =
                validateWhenConfigurable(item) {
                    CnbEventFilter.normalize(value.orEmpty().ifBlank { DEFAULT_EVENT_FILTER })
                }

            @POST
            fun doCheckBranchFilter(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation = validateRefFilter(item, value)

            @POST
            fun doCheckSourceBranchFilter(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation = validateRefFilter(item, value)

            @POST
            fun doCheckTargetBranchFilter(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation = validateRefFilter(item, value)

            @POST
            fun doCheckRequiredPullRequestLabels(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
                @QueryParameter excludedPullRequestLabels: String?,
                @QueryParameter serverId: String?,
                @QueryParameter repositoryPath: String?,
            ): FormValidation =
                validateRepositoryLabels(
                    item,
                    required = value,
                    excluded = excludedPullRequestLabels,
                    serverId = serverId,
                    repositoryPath = repositoryPath,
                    validateRequired = true,
                )

            @POST
            fun doCheckExcludedPullRequestLabels(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
                @QueryParameter requiredPullRequestLabels: String?,
                @QueryParameter serverId: String?,
                @QueryParameter repositoryPath: String?,
            ): FormValidation =
                validateRepositoryLabels(
                    item,
                    required = requiredPullRequestLabels,
                    excluded = value,
                    serverId = serverId,
                    repositoryPath = repositoryPath,
                    validateRequired = false,
                )

            @POST
            fun doAutoCompleteRequiredPullRequestLabels(
                @AncestorInPath item: Item?,
                @QueryParameter serverId: String?,
                @QueryParameter repositoryPath: String?,
                @QueryParameter value: String?,
            ): AutoCompletionCandidates = autocompleteRepositoryLabels(item, serverId, repositoryPath, value)

            @POST
            fun doAutoCompleteExcludedPullRequestLabels(
                @AncestorInPath item: Item?,
                @QueryParameter serverId: String?,
                @QueryParameter repositoryPath: String?,
                @QueryParameter value: String?,
            ): AutoCompletionCandidates = autocompleteRepositoryLabels(item, serverId, repositoryPath, value)

            @POST
            fun doCheckCommentPattern(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation =
                validateWhenConfigurable(item) {
                    CnbPullRequestCommentPolicy.optional(value, CnbPullRequestCommentPolicy.DEFAULT_MINIMUM_ROLE)
                }

            fun doFillCommentMinimumRoleItems(): ListBoxModel =
                ListBoxModel().apply {
                    DEFAULT_COMMENT_ROLE_ORDER.forEach { role -> add(role.displayName, role.wireName) }
                }

            fun doFillTriggerOpenPullRequestOnPushItems(): ListBoxModel =
                ListBoxModel().apply {
                    add("Never", CnbOpenPullRequestPushMode.NEVER.wireName)
                    add("Source branch updates", CnbOpenPullRequestPushMode.SOURCE.wireName)
                    add("Source and target branch updates", CnbOpenPullRequestPushMode.BOTH.wireName)
                }

            @POST
            fun doCheckCommentMinimumRole(
                @AncestorInPath item: Item?,
                @QueryParameter value: String?,
            ): FormValidation =
                validateWhenConfigurable(item) {
                    CnbRepositoryRole.parseMinimum(
                        value.orEmpty().ifBlank { CnbPullRequestCommentPolicy.DEFAULT_MINIMUM_ROLE },
                    )
                }

            private fun validateRefFilter(
                item: Item?,
                value: String?,
            ): FormValidation = validateWhenConfigurable(item) { CnbRefGlob.compile(normalizeRefFilter(value)) }

            private fun autocompleteRepositoryLabels(
                item: Item?,
                serverId: String?,
                repositoryPath: String?,
                value: String?,
            ): AutoCompletionCandidates {
                val candidates = AutoCompletionCandidates()
                if (!canLookupRepositoryLabels(item, serverId, repositoryPath)) return candidates
                val query = value.orEmpty().substringAfterLast(',').trim()
                if (query.isEmpty() || query.length > MAX_LABEL_LENGTH) return candidates
                val catalog =
                    repositoryLabelLookup.lookup(
                        requireNotNull(serverId).trim(),
                        requireNotNull(repositoryPath).trim().trim('/'),
                    ) as? CnbRepositoryLabelCatalogResult.Available ?: return candidates
                catalog.labels
                    .asSequence()
                    .filter { label -> label.startsWith(query, ignoreCase = true) }
                    .take(MAX_AUTOCOMPLETE_LABELS)
                    .forEach(candidates::add)
                return candidates
            }

            private fun validateRepositoryLabels(
                item: Item?,
                required: String?,
                excluded: String?,
                serverId: String?,
                repositoryPath: String?,
                validateRequired: Boolean,
            ): FormValidation {
                if (item == null || !item.hasPermission(Item.CONFIGURE)) return FormValidation.ok()
                val policy =
                    try {
                        CnbPullRequestLabelPolicy(required.orEmpty(), excluded.orEmpty())
                    } catch (failure: IllegalArgumentException) {
                        return FormValidation.error(failure.message)
                    }
                val configured =
                    (if (validateRequired) policy.requiredConfiguration else policy.excludedConfiguration)
                        .split(',')
                        .filter(String::isNotEmpty)
                if (configured.isEmpty() || !canLookupRepositoryLabels(item, serverId, repositoryPath)) {
                    return FormValidation.ok()
                }
                return when (
                    val catalog =
                        repositoryLabelLookup.lookup(
                            requireNotNull(serverId).trim(),
                            requireNotNull(repositoryPath).trim().trim('/'),
                        )
                ) {
                    CnbRepositoryLabelCatalogResult.Unavailable -> {
                        FormValidation.warning("Could not verify labels against CNB; runtime matching remains fail-closed")
                    }

                    is CnbRepositoryLabelCatalogResult.Available -> {
                        val unknown = configured.filterNot(catalog.labels.toHashSet()::contains)
                        when {
                            !catalog.complete -> {
                                FormValidation.warning("CNB has more labels than the configuration catalog can display")
                            }

                            unknown.isNotEmpty() -> {
                                FormValidation.warning("One or more labels were not found in the CNB repository")
                            }

                            else -> {
                                FormValidation.ok()
                            }
                        }
                    }
                }
            }

            private fun canLookupRepositoryLabels(
                item: Item?,
                serverId: String?,
                repositoryPath: String?,
            ): Boolean =
                item != null &&
                    item.hasPermission(Item.CONFIGURE) &&
                    serverId?.trim()?.matches(SERVER_ID_PATTERN) == true &&
                    repositoryPath?.trim()?.trim('/')?.let(::isRepositoryPath) == true

            private fun validateWhenConfigurable(
                item: Item?,
                validation: () -> Unit,
            ): FormValidation {
                if (item == null || !item.hasPermission(Item.CONFIGURE)) return FormValidation.ok()
                return try {
                    validation()
                    FormValidation.ok()
                } catch (e: IllegalArgumentException) {
                    FormValidation.error(e.message)
                }
            }
        }

        companion object {
            private const val serialVersionUID = 1L
            private const val DEFAULT_BRANCH_FILTER = "**"
            private const val DEFAULT_REF_FILTER = "**"
            private const val DEFAULT_EVENT_FILTER = "push,tag_push"
            private const val MAX_LABEL_LENGTH = 100
            private const val MAX_AUTOCOMPLETE_LABELS = 50
            private val SERVER_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
            private val LOGGER = Logger.getLogger(CnbPushTrigger::class.java.name)

            internal fun revisionMatches(
                delivery: CnbWebhookDelivery,
                getBranch: (String, String) -> CnbBranch,
                listTags: (String) -> List<CnbTag>,
            ): Boolean =
                revisionMatches(
                    delivery,
                    getBranch,
                    getTag = { repository, name ->
                        listTags(repository).firstOrNull { it.name == name }
                            ?: throw CnbApiException("CNB tag does not exist", statusCode = 404)
                    },
                    getPullRequest = { _, _ -> throw CnbApiException("CNB pull request is unavailable", statusCode = 404) },
                )

            internal fun revisionMatches(
                delivery: CnbWebhookDelivery,
                getBranch: (String, String) -> CnbBranch,
                getTag: (String, String) -> CnbTag,
                getPullRequest: (String, String) -> CnbPullRequest,
            ): Boolean {
                val payload = delivery.payload
                val expected = payload.ref.commit.ifEmpty { payload.ref.sha }

                return try {
                    when (payload.event) {
                        CnbWebhookEvent.PUSH,
                        CnbWebhookEvent.COMMIT_ADD,
                        CnbWebhookEvent.BRANCH_CREATE,
                        -> {
                            if (payload.ref.tag) return false
                            if (!isPresentObjectId(expected)) return false
                            val branch = getBranch(payload.repository.slug, payload.ref.name)
                            branch.name == payload.ref.name && branch.sha.equals(expected, ignoreCase = true)
                        }

                        CnbWebhookEvent.BRANCH_DELETE -> {
                            try {
                                getBranch(payload.repository.slug, payload.ref.name)
                                false
                            } catch (failure: CnbApiException) {
                                if (failure.statusCode == 404) true else throw failure
                            }
                        }

                        CnbWebhookEvent.TAG_PUSH -> {
                            if (!payload.ref.tag) return false
                            if (isPresentObjectId(expected)) {
                                val tag = getTag(payload.repository.slug, payload.ref.name)
                                tag.name == payload.ref.name && tag.sha.equals(expected, ignoreCase = true)
                            } else {
                                try {
                                    getTag(payload.repository.slug, payload.ref.name)
                                    false
                                } catch (failure: CnbApiException) {
                                    if (failure.statusCode == 404) true else throw failure
                                }
                            }
                        }

                        CnbWebhookEvent.PULL_REQUEST,
                        CnbWebhookEvent.PULL_REQUEST_UPDATE,
                        CnbWebhookEvent.PULL_REQUEST_APPROVED,
                        CnbWebhookEvent.PULL_REQUEST_CHANGES_REQUESTED,
                        CnbWebhookEvent.PULL_REQUEST_COMMENT,
                        CnbWebhookEvent.PULL_REQUEST_TARGET,
                        CnbWebhookEvent.PULL_REQUEST_MERGEABLE,
                        CnbWebhookEvent.PULL_REQUEST_MERGED,
                        -> {
                            pullRequestRevisionMatches(payload.event, payload.repository.slug, payload.pullRequest, getPullRequest)
                        }
                    }
                } catch (failure: CnbApiException) {
                    if (failure.statusCode == 404) false else throw failure
                }
            }

            private fun pullRequestRevisionMatches(
                event: CnbWebhookEvent,
                repositoryPath: String,
                advertised: CnbWebhookPullRequest?,
                getPullRequest: (String, String) -> CnbPullRequest,
            ): Boolean {
                advertised ?: return false
                val current = getPullRequest(repositoryPath, advertised.number)
                if (current.number != advertised.number ||
                    current.sourceRepo != advertised.sourceRepository ||
                    current.sourceBranch != advertised.sourceBranch ||
                    !current.sourceSha.equals(advertised.sourceSha, ignoreCase = true) ||
                    current.targetBranch != advertised.targetBranch ||
                    current.draft != advertised.wip
                ) {
                    return false
                }
                if (current.targetRepo.isNotEmpty() && current.targetRepo != repositoryPath) return false
                if (event == CnbWebhookEvent.PULL_REQUEST_MERGED) {
                    val advertisedMerge = advertised.mergeSha
                    val mergeMatches =
                        advertisedMerge != null && current.mergeSha?.equals(advertisedMerge, ignoreCase = true) == true
                    return when (current.state) {
                        CnbPullRequestState.MERGED -> advertisedMerge == null || mergeMatches
                        CnbPullRequestState.CLOSED -> mergeMatches
                        else -> false
                    }
                }
                return current.targetSha.equals(advertised.targetSha, ignoreCase = true)
            }

            private fun normalizeRefFilter(value: String?): String {
                val normalized = value.orEmpty().trim().ifBlank { DEFAULT_REF_FILTER }
                CnbRefGlob.compile(normalized)
                return normalized
            }

            private fun isPresentObjectId(value: String): Boolean = CnbGitObjectId.isPresent(value)

            private val DELETION_CAPABLE_EVENTS = setOf(CnbWebhookEvent.BRANCH_DELETE, CnbWebhookEvent.TAG_PUSH)
            private val TARGET_PUSH_EVENTS = setOf(CnbWebhookEvent.PUSH, CnbWebhookEvent.COMMIT_ADD)
            private val EXPLICIT_PULL_REQUEST_UPDATE_ACTIONS = setOf("update", "synchronize")
            private val DEFAULT_COMMENT_ROLE_ORDER =
                listOf(
                    CnbRepositoryRole.DEVELOPER,
                    CnbRepositoryRole.REPORTER,
                    CnbRepositoryRole.MASTER,
                    CnbRepositoryRole.OWNER,
                )

            @SuppressFBWarnings(
                value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
                justification = "Kotlin collection operators preserve their concrete List implementation here.",
            )
            private fun withoutPullRequestEvents(value: String): String {
                val configured = CnbEventFilter.normalize(value)
                val retained =
                    if (configured == "*") {
                        CnbWebhookEvent.entries.filterNot { it.pullRequestEvent }
                    } else {
                        val names = configured.split(',').toSet()
                        CnbWebhookEvent.entries.filter { !it.pullRequestEvent && it.wireName in names }
                    }
                return retained.joinToString(",") { it.wireName }.ifBlank { DEFAULT_EVENT_FILTER }
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

internal object CnbCiSkip {
    fun matches(commitMessage: String): Boolean {
        val normalized = commitMessage.lowercase(Locale.ROOT)
        return "[ci-skip]" in normalized || "[ci skip]" in normalized || "[skip ci]" in normalized
    }
}

internal object CnbEventFilter {
    fun matches(
        filter: String,
        event: CnbWebhookEvent,
    ): Boolean {
        val normalized = normalize(filter)
        return normalized == "*" || normalized.split(',').any { it == event.wireName }
    }

    @SuppressFBWarnings(
        value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
        justification = "Kotlin collection operators preserve their concrete List implementation here.",
    )
    fun normalize(value: String): String {
        require(value.isNotBlank()) { "Event filter must not be empty" }
        require(value.length <= 2048 && value.none { it.isISOControl() && !it.isWhitespace() }) {
            "Event filter is invalid"
        }
        val names =
            value
                .trim()
                .split(Regex("[,\\s]+"))
                .filter(String::isNotBlank)
                .map { it.lowercase(Locale.ROOT) }
                .toSet()
        require(names.isNotEmpty()) { "Event filter must not be empty" }
        if ("*" in names) {
            require(names.size == 1) { "Wildcard event filter cannot be combined with event names" }
            return "*"
        }
        val supported = CnbWebhookEvent.entries.associateBy { it.wireName }
        val unknown = names - supported.keys
        require(unknown.isEmpty()) { "Unsupported CNB events: ${unknown.sorted().joinToString(",")}" }
        return CnbWebhookEvent.entries
            .map { it.wireName }
            .filter { it in names }
            .joinToString(",")
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
