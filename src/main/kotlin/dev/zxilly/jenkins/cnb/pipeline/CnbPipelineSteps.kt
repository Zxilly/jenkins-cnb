package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbBuildEventName
import dev.zxilly.jenkins.cnb.api.model.CnbBuildHistoryQuery
import dev.zxilly.jenkins.cnb.api.model.CnbBuildNpc
import dev.zxilly.jenkins.cnb.api.model.CnbBuildNpcName
import dev.zxilly.jenkins.cnb.api.model.CnbBuildRequest
import dev.zxilly.jenkins.cnb.api.model.CnbBuildResult
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStage
import dev.zxilly.jenkins.cnb.api.model.CnbBuildState
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStatus
import dev.zxilly.jenkins.cnb.api.model.CnbBuildTriggerEvent
import dev.zxilly.jenkins.cnb.api.model.CnbCommitQuery
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatus
import dev.zxilly.jenkins.cnb.api.model.CnbMergePullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbMergePullResult
import dev.zxilly.jenkins.cnb.api.model.CnbPipelineLabel
import dev.zxilly.jenkins.cnb.api.model.CnbPipelineStatus
import dev.zxilly.jenkins.cnb.api.model.CnbPullMergeStyle
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewComment
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewEvent
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewSide
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewSubjectType
import hudson.AbortException
import hudson.EnvVars
import hudson.Extension
import hudson.model.Run
import hudson.model.TaskListener
import org.jenkinsci.Symbol
import org.jenkinsci.plugins.workflow.steps.Step
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepDescriptor
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import java.io.Serializable
import java.util.LinkedHashMap
import java.util.Locale

/** Shared optional CNB destination fields used by all API Pipeline steps. */
abstract class CnbContextAwareStep : Step() {
    var serverId: String? = null
        private set
    var repository: String? = null
        private set
    var pullRequestNumber: String? = null
        private set
    var sha: String? = null
        private set
    var credentialsId: String? = null
        private set

    @DataBoundSetter
    fun setServerId(value: String?) {
        serverId = clean(value)
    }

    @DataBoundSetter
    fun setRepository(value: String?) {
        repository = clean(value)
    }

    @DataBoundSetter
    fun setPullRequestNumber(value: String?) {
        pullRequestNumber = clean(value)
    }

    @DataBoundSetter
    fun setSha(value: String?) {
        sha = clean(value)
    }

    @DataBoundSetter
    fun setCredentialsId(value: String?) {
        credentialsId = clean(value)
    }

    protected fun input(): CnbRunContextInput = CnbRunContextInput(serverId, repository, pullRequestNumber, sha, credentialsId)

    internal fun execution(
        request: CnbStepRequest,
        context: StepContext,
        supplied: CnbRunContextInput = input(),
    ): StepExecution = CnbStepExecution(supplied, request, context)

    private fun clean(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty)
}

/** Adds a comment to the resolved CNB pull request and returns the created comment as a Map. */
class CnbPullRequestCommentStep
    @DataBoundConstructor
    constructor(
        comment: String,
    ) : CnbContextAwareStep() {
        val comment: String = comment

        init {
            require(this.comment.isNotBlank()) { "CNB pull request comment must not be blank" }
            require(this.comment.length <= MAX_COMMENT_LENGTH) { "CNB pull request comment is too long" }
        }

        override fun start(context: StepContext): StepExecution = execution(CnbStepRequest.PullRequestComment(comment), context)

        @Extension
        @Symbol("cnbPullRequestComment")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbPullRequestComment"

            override fun getDisplayName(): String = "Add a comment to a CNB pull request"
        }

        companion object {
            private const val MAX_COMMENT_LENGTH = 60_000
        }
    }

/** Checks the current labels from CNB and returns true when an exact label name exists. */
class CnbPullRequestLabelExistsStep
    @DataBoundConstructor
    constructor(
        label: String,
    ) : CnbContextAwareStep() {
        val label: String = label.trim()

        init {
            require(this.label.isNotEmpty()) { "CNB pull request label must not be blank" }
        }

        override fun start(context: StepContext): StepExecution = execution(CnbStepRequest.PullRequestLabelExists(label), context)

        @Extension
        @Symbol("cnbPullRequestLabelExists")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbPullRequestLabelExists"

            override fun getDisplayName(): String = "Check a CNB pull request label"
        }
    }

