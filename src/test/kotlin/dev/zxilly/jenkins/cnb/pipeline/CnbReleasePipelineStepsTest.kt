package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbCreateReleaseRequest
import dev.zxilly.jenkins.cnb.api.model.CnbRelease
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAsset
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseAssetHead
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseMakeLatest
import dev.zxilly.jenkins.cnb.api.model.CnbReleaseUser
import dev.zxilly.jenkins.cnb.api.model.CnbUpdateReleaseRequest
import hudson.FilePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.LinkedHashMap

class CnbReleasePipelineStepsTest {
    private val context = CnbRunContext("cnb-cool", "team/project", null, null, null)
    private val user = CnbReleaseUser("alice", "Alice", "alice@example.test", "https://signed/avatar")
    private val asset =
        CnbReleaseAsset(
            id = "asset-1",
            name = "plugin.hpi",
            path = "releases/plugin.hpi",
            size = 42,
            contentType = "application/octet-stream",
            downloadCount = 7,
            hashAlgorithm = "sha256",
            hashValue = "deadbeef",
            browserDownloadUrl = "https://signed/browser-download",
            apiUrl = "https://signed/api-download",
            createdAt = "created",
            updatedAt = "updated",
            uploader = user,
        )
    private val release =
        CnbRelease(
            id = "release-1",
            tagName = "v1.0.0",
            name = "Version 1",
            body = "Release notes",
            tagCommitish = "master",
            latest = true,
            createdAt = "created",
            updatedAt = "updated",
            publishedAt = "published",
            author = user,
            assets = listOf(asset),
        )

    @Test
    fun `release reads return only CPS safe values without signed or API URLs`() {
        val client =
            client(
                mapOf(
                    "listReleases" to { listOf(release) },
                    "getLatestRelease" to { release },
                    "getRelease" to { release },
                    "getReleaseByTag" to { release },
                    "getReleaseAsset" to { asset },
                    "headReleaseAsset" to {
                        CnbReleaseAssetHead(true, 42, "application/octet-stream", "etag", "modified")
                    },
                ),
            )

        val values =
            listOf(
                CnbReleaseStepDispatcher.execute(CnbReleaseStepRequest.ListReleases, context, client),
                CnbReleaseStepDispatcher.execute(CnbReleaseStepRequest.LatestRelease, context, client),
                CnbReleaseStepDispatcher.execute(
                    CnbReleaseStepRequest.GetRelease(CnbReleaseId.parse("release-1")),
                    context,
                    client,
                ),
                CnbReleaseStepDispatcher.execute(
                    CnbReleaseStepRequest.GetReleaseByTag(CnbReleaseTag.parse("v1.0.0")),
                    context,
                    client,
                ),
                CnbReleaseStepDispatcher.execute(
                    CnbReleaseStepRequest.GetAsset(
                        CnbReleaseId.parse("release-1"),
                        CnbReleaseAssetId.parse("asset-1"),
                    ),
                    context,
                    client,
                ),
                CnbReleaseStepDispatcher.execute(
                    CnbReleaseStepRequest.HeadAsset(
                        CnbReleaseTag.parse("v1.0.0"),
                        CnbReleaseAssetName.parse("plugin.hpi"),
                    ),
                    context,
                    client,
                ),
            )

        values.forEach(::assertCpsSafe)
        val mappedRelease = (values[0] as List<*>).single() as Map<*, *>
        val mappedAsset = (mappedRelease["assets"] as List<*>).single() as Map<*, *>
        val mappedUser = mappedAsset["uploader"] as Map<*, *>
        assertEquals("Release notes", mappedRelease["body"])
        assertFalse(mappedAsset.containsKey("browserDownloadUrl"))
        assertFalse(mappedAsset.containsKey("apiUrl"))
        assertFalse(mappedUser.containsKey("avatarUrl"))
        assertFalse(mappedRelease.containsKey("token"))
    }

