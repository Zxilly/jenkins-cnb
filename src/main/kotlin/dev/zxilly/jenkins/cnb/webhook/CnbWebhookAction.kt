package dev.zxilly.jenkins.cnb.webhook

import com.cloudbees.plugins.credentials.CredentialsProvider
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.trigger.JenkinsCnbWebhookDispatcher
import hudson.Extension
import hudson.model.UnprotectedRootAction
import hudson.security.ACL
import hudson.security.csrf.CrumbExclusion
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jenkins.model.Jenkins
import jenkins.scm.api.SCMEvent
import org.jenkinsci.plugins.plaincredentials.StringCredentials
import org.kohsuke.stapler.StaplerRequest2
import org.kohsuke.stapler.StaplerResponse2
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

@Extension
class CnbWebhookAction : UnprotectedRootAction {
    override fun getIconFileName(): String? = null

    override fun getDisplayName(): String? = null

    override fun getUrlName(): String = URL_NAME

    @Suppress("unused") // Stapler dynamic action: /cnb-webhook/{serverId}
    fun doDynamic(
        request: StaplerRequest2,
        response: StaplerResponse2,
    ) {
        response.setHeader("Cache-Control", "no-store")
        response.setHeader("X-Content-Type-Options", "nosniff")
        val route = WEBHOOK_ROUTE_PATTERN.matchEntire(request.restOfPath)
        val validatedServerId = route?.groupValues?.get(1)
        if (validatedServerId == null) {
            writeJson(response, HttpServletResponse.SC_NOT_FOUND, "rejected", duplicate = false)
            return
        }
        if (request.method != "POST") {
            response.setHeader("Allow", "POST")
            writeJson(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "rejected", duplicate = false)
            return
        }
        val mediaType =
            request.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.ROOT)
        if (mediaType != JSON_MEDIA_TYPE) {
            writeJson(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "rejected", duplicate = false)
            return
        }
        if (request.contentLengthLong > MAX_BODY_BYTES) {
            writeJson(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "rejected", duplicate = false)
            return
        }

        val rawBody =
            try {
                request.inputStream.use { input ->
                    val output = ByteArrayOutputStream(8192)
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BODY_BYTES) throw RequestTooLargeException()
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            } catch (_: RequestTooLargeException) {
                writeJson(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "rejected", duplicate = false)
                return
            } catch (e: IOException) {
                LOGGER.log(Level.FINE, "Failed to read a CNB webhook request body", e)
                writeJson(response, HttpServletResponse.SC_BAD_REQUEST, "rejected", duplicate = false)
                return
            }
        if (rawBody.isEmpty()) {
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST, "rejected", duplicate = false)
            return
        }

        val signatures = Collections.list(request.getHeaders(SIGNATURE_HEADER))
        val signature = signatures.singleOrNull()
        try {
            val result =
                CnbWebhookRuntime.processor.process(
                    serverId = validatedServerId,
                    rawBody = rawBody,
                    signature = signature,
                    origin = SCMEvent.originOf(request) ?: SCMEvent.ORIGIN_UNKNOWN,
                )
            val payload = result.delivery.payload
            val level = if (result.duplicate) Level.FINE else Level.INFO
            LOGGER.log(
                level,
                "Accepted CNB webhook server={0}, delivery={1}, event={2}, repository={3}, duplicate={4}",
                arrayOf<Any>(
                    validatedServerId,
                    payload.deliveryId,
                    payload.event.wireName,
                    payload.repository.slug,
                    result.duplicate,
                ),
            )
            writeJson(response, HttpServletResponse.SC_ACCEPTED, "accepted", result.duplicate)
        } catch (e: CnbWebhookRequestException) {
            LOGGER.log(
                if (e.status >= 500) Level.WARNING else Level.FINE,
                "Rejected CNB webhook server={0}, status={1}, reason={2}",
                arrayOf<Any>(validatedServerId, e.status, sanitizeAuditValue(e.auditReason)),
            )
            writeJson(response, e.status, "rejected", duplicate = false)
        } catch (e: Exception) {
            // Do not log request bytes, headers, credentials, or exception messages from external data.
            LOGGER.log(Level.WARNING, "CNB webhook processing failed with ${e.javaClass.simpleName}")
            writeJson(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "rejected", duplicate = false)
        } finally {
            rawBody.fill(0)
        }
    }

    private fun writeJson(
        response: StaplerResponse2,
        status: Int,
        state: String,
        duplicate: Boolean,
    ) {
        response.status = status
        response.contentType = "$JSON_MEDIA_TYPE; charset=utf-8"
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write("{\"status\":\"$state\",\"duplicate\":$duplicate}")
    }

    private fun sanitizeAuditValue(value: String): String = value.replace(Regex("[\\p{Cntrl}]"), "?").take(MAX_AUDIT_VALUE_LENGTH)

    private class RequestTooLargeException : IOException()

    companion object {
        const val URL_NAME = "cnb-webhook"
        const val SIGNATURE_HEADER = "X-CNB-Signature"
        const val MAX_BODY_BYTES = 256 * 1024
        private const val JSON_MEDIA_TYPE = "application/json"
        private const val MAX_AUDIT_VALUE_LENGTH = 256
        private val WEBHOOK_ROUTE_PATTERN = Regex("^/([A-Za-z0-9][A-Za-z0-9._-]{0,63})/?$")
        private val LOGGER = Logger.getLogger(CnbWebhookAction::class.java.name)
    }
}

