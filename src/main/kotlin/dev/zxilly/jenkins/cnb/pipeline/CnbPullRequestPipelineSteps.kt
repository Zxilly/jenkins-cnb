package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbCreatePullRequestRequest
import dev.zxilly.jenkins.cnb.api.model.CnbLabel
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestListState
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestState
import dev.zxilly.jenkins.cnb.api.model.CnbPullReview
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewCommentInfo
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewDiffLine
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewReplyRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewer
import dev.zxilly.jenkins.cnb.api.model.CnbReactionSummary
import dev.zxilly.jenkins.cnb.api.model.CnbUpdatePullRequestRequest
import dev.zxilly.jenkins.cnb.api.model.CnbUser
import dev.zxilly.jenkins.cnb.security.CnbGitObjectId
import dev.zxilly.jenkins.cnb.security.CnbRepositoryPath
import hudson.AbortException
import hudson.EnvVars
import hudson.Extension
import hudson.model.Run
import hudson.model.TaskListener
import org.eclipse.jgit.lib.Repository
import org.jenkinsci.Symbol
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import java.io.Serializable
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.Locale

abstract class CnbPullRequestApiStep : CnbContextAwareStep() {
    internal fun pullRequestExecution(
        request: CnbPullRequestStepRequest,
        context: StepContext,
    ): StepExecution = CnbPullRequestStepExecution(input(), request, context)
}

/** Lists pull requests with an explicit, typed open/closed/all filter. */
class CnbPullRequestsStep
    @DataBoundConstructor
    constructor() : CnbPullRequestApiStep() {
        var state: String = CnbPullRequestListState.OPEN.wireValue
            private set

        @DataBoundSetter
        fun setState(value: String?) {
            state = CnbPullRequestInput.listState(value).wireValue
        }

        override fun start(context: StepContext): StepExecution =
            pullRequestExecution(CnbPullRequestStepRequest.List(CnbPullRequestInput.listState(state)), context)

        @Extension
        @Symbol("cnbPullRequests")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbPullRequests"

            override fun getDisplayName(): String = "List CNB pull requests"
        }
    }

/** Reads the pull request resolved from explicit or build context. */
class CnbPullRequestStep
    @DataBoundConstructor
    constructor() : CnbPullRequestApiStep() {
        override fun start(context: StepContext): StepExecution = pullRequestExecution(CnbPullRequestStepRequest.Get, context)

        @Extension
        @Symbol("cnbPullRequest")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbPullRequest"

            override fun getDisplayName(): String = "Read a CNB pull request"
        }
    }

/** Creates a pull request only while its source branch still matches this build SHA. */
class CnbCreatePullRequestStep
    @DataBoundConstructor
    constructor(
        targetBranch: String,
        sourceBranch: String,
        title: String,
    ) : CnbPullRequestApiStep() {
        val targetBranch: String = CnbPullRequestInput.branch(targetBranch, "target")
        val sourceBranch: String = CnbPullRequestInput.branch(sourceBranch, "source")
        val title: String = CnbPullRequestInput.title(title)
        var body: String = ""
            private set
        var sourceRepository: String? = null
            private set

        @DataBoundSetter
        fun setBody(value: String?) {
            body = CnbPullRequestInput.body(value.orEmpty())
        }

        @DataBoundSetter
        fun setSourceRepository(value: String?) {
            sourceRepository = CnbPullRequestInput.repository(value)
        }

        override fun start(context: StepContext): StepExecution =
            pullRequestExecution(
                CnbPullRequestStepRequest.Create(
                    CnbCreatePullRequestRequest(targetBranch, sourceBranch, title, body, sourceRepository),
                ),
                context,
            )

        @Extension
        @Symbol("cnbCreatePullRequest")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbCreatePullRequest"

            override fun getDisplayName(): String = "Create a CNB pull request"
        }
    }