/** Adds, replaces, or removes CNB pull request labels and returns CNB's label acknowledgements. */
class CnbPullRequestLabelsStep
    @DataBoundConstructor
    constructor(
        mode: String,
        labels: List<String>,
    ) : CnbContextAwareStep() {
        val mode: String = CnbPullRequestInput.labelMode(mode).wireValue
        val labels: List<String> = CnbPullRequestInput.labels(labels)
        var confirm: String? = null
            private set

        init {
            val parsed = CnbPullRequestInput.labelMode(this.mode)
            require(parsed in setOf(CnbPullRequestLabelMode.LIST, CnbPullRequestLabelMode.CLEAR) || this.labels.isNotEmpty()) {
                "CNB add, replace, and remove label modes require at least one label"
            }
        }

        @DataBoundSetter
        fun setConfirm(value: String?) {
            confirm = CnbPullRequestInput.optionalConfirmation(value)
        }

        override fun start(context: StepContext): StepExecution =
            execution(CnbStepRequest.PullRequestLabels(CnbPullRequestInput.labelMode(mode), labels, confirm), context)

        @Extension
        @Symbol("cnbPullRequestLabels")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbPullRequestLabels"

            override fun getDisplayName(): String = "Update CNB pull request labels"
        }
    }

/** Submits an approve, comment, or request-changes review and returns true on acceptance. */
class CnbReviewPullRequestStep
    @DataBoundConstructor
    constructor(
        action: String,
    ) : CnbContextAwareStep() {
        val action: String = parseReviewEvent(action).wireValue
        var body: String? = null
            private set
        var comments: List<CnbPullRequestReviewComment> = emptyList()
            private set

        @DataBoundSetter
        fun setBody(value: String?) {
            val normalized = value?.takeIf(String::isNotBlank)
            require(normalized == null || normalized.length <= MAX_REVIEW_BODY_LENGTH) {
                "CNB pull request review body is too long"
            }
            body = normalized
        }

        @DataBoundSetter
        fun setComments(value: List<CnbPullRequestReviewComment>?) {
            comments = ArrayList(value.orEmpty())
        }

        override fun start(context: StepContext): StepExecution =
            execution(
                CnbStepRequest.ReviewPullRequest(
                    parseReviewEvent(action),
                    body.orEmpty(),
                    ArrayList<CnbPullReviewComment>().apply {
                        comments.forEach { add(it.toApi()) }
                    },
                ),
                context,
            )

        @Extension
        @Symbol("cnbReviewPullRequest")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbReviewPullRequest"

            override fun getDisplayName(): String = "Review a CNB pull request"
        }

        companion object {
            private const val MAX_REVIEW_BODY_LENGTH = 60_000

            private fun parseReviewEvent(value: String): CnbPullReviewEvent {
                val normalized = value.trim().lowercase(Locale.ROOT)
                return CnbPullReviewEvent.entries.firstOrNull { it.wireValue == normalized }
                    ?: throw IllegalArgumentException(
                        "CNB review action must be approve, comment, request_changes, or pending",
                    )
            }
        }
    }

