package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.CnbDownloadTarget
import dev.zxilly.jenkins.cnb.api.CnbRepeatableInput
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAsset
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAssetUploadRequest
import hudson.AbortException
import hudson.Extension
import hudson.FilePath
import hudson.remoting.RemoteOutputStream
import hudson.remoting.VirtualChannel
import jenkins.MasterToSlaveFileCallable
import org.jenkinsci.Symbol
import org.jenkinsci.plugins.workflow.steps.StepContext
import org.jenkinsci.plugins.workflow.steps.StepExecution
import org.kohsuke.stapler.DataBoundConstructor
import org.kohsuke.stapler.DataBoundSetter
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.Serializable
import java.nio.file.CopyOption
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

/** Uploads a repeatable, fixed-size Jenkins workspace file as one CNB release asset. */
class CnbUploadReleaseAssetStep
    @DataBoundConstructor
    constructor(
        releaseId: String,
        path: String,
    ) : CnbReleaseStep() {
        val releaseId: String = CnbReleaseId.parse(releaseId).value
        val path: String = CnbWorkspaceRelativePath.parse(path).value
        var assetName: String = CnbWorkspaceRelativePath.parse(path).fileName
            private set
        var overwrite: Boolean = false
            private set
        var ttlDays: Int = 0
            private set

        @DataBoundSetter
        fun setAssetName(value: String?) {
            assetName = CnbReleaseAssetName.parse(requireNotNull(value) { "CNB release asset name is required" }).value
        }

        @DataBoundSetter
        fun setOverwrite(value: Boolean) {
            overwrite = value
        }

        @DataBoundSetter
        fun setTtlDays(value: Int) {
            ttlDays = CnbReleaseAssetTtl.parse(value).days
        }

        override fun start(context: StepContext): StepExecution =
            releaseExecution(
                CnbReleaseStepRequest.UploadAsset(
                    CnbReleaseId.parse(releaseId),
                    CnbWorkspaceRelativePath.parse(path),
                    CnbReleaseAssetName.parse(assetName),
                    overwrite,
                    CnbReleaseAssetTtl.parse(ttlDays),
                ),
                context,
            )

        @Extension
        @Symbol("cnbUploadReleaseAsset")
        class DescriptorImpl : CnbWorkspaceApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbUploadReleaseAsset"

            override fun getDisplayName(): String = "Upload a CNB release asset from the workspace"
        }
    }

/** Downloads one CNB release asset and atomically publishes it inside the Jenkins workspace. */
class CnbDownloadReleaseAssetStep
    @DataBoundConstructor
    constructor(
        tag: String,
        assetName: String,
        path: String,
    ) : CnbReleaseStep() {
        val tag: String = CnbReleaseTag.parse(tag).value
        val assetName: String = CnbReleaseAssetName.parse(assetName).value
        val path: String = CnbWorkspaceRelativePath.parse(path).value
        var share: Boolean = false
            private set
        var overwrite: Boolean = false
            private set
        var maxBytes: Long = CnbReleaseTransferLimit.MAX_BYTES
            private set

        @DataBoundSetter
        fun setShare(value: Boolean) {
            share = value
        }

        @DataBoundSetter
        fun setOverwrite(value: Boolean) {
            overwrite = value
        }

        @DataBoundSetter
        fun setMaxBytes(value: Long) {
            maxBytes = CnbReleaseTransferLimit.parse(value).bytes
        }

        override fun start(context: StepContext): StepExecution =
            releaseExecution(
                CnbReleaseStepRequest.DownloadAsset(
                    CnbReleaseTag.parse(tag),
                    CnbReleaseAssetName.parse(assetName),
                    CnbWorkspaceRelativePath.parse(path),
                    share,
                    overwrite,
                    CnbReleaseTransferLimit.parse(maxBytes),
                ),
                context,
            )

        @Extension
        @Symbol("cnbDownloadReleaseAsset")
        class DescriptorImpl : CnbWorkspaceApiStepDescriptor() {
            override fun getFunctionName(): String = "cnbDownloadReleaseAsset"

            override fun getDisplayName(): String = "Download a CNB release asset to the workspace"
        }
    }

