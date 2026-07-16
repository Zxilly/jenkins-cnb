package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.model.CnbBuildEventName
import dev.zxilly.jenkins.cnb.api.model.CnbBuildHistory
import dev.zxilly.jenkins.cnb.api.model.CnbBuildInfo
import dev.zxilly.jenkins.cnb.api.model.CnbBuildPipeline
import dev.zxilly.jenkins.cnb.api.model.CnbCommit
import dev.zxilly.jenkins.cnb.api.model.CnbCommitComparison
import dev.zxilly.jenkins.cnb.api.model.CnbCommitDiffFile
import dev.zxilly.jenkins.cnb.api.model.CnbCommitPerson
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatus
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatuses
import dev.zxilly.jenkins.cnb.api.model.CnbPullFile
import dev.zxilly.jenkins.cnb.api.model.CnbPullReview
import hudson.Extension
import org.jenkinsci.Symbol
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import java.time.LocalDate
import java.util.LinkedHashMap

/** Reads one CNB commit by branch, tag, or SHA. */
class CnbCommitStep
    @DataBoundConstructor
    constructor(
        ref: String,
    ) : CnbContextAwareStep() {
        val ref: String = CnbReadInput.required(ref, "commit ref")

        override fun start(context: StepContext): StepExecution = execution(CnbStepRequest.Commit(ref), context)

        @Extension
        @Symbol("cnbCommit")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbCommit"

            override fun getDisplayName(): String = "Read a CNB commit"
        }
    }

/** Lists CNB commits with the filters supported by the OpenAPI. */
class CnbCommitsStep
    @DataBoundConstructor
    constructor() : CnbContextAwareStep() {
        var author: String? = null
            private set
        var committer: String? = null
            private set
        var since: String? = null
            private set
        var until: String? = null
            private set

        @DataBoundSetter fun setAuthor(value: String?) {
            author = CnbReadInput.optional(value, "commit author")
        }

        @DataBoundSetter fun setCommitter(value: String?) {
            committer = CnbReadInput.optional(value, "commit committer")
        }

        @DataBoundSetter fun setSince(value: String?) {
            since = CnbReadInput.optional(value, "commit since")
        }

        @DataBoundSetter fun setUntil(value: String?) {
            until = CnbReadInput.optional(value, "commit until")
        }

        override fun start(context: StepContext): StepExecution =
            execution(
                CnbStepRequest.Commits(CnbReadInput.optional(sha, "commit SHA"), author, committer, since, until),
                context,
                input().copy(sha = null),
            )

        @Extension
        @Symbol("cnbCommits")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbCommits"

            override fun getDisplayName(): String = "List CNB commits"
        }
    }

/** Compares two CNB commit references. */
class CnbCompareCommitsStep
    @DataBoundConstructor
    constructor(
        base: String,
        head: String,
    ) : CnbContextAwareStep() {
        val base: String = CnbReadInput.required(base, "base ref")
        val head: String = CnbReadInput.required(head, "head ref")

        override fun start(context: StepContext): StepExecution = execution(CnbStepRequest.CompareCommits(base, head), context)

        @Extension
        @Symbol("cnbCompareCommits")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbCompareCommits"

            override fun getDisplayName(): String = "Compare CNB commits"
        }
    }

/** Lists commits belonging to the resolved CNB pull request. */
class CnbPullRequestCommitsStep
    @DataBoundConstructor
    constructor() : CnbContextAwareStep() {
        override fun start(context: StepContext): StepExecution = execution(CnbStepRequest.PullRequestCommits, context)

        @Extension
        @Symbol("cnbPullRequestCommits")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbPullRequestCommits"

            override fun getDisplayName(): String = "List CNB pull request commits"
        }
    }

/** Lists files changed by the resolved CNB pull request. */
class CnbPullRequestFilesStep
    @DataBoundConstructor
    constructor() : CnbContextAwareStep() {
        override fun start(context: StepContext): StepExecution = execution(CnbStepRequest.PullRequestFiles, context)

        @Extension
        @Symbol("cnbPullRequestFiles")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbPullRequestFiles"

            override fun getDisplayName(): String = "List CNB pull request files"
        }
    }

/** Reads the aggregate and individual statuses for a CNB pull request. */
class CnbPullRequestStatusesStep
    @DataBoundConstructor
    constructor() : CnbContextAwareStep() {
        override fun start(context: StepContext): StepExecution = execution(CnbStepRequest.PullRequestStatuses, context)

        @Extension
        @Symbol("cnbPullRequestStatuses")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbPullRequestStatuses"

            override fun getDisplayName(): String = "Read CNB pull request statuses"
        }
    }

