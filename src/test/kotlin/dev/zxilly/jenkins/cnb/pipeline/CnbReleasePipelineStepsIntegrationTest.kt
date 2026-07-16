package dev.zxilly.jenkins.cnb.pipeline

import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.credentials.CnbTokenCredentials
import hudson.util.Secret
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

@WithJenkins
class CnbReleasePipelineStepsIntegrationTest {
    @Test
    fun `Pipeline covers typed release reads writes deletes and workspace transfers`(jenkins: JenkinsRule) {
        val requests = CopyOnWriteArrayList<Request>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val artifact = "downloaded-release-asset".toByteArray(StandardCharsets.UTF_8)
        val uploaded = CopyOnWriteArrayList<ByteArray>()
        server.createContext("/") { exchange ->
            val request =
                Request(
                    exchange.requestMethod,
                    exchange.requestURI.path,
                    exchange.requestURI.rawQuery.orEmpty(),
                    exchange.requestHeaders.getFirst("Authorization"),
                    if (exchange.requestMethod == "HEAD") byteArrayOf() else exchange.requestBody.readAllBytes(),
                )
            requests += request
            when (request.path) {
                "/team/project/-/releases" -> {
                    when (request.method) {
                        "POST" -> {
                            respond(exchange, 201, releaseJson(server, "release-2", "v2.0.0"))
                        }

                        else -> {
                            val firstPage = request.query.contains("page=1")
                            respond(exchange, 200, if (firstPage) "[${releaseJson(server)}]" else "[]")
                        }
                    }
                }

                "/team/project/-/releases/latest" -> {
                    respond(exchange, 200, releaseJson(server))
                }

                "/team/project/-/releases/tags/v1.0.0" -> {
                    respond(exchange, 200, releaseJson(server))
                }

                "/team/project/-/releases/release-1" -> {
                    when (request.method) {
                        "GET" -> respond(exchange, 200, releaseJson(server))
                        "PATCH", "DELETE" -> respond(exchange, 204, "")
                        else -> respond(exchange, 405, "")
                    }
                }

                "/team/project/-/releases/release-1/assets/asset-1" -> {
                    if (request.method == "GET") {
                        respond(exchange, 200, assetJson(server))
                    } else {
                        respond(exchange, 204, "")
                    }
                }

                "/team/project/-/releases/download/v1.0.0/plugin.hpi" -> {
                    exchange.responseHeaders.add(
                        "Location",
                        "http://127.0.0.1:${server.address.port}/signed/release-object?signature=secret",
                    )
                    exchange.sendResponseHeaders(302, -1)
                    exchange.close()
                }

                "/signed/release-object" -> {
                    exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                    exchange.responseHeaders.add("ETag", "release-etag")
                    exchange.responseHeaders.add("Last-Modified", "Wed, 15 Jul 2026 10:00:00 GMT")
                    if (request.method == "HEAD") {
                        exchange.responseHeaders.add("Content-Length", artifact.size.toString())
                        exchange.sendResponseHeaders(200, -1)
                        exchange.close()
                    } else {
                        exchange.sendResponseHeaders(200, artifact.size.toLong())
                        exchange.responseBody.use { it.write(artifact) }
                    }
                }

                "/team/project/-/releases/release-1/asset-upload-url" -> {
                    respond(
                        exchange,
                        200,
                        """{"expires_in_sec":300,"upload_url":"http://127.0.0.1:${server.address.port}""" +
                            """/signed/upload?signature=secret","verify_url":"http://127.0.0.1:${server.address.port}""" +
                            """/team/project/-/releases/release-1/asset-upload-confirmation/token/""" +
                            """%2Fteam%2Fproject%2F-%2Freleases%2Fdownload%2Fv1.0.0%2Fuploaded.hpi?ttl=7"}""",
                    )
                }

                "/signed/upload" -> {
                    uploaded += request.body
                    respond(exchange, 200, "")
                }

                "/team/project/-/releases/release-1/asset-upload-confirmation/token/" +
                    "/team/project/-/releases/download/v1.0.0/uploaded.hpi",
                -> {
                    respond(exchange, 204, "")
                }

                else -> {
                    respond(exchange, 404, """{"errcode":404,"errmsg":"missing"}""")
                }
            }
        }
        server.start()
        try {
            configureServer(server)
            val job = jenkins.createProject(WorkflowJob::class.java, "cnb-release-pipeline")
            job.definition =
                CpsFlowDefinition(
                    """
                    node {
                      writeFile file: 'dist/uploaded.hpi', text: 'uploaded-release-asset'
                      def releases = cnbReleases(serverId: 'local-cnb', repository: 'team/project')
                      if (releases.size() != 1 || releases[0].id != 'release-1') { error('unexpected releases') }
                      if (releases[0].assets[0].browserDownloadUrl != null || releases[0].assets[0].apiUrl != null) {
                        error('release map exposed an URL')
                      }
                      def latest = cnbLatestRelease(serverId: 'local-cnb', repository: 'team/project')
                      def byId = cnbRelease(serverId: 'local-cnb', repository: 'team/project', releaseId: 'release-1')
                      def byTag = cnbReleaseByTag(serverId: 'local-cnb', repository: 'team/project', tag: 'v1.0.0')
                      def asset = cnbReleaseAsset(
                        serverId: 'local-cnb', repository: 'team/project', releaseId: 'release-1', assetId: 'asset-1'
                      )
                      if (latest.id != byId.id || byTag.id != byId.id || asset.id != 'asset-1') { error('read mismatch') }
                      if (asset.browserDownloadUrl != null || asset.apiUrl != null) { error('asset map exposed an URL') }
                      def created = cnbCreateRelease(
                        serverId: 'local-cnb', repository: 'team/project', tagName: 'v2.0.0',
                        targetCommitish: 'master', name: 'Version 2', body: 'Pipeline release', makeLatest: 'legacy'
                      )
                      if (created.id != 'release-2') { error('create failed') }
                      def updated = cnbUpdateRelease(
                        serverId: 'local-cnb', repository: 'team/project', releaseId: 'release-1',
                        name: 'Updated release', draft: true, makeLatest: 'false'
                      )
                      if (updated.operation != 'updated') { error('update failed') }
                      def upload = cnbUploadReleaseAsset(
                        serverId: 'local-cnb', repository: 'team/project', releaseId: 'release-1',
                        path: 'dist/uploaded.hpi', assetName: 'uploaded.hpi', overwrite: true, ttlDays: 7
                      )
                      if (upload.operation != 'uploaded' || upload.size != 22) { error('upload failed') }
                      def head = cnbReleaseAssetHead(
                        serverId: 'local-cnb', repository: 'team/project', tag: 'v1.0.0', assetName: 'plugin.hpi'
                      )
                      if (!head.exists || head.contentLength != 24) { error('head failed') }
                      def download = cnbDownloadReleaseAsset(
                        serverId: 'local-cnb', repository: 'team/project', tag: 'v1.0.0',
                        assetName: 'plugin.hpi', path: 'downloads/plugin.hpi', overwrite: false, maxBytes: 1024
                      )
                      if (download.operation != 'downloaded' || readFile('downloads/plugin.hpi') != 'downloaded-release-asset') {
                        error('download failed')
                      }
                      def deletedAsset = cnbDeleteReleaseAsset(
                        serverId: 'local-cnb', repository: 'team/project', releaseId: 'release-1',
                        assetId: 'asset-1', confirm: 'asset-1'
                      )
                      def deletedRelease = cnbDeleteRelease(
                        serverId: 'local-cnb', repository: 'team/project', target: 'release-1', confirm: 'release-1'
                      )
                      if (deletedAsset.operation != 'deleted' || deletedRelease.operation != 'deleted') { error('delete failed') }
                    }
                    """.trimIndent(),
                    true,
                )

            jenkins.buildAndAssertSuccess(job)

            assertEquals("uploaded-release-asset", uploaded.single().toString(StandardCharsets.UTF_8))
            val create = requests.single { it.method == "POST" && it.path == "/team/project/-/releases" }
            assertTrue(create.body.toString(StandardCharsets.UTF_8).contains("\"make_latest\":\"legacy\""))
            val update = requests.single { it.method == "PATCH" }
            assertTrue(update.body.toString(StandardCharsets.UTF_8).contains("\"draft\":true"))
            assertEquals(2, requests.count { it.method == "DELETE" })
            val confirmation = requests.single { it.path.contains("asset-upload-confirmation") }
            assertEquals("ttl=7", confirmation.query)
            assertEquals("Bearer top-secret", confirmation.authorization)
            assertTrue(requests.any { it.method == "HEAD" && it.path == "/signed/release-object" })
            assertTrue(requests.any { it.method == "GET" && it.path == "/signed/release-object" })
            requests.filter { it.path.startsWith("/signed/") }.forEach { assertNull(it.authorization) }
            assertFalse(requests.any { it.body.toString(StandardCharsets.UTF_8).contains("signature=secret") })
        } finally {
            server.stop(0)
        }
    }