@JvmInline
internal value class CnbWorkspaceRelativePath private constructor(
    val value: String,
) : Serializable {
    val fileName: String
        get() = value.substringAfterLast('/')

    companion object {
        private const val MAX_PATH_LENGTH = 4_096

        fun parse(value: String): CnbWorkspaceRelativePath {
            require(value == value.trim()) { "CNB workspace path must not have surrounding whitespace" }
            require(value.isNotEmpty() && value.length <= MAX_PATH_LENGTH) { "CNB workspace path is invalid" }
            require(!value.startsWith('/')) { "CNB workspace path must be relative" }
            require('\\' !in value && ':' !in value) { "CNB workspace path contains a forbidden character" }
            require(value.none { it.code < 0x20 || it.code == 0x7f }) {
                "CNB workspace path contains control characters"
            }
            val segments = value.split('/')
            require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
                "CNB workspace path must not contain empty, dot, or parent segments"
            }
            return CnbWorkspaceRelativePath(segments.joinToString("/"))
        }
    }
}

internal object CnbReleaseWorkspaceTransfer {
    private val TRANSFER_ID = Regex("[A-Fa-f0-9-]{36}")

    fun upload(
        request: CnbReleaseStepRequest.UploadAsset,
        context: CnbRunContext,
        client: CnbClient,
        workspace: FilePath,
        transferId: String =
            java.util.UUID
                .randomUUID()
                .toString(),
        resumed: Boolean = false,
        retainSnapshotUntilCheckpoint: Boolean = false,
    ): Any {
        val snapshot = workspace.act(PrepareWorkspaceUploadSnapshot(request.workspacePath.value, transferId))
        var primaryFailure: Throwable? = null
        var preState: RemoteUploadPreState? = null
        var preInspectionFailure: Throwable? = null
        var uploadAttempted = false
        try {
            throwIfTransferInterrupted()
            if (request.overwrite) {
                try {
                    preState = inspectUploadMetadata(request, context, client, snapshot)
                } catch (failure: Exception) {
                    if (failure.indicatesInterruption()) {
                        Thread.currentThread().interrupt()
                        throw failure
                    }
                    preInspectionFailure = failure
                }
            } else {
                when (inspectResumedUpload(request, context, client, snapshot)) {
                    RemoteUploadState.MATCH -> {
                        if (resumed) return uploadResult(request, snapshot.size)
                        preState = RemoteUploadPreState.KNOWN_MATCH
                    }

                    RemoteUploadState.CONFLICT -> {
                        throw AbortException("CNB upload found a different asset with the same name")
                    }

                    RemoteUploadState.ABSENT -> preState = RemoteUploadPreState.ABSENT
                }
            }
            throwIfTransferInterrupted()
            val input = FixedSizeWorkspaceInput(workspace, snapshot)
            uploadAttempted = true
            client.uploadReleaseAsset(
                context.repository,
                request.releaseId.value,
                CnbReleaseAssetUploadRequest(
                    assetName = request.assetName.value,
                    size = snapshot.size,
                    overwrite = request.overwrite,
                    ttlDays = request.ttl.days,
                ),
                input,
            )
            return uploadResult(request, snapshot.size)
        } catch (failure: Throwable) {
            if (failure.indicatesInterruption()) {
                Thread.currentThread().interrupt()
                primaryFailure = failure
                throw failure
            }
            if (uploadAttempted && preState?.provesNonMatch == true) {
                try {
                    if (inspectResumedUpload(request, context, client, snapshot) == RemoteUploadState.MATCH) {
                        return uploadResult(request, snapshot.size)
                    }
                } catch (inspectionFailure: Throwable) {
                    if (inspectionFailure.indicatesInterruption()) {
                        Thread.currentThread().interrupt()
                        if (inspectionFailure !== failure) inspectionFailure.addSuppressed(failure)
                        primaryFailure = inspectionFailure
                        throw inspectionFailure
                    }
                    failure.addSuppressed(inspectionFailure)
                }
            }
            preInspectionFailure?.takeIf { it !== failure }?.let(failure::addSuppressed)
            primaryFailure = failure
            throw failure
        } finally {
            if (primaryFailure != null || !retainSnapshotUntilCheckpoint) {
                cleanupUploadSnapshot(workspace, snapshot, primaryFailure)
            }
        }
    }

    fun cleanupUploadSnapshot(
        workspace: FilePath,
        sourcePath: CnbWorkspaceRelativePath,
        transferId: String,
    ) {
        workspace.act(DeleteWorkspaceUploadArtifacts(sourcePath.value, transferId))
    }

    fun cleanupDownloadTemporary(
        workspace: FilePath,
        path: CnbWorkspaceRelativePath,
        transferId: String,
    ) {
        if (!TRANSFER_ID.matches(transferId)) throw IOException("Invalid CNB release download transfer identifier")
        workspace.act(DeleteWorkspaceTemporary(path.value, ".cnb-release-download-$transferId.tmp"))
    }