/** Lists reviews for the resolved CNB pull request. */
class CnbPullRequestReviewsStep
    @DataBoundConstructor
    constructor() : CnbContextAwareStep() {
        override fun start(context: StepContext): StepExecution = execution(CnbStepRequest.PullRequestReviews, context)

        @Extension
        @Symbol("cnbPullRequestReviews")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbPullRequestReviews"

            override fun getDisplayName(): String = "List CNB pull request reviews"
        }
    }

/** Reads CNB build history with every filter exposed by [dev.zxilly.jenkins.cnb.api.model.CnbBuildHistoryQuery]. */
class CnbBuildHistoryStep
    @DataBoundConstructor
    constructor() : CnbContextAwareStep() {
        var createTime: String? = null
            private set
        var endTime: String? = null
            private set
        var event: String? = null
            private set
        var sn: String? = null
            private set
        var sourceRef: String? = null
            private set
        var status: String? = null
            private set
        var targetRef: String? = null
            private set
        var userId: String? = null
            private set
        var userName: String? = null
            private set

        @DataBoundSetter fun setCreateTime(value: String?) {
            createTime = CnbReadInput.date(value, "createTime")
        }

        @DataBoundSetter fun setEndTime(value: String?) {
            endTime = CnbReadInput.date(value, "endTime")
        }

        @DataBoundSetter fun setEvent(value: String?) {
            event = CnbReadInput.optional(value, "build event")
        }

        @DataBoundSetter fun setSn(value: String?) {
            sn = CnbReadInput.optional(value, "build serial number")
        }

        @DataBoundSetter fun setSourceRef(value: String?) {
            sourceRef = CnbReadInput.optional(value, "build source ref")
        }

        @DataBoundSetter fun setStatus(value: String?) {
            status = CnbReadInput.optional(value, "build status")
        }

        @DataBoundSetter fun setTargetRef(value: String?) {
            targetRef = CnbReadInput.optional(value, "build target ref")
        }

        @DataBoundSetter fun setUserId(value: String?) {
            userId = CnbReadInput.optional(value, "build user ID")
        }

        @DataBoundSetter fun setUserName(value: String?) {
            userName = CnbReadInput.optional(value, "build user name")
        }

        override fun start(context: StepContext): StepExecution =
            execution(
                CnbStepRequest.BuildHistory(
                    createTime = createTime,
                    endTime = endTime,
                    event = event?.let(::CnbBuildEventName),
                    sha = CnbReadInput.optional(sha, "build SHA"),
                    sn = sn,
                    sourceRef = sourceRef,
                    status = status,
                    targetRef = targetRef,
                    userId = userId,
                    userName = userName,
                ),
                context,
                input().copy(sha = null),
            )

        @Extension
        @Symbol("cnbBuildHistory")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbBuildHistory"

            override fun getDisplayName(): String = "Read CNB build history"
        }
    }

internal object CnbReadInput {
    private const val MAX_FILTER_LENGTH = 1_024

    fun required(
        value: String,
        name: String,
    ): String =
        optional(value, name)
            ?: throw IllegalArgumentException("CNB $name must not be blank")

    fun optional(
        value: String?,
        name: String,
    ): String? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        require(normalized.length <= MAX_FILTER_LENGTH) { "CNB $name must be at most $MAX_FILTER_LENGTH characters" }
        require(normalized.none { it.code < 0x20 || it.code == 0x7f }) { "CNB $name contains control characters" }
        return normalized
    }

    fun date(
        value: String?,
        name: String,
    ): String? {
        val normalized = optional(value, name) ?: return null
        require(runCatching { LocalDate.parse(normalized) }.isSuccess) { "CNB $name must use YYYY-MM-DD" }
        return normalized
    }
}

/** Converts every read-only domain result into types safely persisted by Pipeline CPS. */
internal object CnbReadPipelineValues {
    fun commit(value: CnbCommit): LinkedHashMap<String, Any?> =
        mapOfValues(
            "sha" to value.sha,
            "message" to value.message,
            "author" to person(value.author),
            "committer" to person(value.committer),
            "parentShas" to ArrayList(value.parentShas),
        )

    fun commits(values: List<CnbCommit>): ArrayList<LinkedHashMap<String, Any?>> =
        ArrayList<LinkedHashMap<String, Any?>>().apply {
            values.forEach { add(commit(it)) }
        }

    fun comparison(value: CnbCommitComparison): LinkedHashMap<String, Any?> =
        mapOfValues(
            "baseCommit" to value.baseCommit?.let(::commit),
            "headCommit" to value.headCommit?.let(::commit),
            "mergeBaseCommit" to value.mergeBaseCommit?.let(::commit),
            "commits" to commits(value.commits),
            "files" to
                ArrayList<LinkedHashMap<String, Any?>>().apply {
                    value.files.forEach { add(comparisonFile(it)) }
                },
            "totalCommits" to value.totalCommits,
        )

