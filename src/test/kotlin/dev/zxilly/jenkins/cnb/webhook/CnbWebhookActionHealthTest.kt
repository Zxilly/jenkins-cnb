package dev.zxilly.jenkins.cnb.webhook

import dev.zxilly.jenkins.cnb.health.CnbHealthComponent
import dev.zxilly.jenkins.cnb.health.CnbOperationalHealth
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.kohsuke.stapler.StaplerRequest2
import org.kohsuke.stapler.StaplerResponse2
import java.io.ByteArrayInputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class CnbWebhookActionHealthTest {
    @Test
    fun `accepted and duplicate deliveries update the parsed repository health scope`() {
        val health = CnbOperationalHealth()
        val attempts = AtomicInteger()
        val action =
            CnbWebhookAction(
                health,
                CnbWebhookRequestProcessor { serverId, rawBody, _, origin ->
                    val payload = CnbWebhookPayloadParser.parse(rawBody)
                    CnbWebhookProcessingResult(
                        CnbWebhookDelivery(serverId, payload, origin),
                        duplicate = attempts.getAndIncrement() > 0,
                    )
                },
            )

        val accepted = invoke(action, validPayload())
        val acceptedEntry = health.snapshot().entries.single()

        assertEquals(202, accepted.status)
        assertEquals("event=push status=accepted", acceptedEntry.summary)
        assertEquals("cnb-cool", acceptedEntry.serverId)
        assertEquals("team/project", acceptedEntry.repository)
        assertEquals(CnbHealthComponent.WEBHOOK, acceptedEntry.component)
        assertNotNull(acceptedEntry.lastSuccessAt)
        assertNull(acceptedEntry.lastFailureAt)

        val duplicate = invoke(action, validPayload())
        val duplicateEntry = health.snapshot().entries.single()

        assertEquals(202, duplicate.status)
        assertEquals("event=push status=duplicate", duplicateEntry.summary)
        assertNotNull(duplicateEntry.lastSuccessAt)
        assertNull(duplicateEntry.lastFailureAt)
        assertTrue(duplicate.body.toString().contains("\"duplicate\":true"))
    }

    @Test
    fun `client failures cannot create attacker-controlled health scopes`() {
        val requestHealth = CnbOperationalHealth()
        val requestFailure =
            CnbWebhookAction(
                requestHealth,
                CnbWebhookRequestProcessor { _, _, _, _ ->
                    throw CnbWebhookRequestException(
                        401,
                        "token=public-message-must-not-leak",
                        "https://cnb.cool/team/project?token=audit-must-not-leak",
                    )
                },
            )

        val requestResponse = invoke(requestFailure, validPayload())

        assertEquals(401, requestResponse.status)
        assertTrue(requestHealth.snapshot().entries.isEmpty())
    }

    @Test
    fun `server and unexpected failures use an unknown repository and safe class summaries`() {
        val serverHealth = CnbOperationalHealth()
        val serverFailure =
            CnbWebhookAction(
                serverHealth,
                CnbWebhookRequestProcessor { _, _, _, _ ->
                    throw CnbWebhookRequestException(
                        503,
                        "token=public-message-must-not-leak",
                        "https://cnb.cool/team/project?token=audit-must-not-leak",
                    )
                },
            )

        val (serverResponse, serverLogs) = captureActionLogs { invoke(serverFailure, validPayload()) }
        val serverEntry = serverHealth.snapshot().entries.single()

        assertEquals(503, serverResponse.status)
        assertEquals(CnbOperationalHealth.UNKNOWN_SCOPE, serverEntry.repository)
        assertEquals("status=503 class=Cnb.Webhook.Request.Exception", serverEntry.summary)
        assertNotNull(serverEntry.lastFailureAt)
        assertNull(serverEntry.lastSuccessAt)
        assertTrue("must-not-leak" !in serverEntry.summary)
        assertTrue(serverLogs.none { "must-not-leak" in it })

        val unexpectedHealth = CnbOperationalHealth()
        val unexpectedFailure =
            CnbWebhookAction(
                unexpectedHealth,
                CnbWebhookRequestProcessor { _, _, _, _ ->
                    throw IllegalStateException("Bearer unexpected-secret-must-not-leak")
                },
            )

        val unexpectedResponse = invoke(unexpectedFailure, validPayload())
        val unexpectedEntry = unexpectedHealth.snapshot().entries.single()

        assertEquals(500, unexpectedResponse.status)
        assertEquals(CnbOperationalHealth.UNKNOWN_SCOPE, unexpectedEntry.repository)
        assertEquals("class=Illegal.State.Exception", unexpectedEntry.summary)
        assertNotNull(unexpectedEntry.lastFailureAt)
        assertTrue("unexpected-secret" !in unexpectedEntry.summary)
    }

    @Test
    fun `a malformed client payload does not activate operational health`() {
        val health = CnbOperationalHealth()
        val action =
            CnbWebhookAction(
                health,
                CnbWebhookRequestProcessor { _, _, _, _ ->
                    throw CnbWebhookRequestException(
                        400,
                        "Invalid webhook payload",
                        "request body token=processor-message-must-not-leak",
                    )
                },
            )
        val malformed = """{"token":"raw-body-secret-must-not-leak""".toByteArray(StandardCharsets.UTF_8)

        val response = invoke(action, malformed)

        assertEquals(400, response.status)
        assertTrue(health.snapshot().entries.isEmpty())
    }

    private fun invoke(
        action: CnbWebhookAction,
        body: ByteArray,
    ): ResponseCapture {
        val response = ResponseCapture()
        action.doDynamic(request(body), response.proxy)
        return response
    }

    private fun request(body: ByteArray): StaplerRequest2 =
        Proxy.newProxyInstance(
            StaplerRequest2::class.java.classLoader,
            arrayOf(StaplerRequest2::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getRestOfPath" -> {
                    "/cnb-cool"
                }

                "getMethod" -> {
                    "POST"
                }

                "getContentType" -> {
                    "application/json; charset=utf-8"
                }

                "getContentLengthLong" -> {
                    body.size.toLong()
                }

                "getInputStream" -> {
                    ByteArrayServletInputStream(body)
                }

                "getHeaders" -> {
                    if (arguments?.firstOrNull() == CnbWebhookAction.SIGNATURE_HEADER) {
                        Collections.enumeration(listOf(VALID_SIGNATURE))
                    } else {
                        Collections.emptyEnumeration<String>()
                    }
                }

                "getHeader" -> {
                    if (arguments?.firstOrNull() == CnbWebhookAction.SIGNATURE_HEADER) VALID_SIGNATURE else null
                }

                "getRemoteAddr" -> {
                    "127.0.0.1"
                }

                "getRemoteHost" -> {
                    "localhost"
                }

                "getRequestURL" -> {
                    StringBuffer("https://jenkins.example/cnb-webhook/cnb-cool")
                }

                "getRequestURI" -> {
                    "/cnb-webhook/cnb-cool"
                }

                "toString" -> {
                    "test-webhook-request"
                }

                "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                "equals" -> {
                    proxy === arguments?.singleOrNull()
                }

                else -> {
                    defaultValue(method.returnType)
                }
            }
        } as StaplerRequest2

    private fun validPayload(): ByteArray =
        """
        {
          "CNB_WEB_ENDPOINT":"https://cnb.cool",
          "CNB_API_ENDPOINT":"https://api.cnb.cool",
          "CNB_EVENT":"push",
          "CNB_EVENT_URL":"https://cnb.cool/team/project?token=must-not-leak",
          "CNB_BRANCH":"main",
          "CNB_BRANCH_SHA":"${"a".repeat(40)}",
          "CNB_BEFORE_SHA":"${"b".repeat(40)}",
          "CNB_COMMIT":"${"a".repeat(40)}",
          "CNB_IS_TAG":"false",
          "CNB_REPO_SLUG":"team/project",
          "CNB_REPO_ID":"repo-health-1",
          "CNB_REPO_URL_HTTPS":"https://cnb.cool/team/project",
          "CNB_BUILD_ID":"build-health-1",
          "CNB_BUILD_START_TIME":"${Instant.now()}",
          "CNB_BUILD_USER":"alice",
          "CNB_BUILD_USER_ID":"user-health-1",
          "CNB_PIPELINE_ID":"delivery-${System.nanoTime()}",
          "CNB_IS_RETRY":"false"
        }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    private fun <T> captureActionLogs(block: () -> T): Pair<T, List<String>> {
        val messages = ArrayList<String>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    messages += record.message + record.parameters.orEmpty().joinToString(prefix = " [", postfix = "]")
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        handler.level = Level.ALL
        val logger = Logger.getLogger(CnbWebhookAction::class.java.name)
        logger.addHandler(handler)
        return try {
            block() to messages
        } finally {
            logger.removeHandler(handler)
        }
    }

    private inner class ResponseCapture {
        var status: Int = 0
        val body = StringWriter()
        private val writer = PrintWriter(body)
        val proxy: StaplerResponse2 =
            Proxy.newProxyInstance(
                StaplerResponse2::class.java.classLoader,
                arrayOf(StaplerResponse2::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "setStatus" -> {
                        status = arguments?.first() as Int
                        null
                    }

                    "getStatus" -> {
                        status
                    }

                    "getWriter" -> {
                        writer
                    }

                    "toString" -> {
                        "test-webhook-response"
                    }

                    "hashCode" -> {
                        System.identityHashCode(proxy)
                    }

                    "equals" -> {
                        proxy === arguments?.singleOrNull()
                    }

                    else -> {
                        defaultValue(method.returnType)
                    }
                }
            } as StaplerResponse2
    }

    private class ByteArrayServletInputStream(
        bytes: ByteArray,
    ) : ServletInputStream() {
        private val input = ByteArrayInputStream(bytes)

        override fun read(): Int = input.read()

        override fun isFinished(): Boolean = input.available() == 0

        override fun isReady(): Boolean = true

        override fun setReadListener(readListener: ReadListener?) = Unit
    }

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0F
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }

    companion object {
        private val VALID_SIGNATURE = "sha256=${"0".repeat(64)}"
    }
}
