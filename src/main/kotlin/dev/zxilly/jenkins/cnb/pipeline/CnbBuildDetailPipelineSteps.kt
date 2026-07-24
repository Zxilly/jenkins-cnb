package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStage
import hudson.AbortException
import hudson.EnvVars
import hudson.Extension
import hudson.FilePath
import hudson.model.Run
import hudson.model.TaskListener
import org.jenkinsci.Symbol
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.jenkinsci.plugins.workflow.steps.SynchronousNonBlockingStepExecution
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import java.io.Serializable
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.UUID

abstract class CnbBuildDetailStep : CnbContextAwareStep() {
    internal fun buildDetailExecution(
        request: CnbBuildDetailRequest,
        context: StepContext,
    ): StepExecution =
        if (request is CnbBuildDetailRequest.DownloadRunnerLog) {
            CnbBuildDetailTransferExecution(input(), request, context)
        } else {
            CnbBuildDetailExecution(input(), request, context)
        }
}

/** Reads one typed CNB stage, including bounded stage log lines returned by that endpoint. */
class CnbBuildStageStep
    @DataBoundConstructor
    constructor(
        sn: String,
        pipelineId: String,
        stageId: String,
    ) : CnbBuildDetailStep() {
        val sn: String = CnbBuildResourceId.parse(sn, "build serial number").value
        val pipelineId: String = CnbBuildResourceId.parse(pipelineId, "pipeline ID").value
        val stageId: String = CnbBuildResourceId.parse(stageId, "stage ID").value

        override fun start(context: StepContext): StepExecution =
            buildDetailExecution(
                CnbBuildDetailRequest.Stage(
                    CnbBuildResourceId.parse(sn, "build serial number"),
                    CnbBuildResourceId.parse(pipelineId, "pipeline ID"),
                    CnbBuildResourceId.parse(stageId, "stage ID"),
                ),
                context,
            )

        @Extension
        @Symbol("cnbBuildStage")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbBuildStage"

            override fun getDisplayName(): String = "Read a CNB build stage"
        }
    }

/** Downloads a runner log to the workspace without returning its body to Groovy or the listener. */
class CnbDownloadBuildRunnerLogStep
    @DataBoundConstructor
    constructor(
        pipelineId: String,
        path: String,
    ) : CnbBuildDetailStep() {
        val pipelineId: String = CnbBuildResourceId.parse(pipelineId, "pipeline ID").value
        val path: String = CnbWorkspaceRelativePath.parse(path).value
        var overwrite: Boolean = false
            private set
        var maxBytes: Long = CnbReleaseTransferLimit.MAX_BYTES
            private set

        @DataBoundSetter
        fun setOverwrite(value: Boolean) {
            overwrite = value
        }

        @DataBoundSetter
        fun setMaxBytes(value: Long) {
            maxBytes = CnbReleaseTransferLimit.parse(value).bytes
        }

        override fun start(context: StepContext): StepExecution =
            buildDetailExecution(
                CnbBuildDetailRequest.DownloadRunnerLog(
                    CnbBuildResourceId.parse(pipelineId, "pipeline ID"),
                    CnbWorkspaceRelativePath.parse(path),
                    overwrite,
                    CnbReleaseTransferLimit.parse(maxBytes),
                ),
                context,
            )

        @Extension
        @Symbol("cnbDownloadBuildRunnerLog")
        class DescriptorImpl : CnbWorkspaceApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbDownloadBuildRunnerLog"

            override fun getDisplayName(): String = "Download a CNB build runner log to the workspace"
        }
    }

@JvmInline
internal value class CnbBuildResourceId private constructor(
    val value: String,
) : Serializable {
    companion object {
        private val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

        fun parse(
            value: String,
            field: String,
        ): CnbBuildResourceId {
            val normalized = value.trim()
            require(PATTERN.matches(normalized)) { "CNB $field is invalid" }
            return CnbBuildResourceId(normalized)
        }
    }
}

internal sealed interface CnbBuildDetailRequest : Serializable {
    data class Stage(
        val sn: CnbBuildResourceId,
        val pipelineId: CnbBuildResourceId,
        val stageId: CnbBuildResourceId,
    ) : CnbBuildDetailRequest

    data class DownloadRunnerLog(
        val pipelineId: CnbBuildResourceId,
        val path: CnbWorkspaceRelativePath,
        val overwrite: Boolean,
        val limit: CnbReleaseTransferLimit,
    ) : CnbBuildDetailRequest
}