    private fun configureServer(server: HttpServer) {
        val credential =
            CnbTokenCredentials(
                CredentialsScope.GLOBAL,
                "release-test-token",
                "Release integration test token",
                Secret.fromString("top-secret"),
            )
        SystemCredentialsProvider.getInstance().setDomainCredentialsMap(mapOf(Domain.global() to listOf(credential)))
        val local =
            CnbServer(
                "local-cnb",
                "Local CNB",
                "http://127.0.0.1:${server.address.port}",
                "http://127.0.0.1:${server.address.port}",
            )
        local.setCredentialsId("release-test-token")
        local.setAllowInsecureHttp(true)
        local.setAllowPrivateNetwork(true)
        CnbGlobalConfiguration.get().setServers(listOf(local))
    }

    private fun releaseJson(
        server: HttpServer,
        id: String = "release-1",
        tag: String = "v1.0.0",
    ): String =
        """
        {
          "id":"$id","tag_name":"$tag","name":"Version $tag","body":"Production release",
          "tag_commitish":"master","draft":false,"prerelease":false,"is_latest":true,
          "created_at":"2026-07-15T10:00:00Z","updated_at":"2026-07-15T10:01:00Z","published_at":"2026-07-15T10:02:00Z",
          "author":{"username":"alice","nickname":"Alice","avatar":"http://127.0.0.1:${server.address.port}/avatar"},
          "assets":[${assetJson(server, tag)}]
        }
        """.trimIndent()

    private fun assetJson(
        server: HttpServer,
        tag: String = "v1.0.0",
    ): String =
        """
        {
          "id":"asset-1","name":"plugin.hpi","path":"/team/project/-/releases/download/$tag/plugin.hpi","size":24,
          "content_type":"application/octet-stream","download_count":3,"hash_algo":"sha256","hash_value":"${"ab".repeat(32)}",
          "browser_download_url":"http://127.0.0.1:${server.address.port}/signed/browser",
          "url":"http://127.0.0.1:${server.address.port}/signed/api",
          "created_at":"2026-07-15T10:00:00Z","updated_at":"2026-07-15T10:01:00Z",
          "uploader":{"username":"alice","avatar":"http://127.0.0.1:${server.address.port}/avatar"}
        }
        """.trimIndent()

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, if (status == 204) -1 else bytes.size.toLong())
        if (status == 204) {
            exchange.close()
        } else {
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: String,
        val authorization: String?,
        val body: ByteArray,
    )
}