/** One optional file or line comment included in a CNB pull request review. */
class CnbPullRequestReviewComment
    @DataBoundConstructor
    constructor(
        body: String,
        path: String,
    ) : Serializable {
        val body: String = body
        val path: String = path.trim()
        var subjectType: CnbPullReviewSubjectType = CnbPullReviewSubjectType.FILE
            private set
        var startLine: Int? = null
            private set
        var startSide: CnbPullReviewSide? = null
            private set
        var endLine: Int? = null
            private set
        var endSide: CnbPullReviewSide? = null
            private set

        init {
            require(this.body.isNotBlank() && this.body.length <= 60_000) { "Invalid CNB review comment body" }
            require(this.path.isNotEmpty() && this.path.length <= 4_096) { "Invalid CNB review comment path" }
        }

        @DataBoundSetter fun setSubjectType(value: String?) {
            subjectType = parseSubjectType(value)
        }

        @DataBoundSetter fun setStartLine(value: Int?) {
            startLine = value
        }

        @DataBoundSetter fun setStartSide(value: String?) {
            startSide = value?.let(::parseSide)
        }

        @DataBoundSetter fun setEndLine(value: Int?) {
            endLine = value
        }

        @DataBoundSetter fun setEndSide(value: String?) {
            endSide = value?.let(::parseSide)
        }

        internal fun toApi(): CnbPullReviewComment {
            if (subjectType == CnbPullReviewSubjectType.LINE) {
                val resolvedEndLine = endLine
                require(resolvedEndLine != null && resolvedEndLine > 0 && endSide != null) {
                    "CNB line review comments require a positive endLine and endSide left or right"
                }
                val resolvedStartLine = startLine
                if (resolvedStartLine != null || startSide != null) {
                    require(resolvedStartLine != null && resolvedStartLine > 0 && startSide != null) {
                        "CNB line review comments require a valid startLine and startSide"
                    }
                }
            }
            return CnbPullReviewComment(
                body,
                path,
                subjectType,
                startLine,
                startSide,
                endLine,
                endSide,
            )
        }

        companion object {
            private const val serialVersionUID = 1L

            private fun parseSubjectType(value: String?): CnbPullReviewSubjectType {
                val normalized = value?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: "file"
                return CnbPullReviewSubjectType.entries.firstOrNull { it.wireValue == normalized }
                    ?: throw IllegalArgumentException("CNB review comment subjectType must be file or line")
            }

            private fun parseSide(value: String): CnbPullReviewSide {
                val normalized = value.trim().lowercase(Locale.ROOT)
                return CnbPullReviewSide.entries.firstOrNull { it.wireValue == normalized }
                    ?: throw IllegalArgumentException("CNB review comment side must be left or right")
            }
        }
    }

/** Merges a pull request with CNB's merge, squash, or rebase strategy. */
class CnbMergePullRequestStep
    @DataBoundConstructor
    constructor(
        method: String,
    ) : CnbContextAwareStep() {
        val method: String = parseMergeStyle(method).wireValue
        var commitTitle: String? = null
            private set
        var commitMessage: String? = null
            private set

        @DataBoundSetter
        fun setCommitTitle(value: String?) {
            commitTitle = value?.trim()?.takeIf(String::isNotEmpty)
        }

        @DataBoundSetter
        fun setCommitMessage(value: String?) {
            commitMessage = value?.trim()?.takeIf(String::isNotEmpty)
        }

        override fun start(context: StepContext): StepExecution =
            execution(
                CnbStepRequest.MergePullRequest(
                    parseMergeStyle(method),
                    commitTitle.orEmpty(),
                    commitMessage.orEmpty(),
                ),
                context,
            )

        @Extension
        @Symbol("cnbMergePullRequest")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbMergePullRequest"

            override fun getDisplayName(): String = "Merge a CNB pull request"
        }

        companion object {
            private fun parseMergeStyle(value: String): CnbPullMergeStyle {
                val normalized = value.trim().lowercase(Locale.ROOT)
                return CnbPullMergeStyle.entries.firstOrNull { it.wireValue == normalized }
                    ?: throw IllegalArgumentException("CNB merge method must be merge, squash, or rebase")
            }
        }
    }

/** Returns CNB commit statuses without modifying repository state. */
class CnbCommitStatusesStep
    @DataBoundConstructor
    constructor() : CnbContextAwareStep() {
        override fun start(context: StepContext): StepExecution = execution(CnbStepRequest.CommitStatuses, context)

        @Extension
        @Symbol("cnbCommitStatuses")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbCommitStatuses"

            override fun getDisplayName(): String = "Read CNB commit statuses"
        }
    }