    private fun uploadResult(
        request: CnbReleaseStepRequest.UploadAsset,
        size: Long,
    ): Any =
        CnbReleasePipelineValues.mapOfValues(
            "operation" to "uploaded",
            "releaseId" to request.releaseId.value,
            "assetName" to request.assetName.value,
            "path" to request.workspacePath.value,
            "size" to size,
            "overwrite" to request.overwrite,
            "ttlDays" to request.ttl.days,
        )

    private fun inspectUploadMetadata(
        request: CnbReleaseStepRequest.UploadAsset,
        context: CnbRunContext,
        client: CnbClient,
        snapshot: WorkspaceUploadSnapshot,
    ): RemoteUploadPreState {
        throwIfTransferInterrupted()
        val lookup = lookupRemoteUploadAsset(request, context, client)
        throwIfTransferInterrupted()
        if (lookup.multipleMatches) return RemoteUploadPreState.KNOWN_CONFLICT
        val asset = lookup.asset ?: return RemoteUploadPreState.ABSENT
        if (asset.size != snapshot.size) return RemoteUploadPreState.KNOWN_CONFLICT

        val normalizedAlgorithm = asset.hashAlgorithm.replace("-", "").lowercase()
        if (normalizedAlgorithm != "sha256" || asset.hashValue.isBlank()) {
            return RemoteUploadPreState.UNKNOWN_EXISTING
        }
        return if (asset.hashValue.equals(snapshot.sha256.toHex(), ignoreCase = true)) {
            RemoteUploadPreState.KNOWN_MATCH
        } else {
            RemoteUploadPreState.KNOWN_CONFLICT
        }
    }

    private fun inspectResumedUpload(
        request: CnbReleaseStepRequest.UploadAsset,
        context: CnbRunContext,
        client: CnbClient,
        snapshot: WorkspaceUploadSnapshot,
    ): RemoteUploadState {
        throwIfTransferInterrupted()
        val lookup = lookupRemoteUploadAsset(request, context, client)
        throwIfTransferInterrupted()
        if (lookup.multipleMatches) return RemoteUploadState.CONFLICT
        val asset = lookup.asset ?: return RemoteUploadState.ABSENT
        if (asset.size != snapshot.size) return RemoteUploadState.CONFLICT

        val normalizedAlgorithm = asset.hashAlgorithm.replace("-", "").lowercase()
        if (normalizedAlgorithm == "sha256" && asset.hashValue.isNotBlank()) {
            return if (asset.hashValue.equals(snapshot.sha256.toHex(), ignoreCase = true)) {
                RemoteUploadState.MATCH
            } else {
                RemoteUploadState.CONFLICT
            }
        }

        val target = DigestDownloadTarget()
        val downloaded =
            client.downloadReleaseAsset(
                context.repository,
                lookup.releaseTag,
                asset.name,
                target,
                share = false,
                maxBytes = maxOf(1L, snapshot.size),
            )
        throwIfTransferInterrupted()
        downloaded ?: return RemoteUploadState.CONFLICT
        return if (downloaded.contentLength == snapshot.size && target.matches(snapshot.sha256, snapshot.size)) {
            RemoteUploadState.MATCH
        } else {
            RemoteUploadState.CONFLICT
        }
    }

    private fun lookupRemoteUploadAsset(
        request: CnbReleaseStepRequest.UploadAsset,
        context: CnbRunContext,
        client: CnbClient,
    ): RemoteUploadAssetLookup {
        val release = client.getRelease(context.repository, request.releaseId.value)
        var matchingAsset: CnbReleaseAsset? = null
        for (asset in release.assets) {
            if (asset.name != request.assetName.value) continue
            if (matchingAsset != null) {
                return RemoteUploadAssetLookup(release.tagName, null, multipleMatches = true)
            }
            matchingAsset = asset
        }
        return RemoteUploadAssetLookup(release.tagName, matchingAsset, multipleMatches = false)
    }

    private data class RemoteUploadAssetLookup(
        val releaseTag: String,
        val asset: CnbReleaseAsset?,
        val multipleMatches: Boolean,
    )

    private enum class RemoteUploadPreState(
        val provesNonMatch: Boolean,
    ) {
        ABSENT(true),
        KNOWN_MATCH(false),
        KNOWN_CONFLICT(true),
        UNKNOWN_EXISTING(false),
    }