/** Updates explicitly supplied fields on the resolved pull request. */
class CnbUpdatePullRequestStep
    @DataBoundConstructor
    constructor() : CnbPullRequestApiStep() {
        var title: String? = null
            private set
        var body: String? = null
            private set
        var state: String? = null
            private set
        var confirm: String? = null
            private set

        @DataBoundSetter
        fun setTitle(value: String?) {
            title = value?.let(CnbPullRequestInput::title)
        }

        @DataBoundSetter
        fun setBody(value: String?) {
            body = CnbPullRequestInput.body(value.orEmpty())
        }

        @DataBoundSetter
        fun setState(value: String?) {
            state = value?.let(CnbPullRequestInput::updateState)?.wireValue
        }

        @DataBoundSetter
        fun setConfirm(value: String?) {
            confirm = CnbPullRequestInput.optionalConfirmation(value)
        }

        override fun start(context: StepContext): StepExecution {
            require(title != null || body != null || state != null) {
                "CNB pull request update requires at least one explicitly supplied field"
            }
            return pullRequestExecution(
                CnbPullRequestStepRequest.Update(
                    CnbUpdatePullRequestRequest(
                        title,
                        body,
                        state?.let(CnbPullRequestInput::updateState),
                    ),
                    confirm,
                ),
                context,
            )
        }

        @Extension
        @Symbol("cnbUpdatePullRequest")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbUpdatePullRequest"

            override fun getDisplayName(): String = "Update a CNB pull request"
        }
    }

class CnbPullRequestAssigneesStep
    @DataBoundConstructor
    constructor() : CnbPullRequestApiStep() {
        override fun start(context: StepContext): StepExecution = pullRequestExecution(CnbPullRequestStepRequest.ListAssignees, context)

        @Extension
        @Symbol("cnbPullRequestAssignees")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbPullRequestAssignees"

            override fun getDisplayName(): String = "List CNB pull request assignees"
        }
    }

class CnbAddPullRequestAssigneesStep
    @DataBoundConstructor
    constructor(
        usernames: List<String>,
    ) : CnbPullRequestApiStep() {
        val usernames: List<String> = CnbPullRequestInput.participants(usernames, "assignee")

        override fun start(context: StepContext): StepExecution =
            pullRequestExecution(CnbPullRequestStepRequest.AddAssignees(ArrayList(usernames)), context)

        @Extension
        @Symbol("cnbAddPullRequestAssignees")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbAddPullRequestAssignees"

            override fun getDisplayName(): String = "Add CNB pull request assignees"
        }
    }

class CnbRemovePullRequestAssigneesStep
    @DataBoundConstructor
    constructor(
        usernames: List<String>,
        confirm: String,
    ) : CnbPullRequestApiStep() {
        val usernames: List<String> = CnbPullRequestInput.participants(usernames, "assignee")
        val confirm: String = CnbPullRequestInput.confirmation(confirm)

        override fun start(context: StepContext): StepExecution =
            pullRequestExecution(CnbPullRequestStepRequest.RemoveAssignees(ArrayList(usernames), confirm), context)

        @Extension
        @Symbol("cnbRemovePullRequestAssignees")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbRemovePullRequestAssignees"

            override fun getDisplayName(): String = "Remove CNB pull request assignees"
        }
    }

class CnbAddPullRequestReviewersStep
    @DataBoundConstructor
    constructor(
        usernames: List<String>,
    ) : CnbPullRequestApiStep() {
        val usernames: List<String> = CnbPullRequestInput.participants(usernames, "reviewer")

        override fun start(context: StepContext): StepExecution =
            pullRequestExecution(CnbPullRequestStepRequest.AddReviewers(ArrayList(usernames)), context)

        @Extension
        @Symbol("cnbAddPullRequestReviewers")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbAddPullRequestReviewers"

            override fun getDisplayName(): String = "Add CNB pull request reviewers"
        }
    }

class CnbRemovePullRequestReviewersStep
    @DataBoundConstructor
    constructor(
        usernames: List<String>,
        confirm: String,
    ) : CnbPullRequestApiStep() {
        val usernames: List<String> = CnbPullRequestInput.participants(usernames, "reviewer")
        val confirm: String = CnbPullRequestInput.confirmation(confirm)

        override fun start(context: StepContext): StepExecution =
            pullRequestExecution(CnbPullRequestStepRequest.RemoveReviewers(ArrayList(usernames), confirm), context)

        @Extension
        @Symbol("cnbRemovePullRequestReviewers")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbRemovePullRequestReviewers"

            override fun getDisplayName(): String = "Remove CNB pull request reviewers"
        }
    }

class CnbPullRequestCommentsStep
    @DataBoundConstructor
    constructor() : CnbPullRequestApiStep() {
        override fun start(context: StepContext): StepExecution = pullRequestExecution(CnbPullRequestStepRequest.ListComments, context)

        @Extension
        @Symbol("cnbPullRequestComments")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbPullRequestComments"

            override fun getDisplayName(): String = "List CNB pull request comments"
        }
    }