/** Starts a CNB build. Only API-trigger event names are accepted. */
class CnbStartBuildStep
    @DataBoundConstructor
    constructor(
        event: String,
    ) : CnbContextAwareStep() {
        val event: String = CnbBuildTriggerEvent(event.trim()).wireValue
        var branch: String? = null
            private set
        var tag: String? = null
            private set
        var title: String? = null
            private set
        var config: String? = null
            private set
        var sync: Boolean = false
            private set
        var env: Map<String, String> = emptyMap()
            private set
        var npcName: String? = null
            private set
        var npcWorkMode: Boolean = false
            private set

        @DataBoundSetter fun setBranch(value: String?) {
            branch = value?.trim()?.takeIf(String::isNotEmpty)
        }

        @DataBoundSetter fun setTag(value: String?) {
            tag = value?.trim()?.takeIf(String::isNotEmpty)
        }

        @DataBoundSetter fun setTitle(value: String?) {
            title = value?.trim()?.takeIf(String::isNotEmpty)
        }

        @DataBoundSetter fun setConfig(value: String?) {
            config = value?.takeIf(String::isNotEmpty)
        }

        @DataBoundSetter fun setSync(value: Boolean) {
            sync = value
        }

        @DataBoundSetter fun setEnv(value: Map<String, String>?) {
            env = LinkedHashMap(value.orEmpty())
        }

        @DataBoundSetter fun setNpcName(value: String?) {
            npcName = value?.trim()?.takeIf(String::isNotEmpty)
            require(npcName == null || npcName == "CodeBuddy") { "CNB only supports the CodeBuddy NPC" }
        }

        @DataBoundSetter fun setNpcWorkMode(value: Boolean) {
            npcWorkMode = value
        }

        override fun start(context: StepContext): StepExecution =
            execution(
                CnbStepRequest.StartBuild(
                    event = CnbBuildTriggerEvent(event),
                    branch = branch,
                    tag = tag,
                    title = title,
                    config = config,
                    sync = sync,
                    env = env,
                    npcName = npcName,
                    npcWorkMode = npcWorkMode,
                ),
                context,
            )

        @Extension
        @Symbol("cnbStartBuild")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbStartBuild"

            override fun getDisplayName(): String = "Start a CNB build"
        }
    }

/** Returns the current CNB build status and its pipeline/stage details. */
class CnbBuildStatusStep
    @DataBoundConstructor
    constructor(
        sn: String,
    ) : CnbContextAwareStep() {
        val sn: String = sn.trim()

        init {
            require(this.sn.isNotEmpty() && this.sn.length <= 256) { "CNB build serial number must not be blank" }
        }

        override fun start(context: StepContext): StepExecution = execution(CnbStepRequest.BuildStatus(sn), context)

        @Extension
        @Symbol("cnbBuildStatus")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbBuildStatus"

            override fun getDisplayName(): String = "Read CNB build status"
        }
    }

/** Stops a CNB build and returns CNB's acknowledgement. */
class CnbStopBuildStep
    @DataBoundConstructor
    constructor(
        sn: String,
    ) : CnbContextAwareStep() {
        val sn: String = sn.trim()

        init {
            require(this.sn.isNotEmpty() && this.sn.length <= 256) { "CNB build serial number must not be blank" }
        }

        override fun start(context: StepContext): StepExecution = execution(CnbStepRequest.StopBuild(sn), context)

        @Extension
        @Symbol("cnbStopBuild")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbStopBuild"

            override fun getDisplayName(): String = "Stop a CNB build"
        }
    }

abstract class CnbApiStepDescriptor : StepDescriptor() {
    override fun getRequiredContext(): Set<Class<*>> = setOf(Run::class.java, TaskListener::class.java, EnvVars::class.java)

    override fun takesImplicitBlockArgument(): Boolean = false
}

internal sealed interface CnbStepRequest : Serializable {
    data class PullRequestComment(
        val comment: String,
    ) : CnbStepRequest

    data class PullRequestLabelExists(
        val label: String,
    ) : CnbStepRequest