    fun pullFiles(values: List<CnbPullFile>): ArrayList<LinkedHashMap<String, Any?>> =
        ArrayList<LinkedHashMap<String, Any?>>().apply {
            values.forEach { add(pullFile(it)) }
        }

    fun statuses(value: CnbCommitStatuses): LinkedHashMap<String, Any?> =
        mapOfValues(
            "sha" to value.sha,
            "state" to value.state.wireValue,
            "statuses" to
                ArrayList<LinkedHashMap<String, Any?>>().apply {
                    value.statuses.forEach { add(commitStatus(it)) }
                },
        )

    fun reviews(values: List<CnbPullReview>): ArrayList<LinkedHashMap<String, Any?>> =
        ArrayList<LinkedHashMap<String, Any?>>().apply {
            values.forEach { add(review(it)) }
        }

    fun buildHistory(value: CnbBuildHistory): LinkedHashMap<String, Any?> =
        mapOfValues(
            "total" to value.total,
            "timestamp" to value.timestamp,
            "builds" to
                ArrayList<LinkedHashMap<String, Any?>>().apply {
                    value.builds.forEach { add(build(it)) }
                },
        )

    private fun person(value: CnbCommitPerson): LinkedHashMap<String, Any?> =
        mapOfValues(
            "username" to value.username,
            "nickname" to value.nickname,
            "name" to value.name,
            "email" to value.email,
            "date" to value.date,
        )

    private fun comparisonFile(value: CnbCommitDiffFile): LinkedHashMap<String, Any?> =
        mapOfValues(
            "path" to value.path,
            "name" to value.name,
            "previousFilename" to value.previousFilename,
            "status" to value.status.wireValue,
            "additions" to value.additions,
            "deletions" to value.deletions,
            "patch" to value.patch,
            "mode" to value.mode?.wireValue,
            "previousMode" to value.previousMode?.wireValue,
        )

    private fun pullFile(value: CnbPullFile): LinkedHashMap<String, Any?> =
        mapOfValues(
            "filename" to value.filename,
            "status" to value.status.wireValue,
            "sha" to value.sha,
            "additions" to value.additions,
            "deletions" to value.deletions,
            "patch" to value.patch,
            "blobUrl" to value.blobUrl,
            "rawUrl" to value.rawUrl,
            "contentsUrl" to value.contentsUrl,
        )

    private fun commitStatus(value: CnbCommitStatus): LinkedHashMap<String, Any?> =
        mapOfValues(
            "context" to value.context,
            "state" to value.state.wireValue,
            "description" to value.description,
            "targetUrl" to value.targetUrl,
            "createdAt" to value.createdAt,
            "updatedAt" to value.updatedAt,
        )

    private fun review(value: CnbPullReview): LinkedHashMap<String, Any?> =
        mapOfValues(
            "id" to value.id,
            "body" to value.body,
            "state" to value.state.wireValue,
            "author" to value.author,
            "createdAt" to value.createdAt,
            "updatedAt" to value.updatedAt,
        )

    private fun build(value: CnbBuildInfo): LinkedHashMap<String, Any?> =
        mapOfValues(
            "sn" to value.sn,
            "sha" to value.sha,
            "slug" to value.slug,
            "status" to value.status.wireValue,
            "event" to value.event?.wireValue,
            "sourceRef" to value.sourceRef,
            "sourceSlug" to value.sourceSlug,
            "targetRef" to value.targetRef,
            "title" to value.title,
            "commitTitle" to value.commitTitle,
            "buildLogUrl" to value.buildLogUrl,
            "eventUrl" to value.eventUrl,
            "createTime" to value.createTime,
            "duration" to value.duration,
            "labels" to value.labels,
            "groupName" to value.groupName,
            "userName" to value.userName,
            "nickName" to value.nickName,
            "freeze" to value.freeze,
            "pipelineFailCount" to value.pipelineFailCount,
            "pipelineSuccessCount" to value.pipelineSuccessCount,
            "pipelineTotalCount" to value.pipelineTotalCount,
            "pipelines" to
                ArrayList<LinkedHashMap<String, Any?>>().apply {
                    value.pipelines.forEach { add(pipeline(it)) }
                },
        )

    private fun pipeline(value: CnbBuildPipeline): LinkedHashMap<String, Any?> =
        mapOfValues(
            "id" to value.id,
            "status" to value.status.wireValue,
            "createTime" to value.createTime,
            "duration" to value.duration,
            "labels" to value.labels,
        )

    private fun mapOfValues(vararg entries: Pair<String, Any?>): LinkedHashMap<String, Any?> =
        LinkedHashMap<String, Any?>(entries.size).apply {
            entries.forEach { (key, value) -> put(key, value) }
        }
}
