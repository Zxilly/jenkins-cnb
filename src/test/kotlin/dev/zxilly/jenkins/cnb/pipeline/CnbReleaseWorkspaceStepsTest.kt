package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.CnbDownloadTarget
import dev.zxilly.jenkins.cnb.api.CnbRepeatableInput
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.ArrayList
import java.util.LinkedHashMap

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
    fun `download writes a random same-directory temporary then atomically publishes metadata`() {
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
    fun `download never overwrites implicitly and only replaces an explicit regular target`() {
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
        assertEquals(0, calls)

        CnbReleaseWorkspaceTransfer.download(
            downloadRequest("plugin.hpi", overwrite = true),
            context,
            client,
            FilePath(workspacePath.toFile()),
        )
        assertEquals("new", Files.readString(destination))
        assertEquals(1, calls)
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