    data class PullRequestLabels(
        val mode: CnbPullRequestLabelMode,
        val labels: List<String>,
        val confirm: String?,
    ) : CnbStepRequest

    data class Commit(
        val ref: String,
    ) : CnbStepRequest

    data class Commits(
        val sha: String?,
        val author: String?,
        val committer: String?,
        val since: String?,
        val until: String?,
    ) : CnbStepRequest

    data class CompareCommits(
        val base: String,
        val head: String,
    ) : CnbStepRequest

    data class CommitAnnotations(
        val commitHashes: List<String>,
        val keys: List<String>,
    ) : CnbStepRequest

    data class ReviewPullRequest(
        val action: CnbPullReviewEvent,
        val body: String,
        val comments: List<CnbPullReviewComment> = emptyList(),
    ) : CnbStepRequest

    data class MergePullRequest(
        val method: CnbPullMergeStyle,
        val commitTitle: String,
        val commitMessage: String,
    ) : CnbStepRequest

    data class StartBuild(
        val event: CnbBuildTriggerEvent,
        val branch: String?,
        val tag: String?,
        val title: String?,
        val config: String?,
        val sync: Boolean,
        val env: Map<String, String>,
        val npcName: String?,
        val npcWorkMode: Boolean,
    ) : CnbStepRequest

    data class BuildStatus(
        val sn: String,
    ) : CnbStepRequest

    data class StopBuild(
        val sn: String,
    ) : CnbStepRequest

    data class BuildHistory(
        val createTime: String?,
        val endTime: String?,
        val event: CnbBuildEventName?,
        val sha: String?,
        val sn: String?,
        val sourceRef: String?,
        val status: String?,
        val targetRef: String?,
        val userId: String?,
        val userName: String?,
    ) : CnbStepRequest

    data object PullRequestCommits : CnbStepRequest

    data object PullRequestFiles : CnbStepRequest

    data object PullRequestStatuses : CnbStepRequest

    data object PullRequestReviews : CnbStepRequest

    data object CommitStatuses : CnbStepRequest
}

