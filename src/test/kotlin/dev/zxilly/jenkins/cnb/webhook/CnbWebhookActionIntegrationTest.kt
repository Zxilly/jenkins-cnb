package dev.zxilly.jenkins.cnb.webhook

import com.cloudbees.plugins.credentials.Credentials
import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.config.CnbWebhookCredentials
import dev.zxilly.jenkins.cnb.health.CnbHealthComponent
import dev.zxilly.jenkins.cnb.health.CnbOperationalHealth
import hudson.util.Secret
import org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@WithJenkins
class CnbWebhookActionIntegrationTest {
    @BeforeEach
    fun clearOperationalHealth() {
        CnbOperationalHealth.get().clear()
    }

    @AfterEach
    fun resetOperationalHealth() {
        CnbOperationalHealth.get().clear()
    }

    @Test
    fun `HTTP endpoint enforces its complete security contract and accepts one signed delivery`(jenkins: JenkinsRule) {
        configureWebhook()
        val client = HttpClient.newHttpClient()
        val root = jenkins.url.toURI()
        val endpoint = root.resolve("cnb-webhook/cnb-cool")
        val body = payload("team/project", "http-delivery-${System.nanoTime()}")
        val signature = sign(body, SECRET)

        val invalidRoute = send(client, root.resolve("cnb-webhook/-bad"), body, signature)
        assertEquals(403, invalidRoute.statusCode(), "An invalid route must not bypass Jenkins CSRF protection")
        assertResponse(send(client, root.resolve("cnb-webhook/unknown"), body, signature), 404, "rejected", false)
        val extraPath = send(client, URI.create("$endpoint/extra"), body, signature)
        assertEquals(403, extraPath.statusCode(), "A nested route must not bypass Jenkins CSRF protection")

        val get =
            send(
                client,
                endpoint,
                ByteArray(0),
                signature = null,
                method = "GET",
                contentType = null,
            )
        assertResponse(get, 405, "rejected", false)
        assertEquals("POST", get.headers().firstValue("Allow").orElse(null))

        assertResponse(send(client, endpoint, body, signature, contentType = "text/plain"), 415, "rejected", false)
        assertResponse(send(client, endpoint, ByteArray(0), signature), 400, "rejected", false)
        val largeBody =
            payload("team/project", "large-delivery-${System.nanoTime()}")
                .toString(StandardCharsets.UTF_8)
                .replaceFirst("{", "{\"future_padding\":\"${"x".repeat(256 * 1024)}\",")
                .toByteArray(StandardCharsets.UTF_8)
        assertResponse(send(client, endpoint, largeBody, sign(largeBody, SECRET)), 202, "accepted", false)
        val oversizedBody =
            payload("team/project", "oversized-delivery-${System.nanoTime()}")
                .toString(StandardCharsets.UTF_8)
                .replaceFirst("{", "{\"future_padding\":\"${"x".repeat(1024 * 1024)}\",")
                .toByteArray(StandardCharsets.UTF_8)
        val oversizedSignature = sign(oversizedBody, SECRET)
        assertResponse(send(client, endpoint, oversizedBody, oversizedSignature), 413, "rejected", false)
        assertResponse(sendChunked(client, endpoint, oversizedBody, oversizedSignature), 413, "rejected", false)

        val malformed = "{".toByteArray(StandardCharsets.UTF_8)
        assertResponse(send(client, endpoint, malformed, sign(malformed, SECRET)), 400, "rejected", false)
        assertResponse(send(client, endpoint, body, "sha256=${"0".repeat(64)}"), 401, "rejected", false)
        assertResponse(send(client, endpoint, body, signature, extraSignature = signature), 401, "rejected", false)

        val unconfigured = payload("team/unconfigured", "unconfigured-${System.nanoTime()}")
        assertResponse(send(client, endpoint, unconfigured, sign(unconfigured, SECRET)), 401, "rejected", false)

        installSecrets("too-short", PREVIOUS_SECRET)
        val shortSecret = payload("team/project", "short-secret-${System.nanoTime()}")
        assertResponse(send(client, endpoint, shortSecret, sign(shortSecret, "too-short")), 503, "rejected", false)
        installSecrets(SECRET, PREVIOUS_SECRET)

        val rotated = payload("team/project", "rotated-delivery-${System.nanoTime()}")
        assertResponse(send(client, endpoint, rotated, sign(rotated, PREVIOUS_SECRET)), 202, "accepted", false)
        assertResponse(send(client, endpoint, body, signature), 202, "accepted", false)
        assertResponse(send(client, endpoint, body, signature), 202, "accepted", true)

        val entries = CnbOperationalHealth.get().snapshot().entries
        val acceptedEntry = entries.single { it.repository == "team/project" }
        val serverFailure = entries.single { it.repository == CnbOperationalHealth.UNKNOWN_SCOPE }
        assertEquals(CnbHealthComponent.WEBHOOK, acceptedEntry.component)
        assertEquals("event=push status=duplicate", acceptedEntry.summary)
        assertTrue(acceptedEntry.lastSuccessAt != null)
        assertEquals("status=503 class=Cnb.Webhook.Request.Exception", serverFailure.summary)
        assertTrue(serverFailure.lastFailureAt != null)
        assertFalse(entries.any { it.repository == "team/unconfigured" })
        assertFalse(entries.any { SECRET in it.summary || "token=" in it.summary })
    }