private class CnbBuildDetailExecution(
    private val supplied: CnbRunContextInput,
    private val request: CnbBuildDetailRequest,
    context: StepContext,
) : SynchronousNonBlockingStepExecution<Any>(context) {
    override fun run(): Any {
        val run = context.get(Run::class.java)
        val listener = context.get(TaskListener::class.java)
        val environment = context.get(EnvVars::class.java)
        val resolved = CnbRunContextResolver.resolve(run, listener, supplied, environment)
        val workspace = context.get(FilePath::class.java)
        return resolved.client(run).use { client ->
            CnbBuildDetailDispatcher.execute(request, resolved, client, workspace)
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

private class CnbBuildDetailTransferExecution(
    private val supplied: CnbRunContextInput,
    private val request: CnbBuildDetailRequest.DownloadRunnerLog,
    context: StepContext,
    transferId: String? = UUID.randomUUID().toString(),
) : CnbRestartableAsyncStepExecution<Any>(context, "CNB build runner log transfer") {
    private var transferId: String? = transferId

    override fun runAttempt(
        attempt: Int,
        resumed: Boolean,
    ): Any {
        val run = context.get(Run::class.java)
        val listener = context.get(TaskListener::class.java)
        if (resumed) {
            listener.logger.printf("[CNB] Retrying interrupted build runner log transfer (attempt %d)%n", attempt)
        }
        val environment = context.get(EnvVars::class.java)
        val resolved = CnbRunContextResolver.resolve(run, listener, supplied, environment)
        val workspace = context.get(FilePath::class.java)
        val currentTransferId = stableTransferId()
        return resolved.client(run).use { client ->
            CnbBuildDetailDispatcher.execute(
                request,
                resolved,
                client,
                workspace,
                transferId = currentTransferId,
                resumed = resumed,
            )
        }
    }

    override fun afterUnsuccessfulCompletion() {
        CnbReleaseWorkspaceTransfer.cleanupDownloadTemporary(
            context.get(FilePath::class.java),
            request.path,
            stableTransferId(),
        )
    }

    private fun stableTransferId(): String =
        synchronized(this) {
            transferId ?: UUID.randomUUID().toString().also { transferId = it }
        }

    companion object {
        private const val serialVersionUID = 1L
    }
}

internal object CnbBuildDetailDispatcher {
    fun execute(
        request: CnbBuildDetailRequest,
        context: CnbRunContext,
        client: CnbClient,
        workspace: FilePath? = null,
        transferId: String = UUID.randomUUID().toString(),
        resumed: Boolean = false,
    ): Any =
        when (request) {
            is CnbBuildDetailRequest.Stage -> {
                CnbBuildDetailPipelineValues.stage(
                    client.getBuildStage(
                        context.repository,
                        request.sn.value,
                        request.pipelineId.value,
                        request.stageId.value,
                    ),
                )
            }

            is CnbBuildDetailRequest.DownloadRunnerLog -> {
                val requiredWorkspace =
                    workspace ?: throw AbortException("CNB runner log download requires a Jenkins workspace")
                val downloaded =
                    CnbReleaseWorkspaceTransfer.downloadToWorkspace(
                        requiredWorkspace,
                        request.path,
                        request.overwrite,
                        request.limit,
                        "CNB build runner log was not found",
                        transferId = transferId,
                        resumed = resumed,
                    ) { target ->
                        client
                            .downloadBuildRunnerLog(
                                context.repository,
                                request.pipelineId.value,
                                target,
                                request.limit.bytes,
                            ).let { value ->
                                CnbReleaseWorkspaceTransfer.CnbWorkspaceDownloadMetadata(
                                    value.contentLength,
                                    value.contentType,
                                    value.etag,
                                )
                            }
                    }
                CnbBuildDetailPipelineValues.download(
                    request.pipelineId.value,
                    request.path.value,
                    request.overwrite,
                    downloaded,
                )
            }
        }
}

internal object CnbBuildDetailPipelineValues {
    fun stage(value: CnbBuildStage): LinkedHashMap<String, Any?> =
        mapOfValues(
            "id" to value.id,
            "name" to value.name,
            "status" to value.status.wireValue,
            "duration" to value.duration,
            "startTime" to value.startTime,
            "endTime" to value.endTime,
            "error" to value.error,
            "content" to ArrayList(value.content),
        )

    fun download(
        pipelineId: String,
        path: String,
        overwrite: Boolean,
        value: CnbReleaseWorkspaceTransfer.CnbWorkspaceDownloadMetadata,
    ): LinkedHashMap<String, Any?> =
        mapOfValues(
            "operation" to "downloaded",
            "pipelineId" to pipelineId,
            "path" to path,
            "size" to value.contentLength,
            "contentType" to value.contentType,
            "etag" to value.etag,
            "overwrite" to overwrite,
        )

    private fun mapOfValues(vararg entries: Pair<String, Any?>): LinkedHashMap<String, Any?> =
        LinkedHashMap<String, Any?>(entries.size).apply {
            entries.forEach { (key, value) -> put(key, value) }
        }
}