class CnbPullRequestCommentByIdStep
    @DataBoundConstructor
    constructor(
        commentId: String,
    ) : CnbPullRequestApiStep() {
        val commentId: String = CnbPullRequestInput.resourceId(commentId, "comment ID")

        override fun start(context: StepContext): StepExecution =
            pullRequestExecution(CnbPullRequestStepRequest.GetComment(commentId), context)

        @Extension
        @Symbol("cnbPullRequestCommentById")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbPullRequestCommentById"

            override fun getDisplayName(): String = "Read a CNB pull request comment"
        }
    }

class CnbUpdatePullRequestCommentStep
    @DataBoundConstructor
    constructor(
        commentId: String,
        comment: String,
    ) : CnbPullRequestApiStep() {
        val commentId: String = CnbPullRequestInput.resourceId(commentId, "comment ID")
        val comment: String = CnbPullRequestInput.comment(comment)

        override fun start(context: StepContext): StepExecution =
            pullRequestExecution(CnbPullRequestStepRequest.UpdateComment(commentId, comment), context)

        @Extension
        @Symbol("cnbUpdatePullRequestComment")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbUpdatePullRequestComment"

            override fun getDisplayName(): String = "Update a CNB pull request comment"
        }
    }

class CnbPullRequestReviewCommentsStep
    @DataBoundConstructor
    constructor(
        reviewId: String,
    ) : CnbPullRequestApiStep() {
        val reviewId: String = CnbPullRequestInput.resourceId(reviewId, "review ID")

        override fun start(context: StepContext): StepExecution =
            pullRequestExecution(CnbPullRequestStepRequest.ListReviewComments(reviewId), context)

        @Extension
        @Symbol("cnbPullRequestReviewComments")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbPullRequestReviewComments"

            override fun getDisplayName(): String = "List CNB pull request review comments"
        }
    }

class CnbReplyPullRequestReviewCommentStep
    @DataBoundConstructor
    constructor(
        reviewId: String,
        commentId: String,
        body: String,
    ) : CnbPullRequestApiStep() {
        val reviewId: String = CnbPullRequestInput.resourceId(reviewId, "review ID")
        val commentId: String = CnbPullRequestInput.resourceId(commentId, "review comment ID")
        val body: String = CnbPullRequestInput.comment(body)

        override fun start(context: StepContext): StepExecution =
            pullRequestExecution(
                CnbPullRequestStepRequest.ReplyReviewComment(
                    reviewId,
                    CnbPullReviewReplyRequest(body, commentId),
                ),
                context,
            )

        @Extension
        @Symbol("cnbReplyPullRequestReviewComment")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbReplyPullRequestReviewComment"

            override fun getDisplayName(): String = "Reply to a CNB pull request review comment"
        }
    }

internal enum class CnbPullRequestLabelMode(
    val wireValue: String,
) {
    LIST("list"),
    ADD("add"),
    REPLACE("replace"),
    REMOVE("remove"),
    CLEAR("clear"),
}

internal sealed interface CnbPullRequestStepRequest : Serializable {
    data class List(
        val state: CnbPullRequestListState,
    ) : CnbPullRequestStepRequest

    data object Get : CnbPullRequestStepRequest

    data class Create(
        val request: CnbCreatePullRequestRequest,
    ) : CnbPullRequestStepRequest

    data class Update(
        val request: CnbUpdatePullRequestRequest,
        val confirm: String?,
    ) : CnbPullRequestStepRequest

    data object ListAssignees : CnbPullRequestStepRequest

    data class AddAssignees(
        val usernames: kotlin.collections.List<String>,
    ) : CnbPullRequestStepRequest

    data class RemoveAssignees(
        val usernames: kotlin.collections.List<String>,
        val confirm: String,
    ) : CnbPullRequestStepRequest

    data class AddReviewers(
        val usernames: kotlin.collections.List<String>,
    ) : CnbPullRequestStepRequest

    data class RemoveReviewers(
        val usernames: kotlin.collections.List<String>,
        val confirm: String,
    ) : CnbPullRequestStepRequest

    data object ListComments : CnbPullRequestStepRequest

    data class GetComment(
        val commentId: String,
    ) : CnbPullRequestStepRequest

    data class UpdateComment(
        val commentId: String,
        val body: String,
    ) : CnbPullRequestStepRequest

