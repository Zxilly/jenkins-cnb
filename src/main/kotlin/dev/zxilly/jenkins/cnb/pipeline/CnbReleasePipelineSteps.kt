package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbCreateReleaseRequest
import dev.zxilly.jenkins.cnb.api.model.CnbRelease
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAsset
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAssetHead
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseMakeLatest
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseUser
import dev.zxilly.jenkins.cnb.api.model.CnbUpdateReleaseRequest
import dev.zxilly.jenkins.cnb.security.CnbGitObjectId
import hudson.AbortException
import hudson.EnvVars
import hudson.Extension
import hudson.FilePath
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
import java.util.LinkedHashSet
import java.util.Locale
import java.util.UUID

/** Common execution boundary for strongly typed CNB release operations. */
abstract class CnbReleaseStep : CnbContextAwareStep() {
    internal fun releaseExecution(
        request: CnbReleaseStepRequest,
        context: StepContext,
    ): StepExecution =
        if (request is CnbReleaseStepRequest.UploadAsset || request is CnbReleaseStepRequest.DownloadAsset) {
            CnbReleaseTransferStepExecution(input(), request, context)
        } else {
            CnbReleaseStepExecution(input(), request, context)
        }
}

/** Lists all releases in the resolved CNB repository. */
class CnbReleasesStep
    @DataBoundConstructor
    constructor() : CnbReleaseStep() {
        override fun start(context: StepContext): StepExecution = releaseExecution(CnbReleaseStepRequest.ListReleases, context)

        @Extension
        @Symbol("cnbReleases")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbReleases"

            override fun getDisplayName(): String = "List CNB releases"
        }
    }

/** Returns the release CNB currently marks as latest, or null when one does not exist. */
class CnbLatestReleaseStep
    @DataBoundConstructor
    constructor() : CnbReleaseStep() {
        override fun start(context: StepContext): StepExecution = releaseExecution(CnbReleaseStepRequest.LatestRelease, context)

        @Extension
        @Symbol("cnbLatestRelease")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbLatestRelease"

            override fun getDisplayName(): String = "Read the latest CNB release"
        }
    }

/** Reads one CNB release by its opaque release ID. */
class CnbReleaseStepById
    @DataBoundConstructor
    constructor(
        releaseId: String,
    ) : CnbReleaseStep() {
        val releaseId: String = CnbReleaseId.parse(releaseId).value

        override fun start(context: StepContext): StepExecution =
            releaseExecution(CnbReleaseStepRequest.GetRelease(CnbReleaseId.parse(releaseId)), context)

        @Extension
        @Symbol("cnbRelease")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbRelease"

            override fun getDisplayName(): String = "Read a CNB release by ID"
        }
    }

/** Reads one CNB release by an exact tag. */
class CnbReleaseByTagStep
    @DataBoundConstructor
    constructor(
        tag: String,
    ) : CnbReleaseStep() {
        val tag: String = CnbReleaseTag.parse(tag).value

        override fun start(context: StepContext): StepExecution =
            releaseExecution(CnbReleaseStepRequest.GetReleaseByTag(CnbReleaseTag.parse(tag)), context)

        @Extension
        @Symbol("cnbReleaseByTag")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbReleaseByTag"

            override fun getDisplayName(): String = "Read a CNB release by tag"
        }
    }

/** Reads one release asset by release and asset ID. */
class CnbReleaseAssetStep
    @DataBoundConstructor
    constructor(
        releaseId: String,
        assetId: String,
    ) : CnbReleaseStep() {
        val releaseId: String = CnbReleaseId.parse(releaseId).value
        val assetId: String = CnbReleaseAssetId.parse(assetId).value

        override fun start(context: StepContext): StepExecution =
            releaseExecution(
                CnbReleaseStepRequest.GetAsset(
                    CnbReleaseId.parse(releaseId),
                    CnbReleaseAssetId.parse(assetId),
                ),
                context,
            )

        @Extension
        @Symbol("cnbReleaseAsset")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbReleaseAsset"

            override fun getDisplayName(): String = "Read a CNB release asset"
        }
    }