    @Test
    fun `latest release preserves an absent result as null`() {
        val result =
            CnbReleaseStepDispatcher.execute(
                CnbReleaseStepRequest.LatestRelease,
                context,
                client(mapOf("getLatestRelease" to { null })),
            )

        assertNull(result)
    }

    @Test
    fun `create and update pass strongly typed release requests and return safe acknowledgements`() {
        var created: CnbCreateReleaseRequest? = null
        var updatedId: String? = null
        var updated: CnbUpdateReleaseRequest? = null
        val client =
            client(
                mapOf(
                    "createRelease" to { args ->
                        created = args?.get(1) as CnbCreateReleaseRequest
                        release
                    },
                    "updateRelease" to { args ->
                        updatedId = args?.get(1) as String
                        updated = args[2] as CnbUpdateReleaseRequest
                        Unit
                    },
                ),
            )
        val createRequest =
            CnbCreateReleaseRequest(
                "v1.0.0",
                "master",
                "Version 1",
                "notes",
                draft = false,
                prerelease = true,
                makeLatest = CnbReleaseMakeLatest.LEGACY,
            )
        val updateRequest =
            CnbUpdateReleaseRequest(
                name = "",
                body = "new notes",
                draft = true,
                makeLatest = CnbReleaseMakeLatest.FALSE,
            )

        val createResult =
            CnbReleaseStepDispatcher.execute(
                CnbReleaseStepRequest.CreateRelease(createRequest),
                context,
                client,
            )
        val updateResult =
            CnbReleaseStepDispatcher.execute(
                CnbReleaseStepRequest.UpdateRelease(CnbReleaseId.parse("release-1"), updateRequest),
                context,
                client,
            ) as Map<*, *>

        assertEquals(createRequest, created)
        assertEquals("release-1", updatedId)
        assertEquals(updateRequest, updated)
        assertEquals("updated", updateResult["operation"])
        assertCpsSafe(createResult)
        assertCpsSafe(updateResult)
    }

