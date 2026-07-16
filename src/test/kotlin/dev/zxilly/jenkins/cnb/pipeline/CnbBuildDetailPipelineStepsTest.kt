package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.CnbDownloadTarget
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbBuildRunnerLogDownload
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStage
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStageStatus
import hudson.AbortException
import hudson.FilePath
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.LinkedHashMap

class CnbBuildDetailPipelineStepsTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val context = CnbRunContext("cnb-cool", "team/project", null, null, null)

    @Test
    fun `stage maps typed status and bounded content to CPS safe values`() {
        val client =
            client(
                mapOf(
                    "getBuildStage" to {
                        CnbBuildStage(
                            "stage-1",
                            "tests",
                            CnbBuildStageStatus.SUCCESS,
                            duration = 17,
                            startTime = 10,
                            endTime = 27,
                            content = listOf("line one", "line two"),
                        )
                    },
                ),
            )

        val result =
            CnbBuildDetailDispatcher.execute(
                CnbBuildDetailRequest.Stage(
                    CnbBuildResourceId.parse("build-1", "build serial number"),
                    CnbBuildResourceId.parse("pipeline-1", "pipeline ID"),
                    CnbBuildResourceId.parse("stage-1", "stage ID"),
                ),
                context,
                client,
            ) as Map<*, *>

        assertEquals("success", result["status"])
        assertEquals(listOf("line one", "line two"), result["content"])
        assertCpsSafe(result)
    }

    @Test
    fun `runner log downloads through the hardened workspace publisher without returning body`() {
        val workspacePath = temporaryDirectory.resolve("workspace")
        Files.createDirectories(workspacePath)
        val bytes = "runner-log-body".toByteArray()
        var maxBytes = 0L
        val client =
            client(
                mapOf(
                    "downloadBuildRunnerLog" to { nullableArgs ->
                        val args = requireNotNull(nullableArgs)
                        maxBytes = args[3] as Long
                        val target = args[2] as CnbDownloadTarget
                        target.openStream().use { it.write(bytes) }
                        CnbBuildRunnerLogDownload(bytes.size.toLong(), "text/plain", "runner-etag")
                    },
                ),
            )

        val result =
            CnbBuildDetailDispatcher.execute(
                CnbBuildDetailRequest.DownloadRunnerLog(
                    CnbBuildResourceId.parse("pipeline-1", "pipeline ID"),
                    CnbWorkspaceRelativePath.parse("logs/runner.log"),
                    false,
                    CnbReleaseTransferLimit.parse(1024),
                ),
                context,
                client,
                FilePath(workspacePath.toFile()),
            ) as Map<*, *>

        assertArrayEquals(bytes, Files.readAllBytes(workspacePath.resolve("logs/runner.log")))
        assertEquals(1024, maxBytes)
        assertEquals(bytes.size.toLong(), result["size"])
        assertFalse(result.containsKey("content"))
        assertFalse(result.values.any { it == "runner-log-body" })
        assertCpsSafe(result)
    }

    @Test
    fun `runner log requires workspace and uses strict IDs paths and limits`() {
        val request =
            CnbBuildDetailRequest.DownloadRunnerLog(
                CnbBuildResourceId.parse("pipeline-1", "pipeline ID"),
                CnbWorkspaceRelativePath.parse("runner.log"),
                false,
                CnbReleaseTransferLimit.parse(10),
            )
        assertThrows(AbortException::class.java) {
            CnbBuildDetailDispatcher.execute(request, context, client(), null)
        }
        assertThrows(IllegalArgumentException::class.java) { CnbBuildResourceId.parse("bad/id", "pipeline ID") }
        assertThrows(IllegalArgumentException::class.java) { CnbDownloadBuildRunnerLogStep("pipeline-1", "../runner.log") }
        assertThrows(IllegalArgumentException::class.java) {
            CnbDownloadBuildRunnerLogStep("pipeline-1", "runner.log").setMaxBytes(0)
        }

        assertEquals("cnbBuildStage", CnbBuildStageStep.DescriptorImpl().functionName)
        val downloadDescriptor = CnbDownloadBuildRunnerLogStep.DescriptorImpl()
        assertEquals("cnbDownloadBuildRunnerLog", downloadDescriptor.functionName)
        assertTrue(downloadDescriptor.requiredContext.contains(FilePath::class.java))
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
    private fun client(handlers: Map<String, (Array<out Any?>?) -> Any?> = emptyMap()): CnbClient =
        Proxy.newProxyInstance(CnbClient::class.java.classLoader, arrayOf(CnbClient::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCapabilities" -> CnbApiCapabilities()
                "close" -> Unit
                "toString" -> "BuildDetailPipelineTestCnbClient"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> handlers[method.name]?.invoke(args) ?: throw UnsupportedOperationException(method.name)
            }
        } as CnbClient
}