/** Reads release asset metadata without downloading its body. */
class CnbReleaseAssetHeadStep
    @DataBoundConstructor
    constructor(
        tag: String,
        assetName: String,
    ) : CnbReleaseStep() {
        val tag: String = CnbReleaseTag.parse(tag).value
        val assetName: String = CnbReleaseAssetName.parse(assetName).value

        override fun start(context: StepContext): StepExecution =
            releaseExecution(
                CnbReleaseStepRequest.HeadAsset(
                    CnbReleaseTag.parse(tag),
                    CnbReleaseAssetName.parse(assetName),
                ),
                context,
            )

        @Extension
        @Symbol("cnbReleaseAssetHead")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbReleaseAssetHead"

            override fun getDisplayName(): String = "Read CNB release asset metadata"
        }
    }

/** Creates a CNB release. This step always performs an explicit repository write. */
class CnbCreateReleaseStep
    @DataBoundConstructor
    constructor(
        tagName: String,
        targetCommitish: String,
    ) : CnbReleaseStep() {
        val tagName: String = CnbReleaseTag.parse(tagName).value
        val targetCommitish: String = CnbReleaseRef.parse(targetCommitish, "targetCommitish").value
        var name: String = ""
            private set
        var body: String = ""
            private set
        var draft: Boolean = false
            private set
        var prerelease: Boolean = false
            private set
        var makeLatest: String = CnbReleaseMakeLatest.TRUE.wireValue
            private set

        @DataBoundSetter
        fun setName(value: String?) {
            name = CnbReleaseInput.releaseName(value.orEmpty())
        }

        @DataBoundSetter
        fun setBody(value: String?) {
            body = CnbReleaseInput.releaseBody(value.orEmpty())
        }

        @DataBoundSetter
        fun setDraft(value: Boolean) {
            draft = value
        }

        @DataBoundSetter
        fun setPrerelease(value: Boolean) {
            prerelease = value
        }

        @DataBoundSetter
        fun setMakeLatest(value: String?) {
            makeLatest = CnbReleaseInput.makeLatest(value).wireValue
        }

        override fun start(context: StepContext): StepExecution =
            releaseExecution(
                CnbReleaseStepRequest.CreateRelease(
                    CnbCreateReleaseRequest(
                        tagName = CnbReleaseTag.parse(tagName).value,
                        targetCommitish = CnbReleaseRef.parse(targetCommitish, "targetCommitish").value,
                        name = name,
                        body = body,
                        draft = draft,
                        prerelease = prerelease,
                        makeLatest = CnbReleaseInput.makeLatest(makeLatest),
                    ),
                ),
                context,
            )

        @Extension
        @Symbol("cnbCreateRelease")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbCreateRelease"

            override fun getDisplayName(): String = "Create a CNB release"
        }
    }

/** Updates explicitly supplied mutable fields on one CNB release. */
class CnbUpdateReleaseStep
    @DataBoundConstructor
    constructor(
        releaseId: String,
    ) : CnbReleaseStep() {
        val releaseId: String = CnbReleaseId.parse(releaseId).value
        var name: String? = null
            private set
        var body: String? = null
            private set
        var draft: Boolean? = null
            private set
        var prerelease: Boolean? = null
            private set
        var makeLatest: String? = null
            private set

        @DataBoundSetter
        fun setName(value: String?) {
            name = CnbReleaseInput.releaseName(value.orEmpty())
        }

        @DataBoundSetter
        fun setBody(value: String?) {
            body = CnbReleaseInput.releaseBody(value.orEmpty())
        }

        @DataBoundSetter
        fun setDraft(value: Boolean?) {
            draft = value
        }

        @DataBoundSetter
        fun setPrerelease(value: Boolean?) {
            prerelease = value
        }

        @DataBoundSetter
        fun setMakeLatest(value: String?) {
            makeLatest = CnbReleaseInput.makeLatest(value).wireValue
        }

        override fun start(context: StepContext): StepExecution {
            require(name != null || body != null || draft != null || prerelease != null || makeLatest != null) {
                "CNB release update requires at least one explicitly supplied field"
            }
            return releaseExecution(
                CnbReleaseStepRequest.UpdateRelease(
                    CnbReleaseId.parse(releaseId),
                    CnbUpdateReleaseRequest(
                        name = name,
                        body = body,
                        draft = draft,
                        prerelease = prerelease,
                        makeLatest = makeLatest?.let(CnbReleaseInput::makeLatest),
                    ),
                ),
                context,
            )
        }

        @Extension
        @Symbol("cnbUpdateRelease")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbUpdateRelease"

            override fun getDisplayName(): String = "Update a CNB release"
        }
    }