    @Test
    fun `release and asset deletion require an exact confirmation before API calls`() {
        val calls = ArrayList<String>()
        val client =
            client(
                mapOf(
                    "getReleaseByTag" to { args ->
                        calls += "lookup:${args?.get(1)}"
                        release
                    },
                    "deleteRelease" to { args ->
                        calls += "release:${args?.get(1)}"
                        Unit
                    },
                    "deleteReleaseAsset" to { args ->
                        val requiredArgs = requireNotNull(args)
                        calls += "asset:${requiredArgs[1]}:${requiredArgs[2]}"
                        Unit
                    },
                ),
            )

        assertThrows(IllegalArgumentException::class.java) {
            CnbReleaseInput.confirmedReleaseDelete("release-1", "release-1 ", false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CnbReleaseInput.confirmedAssetDelete("release-1", "asset-1", "ASSET-1")
        }
        assertTrue(calls.isEmpty())

        val byId = CnbReleaseInput.confirmedReleaseDelete("release-1", "release-1", false)
        val byTag = CnbReleaseInput.confirmedReleaseDelete("v1.0.0", "v1.0.0", true)
        val deleteAsset = CnbReleaseInput.confirmedAssetDelete("release-1", "asset-1", "asset-1")
        val values =
            listOf(
                CnbReleaseStepDispatcher.execute(byId, context, client),
                CnbReleaseStepDispatcher.execute(byTag, context, client),
                CnbReleaseStepDispatcher.execute(deleteAsset, context, client),
            )

        assertEquals(
            listOf("release:release-1", "lookup:v1.0.0", "release:release-1", "asset:release-1:asset-1"),
            calls,
        )
        values.forEach(::assertCpsSafe)
    }

    @Test
    fun `release inputs parse enums and reject unknown or unsafe strings before API use`() {
        assertEquals(CnbReleaseMakeLatest.TRUE, CnbReleaseInput.makeLatest("TRUE"))
        assertEquals(CnbReleaseMakeLatest.FALSE, CnbReleaseInput.makeLatest("false"))
        assertEquals(CnbReleaseMakeLatest.LEGACY, CnbReleaseInput.makeLatest("legacy"))
        assertThrows(IllegalArgumentException::class.java) { CnbReleaseInput.makeLatest("newest") }
        assertThrows(IllegalArgumentException::class.java) { CnbReleaseId.parse("release/id") }
        assertThrows(IllegalArgumentException::class.java) { CnbReleaseTag.parse("bad\u0000tag") }
        assertThrows(IllegalArgumentException::class.java) { CnbReleaseAssetName.parse("dir/plugin.hpi") }
        assertThrows(IllegalArgumentException::class.java) { CnbReleaseAssetName.parse(" plugin.hpi") }
        assertThrows(IllegalArgumentException::class.java) { CnbReleaseAssetTtl.parse(181) }
        assertThrows(IllegalArgumentException::class.java) { CnbReleaseTransferLimit.parse(0) }
        assertThrows(IllegalArgumentException::class.java) {
            CnbReleaseTransferLimit.parse(CnbReleaseTransferLimit.MAX_BYTES + 1)
        }
    }

    @Test
    fun `release descriptors expose stable symbols and workspace only where required`() {
        val descriptors =
            listOf(
                CnbReleasesStep.DescriptorImpl(),
                CnbLatestReleaseStep.DescriptorImpl(),
                CnbReleaseStepById.DescriptorImpl(),
                CnbReleaseByTagStep.DescriptorImpl(),
                CnbReleaseAssetStep.DescriptorImpl(),
                CnbReleaseAssetHeadStep.DescriptorImpl(),
                CnbCreateReleaseStep.DescriptorImpl(),
                CnbUpdateReleaseStep.DescriptorImpl(),
                CnbDeleteReleaseStep.DescriptorImpl(),
                CnbDeleteReleaseAssetStep.DescriptorImpl(),
                CnbUploadReleaseAssetStep.DescriptorImpl(),
                CnbDownloadReleaseAssetStep.DescriptorImpl(),
            )
        assertEquals(
            listOf(
                "cnbReleases",
                "cnbLatestRelease",
                "cnbRelease",
                "cnbReleaseByTag",
                "cnbReleaseAsset",
                "cnbReleaseAssetHead",
                "cnbCreateRelease",
                "cnbUpdateRelease",
                "cnbDeleteRelease",
                "cnbDeleteReleaseAsset",
                "cnbUploadReleaseAsset",
                "cnbDownloadReleaseAsset",
            ),
            descriptors.map { it.functionName },
        )
        descriptors.take(10).forEach { assertFalse(it.requiredContext.contains(FilePath::class.java)) }
        descriptors.takeLast(2).forEach { assertTrue(it.requiredContext.contains(FilePath::class.java)) }
    }

    private fun assertCpsSafe(value: Any?) =
        when (value) {
            null,
            is String,
            is Number,
            is Boolean,
            -> {
                Unit
            }

            is LinkedHashMap<*, *> -> {
                assertTrue(value.keys.all { it is String })
                value.values.forEach(::assertCpsSafe)
            }

            is ArrayList<*> -> {
                value.forEach(::assertCpsSafe)
            }

            else -> {
                fail("Pipeline result contains non-CPS-safe ${value.javaClass.name}")
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun client(handlers: Map<String, (Array<out Any?>?) -> Any?>): CnbClient =
        Proxy.newProxyInstance(CnbClient::class.java.classLoader, arrayOf(CnbClient::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCapabilities" -> {
                    CnbApiCapabilities()
                }

                "close" -> {
                    Unit
                }

                "toString" -> {
                    "ReleasePipelineTestCnbClient"
                }

                "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                "equals" -> {
                    proxy === args?.firstOrNull()
                }

                else -> {
                    val handler = handlers[method.name] ?: throw UnsupportedOperationException(method.name)
                    handler.invoke(args)
                }
            }
        } as CnbClient
}