    data class ListReviewComments(
        val reviewId: String,
    ) : CnbPullRequestStepRequest

    data class ReplyReviewComment(
        val reviewId: String,
        val request: CnbPullReviewReplyRequest,
    ) : CnbPullRequestStepRequest
}

private class CnbPullRequestStepExecution(
    private val supplied: CnbRunContextInput,
    private val request: CnbPullRequestStepRequest,
    context: StepContext,
) : SynchronousNonBlockingStepExecution<Any?>(context) {
    override fun run(): Any? {
        val run = context.get(Run::class.java)
        val listener = context.get(TaskListener::class.java)
        val environment = context.get(EnvVars::class.java)
        val resolved = CnbRunContextResolver.resolve(run, listener, supplied, environment)
        return resolved.client(run).use { client -> CnbPullRequestStepDispatcher.execute(request, resolved, client) }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

internal object CnbPullRequestStepDispatcher {
    fun execute(
        request: CnbPullRequestStepRequest,
        context: CnbRunContext,
        client: CnbClient,
    ): Any =
        when (request) {
            is CnbPullRequestStepRequest.List -> {
                CnbPullRequestPipelineValues.pullRequests(
                    client.listPullRequests(context.repository, request.state),
                )
            }

            CnbPullRequestStepRequest.Get -> {
                CnbPullRequestPipelineValues.pullRequest(
                    client.getPullRequest(context.repository, context.requirePullRequestNumber()),
                )
            }

            is CnbPullRequestStepRequest.Create -> {
                CnbPullRequestMutationGuard.requireFreshSourceBranch(context, client, request.request)
                val created = client.createPullRequest(context.repository, request.request)
                CnbPullRequestPipelineValues.pullRequest(created)
            }

            is CnbPullRequestStepRequest.Update -> {
                val number = CnbPullRequestMutationGuard.requireFresh(context, client)
                if (request.request.state == CnbPullRequestState.CLOSED) {
                    CnbPullRequestMutationGuard.requireConfirmation(request.confirm, number, "close")
                }
                val updated = client.updatePullRequest(context.repository, number, request.request)
                CnbPullRequestPipelineValues.pullRequest(updated)
            }

            CnbPullRequestStepRequest.ListAssignees -> {
                CnbPullRequestPipelineValues.users(
                    client.listPullAssignees(context.repository, context.requirePullRequestNumber()),
                )
            }

            is CnbPullRequestStepRequest.AddAssignees -> {
                val number = CnbPullRequestMutationGuard.requireFresh(context, client)
                CnbPullRequestPipelineValues.pullRequest(
                    client.addPullAssignees(context.repository, number, request.usernames),
                )
            }

            is CnbPullRequestStepRequest.RemoveAssignees -> {
                val number = CnbPullRequestMutationGuard.requireFresh(context, client)
                CnbPullRequestMutationGuard.requireConfirmation(request.confirm, number, "remove assignees from")
                CnbPullRequestPipelineValues.pullRequest(
                    client.removePullAssignees(context.repository, number, request.usernames),
                )
            }

            is CnbPullRequestStepRequest.AddReviewers -> {
                val number = CnbPullRequestMutationGuard.requireFresh(context, client)
                CnbPullRequestPipelineValues.pullRequest(
                    client.addPullReviewers(context.repository, number, request.usernames),
                )
            }

            is CnbPullRequestStepRequest.RemoveReviewers -> {
                val number = CnbPullRequestMutationGuard.requireFresh(context, client)
                CnbPullRequestMutationGuard.requireConfirmation(request.confirm, number, "remove reviewers from")
                CnbPullRequestPipelineValues.pullRequest(
                    client.removePullReviewers(context.repository, number, request.usernames),
                )
            }

            CnbPullRequestStepRequest.ListComments -> {
                CnbPullRequestPipelineValues.comments(
                    client.listPullComments(context.repository, context.requirePullRequestNumber()),
                )
            }

            is CnbPullRequestStepRequest.GetComment -> {
                CnbPullRequestPipelineValues.comment(
                    client.getPullComment(
                        context.repository,
                        context.requirePullRequestNumber(),
                        request.commentId,
                    ),
                )
            }

            is CnbPullRequestStepRequest.UpdateComment -> {
                val number = CnbPullRequestMutationGuard.requireFresh(context, client)
                CnbPullRequestPipelineValues.comment(
                    client.updatePullComment(context.repository, number, request.commentId, request.body),
                )
            }

            is CnbPullRequestStepRequest.ListReviewComments -> {
                CnbPullRequestPipelineValues.reviewComments(
                    client.listPullReviewComments(
                        context.repository,
                        context.requirePullRequestNumber(),
                        request.reviewId,
                    ),
                )
            }

            is CnbPullRequestStepRequest.ReplyReviewComment -> {
                val number = CnbPullRequestMutationGuard.requireFresh(context, client)
                CnbPullRequestPipelineValues.reviewComment(
                    client.replyToPullReviewComment(context.repository, number, request.reviewId, request.request),
                )
            }
        }
}

internal object CnbPullRequestMutationGuard {
    fun requireFresh(
        context: CnbRunContext,
        client: CnbClient,
    ): String {
        val number = context.requirePullRequestNumber()
        val expectedSha = requireFullSha(context.requireSha())
        val currentSha = client.getPullRequest(context.repository, number).sourceSha
        if (!CnbGitObjectId.isValid(currentSha)) {
            throw AbortException("CNB returned a missing or non-full source SHA for pull request $number; refusing to mutate it")
        }
        if (!currentSha.equals(expectedSha, ignoreCase = true)) {
            throw AbortException(
                "CNB pull request $number source SHA no longer matches this build; refusing to mutate a stale pull request",
            )
        }
        return number
    }

    fun requireFreshSourceBranch(
        context: CnbRunContext,
        client: CnbClient,
        request: CnbCreatePullRequestRequest,
    ) {
        val expectedSha = requireFullSha(context.requireSha())
        val sourceRepository = request.sourceRepository ?: context.repository
        val currentSha = client.getBranch(sourceRepository, request.sourceBranch).sha
        if (!CnbGitObjectId.isValid(currentSha)) {
            throw AbortException("CNB returned a missing or non-full source branch SHA; refusing to create the pull request")
        }
        if (!currentSha.equals(expectedSha, ignoreCase = true)) {
            throw AbortException("CNB source branch SHA no longer matches this build; refusing to create a stale pull request")
        }
    }

    fun requireConfirmation(
        confirm: String?,
        number: String,
        operation: String,
    ) {
        if (confirm != number) {
            throw AbortException("CNB confirmation must exactly match pull request number $number to $operation it")
        }
    }

    private fun requireFullSha(value: String): String {
        if (!CnbGitObjectId.isValid(value)) {
            throw AbortException(
                "CNB pull request mutation requires a full 40- or 64-character commit SHA; " +
                    "resolve or set sha from the pull request source revision",
            )
        }
        return value
    }
}

internal object CnbPullRequestInput {
    private const val MAX_TITLE = 1_000
    private const val MAX_BODY = 1024 * 1024
    private const val MAX_COMMENT = 60_000
    private const val MAX_BRANCH = 1_024
    private const val MAX_USERNAME = 256
    private val RESOURCE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

    fun listState(value: String?): CnbPullRequestListState {
        val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: CnbPullRequestListState.OPEN.wireValue
        return CnbPullRequestListState.entries.firstOrNull { it.wireValue == normalized }
            ?: throw IllegalArgumentException("CNB pull request list state must be open, closed, or all")
    }

    fun updateState(value: String): CnbPullRequestState {
        val normalized = value.trim().lowercase(Locale.ROOT)
        val state =
            CnbPullRequestState.entries.firstOrNull { it.wireValue == normalized }
                ?: throw IllegalArgumentException("CNB pull request state must be open or closed")
        require(state != CnbPullRequestState.MERGED) { "CNB pull request state cannot be updated to merged" }
        return state
    }

    fun labelMode(value: String): CnbPullRequestLabelMode {
        val normalized = value.trim().lowercase(Locale.ROOT)
        return CnbPullRequestLabelMode.entries.firstOrNull { it.wireValue == normalized }
            ?: throw IllegalArgumentException("CNB pull request label mode must be list, add, replace, remove, or clear")
    }

    fun labels(values: List<String>): List<String> {
        require(values.size <= 100) { "CNB pull request label count must not exceed 100" }
        values.forEach { value ->
            require(value.isNotBlank() && value.length <= 256) { "CNB pull request label is invalid" }
            require(value == value.trim() && value.none { it.code < 0x20 || it.code == 0x7f }) {
                "CNB pull request label is invalid"
            }
        }
        require(values.distinct().size == values.size) { "CNB pull request labels must be unique" }
        return ArrayList(values)
    }

    fun branch(
        value: String,
        field: String,
    ): String {
        require(value == value.trim()) { "CNB pull request $field branch must not have surrounding whitespace" }
        require(
            value.length in 1..MAX_BRANCH && !value.startsWith("refs/") &&
                Repository.isValidRefName("refs/heads/$value"),
        ) { "CNB pull request $field branch is invalid" }
        return value
    }

    fun title(value: String): String {
        require(value.isNotBlank() && value.length <= MAX_TITLE) { "CNB pull request title is invalid" }
        require(value.none { it.code < 0x20 || it.code == 0x7f }) { "CNB pull request title contains control characters" }
        return value
    }

    fun body(value: String): String {
        require(value.length <= MAX_BODY) { "CNB pull request body is too long" }
        require(value.none(::unsafeBodyCharacter)) { "CNB pull request body contains control characters" }
        return value
    }

    fun comment(value: String): String {
        require(value.isNotBlank() && value.length <= MAX_COMMENT) { "CNB pull request comment is invalid" }
        require(value.none(::unsafeBodyCharacter)) { "CNB pull request comment contains control characters" }
        return value
    }

    fun repository(value: String?): String? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        require(CnbRepositoryPath.isValid(normalized)) { "CNB pull request source repository is invalid" }
        return normalized
    }

    fun participants(
        values: List<String>,
        field: String,
    ): List<String> {
        require(values.size in 1..8) { "CNB pull request $field count must be between 1 and 8" }
        require(values.distinct().size == values.size) { "CNB pull request ${field}s must be unique" }
        values.forEach { value ->
            require(
                value.length in 1..MAX_USERNAME && value == value.trim() &&
                    value.none { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f },
            ) { "CNB pull request $field is invalid" }
        }
        return ArrayList(values)
    }

    fun resourceId(
        value: String,
        field: String,
    ): String {
        val normalized = value.trim()
        require(RESOURCE_ID.matches(normalized)) { "CNB pull request $field is invalid" }
        return normalized
    }

    fun confirmation(value: String): String {
        require(value.isNotEmpty() && value.length <= 19 && value.all(Char::isDigit)) {
            "CNB pull request confirmation must be a pull request number"
        }
        return value
    }

    fun optionalConfirmation(value: String?): String? = value?.let(::confirmation)

    private fun unsafeBodyCharacter(value: Char): Boolean =
        value.code == 0x7f || (value.code < 0x20 && value != '\r' && value != '\n' && value != '\t')
}

internal object CnbPullRequestPipelineValues {
    fun pullRequests(values: List<CnbPullRequest>): ArrayList<LinkedHashMap<String, Any?>> =
        ArrayList<LinkedHashMap<String, Any?>>().apply {
            values.forEach { add(pullRequest(it)) }
        }

    fun pullRequest(value: CnbPullRequest): LinkedHashMap<String, Any?> =
        mapOfValues(
            "number" to value.number,
            "title" to value.title,
            "state" to value.state.wireValue,
            "sourceRepo" to value.sourceRepo,
            "sourceBranch" to value.sourceBranch,
            "sourceSha" to value.sourceSha,
            "targetRepo" to value.targetRepo,
            "targetBranch" to value.targetBranch,
            "targetSha" to value.targetSha,
            "mergeSha" to value.mergeSha,
            "author" to value.author,
            "fromFork" to value.fromFork,
            "draft" to value.draft,
            "updatedAt" to value.updatedAt,
            "body" to value.body,
            "blockedOn" to value.blockedOn?.wireValue,
            "mergeableState" to value.mergeableState?.wireValue,
            "labels" to labels(value.labels),
            "assignees" to users(value.assignees),
            "reviewers" to
                ArrayList<LinkedHashMap<String, Any?>>().apply {
                    value.reviewers.forEach { add(reviewer(it)) }
                },
            "authorInfo" to value.authorInfo?.let(::user),
            "mergedBy" to value.mergedBy?.let(::user),
            "createdAt" to value.createdAt,
        )

    fun users(values: List<CnbUser>): ArrayList<LinkedHashMap<String, Any?>> =
        ArrayList<LinkedHashMap<String, Any?>>().apply {
            values.forEach { add(user(it)) }
        }

    fun labels(values: List<CnbLabel>): ArrayList<LinkedHashMap<String, Any?>> =
        ArrayList<LinkedHashMap<String, Any?>>().apply {
            values.forEach { add(label(it)) }
        }

    fun label(value: CnbLabel): LinkedHashMap<String, Any?> =
        mapOfValues(
            "id" to value.id,
            "name" to value.name,
            "color" to value.color,
            "description" to value.description,
        )

    fun comments(values: List<dev.zxilly.jenkins.cnb.api.model.CnbPullComment>): ArrayList<LinkedHashMap<String, Any?>> =
        ArrayList<LinkedHashMap<String, Any?>>().apply {
            values.forEach { add(comment(it)) }
        }

    fun comment(value: dev.zxilly.jenkins.cnb.api.model.CnbPullComment): LinkedHashMap<String, Any?> =
        mapOfValues(
            "id" to value.id,
            "body" to value.body,
            "author" to value.author,
            "createdAt" to value.createdAt,
            "updatedAt" to value.updatedAt,
        )

    fun reviews(values: List<CnbPullReview>): ArrayList<LinkedHashMap<String, Any?>> =
        ArrayList<LinkedHashMap<String, Any?>>().apply {
            values.forEach { add(review(it)) }
        }

    fun review(value: CnbPullReview): LinkedHashMap<String, Any?> =
        mapOfValues(
            "id" to value.id,
            "body" to value.body,
            "state" to value.state.wireValue,
            "author" to value.author,
            "createdAt" to value.createdAt,
            "updatedAt" to value.updatedAt,
        )

    fun reviewComments(values: List<CnbPullReviewCommentInfo>): ArrayList<LinkedHashMap<String, Any?>> =
        ArrayList<LinkedHashMap<String, Any?>>().apply {
            values.forEach { add(reviewComment(it)) }
        }

    fun reviewComment(value: CnbPullReviewCommentInfo): LinkedHashMap<String, Any?> =
        mapOfValues(
            "id" to value.id,
            "reviewId" to value.reviewId,
            "body" to value.body,
            "author" to value.author?.let(::user),
            "commitSha" to value.commitSha,
            "path" to value.path,
            "reviewState" to value.reviewState?.wireValue,
            "replyToCommentId" to value.replyToCommentId,
            "subjectType" to value.subjectType?.wireValue,
            "startLine" to value.startLine,
            "startSide" to value.startSide?.wireValue,
            "endLine" to value.endLine,
            "endSide" to value.endSide?.wireValue,
            "diffHunk" to
                ArrayList<LinkedHashMap<String, Any?>>().apply {
                    value.diffHunk.forEach { add(diffLine(it)) }
                },
            "reactions" to
                ArrayList<LinkedHashMap<String, Any?>>().apply {
                    value.reactions.forEach { add(reaction(it)) }
                },
            "createdAt" to value.createdAt,
            "updatedAt" to value.updatedAt,
        )

    fun acknowledgement(
        operation: String,
        number: String,
    ): LinkedHashMap<String, Any?> = mapOfValues("operation" to operation, "number" to number)

    private fun user(value: CnbUser): LinkedHashMap<String, Any?> =
        mapOfValues(
            "username" to value.username,
            "nickname" to value.nickname,
            "email" to value.email,
            "frozen" to value.frozen,
            "npc" to value.npc,
        )

    private fun reviewer(value: CnbPullReviewer): LinkedHashMap<String, Any?> =
        mapOfValues(
            "user" to user(value.user),
            "reviewState" to value.reviewState?.wireValue,
        )

    private fun diffLine(value: CnbPullReviewDiffLine): LinkedHashMap<String, Any?> =
        mapOfValues(
            "content" to value.content,
            "type" to value.type.wireValue,
            "prefix" to value.prefix,
            "leftLineNumber" to value.leftLineNumber,
            "rightLineNumber" to value.rightLineNumber,
        )

    private fun reaction(value: CnbReactionSummary): LinkedHashMap<String, Any?> =
        mapOfValues(
            "reaction" to value.reaction,
            "count" to value.count,
            "reactedByCurrentUser" to value.reactedByCurrentUser,
            "topUsers" to users(value.topUsers),
        )

    private fun mapOfValues(vararg entries: Pair<String, Any?>): LinkedHashMap<String, Any?> =
        LinkedHashMap<String, Any?>(entries.size).apply {
            entries.forEach { (key, value) -> put(key, value) }
        }
}