/** Deletes a release only when confirm exactly matches the selected release ID or tag. */
class CnbDeleteReleaseStep
    @DataBoundConstructor
    constructor(
        target: String,
        confirm: String,
    ) : CnbReleaseStep() {
        val target: String = CnbReleaseInput.confirmationValue(target, "release target")
        val confirm: String = CnbReleaseInput.confirmationValue(confirm, "release confirmation")
        var byTag: Boolean = false
            private set

        @DataBoundSetter
        fun setByTag(value: Boolean) {
            byTag = value
        }

        override fun start(context: StepContext): StepExecution =
            releaseExecution(CnbReleaseInput.confirmedReleaseDelete(target, confirm, byTag), context)

        @Extension
        @Symbol("cnbDeleteRelease")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbDeleteRelease"

            override fun getDisplayName(): String = "Delete a CNB release"
        }
    }

/** Deletes one release asset only when confirm exactly matches the asset ID. */
class CnbDeleteReleaseAssetStep
    @DataBoundConstructor
    constructor(
        releaseId: String,
        assetId: String,
        confirm: String,
    ) : CnbReleaseStep() {
        val releaseId: String = CnbReleaseId.parse(releaseId).value
        val assetId: String = CnbReleaseAssetId.parse(assetId).value
        val confirm: String = CnbReleaseInput.confirmationValue(confirm, "release asset confirmation")

        override fun start(context: StepContext): StepExecution =
            releaseExecution(
                CnbReleaseInput.confirmedAssetDelete(releaseId, assetId, confirm),
                context,
            )

        @Extension
        @Symbol("cnbDeleteReleaseAsset")
        class DescriptorImpl : CnbApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbDeleteReleaseAsset"

            override fun getDisplayName(): String = "Delete a CNB release asset"
        }
    }

/** Requires an allocated Jenkins workspace in addition to the normal CNB API context. */
abstract class CnbWorkspaceApiStepDescriptor : CnbApiStepDescriptor() {
    override fun getRequiredContext(): Set<Class<*>> = LinkedHashSet(super.getRequiredContext()).apply { add(FilePath::class.java) }
}

internal sealed interface CnbReleaseStepRequest : Serializable {
    data object ListReleases : CnbReleaseStepRequest

    data object LatestRelease : CnbReleaseStepRequest

    data class GetRelease(
        val releaseId: CnbReleaseId,
    ) : CnbReleaseStepRequest

    data class GetReleaseByTag(
        val tag: CnbReleaseTag,
    ) : CnbReleaseStepRequest

    data class GetAsset(
        val releaseId: CnbReleaseId,
        val assetId: CnbReleaseAssetId,
    ) : CnbReleaseStepRequest

    data class HeadAsset(
        val tag: CnbReleaseTag,
        val assetName: CnbReleaseAssetName,
    ) : CnbReleaseStepRequest

    data class CreateRelease(
        val request: CnbCreateReleaseRequest,
    ) : CnbReleaseStepRequest

    data class UpdateRelease(
        val releaseId: CnbReleaseId,
        val request: CnbUpdateReleaseRequest,
    ) : CnbReleaseStepRequest

    data class DeleteReleaseById(
        val releaseId: CnbReleaseId,
    ) : CnbReleaseStepRequest

    data class DeleteReleaseByTag(
        val tag: CnbReleaseTag,
    ) : CnbReleaseStepRequest

    data class DeleteAsset(
        val releaseId: CnbReleaseId,
        val assetId: CnbReleaseAssetId,
    ) : CnbReleaseStepRequest

    data class UploadAsset(
        val releaseId: CnbReleaseId,
        val workspacePath: CnbWorkspaceRelativePath,
        val assetName: CnbReleaseAssetName,
        val overwrite: Boolean,
        val ttl: CnbReleaseAssetTtl,
    ) : CnbReleaseStepRequest

    data class DownloadAsset(
        val tag: CnbReleaseTag,
        val assetName: CnbReleaseAssetName,
        val workspacePath: CnbWorkspaceRelativePath,
        val share: Boolean,
        val overwrite: Boolean,
        val limit: CnbReleaseTransferLimit,
    ) : CnbReleaseStepRequest
}

@JvmInline
internal value class CnbReleaseId private constructor(
    val value: String,
) : Serializable {
    companion object {
        private val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

        fun parse(value: String): CnbReleaseId {
            val normalized = value.trim()
            require(PATTERN.matches(normalized)) { "CNB release ID is invalid" }
            return CnbReleaseId(normalized)
        }
    }
}