    private fun configureWebhook() {
        installSecrets(SECRET, PREVIOUS_SECRET)
        val server = CnbServer("cnb-cool", "CNB Cool", "https://cnb.cool", "https://api.cnb.cool")
        val binding = CnbWebhookCredentials("team/project", CREDENTIAL_ID)
        binding.setPreviousSecretCredentialsId(PREVIOUS_CREDENTIAL_ID)
        server.setWebhookCredentials(listOf(binding))
        CnbGlobalConfiguration.get().setServers(listOf(server))
    }

    private fun installSecrets(
        current: String,
        previous: String,
    ) {
        val credentials: List<Credentials> =
            listOf(
                StringCredentialsImpl(
                    CredentialsScope.GLOBAL,
                    CREDENTIAL_ID,
                    "CNB webhook integration test",
                    Secret.fromString(current),
                ),
                StringCredentialsImpl(
                    CredentialsScope.GLOBAL,
                    PREVIOUS_CREDENTIAL_ID,
                    "CNB previous webhook integration test",
                    Secret.fromString(previous),
                ),
            )
        SystemCredentialsProvider.getInstance().setDomainCredentialsMap(
            mapOf(Domain.global() to credentials),
        )
    }

    private fun send(
        client: HttpClient,
        endpoint: URI,
        body: ByteArray,
        signature: String?,
        method: String = "POST",
        contentType: String? = "application/json; charset=utf-8",
        extraSignature: String? = null,
    ): HttpResponse<String> {
        val request = HttpRequest.newBuilder(endpoint)
        contentType?.let { request.header("Content-Type", it) }
        signature?.let { request.header(CnbWebhookAction.SIGNATURE_HEADER, it) }
        extraSignature?.let { request.header(CnbWebhookAction.SIGNATURE_HEADER, it) }
        request.method(method, if (method == "GET") HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    private fun sendChunked(
        client: HttpClient,
        endpoint: URI,
        body: ByteArray,
        signature: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(endpoint)
                .header("Content-Type", "application/json; charset=utf-8")
                .header(CnbWebhookAction.SIGNATURE_HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofInputStream { ByteArrayInputStream(body) })
                .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    }

    private fun assertResponse(
        response: HttpResponse<String>,
        expectedStatus: Int,
        expectedState: String,
        duplicate: Boolean,
    ) {
        assertEquals(expectedStatus, response.statusCode(), response.body())
        assertEquals("{\"status\":\"$expectedState\",\"duplicate\":$duplicate}", response.body())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(null))
        assertTrue(
            response
                .headers()
                .firstValue("Content-Type")
                .orElse("")
                .startsWith("application/json"),
        )
    }

    private fun payload(
        repository: String,
        delivery: String,
    ): ByteArray =
        """
        {
          "CNB_WEB_ENDPOINT":"https://cnb.cool",
          "CNB_API_ENDPOINT":"https://api.cnb.cool",
          "CNB_EVENT":"push",
          "CNB_EVENT_URL":"https://cnb.cool/$repository",
          "CNB_BRANCH":"main",
          "CNB_BRANCH_SHA":"${"a".repeat(40)}",
          "CNB_BEFORE_SHA":"${"b".repeat(40)}",
          "CNB_COMMIT":"${"a".repeat(40)}",
          "CNB_IS_TAG":"false",
          "CNB_REPO_SLUG":"$repository",
          "CNB_REPO_ID":"repo-http-1",
          "CNB_REPO_URL_HTTPS":"https://cnb.cool/$repository",
          "CNB_BUILD_ID":"build-http-1",
          "CNB_BUILD_START_TIME":"${Instant.now()}",
          "CNB_BUILD_USER":"alice",
          "CNB_BUILD_USER_ID":"user-http-1",
          "CNB_PIPELINE_ID":"$delivery",
          "CNB_IS_RETRY":"false"
        }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    private fun sign(
        body: ByteArray,
        secret: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val CREDENTIAL_ID = "http-webhook-secret"
        private const val PREVIOUS_CREDENTIAL_ID = "http-webhook-previous-secret"
        private const val SECRET = "http-webhook-secret-with-at-least-32-bytes"
        private const val PREVIOUS_SECRET = "previous-http-webhook-secret-with-at-least-32-bytes"
    }
}