    private fun throwIfTransferInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException("CNB transfer was interrupted")
        }
    }

    private fun Throwable.indicatesInterruption(): Boolean {
        if (Thread.currentThread().isInterrupted) return true

        val pending = ArrayDeque<Throwable>()
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        pending.add(this)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            if (current is InterruptedException || current is CancellationException) {
                return true
            }
            current.cause?.let(pending::addLast)
            current.suppressed.forEach(pending::addLast)
        }
        return false
    }

    private enum class RemoteUploadState {
        ABSENT,
        MATCH,
        CONFLICT,
    }

    private class DigestDownloadTarget : CnbDownloadTarget {
        private var digest = MessageDigest.getInstance("SHA-256")
        private var opened = false
        private var size = 0L

        override fun openStream(): OutputStream {
            digest = MessageDigest.getInstance("SHA-256")
            opened = true
            size = 0L
            return object : OutputStream() {
                override fun write(value: Int) {
                    digest.update(value.toByte())
                    size++
                }

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    digest.update(bytes, offset, length)
                    size += length
                }
            }
        }

        fun matches(
            expectedDigest: ByteArray,
            expectedSize: Long,
        ): Boolean = opened && size == expectedSize && MessageDigest.isEqual(expectedDigest, digest.digest())
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    fun download(
        request: CnbReleaseStepRequest.DownloadAsset,
        context: CnbRunContext,
        client: CnbClient,
        workspace: FilePath,
        transferId: String =
            java.util.UUID
                .randomUUID()
                .toString(),
        resumed: Boolean = false,
    ): Any {
        val downloaded =
            downloadToWorkspace(
                workspace,
                request.workspacePath,
                request.overwrite,
                request.limit,
                "CNB release asset was not found",
                transferId = transferId,
                resumed = resumed,
            ) { target ->
                client
                    .downloadReleaseAsset(
                        context.repository,
                        request.tag.value,
                        request.assetName.value,
                        target,
                        request.share,
                        request.limit.bytes,
                    )?.let { value ->
                        CnbWorkspaceDownloadMetadata(value.contentLength, value.contentType, value.etag)
                    }
            }
        return CnbReleasePipelineValues.mapOfValues(
            "operation" to "downloaded",
            "tagName" to request.tag.value,
            "assetName" to request.assetName.value,
            "path" to request.workspacePath.value,
            "size" to downloaded.contentLength,
            "contentType" to downloaded.contentType,
            "etag" to downloaded.etag,
            "overwrite" to request.overwrite,
        )
    }

    private fun cleanupUploadSnapshot(
        workspace: FilePath,
        snapshot: WorkspaceUploadSnapshot,
        primaryFailure: Throwable?,
    ) {
        try {
            workspace.act(DeleteWorkspaceUploadSnapshot(snapshot.relativePath, snapshot.fileKey))
        } catch (cleanup: Throwable) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanup)
            } else {
                throw cleanup
            }
        }
    }

    internal fun downloadToWorkspace(
        workspace: FilePath,
        path: CnbWorkspaceRelativePath,
        overwrite: Boolean,
        limit: CnbReleaseTransferLimit,
        missingMessage: String,
        transferId: String =
            java.util.UUID
                .randomUUID()
                .toString(),
        resumed: Boolean = false,
        transfer: (CnbDownloadTarget) -> CnbWorkspaceDownloadMetadata?,
    ): CnbWorkspaceDownloadMetadata {
        val prepared = workspace.act(PrepareWorkspaceDownload(path.value, overwrite, resumed, transferId))
        try {
            throwIfTransferInterrupted()
            val downloaded =
                transfer(
                    WorkspaceDownloadTarget(
                        workspace,
                        path.value,
                        prepared,
                    ),
                )
            throwIfTransferInterrupted()
            downloaded ?: throw AbortException(missingMessage)
            val actualSize =
                workspace.act(
                    InspectWorkspaceTemporary(
                        path.value,
                        prepared.temporaryName,
                        prepared.fileKey,
                    ),
                )
            throwIfTransferInterrupted()
            if (actualSize != downloaded.contentLength) {
                throw AbortException("CNB workspace download length did not match the response metadata")
            }
            if (actualSize < 0 || actualSize > limit.bytes) {
                throw AbortException("CNB workspace download exceeded maxBytes")
            }
            if (prepared.destinationExisted && !overwrite) {
                val matches =
                    workspace.act(
                        WorkspaceDownloadMatchesPublishedFile(
                            path.value,
                            prepared.temporaryName,
                            prepared.fileKey,
                        ),
                    )
                throwIfTransferInterrupted()
                if (!matches) {
                    throw IOException("CNB download did not match the file already published in the workspace")
                }
                workspace.act(DeleteWorkspaceTemporary(path.value, prepared.temporaryName))
                throwIfTransferInterrupted()
                return downloaded.copy(contentLength = actualSize)
            }
            throwIfTransferInterrupted()
            workspace.act(
                AtomicWorkspaceMove(
                    path.value,
                    prepared.temporaryName,
                    prepared.fileKey,
                    overwrite,
                ),
            )
            return downloaded.copy(contentLength = actualSize)
        } catch (failure: Throwable) {
            var restoreInterrupt = Thread.interrupted() || failure.indicatesInterruption()
            try {
                workspace.act(
                    DeleteWorkspaceTemporary(
                        path.value,
                        prepared.temporaryName,
                    ),
                )
            } catch (cleanup: Exception) {
                if (cleanup.indicatesInterruption()) restoreInterrupt = true
                failure.addSuppressed(cleanup)
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt()
            }
            throw failure
        }
    }

    internal data class CnbWorkspaceDownloadMetadata(
        val contentLength: Long,
        val contentType: String,
        val etag: String,
    )

    private class FixedSizeWorkspaceInput(
        private val workspace: FilePath,
        private val snapshot: WorkspaceUploadSnapshot,
    ) : CnbRepeatableInput,
        Serializable {
        override fun openStream(): InputStream {
            val current = workspace.act(InspectWorkspaceRegularFile(snapshot.relativePath))
            if (current.size != snapshot.size ||
                (snapshot.fileKey != null && current.fileKey != snapshot.fileKey)
            ) {
                throw IOException("CNB release asset snapshot changed before an upload attempt")
            }
            return DigestVerifyingInputStream(
                workspace.child(snapshot.relativePath).read(workspace, LinkOption.NOFOLLOW_LINKS),
                snapshot.sha256,
            )
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private class DigestVerifyingInputStream(
        private val delegate: InputStream,
        private val expectedSha256: ByteArray,
    ) : InputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var verified = false

        override fun read(): Int {
            val value = delegate.read()
            if (value < 0) {
                verifyDigest()
            } else {
                digest.update(value.toByte())
            }
            return value
        }

        override fun read(
            target: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val count = delegate.read(target, offset, length)
            if (count < 0) {
                verifyDigest()
            } else if (count > 0) {
                digest.update(target, offset, count)
            }
            return count
        }

        private fun verifyDigest() {
            if (verified) return
            verified = true
            if (!MessageDigest.isEqual(expectedSha256, digest.digest())) {
                throw IOException("CNB release asset snapshot changed during an upload attempt")
            }
        }

        override fun close() = delegate.close()
    }

    private class WorkspaceDownloadTarget(
        private val workspace: FilePath,
        private val destinationPath: String,
        private val prepared: PreparedWorkspaceDownload,
    ) : CnbDownloadTarget {
        override fun openStream(): OutputStream =
            workspace.act(
                OpenWorkspaceTemporary(
                    destinationPath,
                    prepared.temporaryName,
                    prepared.fileKey,
                ),
            )
    }

    private data class WorkspaceFileIdentity(
        val size: Long,
        val fileKey: String?,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private data class WorkspaceUploadSnapshot(
        val relativePath: String,
        val size: Long,
        val fileKey: String?,
        val sha256: ByteArray,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private data class PreparedWorkspaceDownload(
        val temporaryName: String,
        val fileKey: String?,
        val destinationExisted: Boolean,
    ) : Serializable {
        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private class InspectWorkspaceRegularFile(
        private val relativePath: String,
    ) : MasterToSlaveFileCallable<WorkspaceFileIdentity>() {
        override fun invoke(
            workspace: File,
            channel: VirtualChannel,
        ): WorkspaceFileIdentity {
            val validated = validatedPath(relativePath)
            val root = validatedWorkspaceRoot(workspace, create = false)
            val file = resolveTrustedFile(root, validated, createParents = false)
            return regularFileIdentity(file, "CNB release asset source")
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private class PrepareWorkspaceUploadSnapshot(
        private val sourceRelativePath: String,
        private val transferId: String,
    ) : MasterToSlaveFileCallable<WorkspaceUploadSnapshot>() {
        override fun invoke(
            workspace: File,
            channel: VirtualChannel,
        ): WorkspaceUploadSnapshot {
            if (!TRANSFER_ID.matches(transferId)) throw IOException("Invalid CNB release upload transfer identifier")
            val validated = validatedPath(sourceRelativePath)
            val root = validatedWorkspaceRoot(workspace, create = false)
            val source = resolveTrustedFile(root, validated, createParents = false)
            val parent = source.parent ?: throw IOException("CNB release asset source has no parent")
            val snapshotName = ".cnb-upload-$transferId.snapshot"
            val stagingName = ".cnb-upload-$transferId.tmp"
            val snapshot = parent.resolve(snapshotName)
            val staging = parent.resolve(stagingName)
            val snapshotRelativePath =
                validated.value.substringBeforeLast('/', "").let { prefix ->
                    if (prefix.isEmpty()) snapshotName else "$prefix/$snapshotName"
                }

            if (Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)) {
                val identity = regularFileIdentity(snapshot, "CNB release asset snapshot")
                val digest = digestRegularFile(snapshot, CnbReleaseTransferLimit.MAX_BYTES)
                return WorkspaceUploadSnapshot(snapshotRelativePath, identity.size, identity.fileKey, digest)
            }
            val sourceIdentity = regularFileIdentity(source, "CNB release asset source")
            if (sourceIdentity.size !in 0..CnbReleaseTransferLimit.MAX_BYTES) {
                throw AbortException("CNB release asset source exceeds the supported size limit")
            }
            deleteStaleUploadPath(staging)

            try {
                val copiedDigest = copyAndDigest(source, staging, CnbReleaseTransferLimit.MAX_BYTES)
                val currentIdentity = regularFileIdentity(source, "CNB release asset source")
                val currentDigest = digestRegularFile(source, CnbReleaseTransferLimit.MAX_BYTES)
                if (currentIdentity.size != sourceIdentity.size ||
                    (sourceIdentity.fileKey != null && currentIdentity.fileKey != sourceIdentity.fileKey) ||
                    !MessageDigest.isEqual(copiedDigest, currentDigest)
                ) {
                    throw IOException("CNB release asset source changed while its immutable snapshot was created")
                }
                try {
                    Files.move(staging, snapshot, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: FileAlreadyExistsException) {
                    Files.deleteIfExists(staging)
                }
                val identity = regularFileIdentity(snapshot, "CNB release asset snapshot")
                if (identity.size != sourceIdentity.size) {
                    throw IOException("CNB release asset snapshot size did not match its source")
                }
                return WorkspaceUploadSnapshot(snapshotRelativePath, identity.size, identity.fileKey, copiedDigest)
            } catch (failure: Throwable) {
                try {
                    deleteStaleUploadPath(staging)
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private class DeleteWorkspaceUploadSnapshot(
        private val relativePath: String,
        private val expectedFileKey: String?,
    ) : MasterToSlaveFileCallable<Boolean>() {
        override fun invoke(
            workspace: File,
            channel: VirtualChannel,
        ): Boolean {
            val validated = validatedPath(relativePath)
            val root = validatedWorkspaceRoot(workspace, create = false)
            val snapshot = resolveTrustedFile(root, validated, createParents = false)
            if (!Files.exists(snapshot, LinkOption.NOFOLLOW_LINKS)) return false
            val identity = regularFileIdentity(snapshot, "CNB release asset snapshot")
            verifyFileKey(identity, expectedFileKey)
            Files.delete(snapshot)
            return true
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private class DeleteWorkspaceUploadArtifacts(
        private val sourceRelativePath: String,
        private val transferId: String,
    ) : MasterToSlaveFileCallable<Boolean>() {
        override fun invoke(
            workspace: File,
            channel: VirtualChannel,
        ): Boolean {
            if (!TRANSFER_ID.matches(transferId)) throw IOException("Invalid CNB release upload transfer identifier")
            val validated = validatedPath(sourceRelativePath)
            val root = validatedWorkspaceRoot(workspace, create = false)
            var parent = root
            for (segment in validated.value.split('/').dropLast(1)) {
                val next = parent.resolve(segment)
                if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS)) return false
                if (Files.isSymbolicLink(next) || !Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("CNB workspace path parent is not a real directory inside the workspace")
                }
                parent = next
            }
            listOf(
                parent.resolve(".cnb-upload-$transferId.snapshot"),
                parent.resolve(".cnb-upload-$transferId.tmp"),
            ).forEach { artifact ->
                if (!Files.exists(artifact, LinkOption.NOFOLLOW_LINKS)) return@forEach
                requireRegularNonLink(artifact, "CNB release asset transfer artifact")
                Files.delete(artifact)
            }
            return true
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private class PrepareWorkspaceDownload(
        private val relativePath: String,
        private val overwrite: Boolean,
        private val resumed: Boolean,
        private val transferId: String,
    ) : MasterToSlaveFileCallable<PreparedWorkspaceDownload>() {
        override fun invoke(
            workspace: File,
            channel: VirtualChannel,
        ): PreparedWorkspaceDownload {
            if (!TRANSFER_ID.matches(transferId)) throw IOException("Invalid CNB release download transfer identifier")
            val validated = validatedPath(relativePath)
            val root = validatedWorkspaceRoot(workspace, create = true)
            val destination = resolveTrustedFile(root, validated, createParents = true)
            val destinationExisted = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
            if (destinationExisted) {
                requireRegularNonLink(destination, "CNB release asset destination")
            }
            val parent = destination.parent ?: throw IOException("CNB release asset destination has no parent")
            val temporaryName = ".cnb-release-download-$transferId.tmp"
            val temporary = parent.resolve(temporaryName)
            if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
                Files.createFile(temporary)
            }
            val identity = regularFileIdentity(temporary, "CNB release asset temporary file")
            return PreparedWorkspaceDownload(temporaryName, identity.fileKey, destinationExisted)
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private class OpenWorkspaceTemporary(
        private val relativePath: String,
        private val temporaryName: String,
        private val expectedFileKey: String?,
    ) : MasterToSlaveFileCallable<OutputStream>() {
        override fun invoke(
            workspace: File,
            channel: VirtualChannel,
        ): OutputStream {
            val temporary = resolveTemporary(workspace, relativePath, temporaryName)
            verifyFileKey(
                regularFileIdentity(temporary, "CNB release asset temporary file"),
                expectedFileKey,
            )
            val output =
                Files.newOutputStream(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS,
                )
            return RemoteOutputStream(output)
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private class InspectWorkspaceTemporary(
        private val relativePath: String,
        private val temporaryName: String,
        private val expectedFileKey: String?,
    ) : MasterToSlaveFileCallable<Long>() {
        override fun invoke(
            workspace: File,
            channel: VirtualChannel,
        ): Long {
            val identity =
                regularFileIdentity(
                    resolveTemporary(workspace, relativePath, temporaryName),
                    "CNB release asset temporary file",
                )
            verifyFileKey(identity, expectedFileKey)
            return identity.size
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private class DeleteWorkspaceTemporary(
        private val relativePath: String,
        private val temporaryName: String,
    ) : MasterToSlaveFileCallable<Boolean>() {
        override fun invoke(
            workspace: File,
            channel: VirtualChannel,
        ): Boolean {
            val temporary = resolveTemporary(workspace, relativePath, temporaryName)
            if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) return false
            if (Files.isDirectory(temporary, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("CNB release asset temporary path became a directory")
            }
            Files.delete(temporary)
            return true
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private class WorkspaceDownloadMatchesPublishedFile(
        private val relativePath: String,
        private val temporaryName: String,
        private val expectedFileKey: String?,
    ) : MasterToSlaveFileCallable<Boolean>() {
        override fun invoke(
            workspace: File,
            channel: VirtualChannel,
        ): Boolean {
            val validated = validatedPath(relativePath)
            val root = validatedWorkspaceRoot(workspace, create = false)
            val destination = resolveTrustedFile(root, validated, createParents = false)
            val temporary = resolveTemporary(workspace, relativePath, temporaryName)
            val destinationIdentity = regularFileIdentity(destination, "CNB release asset destination")
            val temporaryIdentity = regularFileIdentity(temporary, "CNB release asset temporary file")
            verifyFileKey(temporaryIdentity, expectedFileKey)
            if (destinationIdentity.size != temporaryIdentity.size) return false
            val destinationDigest = digestRegularFile(destination, CnbReleaseTransferLimit.MAX_BYTES)
            val temporaryDigest = digestRegularFile(temporary, CnbReleaseTransferLimit.MAX_BYTES)
            return MessageDigest.isEqual(destinationDigest, temporaryDigest)
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    private class AtomicWorkspaceMove(
        private val relativePath: String,
        private val temporaryName: String,
        private val expectedFileKey: String?,
        private val overwrite: Boolean,
    ) : MasterToSlaveFileCallable<Boolean>() {
        override fun invoke(
            workspace: File,
            channel: VirtualChannel,
        ): Boolean {
            val validated = validatedPath(relativePath)
            val root = validatedWorkspaceRoot(workspace, create = false)
            val target = resolveTrustedFile(root, validated, createParents = false)
            val source = resolveTemporary(workspace, relativePath, temporaryName)
            val sourceIdentity = regularFileIdentity(source, "CNB release asset temporary file")
            verifyFileKey(sourceIdentity, expectedFileKey)
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireRegularNonLink(target, "CNB release asset destination")
                if (!overwrite) {
                    throw FileAlreadyExistsException(target.toString())
                }
            }
            Files.move(source, target, *atomicMoveOptions(overwrite))
            return true
        }

        companion object {
            private const val serialVersionUID = 1L
        }
    }

    internal fun atomicMoveOptions(overwrite: Boolean): Array<CopyOption> =
        ArrayList<CopyOption>()
            .apply {
                add(StandardCopyOption.ATOMIC_MOVE)
                if (overwrite) add(StandardCopyOption.REPLACE_EXISTING)
            }.toTypedArray()

    private fun resolveTemporary(
        workspace: File,
        relativePath: String,
        temporaryName: String,
    ): Path {
        if (temporaryName.isEmpty() || temporaryName.contains('/') || temporaryName.contains('\\')) {
            throw IOException("Invalid CNB release asset temporary file")
        }
        val validated = validatedPath(relativePath)
        val root = validatedWorkspaceRoot(workspace, create = false)
        val destination = resolveTrustedFile(root, validated, createParents = false)
        val parent = destination.parent ?: throw IOException("CNB release asset destination has no parent")
        return parent.resolve(temporaryName)
    }

    private fun resolveTrustedFile(
        root: Path,
        path: CnbWorkspaceRelativePath,
        createParents: Boolean,
    ): Path {
        var current = root
        val segments = path.value.split('/')
        segments.dropLast(1).forEach { segment ->
            val next = current.resolve(segment)
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS) && createParents) {
                try {
                    Files.createDirectory(next)
                } catch (_: FileAlreadyExistsException) {
                    // A concurrent creator still has to pass the checks below.
                }
            }
            if (Files.isSymbolicLink(next) || !Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("CNB workspace path parent is not a real directory inside the workspace")
            }
            current = next
        }
        val resolved = current.resolve(segments.last()).normalize()
        if (!resolved.startsWith(root)) {
            throw IOException("CNB workspace path resolves outside the Jenkins workspace")
        }
        return resolved
    }

    private fun validatedWorkspaceRoot(
        workspace: File,
        create: Boolean,
    ): Path {
        val nominal = workspace.toPath().toAbsolutePath().normalize()
        if (!Files.exists(nominal, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) throw IOException("CNB Jenkins workspace does not exist")
            createWorkspaceDirectories(nominal)
        }
        if (Files.isSymbolicLink(nominal) || !Files.isDirectory(nominal, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("CNB Jenkins workspace root must be a real directory")
        }
        return nominal.toRealPath()
    }

    private fun createWorkspaceDirectories(nominal: Path) {
        val missingSegments = ArrayList<String>()
        var ancestor = nominal
        while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            val segment = ancestor.fileName ?: throw IOException("CNB Jenkins workspace has no existing ancestor")
            missingSegments.add(segment.toString())
            ancestor = ancestor.parent ?: throw IOException("CNB Jenkins workspace has no existing ancestor")
        }
        val realAncestor = ancestor.toRealPath()
        if (!Files.isDirectory(realAncestor, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("CNB Jenkins workspace ancestor is not a directory")
        }
        var current = realAncestor
        missingSegments.asReversed().forEach { segment ->
            val next = current.resolve(segment)
            try {
                Files.createDirectory(next)
            } catch (_: FileAlreadyExistsException) {
                // A concurrent creator still has to pass the checks below.
            }
            if (Files.isSymbolicLink(next) || !Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("CNB Jenkins workspace path contains a non-directory or symbolic link")
            }
            current = next
        }
    }

    private fun regularFileIdentity(
        path: Path,
        description: String,
    ): WorkspaceFileIdentity {
        requireRegularNonLink(path, description)
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        return WorkspaceFileIdentity(attributes.size(), attributes.fileKey()?.toString())
    }

    private fun requireRegularNonLink(
        path: Path,
        description: String,
    ) {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("$description must be a regular file and must not be a symbolic link")
        }
    }

    private fun verifyFileKey(
        actual: WorkspaceFileIdentity,
        expectedFileKey: String?,
    ) {
        if (expectedFileKey != null && actual.fileKey != expectedFileKey) {
            throw IOException("CNB release asset temporary file was replaced during download")
        }
    }

    private fun copyAndDigest(
        source: Path,
        target: Path,
        maxBytes: Long,
    ): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            Files
                .newOutputStream(
                    target,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS,
                ).use { output ->
                    val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > maxBytes) throw AbortException("CNB release asset source exceeds the supported size limit")
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
        }
        return digest.digest()
    }

    private fun digestRegularFile(
        source: Path,
        maxBytes: Long,
    ): ByteArray {
        requireRegularNonLink(source, "CNB release asset source")
        val digest = MessageDigest.getInstance("SHA-256")
        var read = 0L
        Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                read += count
                if (read > maxBytes) throw AbortException("CNB release asset source exceeds the supported size limit")
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun deleteStaleUploadPath(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("CNB release asset snapshot staging path became a directory")
        }
        Files.delete(path)
    }

    private fun validatedPath(relativePath: String): CnbWorkspaceRelativePath =
        try {
            CnbWorkspaceRelativePath.parse(relativePath)
        } catch (failure: IllegalArgumentException) {
            throw IOException(failure.message, failure)
        }

    private const val DEFAULT_COPY_BUFFER_BYTES = 64 * 1024
}