@JvmInline
internal value class CnbReleaseAssetId private constructor(
    val value: String,
) : Serializable {
    companion object {
        private val PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

        fun parse(value: String): CnbReleaseAssetId {
            val normalized = value.trim()
            require(PATTERN.matches(normalized)) { "CNB release asset ID is invalid" }
            return CnbReleaseAssetId(normalized)
        }
    }
}

@JvmInline
internal value class CnbReleaseTag private constructor(
    val value: String,
) : Serializable {
    companion object {
        fun parse(value: String): CnbReleaseTag = CnbReleaseTag(CnbReleaseInput.tag(value))
    }
}

@JvmInline
internal value class CnbReleaseRef private constructor(
    val value: String,
) : Serializable {
    companion object {
        fun parse(
            value: String,
            name: String,
        ): CnbReleaseRef = CnbReleaseRef(CnbReleaseInput.commitish(value, name))
    }
}

@JvmInline
internal value class CnbReleaseAssetName private constructor(
    val value: String,
) : Serializable {
    companion object {
        fun parse(value: String): CnbReleaseAssetName {
            require(value == value.trim()) { "CNB release asset name must not have surrounding whitespace" }
            require(value.isNotEmpty() && value.length <= 255) { "CNB release asset name is invalid" }
            require(value != "." && value != "..") { "CNB release asset name is invalid" }
            require(value.none { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) {
                "CNB release asset name contains a forbidden character"
            }
            return CnbReleaseAssetName(value)
        }
    }
}

@JvmInline
internal value class CnbReleaseAssetTtl private constructor(
    val days: Int,
) : Serializable {
    companion object {
        fun parse(days: Int): CnbReleaseAssetTtl {
            require(days in 0..180) { "CNB release asset ttlDays must be between 0 and 180" }
            return CnbReleaseAssetTtl(days)
        }
    }
}

@JvmInline
internal value class CnbReleaseTransferLimit private constructor(
    val bytes: Long,
) : Serializable {
    companion object {
        const val MAX_BYTES: Long = 512L * 1024 * 1024

        fun parse(bytes: Long): CnbReleaseTransferLimit {
            require(bytes in 1..MAX_BYTES) {
                "CNB release asset maxBytes must be between 1 and $MAX_BYTES"
            }
            return CnbReleaseTransferLimit(bytes)
        }
    }
}

internal object CnbReleaseInput {
    private const val MAX_REF_LENGTH = 1_024
    private const val MAX_NAME_LENGTH = 1_000
    private const val MAX_BODY_LENGTH = 1024 * 1024

    private fun ref(
        value: String,
        name: String,
    ): String {
        val normalized = value.trim()
        require(normalized.isNotEmpty() && normalized.length <= MAX_REF_LENGTH) { "CNB $name is invalid" }
        require(normalized.none { it.code < 0x20 || it.code == 0x7f }) { "CNB $name contains control characters" }
        return normalized
    }

    fun tag(value: String): String {
        val normalized = ref(value, "release tag")
        require(Repository.isValidRefName("refs/tags/$normalized")) { "CNB release tag is invalid" }
        return normalized
    }

    fun commitish(
        value: String,
        name: String,
    ): String {
        val normalized = ref(value, name)
        require(
            Repository.isValidRefName("refs/heads/$normalized") ||
                Repository.isValidRefName("refs/tags/$normalized") ||
                CnbGitObjectId.isValid(normalized),
        ) { "CNB $name is not a valid branch, tag, or full object ID" }
        return normalized
    }

    fun releaseName(value: String): String {
        require(value.length <= MAX_NAME_LENGTH) { "CNB release name is too long" }
        require(value.none { it.code < 0x20 || it.code == 0x7f }) { "CNB release name contains control characters" }
        return value
    }

    fun releaseBody(value: String): String {
        require(value.length <= MAX_BODY_LENGTH) { "CNB release body is too long" }
        require(value.none { (it.code < 0x20 && it != '\r' && it != '\n' && it != '\t') || it.code == 0x7f }) {
            "CNB release body contains control characters"
        }
        return value
    }

    fun makeLatest(value: String?): CnbReleaseMakeLatest {
        val normalized = value?.trim()?.lowercase(Locale.ROOT) ?: CnbReleaseMakeLatest.TRUE.wireValue
        return CnbReleaseMakeLatest.entries.firstOrNull { it.wireValue == normalized }
            ?: throw IllegalArgumentException("CNB release makeLatest must be true, false, or legacy")
    }

    fun confirmationValue(
        value: String,
        name: String,
    ): String {
        require(value.isNotEmpty() && value.length <= MAX_REF_LENGTH) { "CNB $name is invalid" }
        require(value.none { it.code < 0x20 || it.code == 0x7f }) { "CNB $name contains control characters" }
        return value
    }

    fun confirmedReleaseDelete(
        target: String,
        confirm: String,
        byTag: Boolean,
    ): CnbReleaseStepRequest {
        require(confirm == target) { "CNB release confirmation must exactly match the target" }
        return if (byTag) {
            CnbReleaseStepRequest.DeleteReleaseByTag(CnbReleaseTag.parse(target))
        } else {
            CnbReleaseStepRequest.DeleteReleaseById(CnbReleaseId.parse(target))
        }
    }

    fun confirmedAssetDelete(
        releaseId: String,
        assetId: String,
        confirm: String,
    ): CnbReleaseStepRequest.DeleteAsset {
        require(confirm == assetId) { "CNB release asset confirmation must exactly match the asset ID" }
        return CnbReleaseStepRequest.DeleteAsset(
            CnbReleaseId.parse(releaseId),
            CnbReleaseAssetId.parse(assetId),
        )
    }
}

private class CnbReleaseStepExecution(
    private val supplied: CnbRunContextInput,
    private val request: CnbReleaseStepRequest,
    context: StepContext,
) : SynchronousNonBlockingStepExecution<Any?>(context) {
    override fun run(): Any? {
        val run = context.get(Run::class.java)
        val listener = context.get(TaskListener::class.java)
        val environment = context.get(EnvVars::class.java)
        val resolved = CnbRunContextResolver.resolve(run, listener, supplied, environment)
        val workspace = context.get(FilePath::class.java)
        return resolved.client(run).use { client ->
            CnbReleaseStepDispatcher.execute(request, resolved, client, workspace)
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

private class CnbReleaseTransferStepExecution(
    private val supplied: CnbRunContextInput,
    private val request: CnbReleaseStepRequest,
    context: StepContext,
    private val transferId: String = UUID.randomUUID().toString(),
) : CnbRestartableAsyncStepExecution<Any?>(context, "CNB release asset transfer") {
    override fun runAttempt(
        attempt: Int,
        resumed: Boolean,
    ): Any? {
        val run = context.get(Run::class.java)
        val listener = context.get(TaskListener::class.java)
        if (resumed) {
            listener.logger.printf("[CNB] Retrying interrupted release asset transfer (attempt %d)%n", attempt)
        }
        val environment = context.get(EnvVars::class.java)
        val resolved = CnbRunContextResolver.resolve(run, listener, supplied, environment)
        val workspace = context.get(FilePath::class.java)
        return resolved.client(run).use { client ->
            CnbReleaseStepDispatcher.execute(request, resolved, client, workspace, transferId, resumed)
        }
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}

/** Keeps API/domain values strongly typed until the final Jenkins Groovy/CPS adapter. */
internal object CnbReleaseStepDispatcher {
    fun execute(
        request: CnbReleaseStepRequest,
        context: CnbRunContext,
        client: CnbClient,
        workspace: FilePath? = null,
        transferId: String = UUID.randomUUID().toString(),
        resumed: Boolean = false,
    ): Any? =
        when (request) {
            CnbReleaseStepRequest.ListReleases -> {
                CnbReleasePipelineValues.releases(client.listReleases(context.repository))
            }

            CnbReleaseStepRequest.LatestRelease -> {
                client.getLatestRelease(context.repository)?.let(CnbReleasePipelineValues::release)
            }

            is CnbReleaseStepRequest.GetRelease -> {
                CnbReleasePipelineValues.release(client.getRelease(context.repository, request.releaseId.value))
            }

            is CnbReleaseStepRequest.GetReleaseByTag -> {
                CnbReleasePipelineValues.release(client.getReleaseByTag(context.repository, request.tag.value))
            }

            is CnbReleaseStepRequest.GetAsset -> {
                CnbReleasePipelineValues.asset(
                    client.getReleaseAsset(context.repository, request.releaseId.value, request.assetId.value),
                )
            }

            is CnbReleaseStepRequest.HeadAsset -> {
                CnbReleasePipelineValues.head(
                    client.headReleaseAsset(context.repository, request.tag.value, request.assetName.value),
                )
            }

            is CnbReleaseStepRequest.CreateRelease -> {
                CnbReleasePipelineValues.release(client.createRelease(context.repository, request.request))
            }

            is CnbReleaseStepRequest.UpdateRelease -> {
                client.updateRelease(context.repository, request.releaseId.value, request.request)
                CnbReleasePipelineValues.acknowledgement(
                    "updated",
                    "releaseId" to request.releaseId.value,
                )
            }

            is CnbReleaseStepRequest.DeleteReleaseById -> {
                client.deleteRelease(context.repository, request.releaseId.value)
                CnbReleasePipelineValues.acknowledgement(
                    "deleted",
                    "releaseId" to request.releaseId.value,
                )
            }

            is CnbReleaseStepRequest.DeleteReleaseByTag -> {
                val release = client.getReleaseByTag(context.repository, request.tag.value)
                val releaseId = CnbReleaseId.parse(release.id)
                client.deleteRelease(context.repository, releaseId.value)
                CnbReleasePipelineValues.acknowledgement(
                    "deleted",
                    "releaseId" to releaseId.value,
                    "tagName" to request.tag.value,
                )
            }

            is CnbReleaseStepRequest.DeleteAsset -> {
                client.deleteReleaseAsset(context.repository, request.releaseId.value, request.assetId.value)
                CnbReleasePipelineValues.acknowledgement(
                    "deleted",
                    "releaseId" to request.releaseId.value,
                    "assetId" to request.assetId.value,
                )
            }

            is CnbReleaseStepRequest.UploadAsset -> {
                CnbReleaseWorkspaceTransfer.upload(
                    request,
                    context,
                    client,
                    workspace ?: throw AbortException("CNB release asset upload requires a Jenkins workspace"),
                    transferId,
                    resumed,
                )
            }

            is CnbReleaseStepRequest.DownloadAsset -> {
                CnbReleaseWorkspaceTransfer.download(
                    request,
                    context,
                    client,
                    workspace ?: throw AbortException("CNB release asset download requires a Jenkins workspace"),
                    resumed,
                )
            }
        }
}

/** The sole adapter from typed release domain objects to Jenkins Groovy/CPS-safe values. */
internal object CnbReleasePipelineValues {
    fun releases(values: List<CnbRelease>): ArrayList<LinkedHashMap<String, Any?>> =
        ArrayList<LinkedHashMap<String, Any?>>().apply {
            values.forEach { add(release(it)) }
        }

    fun release(value: CnbRelease): LinkedHashMap<String, Any?> =
        mapOfValues(
            "id" to value.id,
            "tagName" to value.tagName,
            "name" to value.name,
            "body" to value.body,
            "tagCommitish" to value.tagCommitish,
            "draft" to value.draft,
            "prerelease" to value.prerelease,
            "latest" to value.latest,
            "createdAt" to value.createdAt,
            "updatedAt" to value.updatedAt,
            "publishedAt" to value.publishedAt,
            "author" to value.author?.let(::user),
            "assets" to
                ArrayList<LinkedHashMap<String, Any?>>().apply {
                    value.assets.forEach { add(asset(it)) }
                },
        )

    fun asset(value: CnbReleaseAsset): LinkedHashMap<String, Any?> =
        mapOfValues(
            "id" to value.id,
            "name" to value.name,
            "path" to value.path,
            "size" to value.size,
            "contentType" to value.contentType,
            "downloadCount" to value.downloadCount,
            "hashAlgorithm" to value.hashAlgorithm,
            "hashValue" to value.hashValue,
            "createdAt" to value.createdAt,
            "updatedAt" to value.updatedAt,
            "uploader" to value.uploader?.let(::user),
        )

    fun head(value: CnbReleaseAssetHead): LinkedHashMap<String, Any?> =
        mapOfValues(
            "exists" to value.exists,
            "contentLength" to value.contentLength,
            "contentType" to value.contentType,
            "etag" to value.etag,
            "lastModified" to value.lastModified,
        )

    fun acknowledgement(
        operation: String,
        vararg values: Pair<String, Any?>,
    ): LinkedHashMap<String, Any?> = mapOfValues("operation" to operation, *values)

    fun mapOfValues(vararg entries: Pair<String, Any?>): LinkedHashMap<String, Any?> =
        LinkedHashMap<String, Any?>(entries.size).apply {
            entries.forEach { (key, value) -> put(key, value) }
        }

    private fun user(value: CnbReleaseUser): LinkedHashMap<String, Any?> =
        mapOfValues(
            "username" to value.username,
            "nickname" to value.nickname,
            "email" to value.email,
            "frozen" to value.frozen,
            "npc" to value.npc,
        )
}
