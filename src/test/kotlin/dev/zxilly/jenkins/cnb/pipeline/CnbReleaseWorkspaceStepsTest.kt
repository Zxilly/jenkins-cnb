package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.CnbDownloadTarget
import dev.zxilly.jenkins.cnb.api.CnbRepeatableInput
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbRelease
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAsset
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAssetDownload
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAssetUploadRequest
import hudson.AbortException
import hudson.FilePath
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.lang.reflect.Proxy
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.ArrayList
import java.util.LinkedHashMap
import java.util.concurrent.CancellationException

class CnbReleaseWorkspaceStepsTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val context = CnbRunContext("cnb-cool", "team/project", null, null, null)

    @Test
    fun `upload opens a repeatable NOFOLLOW workspace file with one fixed size`() {
        val workspacePath = temporaryDirectory.resolve("jenkins/workspace/job")
        Files.createDirectories(workspacePath.resolve("dist"))
        Files.writeString(workspacePath.resolve("dist/plugin.hpi"), "plugin", StandardCharsets.UTF_8)
        val workspace = FilePath(workspacePath.toFile())
        var uploadedRequest: CnbReleaseAssetUploadRequest? = null
        val client =
            client(
                mapOf(
                    "uploadReleaseAsset" to { args ->
                        uploadedRequest = requireNotNull(args)[2] as CnbReleaseAssetUploadRequest
                        val source = args[3] as CnbRepeatableInput
                        repeat(2) {
                            assertEquals("plugin", source.openStream().use { it.readAllBytes().decodeToString() })
                        }
                        Unit
                    },
                ),
            )

        val result =
            CnbReleaseWorkspaceTransfer.upload(
                uploadRequest("dist/plugin.hpi", overwrite = true, ttlDays = 7),
                context,
                client,
                workspace,
            ) as Map<*, *>

        assertEquals(6, uploadedRequest?.size)
        assertEquals("plugin.hpi", uploadedRequest?.assetName)
        assertEquals(true, uploadedRequest?.overwrite)
        assertEquals(7, uploadedRequest?.ttlDays)
        assertEquals("uploaded", result["operation"])
        assertCpsSafe(result)
    }

    @Test
    fun `upload retries read the same immutable snapshot after source changes`() {
        val workspacePath = temporaryDirectory.resolve("workspace")
        Files.createDirectories(workspacePath)
        val sourcePath = workspacePath.resolve("plugin.hpi")
        Files.writeString(sourcePath, "first", StandardCharsets.UTF_8)
        val client =
            client(
                mapOf(
                    "getRelease" to { CnbRelease(id = "release-1", tagName = "v1.0.0") },
                    "uploadReleaseAsset" to { args ->
                        val source = requireNotNull(args)[3] as CnbRepeatableInput
                        val first = source.openStream().use { it.readAllBytes() }
                        Files.writeString(sourcePath, "other", StandardCharsets.UTF_8)
                        val retried = source.openStream().use { it.readAllBytes() }
                        assertArrayEquals(first, retried)
                        assertEquals("first", retried.decodeToString())
                    },
                ),
            )

        CnbReleaseWorkspaceTransfer.upload(
            uploadRequest("plugin.hpi"),
            context,
            client,
            FilePath(workspacePath.toFile()),
            "12345678-1234-1234-1234-123456789abc",
        )

        assertFalse(
            Files.list(workspacePath).use { files ->
                files.anyMatch { it.fileName.toString().startsWith(".cnb-upload-") }
            },
        )
    }

    @Test
    fun `resumed upload converges when CNB already has the immutable snapshot`() {
        val workspacePath = temporaryDirectory.resolve("resumed-upload")
        Files.createDirectories(workspacePath)
        Files.writeString(workspacePath.resolve("plugin.hpi"), "plugin")
        var uploads = 0
        val digest = MessageDigest.getInstance("SHA-256").digest("plugin".toByteArray()).toHex()
        val client =
            client(
                mapOf(
                    "getRelease" to {
                        CnbRelease(
                            id = "release-1",
                            tagName = "v1.0.0",
                            assets =
                                listOf(
                                    CnbReleaseAsset(
                                        id = "asset-1",
                                        name = "plugin.hpi",
                                        path = "plugin.hpi",
                                        size = 6,
                                        hashAlgorithm = "sha256",
                                        hashValue = digest,
                                    ),
                                ),
                        )
                    },
                    "uploadReleaseAsset" to {
                        uploads++
                        Unit
                    },
                ),
            )

        val result =
            CnbReleaseWorkspaceTransfer.upload(
                uploadRequest("plugin.hpi", ttlDays = 180),
                context,
                client,
                FilePath(workspacePath.toFile()),
                "12345678-1234-1234-1234-123456789abc",
                resumed = true,
            ) as Map<*, *>

        assertEquals("uploaded", result["operation"])
        assertEquals(180, result["ttlDays"])
        assertEquals(0, uploads)
    }

    @Test
    fun `fresh upload never claims an unapplied TTL from preexisting matching content`() {
        val workspacePath = temporaryDirectory.resolve("fresh-upload-preexisting-match")
        Files.createDirectories(workspacePath)
        Files.writeString(workspacePath.resolve("plugin.hpi"), "plugin")
        var uploadedRequest: CnbReleaseAssetUploadRequest? = null
        val client =
            client(
                mapOf(
                    "getRelease" to { releaseWithAsset("plugin".toByteArray()) },
                    "uploadReleaseAsset" to { args ->
                        uploadedRequest = requireNotNull(args)[2] as CnbReleaseAssetUploadRequest
                        throw IllegalStateException("asset already exists")
                    },
                ),
            )

        val failure =
            assertThrows(IllegalStateException::class.java) {
                CnbReleaseWorkspaceTransfer.upload(
                    uploadRequest("plugin.hpi", ttlDays = 7),
                    context,
                    client,
                    FilePath(workspacePath.toFile()),
                    resumed = false,
                )
            }

        assertEquals("asset already exists", failure.message)
        assertEquals(7, uploadedRequest?.ttlDays)
    }

    @Test
    fun `overwrite upload never treats preexisting matching content as a successful overwrite`() {
        val workspacePath = temporaryDirectory.resolve("overwrite-preexisting-match")
        Files.createDirectories(workspacePath)
        Files.writeString(workspacePath.resolve("plugin.hpi"), "plugin")
        var uploads = 0
        val client =
            client(
                mapOf(
                    "getRelease" to { releaseWithAsset("plugin".toByteArray()) },
                    "uploadReleaseAsset" to {
                        uploads++
                        throw IllegalStateException("overwrite forbidden")
                    },
                ),
            )

        listOf(false, true).forEach { resumed ->
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    CnbReleaseWorkspaceTransfer.upload(
                        uploadRequest("plugin.hpi", overwrite = true, ttlDays = 7),
                        context,
                        client,
                        FilePath(workspacePath.toFile()),
                        resumed = resumed,
                    )
                }
            assertEquals("overwrite forbidden", failure.message)
        }

        assertEquals(2, uploads)
    }

    @Test
    fun `overwrite with unknown existing content neither downloads it nor masks upload failure`() {
        val workspacePath = temporaryDirectory.resolve("overwrite-unknown-existing")
        Files.createDirectories(workspacePath)
        Files.writeString(workspacePath.resolve("plugin.hpi"), "plugin")
        var uploads = 0
        var downloads = 0
        var failUpload = false
        val client =
            client(
                mapOf(
                    "getRelease" to {
                        CnbRelease(
                            id = "release-1",
                            tagName = "v1.0.0",
                            assets = listOf(CnbReleaseAsset("asset-1", "plugin.hpi", "plugin.hpi", 6)),
                        )
                    },
                    "downloadReleaseAsset" to {
                        downloads++
                        throw AssertionError("overwrite preflight must not download the old asset")
                    },
                    "uploadReleaseAsset" to {
                        uploads++
                        if (failUpload) throw IllegalStateException("overwrite failed")
                        Unit
                    },
                ),
            )
        val request = uploadRequest("plugin.hpi", overwrite = true)
        val workspace = FilePath(workspacePath.toFile())

        CnbReleaseWorkspaceTransfer.upload(request, context, client, workspace, resumed = true)
        failUpload = true
        val failure =
            assertThrows(IllegalStateException::class.java) {
                CnbReleaseWorkspaceTransfer.upload(request, context, client, workspace, resumed = true)
            }

        assertEquals("overwrite failed", failure.message)
        assertEquals(2, uploads)
        assertEquals(0, downloads)
    }

    @Test
    fun `wrapped preflight interruption prevents upload and post inspection`() {
        val workspacePath = temporaryDirectory.resolve("interrupted-preflight")
        Files.createDirectories(workspacePath)
        Files.writeString(workspacePath.resolve("plugin.hpi"), "plugin")
        var inspections = 0
        var uploads = 0
        var downloads = 0
        val client =
            client(
                mapOf(
                    "getRelease" to {
                        inspections++
                        throw IllegalStateException("wrapped interruption", InterruptedException("cancelled"))
                    },
                    "uploadReleaseAsset" to {
                        uploads++
                        Unit
                    },
                    "downloadReleaseAsset" to {
                        downloads++
                        throw AssertionError("cancelled upload must not inspect remote content")
                    },
                ),
            )

        try {
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    CnbReleaseWorkspaceTransfer.upload(
                        uploadRequest("plugin.hpi", overwrite = true),
                        context,
                        client,
                        FilePath(workspacePath.toFile()),
                    )
                }
            assertEquals("wrapped interruption", failure.message)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }

        assertEquals(1, inspections)
        assertEquals(0, uploads)
        assertEquals(0, downloads)
    }

    @Test
    fun `wrapped upload cancellation prevents post inspection`() {
        val workspacePath = temporaryDirectory.resolve("interrupted-upload")
        Files.createDirectories(workspacePath)
        Files.writeString(workspacePath.resolve("plugin.hpi"), "plugin")
        var inspections = 0
        var uploads = 0
        val client =
            client(
                mapOf(
                    "getRelease" to {
                        inspections++
                        CnbRelease(id = "release-1", tagName = "v1.0.0")
                    },
                    "uploadReleaseAsset" to {
                        uploads++
                        throw IllegalStateException("wrapped cancellation", CancellationException("cancelled"))
                    },
                ),
            )

        try {
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    CnbReleaseWorkspaceTransfer.upload(
                        uploadRequest("plugin.hpi"),
                        context,
                        client,
                        FilePath(workspacePath.toFile()),
                    )
                }
            assertEquals("wrapped cancellation", failure.message)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }

        assertEquals(1, inspections)
        assertEquals(1, uploads)
    }

    @Test
    fun `upload response timeout still converges after post inspection`() {
        val workspacePath = temporaryDirectory.resolve("upload-response-timeout")
        Files.createDirectories(workspacePath)
        Files.writeString(workspacePath.resolve("plugin.hpi"), "plugin")
        var inspections = 0
        var uploads = 0
        var remoteBytes: ByteArray? = null
        val client =
            client(
                mapOf(
                    "getRelease" to {
                        inspections++
                        releaseWithAsset(remoteBytes)
                    },
                    "uploadReleaseAsset" to { args ->
                        uploads++
                        remoteBytes = (requireNotNull(args)[3] as CnbRepeatableInput).openStream().use { it.readAllBytes() }
                        throw IllegalStateException("wrapped timeout", SocketTimeoutException("response timed out"))
                    },
                ),
            )

        val result =
            CnbReleaseWorkspaceTransfer.upload(
                uploadRequest("plugin.hpi"),
                context,
                client,
                FilePath(workspacePath.toFile()),
            ) as Map<*, *>

        assertEquals("uploaded", result["operation"])
        assertEquals(2, inspections)
        assertEquals(1, uploads)
        assertFalse(Thread.currentThread().isInterrupted)
    }

    @Test
    fun `upload converges after verification response is lost`() {
        val workspacePath = temporaryDirectory.resolve("verification-loss")
        Files.createDirectories(workspacePath)
        Files.writeString(workspacePath.resolve("plugin.hpi"), "plugin")
        var uploaded: ByteArray? = null
        val client =
            client(
                mapOf(
                    "uploadReleaseAsset" to { args ->
                        uploaded = (requireNotNull(args)[3] as CnbRepeatableInput).openStream().use { it.readAllBytes() }
                        throw IOException("verification response lost")
                    },
                    "getRelease" to { releaseWithAsset(uploaded) },
                ),
            )

        val result =
            CnbReleaseWorkspaceTransfer.upload(
                uploadRequest("plugin.hpi"),
                context,
                client,
                FilePath(workspacePath.toFile()),
            ) as Map<*, *>

        assertEquals("uploaded", result["operation"])
        assertEquals("plugin", uploaded?.decodeToString())
        assertTrue(Files.list(workspacePath).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".cnb-upload-") } })
    }

    @Test
    fun `new upload execution does not converge from another execution's matching content`() {
        val workspacePath = temporaryDirectory.resolve("cross-execution-verification-loss")
        Files.createDirectories(workspacePath)
        Files.writeString(workspacePath.resolve("plugin.hpi"), "plugin")
        var uploaded: ByteArray? = null
        var uploads = 0
        var inspections = 0
        val client =
            client(
                mapOf(
                    "getRelease" to {
                        inspections++
                        when (inspections) {
                            1 -> CnbRelease(id = "release-1", tagName = "v1.0.0")
                            2 -> throw IllegalStateException("inspection unavailable")
                            else -> releaseWithAsset(requireNotNull(uploaded))
                        }
                    },
                    "uploadReleaseAsset" to { args ->
                        uploads++
                        uploaded = (requireNotNull(args)[3] as CnbRepeatableInput).openStream().use { it.readAllBytes() }
                        throw IllegalStateException("verification response lost")
                    },
                ),
            )
        val workspace = FilePath(workspacePath.toFile())

        assertThrows(IllegalStateException::class.java) {
            CnbReleaseWorkspaceTransfer.upload(
                uploadRequest("plugin.hpi"),
                context,
                client,
                workspace,
                transferId = "12345678-1234-1234-1234-123456789abc",
            )
        }
        val failure =
            assertThrows(IllegalStateException::class.java) {
                CnbReleaseWorkspaceTransfer.upload(
                    uploadRequest("plugin.hpi"),
                    context,
                    client,
                    workspace,
                    transferId = "abcdefab-1234-1234-1234-abcdefabcdef",
                )
            }

        assertEquals("verification response lost", failure.message)
        assertEquals(2, uploads)
        assertEquals(3, inspections)
        assertTrue(Files.list(workspacePath).use { paths -> paths.noneMatch { it.fileName.toString().startsWith(".cnb-upload-") } })
    }

    @Test
    fun `upload comparison accepts a fresh digest sink on each transport retry`() {
        val workspacePath = temporaryDirectory.resolve("digest-retry")
        Files.createDirectories(workspacePath)
        Files.writeString(workspacePath.resolve("plugin.hpi"), "plugin")
        var uploads = 0
        val client =
            client(
                mapOf(
                    "getRelease" to {
                        CnbRelease(
                            id = "release-1",
                            tagName = "v1.0.0",
                            assets = listOf(CnbReleaseAsset("asset-1", "plugin.hpi", "plugin.hpi", 6)),
                        )
                    },
                    "downloadReleaseAsset" to { args ->
                        val target = requireNotNull(args)[3] as CnbDownloadTarget
                        target.openStream().use { it.write("partial".toByteArray()) }
                        target.openStream().use { it.write("plugin".toByteArray()) }
                        CnbReleaseAssetDownload(6, "application/octet-stream")
                    },
                    "uploadReleaseAsset" to {
                        uploads++
                        Unit
                    },
                ),
            )

        CnbReleaseWorkspaceTransfer.upload(
            uploadRequest("plugin.hpi"),
            context,
            client,
            FilePath(workspacePath.toFile()),
            resumed = true,
        )

        assertEquals(0, uploads)
    }

    @Test
    fun `upload retains one immutable snapshot until checkpoint cleanup`() {
        val workspacePath = temporaryDirectory.resolve("checkpointed-upload")
        Files.createDirectories(workspacePath)
        val source = workspacePath.resolve("plugin.hpi")
        Files.writeString(source, "first")
        val transferId = "12345678-1234-1234-1234-123456789abc"
        var remoteBytes: ByteArray? = null
        var uploads = 0
        val client =
            client(
                mapOf(
                    "uploadReleaseAsset" to { args ->
                        uploads++
                        remoteBytes = (requireNotNull(args)[3] as CnbRepeatableInput).openStream().use { it.readAllBytes() }
                    },
                    "getRelease" to { releaseWithAsset(remoteBytes) },
                ),
            )
        val workspace = FilePath(workspacePath.toFile())

        CnbReleaseWorkspaceTransfer.upload(
            uploadRequest("plugin.hpi"),
            context,
            client,
            workspace,
            transferId = transferId,
            retainSnapshotUntilCheckpoint = true,
        )
        Files.delete(source)
        CnbReleaseWorkspaceTransfer.upload(
            uploadRequest("plugin.hpi"),
            context,
            client,
            workspace,
            transferId = transferId,
            resumed = true,
            retainSnapshotUntilCheckpoint = true,
        )

        assertEquals(1, uploads)
        assertEquals("first", remoteBytes?.decodeToString())
        assertTrue(Files.exists(workspacePath.resolve(".cnb-upload-$transferId.snapshot")))
        CnbReleaseWorkspaceTransfer.cleanupUploadSnapshot(workspace, CnbWorkspaceRelativePath.parse("plugin.hpi"), transferId)
        assertFalse(Files.exists(workspacePath.resolve(".cnb-upload-$transferId.snapshot")))
    }

    @Test
    fun `upload rejects directories and symbolic links without calling CNB`() {
        val workspacePath = temporaryDirectory.resolve("workspace")
        val outside = temporaryDirectory.resolve("outside.hpi")
        Files.createDirectories(workspacePath.resolve("dist"))
        Files.writeString(outside, "outside")
        var calls = 0
        val client =
            client(
                mapOf(
                    "uploadReleaseAsset" to {
                        calls++
                        Unit
                    },
                ),
            )

        assertThrows(IOException::class.java) {
            CnbReleaseWorkspaceTransfer.upload(
                uploadRequest("dist"),
                context,
                client,
                FilePath(workspacePath.toFile()),
            )
        }

        val link = workspacePath.resolve("linked.hpi")
        assumeTrue(runCatching { Files.createSymbolicLink(link, outside) }.isSuccess, "symbolic links unavailable")
        assertThrows(IOException::class.java) {
            CnbReleaseWorkspaceTransfer.upload(
                uploadRequest("linked.hpi"),
                context,
                client,
                FilePath(workspacePath.toFile()),
            )
        }
        assertEquals(0, calls)
    }

    @Test
    fun `download writes a stable same-directory temporary then atomically publishes metadata`() {
        val workspacePath = temporaryDirectory.resolve("jenkins-download/workspace/job")
        val payload = "release-asset".toByteArray(StandardCharsets.UTF_8)
        var suppliedLimit = 0L
        var suppliedShare = false
        val client =
            downloadClient { args, target ->
                suppliedShare = args[4] as Boolean
                suppliedLimit = args[5] as Long
                target.openStream().use { it.write(payload) }
                CnbReleaseAssetDownload(payload.size.toLong(), "application/octet-stream", "etag-1")
            }

        val result =
            CnbReleaseWorkspaceTransfer.download(
                downloadRequest("downloads/plugin.hpi", share = true, maxBytes = 1024),
                context,
                client,
                FilePath(workspacePath.toFile()),
            ) as Map<*, *>

        assertArrayEquals(payload, Files.readAllBytes(workspacePath.resolve("downloads/plugin.hpi")))
        assertEquals(true, suppliedShare)
        assertEquals(1024, suppliedLimit)
        assertEquals("downloaded", result["operation"])
        assertEquals(payload.size.toLong(), result["size"])
        assertTrue(downloadTemporaries(workspacePath).isEmpty())
        assertCpsSafe(result)
    }

    @Test
    fun `download interrupted after transfer never publishes and removes its temporary`() {
        listOf(false, true).forEachIndexed { index, destinationExists ->
            val workspacePath = temporaryDirectory.resolve("interrupted-download-$index")
            Files.createDirectories(workspacePath)
            val destination = workspacePath.resolve("plugin.hpi")
            if (destinationExists) Files.writeString(destination, "old")
            val client =
                downloadClient { _, target ->
                    target.openStream().use { it.write("new".toByteArray()) }
                    Thread.currentThread().interrupt()
                    CnbReleaseAssetDownload(3, "application/octet-stream")
                }

            try {
                assertThrows(InterruptedException::class.java) {
                    CnbReleaseWorkspaceTransfer.download(
                        downloadRequest("plugin.hpi", overwrite = destinationExists),
                        context,
                        client,
                        FilePath(workspacePath.toFile()),
                    )
                }
                assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }

            if (destinationExists) {
                assertEquals("old", Files.readString(destination))
            } else {
                assertFalse(Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
            }
            assertTrue(downloadTemporaries(workspacePath).isEmpty())
        }
    }

    @Test
    fun `download never overwrites different content implicitly and only replaces an explicit regular target`() {
        val workspacePath = temporaryDirectory.resolve("workspace")
        Files.createDirectories(workspacePath)
        val destination = workspacePath.resolve("plugin.hpi")
        Files.writeString(destination, "old")
        var calls = 0
        val client =
            downloadClient { _, target ->
                calls++
                target.openStream().use { it.write("new".toByteArray()) }
                CnbReleaseAssetDownload(3, "application/octet-stream")
            }

        assertThrows(IOException::class.java) {
            CnbReleaseWorkspaceTransfer.download(
                downloadRequest("plugin.hpi"),
                context,
                client,
                FilePath(workspacePath.toFile()),
            )
        }
        assertEquals("old", Files.readString(destination))
        assertEquals(1, calls)

        CnbReleaseWorkspaceTransfer.download(
            downloadRequest("plugin.hpi", overwrite = true),
            context,
            client,
            FilePath(workspacePath.toFile()),
        )
        assertEquals("new", Files.readString(destination))
        assertEquals(2, calls)
    }

    @Test
    fun `new download execution converges with content published before checkpoint failure`() {
        val workspacePath = temporaryDirectory.resolve("download-after-checkpoint-failure")
        Files.createDirectories(workspacePath)
        val destination = workspacePath.resolve("plugin.hpi")
        Files.writeString(destination, "release-asset")
        var calls = 0
        val client =
            downloadClient { _, target ->
                calls++
                target.openStream().use { it.write("release-asset".toByteArray()) }
                CnbReleaseAssetDownload(13, "application/octet-stream", "etag-1")
            }

        val result =
            CnbReleaseWorkspaceTransfer.download(
                downloadRequest("plugin.hpi"),
                context,
                client,
                FilePath(workspacePath.toFile()),
                transferId = "abcdefab-1234-1234-1234-abcdefabcdef",
                resumed = false,
            ) as Map<*, *>

        assertEquals("downloaded", result["operation"])
        assertEquals("release-asset", Files.readString(destination))
        assertEquals(1, calls)
        assertTrue(downloadTemporaries(workspacePath).isEmpty())
    }

    @Test
    fun `resumed download converges only when the published file matches`() {
        val workspacePath = temporaryDirectory.resolve("resumed-download")
        Files.createDirectories(workspacePath)
        val destination = workspacePath.resolve("plugin.hpi")
        Files.writeString(destination, "release-asset")
        val matching =
            downloadClient { _, target ->
                target.openStream().use { it.write("release-asset".toByteArray()) }
                CnbReleaseAssetDownload(13, "application/octet-stream", "etag-1")
            }

        val result =
            CnbReleaseWorkspaceTransfer.download(
                downloadRequest("plugin.hpi"),
                context,
                matching,
                FilePath(workspacePath.toFile()),
                resumed = true,
            ) as Map<*, *>

        assertEquals("downloaded", result["operation"])
        assertEquals("release-asset", Files.readString(destination))
        assertTrue(downloadTemporaries(workspacePath).isEmpty())

        val different =
            downloadClient { _, target ->
                target.openStream().use { it.write("other-content".toByteArray()) }
                CnbReleaseAssetDownload(13, "application/octet-stream", "etag-2")
            }
        assertThrows(IOException::class.java) {
            CnbReleaseWorkspaceTransfer.download(
                downloadRequest("plugin.hpi"),
                context,
                different,
                FilePath(workspacePath.toFile()),
                resumed = true,
            )
        }
        assertEquals("release-asset", Files.readString(destination))
        assertTrue(downloadTemporaries(workspacePath).isEmpty())
    }

    @Test
    fun `resumed download reuses and publishes its stable orphan temporary`() {
        val workspacePath = temporaryDirectory.resolve("orphan-download")
        Files.createDirectories(workspacePath)
        val transferId = "12345678-1234-1234-1234-123456789abc"
        val orphan = workspacePath.resolve(".cnb-release-download-$transferId.tmp")
        Files.writeString(orphan, "partial bytes from interrupted controller")
        val client =
            downloadClient { _, target ->
                target.openStream().use { it.write("complete".toByteArray()) }
                CnbReleaseAssetDownload(8, "application/octet-stream")
            }

        CnbReleaseWorkspaceTransfer.download(
            downloadRequest("plugin.hpi"),
            context,
            client,
            FilePath(workspacePath.toFile()),
            transferId = transferId,
            resumed = true,
        )

        assertEquals("complete", Files.readString(workspacePath.resolve("plugin.hpi")))
        assertFalse(Files.exists(orphan, LinkOption.NOFOLLOW_LINKS))
        assertTrue(downloadTemporaries(workspacePath).isEmpty())
    }

    @Test
    fun `stopped download cleanup removes its stable temporary idempotently`() {
        val workspacePath = temporaryDirectory.resolve("stopped-download")
        val downloadDirectory = workspacePath.resolve("downloads")
        Files.createDirectories(downloadDirectory)
        val transferId = "12345678-1234-1234-1234-123456789abc"
        val temporary = downloadDirectory.resolve(".cnb-release-download-$transferId.tmp")
        Files.writeString(temporary, "partial")
        val workspace = FilePath(workspacePath.toFile())
        val path = CnbWorkspaceRelativePath.parse("downloads/plugin.hpi")

        CnbReleaseWorkspaceTransfer.cleanupDownloadTemporary(workspace, path, transferId)
        CnbReleaseWorkspaceTransfer.cleanupDownloadTemporary(workspace, path, transferId)

        assertFalse(Files.exists(temporary, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `download removes partial files after transport failure length mismatch and maxBytes breach`() {
        val cases =
            listOf<(CnbDownloadTarget) -> CnbReleaseAssetDownload?>(
                { target ->
                    target.openStream().use { it.write(byteArrayOf(1, 2)) }
                    throw IOException("transport failed")
                },
                { target ->
                    target.openStream().use { it.write(byteArrayOf(1, 2)) }
                    CnbReleaseAssetDownload(3, "application/octet-stream")
                },
                { target ->
                    target.openStream().use { it.write(byteArrayOf(1, 2, 3, 4)) }
                    CnbReleaseAssetDownload(4, "application/octet-stream")
                },
                { null },
            )

        cases.forEachIndexed { index, action ->
            val workspacePath = temporaryDirectory.resolve("workspace-$index")
            Files.createDirectories(workspacePath)
            val client = downloadClient { _, target -> action(target) }
            val request = downloadRequest("nested/plugin.hpi", maxBytes = if (index == 2) 3 else 100)

            assertThrows(Exception::class.java) {
                CnbReleaseWorkspaceTransfer.download(
                    request,
                    context,
                    client,
                    FilePath(workspacePath.toFile()),
                )
            }
            assertFalse(Files.exists(workspacePath.resolve("nested/plugin.hpi"), LinkOption.NOFOLLOW_LINKS))
            assertTrue(downloadTemporaries(workspacePath).isEmpty())
        }
    }

    @Test
    fun `download rejects parent and target symbolic links even with overwrite`() {
        val workspacePath = temporaryDirectory.resolve("workspace")
        val outsideDirectory = temporaryDirectory.resolve("outside")
        Files.createDirectories(workspacePath)
        Files.createDirectories(outsideDirectory)
        val parentLink = workspacePath.resolve("downloads")
        assumeTrue(
            runCatching { Files.createSymbolicLink(parentLink, outsideDirectory) }.isSuccess,
            "symbolic links unavailable",
        )
        var calls = 0
        val client =
            downloadClient { _, _ ->
                calls++
                CnbReleaseAssetDownload(0, "")
            }

        assertThrows(IOException::class.java) {
            CnbReleaseWorkspaceTransfer.download(
                downloadRequest("downloads/plugin.hpi", overwrite = true),
                context,
                client,
                FilePath(workspacePath.toFile()),
            )
        }
        assertFalse(Files.exists(outsideDirectory.resolve("plugin.hpi")))

        Files.delete(parentLink)
        val outsideTarget = outsideDirectory.resolve("target.hpi")
        Files.writeString(outsideTarget, "outside")
        val targetLink = workspacePath.resolve("plugin.hpi")
        Files.createSymbolicLink(targetLink, outsideTarget)
        assertThrows(IOException::class.java) {
            CnbReleaseWorkspaceTransfer.download(
                downloadRequest("plugin.hpi", overwrite = true),
                context,
                client,
                FilePath(workspacePath.toFile()),
            )
        }
        assertEquals("outside", Files.readString(outsideTarget))
        assertEquals(0, calls)
    }

    @Test
    fun `download rejects a replaced temporary and leaves the final target absent`() {
        val workspacePath = temporaryDirectory.resolve("workspace")
        val downloadDirectory = workspacePath.resolve("downloads")
        val outside = temporaryDirectory.resolve("outside.tmp")
        Files.createDirectories(downloadDirectory)
        Files.writeString(outside, "outside")
        val symbolicLinkProbe = downloadDirectory.resolve("symlink-probe")
        assumeTrue(
            runCatching {
                Files.createSymbolicLink(symbolicLinkProbe, outside)
                Files.delete(symbolicLinkProbe)
            }.isSuccess,
            "symbolic links unavailable",
        )
        val client =
            downloadClient { _, target ->
                target.openStream().use { it.write("safe".toByteArray()) }
                val temporary =
                    Files
                        .list(downloadDirectory)
                        .use { paths -> paths.filter { it.fileName.toString().startsWith(".cnb-release-download-") }.findFirst() }
                        .orElseThrow()
                Files.delete(temporary)
                Files.createSymbolicLink(temporary, outside)
                CnbReleaseAssetDownload(4, "application/octet-stream")
            }

        assertThrows(IOException::class.java) {
            CnbReleaseWorkspaceTransfer.download(
                downloadRequest("downloads/plugin.hpi", overwrite = true),
                context,
                client,
                FilePath(workspacePath.toFile()),
            )
        }
        assertFalse(Files.exists(downloadDirectory.resolve("plugin.hpi"), LinkOption.NOFOLLOW_LINKS))
        assertEquals("outside", Files.readString(outside))
        assertTrue(downloadTemporaries(workspacePath).isEmpty())
    }

    @Test
    fun `workspace paths and transfer options are strict typed values`() {
        listOf(
            "",
            "../plugin.hpi",
            "dist/../plugin.hpi",
            "/tmp/plugin.hpi",
            "C:/plugin.hpi",
            "dist\\plugin.hpi",
            "dist//plugin.hpi",
            "dist/./plugin.hpi",
            " plugin.hpi",
            "plugin.hpi ",
            "plugin\u0000.hpi",
        ).forEach { value ->
            assertThrows(
                IllegalArgumentException::class.java,
                { CnbWorkspaceRelativePath.parse(value) },
                value,
            )
        }
        assertEquals("plugin.hpi", CnbWorkspaceRelativePath.parse("dist/plugin.hpi").fileName)

        val plain = CnbReleaseWorkspaceTransfer.atomicMoveOptions(false)
        val replacing = CnbReleaseWorkspaceTransfer.atomicMoveOptions(true)
        assertArrayEquals(arrayOf(StandardCopyOption.ATOMIC_MOVE), plain)
        assertArrayEquals(
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
            replacing,
        )
    }

    private fun uploadRequest(
        path: String,
        overwrite: Boolean = false,
        ttlDays: Int = 0,
    ): CnbReleaseStepRequest.UploadAsset =
        CnbReleaseStepRequest.UploadAsset(
            CnbReleaseId.parse("release-1"),
            CnbWorkspaceRelativePath.parse(path),
            CnbReleaseAssetName.parse(path.substringAfterLast('/')),
            overwrite,
            CnbReleaseAssetTtl.parse(ttlDays),
        )

    private fun downloadRequest(
        path: String,
        share: Boolean = false,
        overwrite: Boolean = false,
        maxBytes: Long = 1024,
    ): CnbReleaseStepRequest.DownloadAsset =
        CnbReleaseStepRequest.DownloadAsset(
            CnbReleaseTag.parse("v1.0.0"),
            CnbReleaseAssetName.parse("plugin.hpi"),
            CnbWorkspaceRelativePath.parse(path),
            share,
            overwrite,
            CnbReleaseTransferLimit.parse(maxBytes),
        )

    private fun downloadClient(handler: (Array<out Any?>, CnbDownloadTarget) -> CnbReleaseAssetDownload?): CnbClient =
        client(
            mapOf(
                "downloadReleaseAsset" to { nullableArgs ->
                    val args = requireNotNull(nullableArgs)
                    handler(args, args[3] as CnbDownloadTarget)
                },
            ),
        )

    private fun downloadTemporaries(workspace: Path): List<Path> =
        Files
            .walk(workspace)
            .use { paths ->
                paths.filter { it.fileName.toString().startsWith(".cnb-release-download-") }.toList()
            }

    private fun assertCpsSafe(value: Any?) {
        when (value) {
            null,
            is String,
            is Number,
            is Boolean,
            -> Unit

            is LinkedHashMap<*, *> -> value.values.forEach(::assertCpsSafe)

            is ArrayList<*> -> value.forEach(::assertCpsSafe)

            else -> throw AssertionError("Pipeline result contains non-CPS-safe ${value.javaClass.name}")
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun releaseWithAsset(bytes: ByteArray?): CnbRelease =
        CnbRelease(
            id = "release-1",
            tagName = "v1.0.0",
            assets =
                bytes
                    ?.let {
                        listOf(
                            CnbReleaseAsset(
                                id = "asset-1",
                                name = "plugin.hpi",
                                path = "plugin.hpi",
                                size = it.size.toLong(),
                                hashAlgorithm = "sha256",
                                hashValue = MessageDigest.getInstance("SHA-256").digest(it).toHex(),
                            ),
                        )
                    }.orEmpty(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun client(handlers: Map<String, (Array<out Any?>?) -> Any?>): CnbClient =
        Proxy.newProxyInstance(CnbClient::class.java.classLoader, arrayOf(CnbClient::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCapabilities" -> CnbApiCapabilities()
                "close" -> Unit
                "toString" -> "ReleaseWorkspaceTestCnbClient"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> handlers[method.name]?.invoke(args) ?: throw UnsupportedOperationException(method.name)
            }
        } as CnbClient
}