@Extension
class CnbWebhookCrumbExclusion : CrumbExclusion() {
    override fun process(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ): Boolean {
        val path = request.pathInfo ?: return false
        if (!matches(request.method, path)) return false
        chain.doFilter(request, response)
        return true
    }

    companion object {
        private val EXACT_WEBHOOK_PATH =
            Regex("/${Regex.escape(CnbWebhookAction.URL_NAME)}/[A-Za-z0-9][A-Za-z0-9._-]{0,63}/?")

        internal fun matches(
            method: String,
            path: String,
        ): Boolean = method == "POST" && EXACT_WEBHOOK_PATH.matches(path)
    }
}

private object CnbWebhookRuntime {
    val processor: CnbWebhookProcessor by lazy {
        CnbWebhookProcessor(
            serverLookup =
                CnbWebhookServerLookup { serverId ->
                    CnbGlobalConfiguration.get().getServers().firstOrNull { it.id == serverId }
                },
            secretProvider = JenkinsCnbWebhookSecretProvider,
            dispatcher = JenkinsCnbWebhookDispatcher,
            replayCache =
                CnbReplayCache(
                    path =
                        Jenkins
                            .get()
                            .rootDir
                            .toPath()
                            .resolve("cnb")
                            .resolve("webhook-replay.properties"),
                ),
        )
    }
}

private object JenkinsCnbWebhookSecretProvider : CnbWebhookSecretProvider {
    override fun secrets(
        server: CnbServer,
        repositoryPath: String,
    ): List<CharArray> {
        val configured = server.webhookCredentialsFor(repositoryPath) ?: throw NoSuchElementException()
        val ids = listOfNotNull(configured.secretCredentialsId, configured.previousSecretCredentialsId).distinct()
        val resolved = mutableListOf<CharArray>()
        try {
            ids.forEach { credentialId ->
                val credential =
                    CredentialsProvider.findCredentialByIdInItemGroup(
                        credentialId,
                        StringCredentials::class.java,
                        Jenkins.get(),
                        ACL.SYSTEM2,
                        URIRequirementBuilder.fromUri(server.webUrl).build(),
                    ) ?: throw IllegalStateException("Configured webhook Secret Text credential was not found")
                val plaintext = credential.secret.plainText
                val encoded = plaintext.toByteArray(StandardCharsets.UTF_8)
                try {
                    require(encoded.size >= MIN_WEBHOOK_SECRET_BYTES) {
                        "Webhook secret must contain at least $MIN_WEBHOOK_SECRET_BYTES UTF-8 bytes"
                    }
                } finally {
                    encoded.fill(0)
                }
                resolved += plaintext.toCharArray()
            }
            return resolved
        } catch (failure: Exception) {
            resolved.forEach { it.fill('\u0000') }
            throw failure
        }
    }

    private const val MIN_WEBHOOK_SECRET_BYTES = 32
}