private class CnbStepExecution(
    private val supplied: CnbRunContextInput,
    private val request: CnbStepRequest,
    context: StepContext,
) : SynchronousNonBlockingStepExecution<Any?>(context) {
    override fun run(): Any? {
        val run = context.get(Run::class.java)
        val listener = context.get(TaskListener::class.java)
        val environment = context.get(EnvVars::class.java)
        val resolved = CnbRunContextResolver.resolve(run, listener, supplied, environment)
        return resolved.client(run).use { client ->
            CnbStepDispatcher.execute(request.expand(environment), resolved, client)
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** Pure operation dispatcher kept separate from Jenkins CPS plumbing for deterministic tests. */
internal object CnbStepDispatcher {
    fun execute(
        request: CnbStepRequest,
        context: CnbRunContext,
        client: CnbClient,
    ): Any =
        when (request) {
            is CnbStepRequest.PullRequestComment -> {
                if (!client.capabilities.supportsPullComments) {
                    throw AbortException("The selected CNB server does not support pull request comments")
                }
                val number = CnbPullRequestMutationGuard.requireFresh(context, client)
                CnbPullRequestPipelineValues.comment(
                    client.createPullComment(
                        context.repository,
                        number,
                        request.comment,
                    ),
                )
            }

            is CnbStepRequest.PullRequestLabelExists -> {
                client
                    .listPullLabels(context.repository, context.requirePullRequestNumber())
                    .any { it.name == request.label }
            }

            is CnbStepRequest.PullRequestLabels -> {
                if (request.mode == CnbPullRequestLabelMode.LIST) {
                    CnbPullRequestPipelineValues.labels(
                        client.listPullLabels(context.repository, context.requirePullRequestNumber()),
                    )
                } else {
                    val number = CnbPullRequestMutationGuard.requireFresh(context, client)
                    if (request.mode in
                        setOf(
                            CnbPullRequestLabelMode.REPLACE,
                            CnbPullRequestLabelMode.REMOVE,
                            CnbPullRequestLabelMode.CLEAR,
                        )
                    ) {
                        CnbPullRequestMutationGuard.requireConfirmation(request.confirm, number, request.mode.wireValue)
                    }
                    when (request.mode) {
                        CnbPullRequestLabelMode.ADD -> {
                            ArrayList<LinkedHashMap<String, Any?>>().apply {
                                request.labels.forEach {
                                    add(
                                        CnbPullRequestPipelineValues.label(
                                            client.addPullLabel(context.repository, number, it),
                                        ),
                                    )
                                }
                            }
                        }

                        CnbPullRequestLabelMode.REPLACE -> {
                            arrayListOf(
                                CnbPullRequestPipelineValues.label(client.replacePullLabels(context.repository, number, request.labels)),
                            )
                        }

                        CnbPullRequestLabelMode.REMOVE -> {
                            ArrayList<LinkedHashMap<String, Any?>>().apply {
                                request.labels.forEach {
                                    add(
                                        CnbPullRequestPipelineValues.label(
                                            client.removePullLabel(context.repository, number, it),
                                        ),
                                    )
                                }
                            }
                        }

                        CnbPullRequestLabelMode.CLEAR -> {
                            client.clearPullLabels(context.repository, number)
                            CnbPullRequestPipelineValues.acknowledgement("labels-cleared", number)
                        }

                        CnbPullRequestLabelMode.LIST -> {
                            throw AbortException("Unsupported CNB pull request label mode")
                        }
                    }
                }
            }

            is CnbStepRequest.Commit -> {
                CnbReadPipelineValues.commit(client.getCommit(context.repository, request.ref))
            }

            is CnbStepRequest.Commits -> {
                CnbReadPipelineValues.commits(
                    client.listCommits(
                        context.repository,
                        CnbCommitQuery(request.sha, request.author, request.committer, request.since, request.until),
                    ),
                )
            }

            is CnbStepRequest.CompareCommits -> {
                CnbReadPipelineValues.comparison(
                    client.compareCommits(context.repository, request.base, request.head),
                )
            }

            is CnbStepRequest.CommitAnnotations -> {
                CnbReadPipelineValues.commitAnnotations(
                    client.getCommitAnnotationsInBatch(context.repository, request.commitHashes, request.keys),
                )
            }

            CnbStepRequest.PullRequestCommits -> {
                CnbReadPipelineValues.commits(
                    client.listPullCommits(context.repository, context.requirePullRequestNumber()),
                )
            }

            CnbStepRequest.PullRequestFiles -> {
                CnbReadPipelineValues.pullFiles(
                    client.listPullFiles(context.repository, context.requirePullRequestNumber()),
                )
            }

            CnbStepRequest.PullRequestStatuses -> {
                CnbReadPipelineValues.statuses(
                    client.listPullCommitStatuses(context.repository, context.requirePullRequestNumber()),
                )
            }

            CnbStepRequest.PullRequestReviews -> {
                CnbReadPipelineValues.reviews(
                    client.listPullReviews(context.repository, context.requirePullRequestNumber()),
                )
            }

            is CnbStepRequest.ReviewPullRequest -> {
                val number = CnbPullRequestMutationGuard.requireFresh(context, client)
                client.createPullReview(
                    context.repository,
                    number,
                    CnbPullReviewRequest(
                        request.action,
                        request.body,
                        request.comments,
                    ),
                )
                true
            }

            is CnbStepRequest.MergePullRequest -> {
                val number = CnbPullRequestMutationGuard.requireFresh(context, client)
                val result =
                    client.mergePullRequest(
                        context.repository,
                        number,
                        CnbMergePullRequest(
                            request.method,
                            request.commitTitle,
                            request.commitMessage,
                        ),
                    )
                if (!result.merged) {
                    throw AbortException(
                        result.message.takeIf(String::isNotBlank)?.let { "CNB did not merge the pull request: $it" }
                            ?: "CNB did not merge the pull request",
                    )
                }
                result.asPipelineValue()
            }

            is CnbStepRequest.StartBuild -> {
                client
                    .startBuild(
                        context.repository,
                        CnbBuildRequest(
                            event = request.event,
                            branch = request.branch,
                            tag = request.tag,
                            sha = context.sha,
                            title = request.title,
                            config = request.config,
                            sync = request.sync,
                            env = request.env,
                            npc = request.npcName?.let { CnbBuildNpc(CnbBuildNpcName.CODE_BUDDY, request.npcWorkMode) },
                        ),
                    ).asPipelineValue()
            }

            is CnbStepRequest.BuildStatus -> {
                client.getBuildStatus(context.repository, request.sn).asPipelineValue()
            }

            is CnbStepRequest.StopBuild -> {
                client.stopBuild(context.repository, request.sn).asPipelineValue()
            }

            is CnbStepRequest.BuildHistory -> {
                CnbReadPipelineValues.buildHistory(
                    client.listBuildHistory(
                        context.repository,
                        CnbBuildHistoryQuery(
                            createTime = request.createTime,
                            endTime = request.endTime,
                            event = request.event,
                            sha = request.sha,
                            sn = request.sn,
                            sourceRef = request.sourceRef,
                            status =
                                request.status?.let { raw ->
                                    CnbBuildState.entries.firstOrNull { it.wireValue == raw }
                                        ?: throw AbortException("Unsupported CNB build history status")
                                },
                            targetRef = request.targetRef,
                            userId = request.userId,
                            userName = request.userName,
                        ),
                    ),
                )
            }

            CnbStepRequest.CommitStatuses -> {
                ArrayList<Map<String, String>>().apply {
                    client.listCommitStatuses(context.repository, context.requireSha()).forEach {
                        add(it.asPipelineValue())
                    }
                }
            }
        }

    private fun CnbCommitStatus.asPipelineValue(): Map<String, String> =
        LinkedHashMap<String, String>().apply {
            put("context", context)
            put("state", state.wireValue)
            put("description", description)
            put("targetUrl", targetUrl)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
        }

    private fun CnbMergePullResult.asPipelineValue(): Map<String, Any> =
        linkedMapOf(
            "merged" to merged,
            "message" to message,
            "sha" to sha,
        )

    private fun CnbBuildResult.asPipelineValue(): Map<String, Any> =
        linkedMapOf(
            "sn" to sn,
            "buildLogUrl" to buildLogUrl,
            "message" to message,
            "success" to success,
        )

    private fun CnbBuildStatus.asPipelineValue(): Map<String, Any> =
        linkedMapOf(
            "status" to status.wireValue,
            "pipelinesStatus" to pipelinesStatus.mapValues { (_, pipeline) -> pipeline.asPipelineValue() },
        )

    private fun CnbPipelineStatus.asPipelineValue(): Map<String, Any> =
        linkedMapOf(
            "id" to id,
            "name" to name,
            "status" to status.wireValue,
            "duration" to duration,
            "metricCoreHours" to metricCoreHours,
            "metricDuration" to metricDuration,
            "labels" to
                ArrayList<Map<String, Any>>().apply {
                    labels.forEach { add(it.asPipelineValue()) }
                },
            "stages" to
                ArrayList<Map<String, Any>>().apply {
                    stages.forEach { add(it.asPipelineValue()) }
                },
        )

    private fun CnbPipelineLabel.asPipelineValue(): Map<String, Any> = linkedMapOf("key" to key, "values" to values)

    private fun CnbBuildStage.asPipelineValue(): Map<String, Any> =
        linkedMapOf(
            "id" to id,
            "name" to name,
            "status" to status.wireValue,
            "duration" to duration,
        )
}

private fun CnbStepRequest.expand(environment: EnvVars): CnbStepRequest =
    when (this) {
        is CnbStepRequest.StartBuild -> {
            copy(
                branch = branch?.let(environment::expand),
                tag = tag?.let(environment::expand),
                title = title?.let(environment::expand),
                config = config?.let(environment::expand),
                env = env.mapValues { (_, value) -> environment.expand(value) },
                npcName = npcName?.let(environment::expand),
            )
        }

        else -> {
            this
        }
    }
