package dev.zxilly.jenkins.cnb.api.wire

import dev.zxilly.jenkins.cnb.api.CnbApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

class CnbJsonCodecTest {
    @Test
    fun `decodes required and optional fields while ignoring unknown additions`() {
        val user =
            decodeUser(
                """{"username":"alice","future":{"supportedLater":true}}""",
            )

        assertEquals("alice", user.username)
        assertEquals("", user.nickname)
        assertEquals("", user.email)
    }

    @Test
    fun `rejects missing required fields and incompatible field types`() {
        listOf(
            """{"nickname":"Alice"}""",
            """{"username":42}""",
            """{"username":null}""",
        ).forEach { body ->
            assertThrows(CnbApiException::class.java) { decodeUser(body) }
        }
    }

    @Test
    fun `accepts confirmed string and integer identifier representations`() {
        val stringId = decodeLabel("""{"id":"label-7","name":"trusted"}""")
        val integerId = decodeLabel("""{"id":7,"name":"trusted"}""")

        assertEquals("label-7", stringId.id)
        assertEquals("7", integerId.id)
    }

    @Test
    fun `rejects non integer identifier representations`() {
        listOf(
            "true",
            "null",
            "{}",
            "7.5",
            "7e2",
        ).forEach { id ->
            assertThrows(CnbApiException::class.java) {
                decodeLabel("""{"id":$id,"name":"trusted"}""")
            }
        }
    }

    @Test
    fun `rejects duplicate object keys before typed decoding`() {
        assertThrows(CnbApiException::class.java) {
            decodeUser("""{"username":"alice","username":"mallory"}""")
        }
    }

    @Test
    fun `malformed and duplicate input never escapes through exception graphs`() {
        val secret = "Bearer secret-shaped-parser-input-1234567890"
        val inputs =
            listOf(
                """{"username":"$secret","username":"mallory"}""",
                """{"username":"$secret","future":}""",
            )

        inputs.forEach { input ->
            val failure = assertThrows(CnbApiException::class.java) { decodeUser(input) }
            val graph = throwableGraph(failure)

            assertEquals("CNB test user response contained invalid JSON", failure.message)
            assertNull(failure.cause)
            assertTrue(graph.none { secret in it.toString() })
            assertTrue(graph.none { "JSON input:" in it.toString() })
            assertFalse(graph.any { it.suppressed.isNotEmpty() })
        }
    }

    @Test
    fun `rejects documents beyond the lexical nesting bound`() {
        val nestedValue = "[".repeat(65) + "0" + "]".repeat(65)

        assertThrows(CnbApiException::class.java) {
            decodeUser("""{"username":"alice","future":$nestedValue}""")
        }
    }

    @Test
    fun `typed request encoding uses wire names and omits null optionals`() {
        val encoded =
            CnbJsonCodec
                .encode(
                    CnbMergePullRequestWire.serializer(),
                    CnbMergePullRequestWire(mergeStyle = "squash"),
                ).toString(StandardCharsets.UTF_8)

        assertEquals("""{"merge_style":"squash"}""", encoded)
    }

    @Test
    fun `decodes typed arrays and typed items envelopes while rejecting other roots`() {
        val array = decodeUsers("""[{"username":"array-user"}]""")
        val envelope = decodeUsers("""{"items":[{"username":"envelope-user"}]}""")

        assertEquals(listOf("array-user"), array.map(CnbAuthenticatedUserWire::username))
        assertEquals(listOf("envelope-user"), envelope.map(CnbAuthenticatedUserWire::username))
        listOf("null", "true", "42", "{}", """{"items":"not-an-array"}""").forEach { body ->
            assertThrows(CnbApiException::class.java) { decodeUsers(body) }
        }
    }

    private fun decodeUser(body: String): CnbAuthenticatedUserWire =
        CnbJsonCodec.decode(
            CnbAuthenticatedUserWire.serializer(),
            body.toByteArray(StandardCharsets.UTF_8),
            "test user response",
        )

    private fun decodeLabel(body: String): CnbLabelWire =
        CnbJsonCodec.decode(
            CnbLabelWire.serializer(),
            body.toByteArray(StandardCharsets.UTF_8),
            "test label response",
        )

    private fun decodeUsers(body: String): List<CnbAuthenticatedUserWire> =
        CnbJsonCodec.decodeArrayOrEnvelope(
            elementSerializer = CnbAuthenticatedUserWire.serializer(),
            envelopeDeserializer = CnbItemsEnvelopeWire.serializer(CnbAuthenticatedUserWire.serializer()),
            extract = { envelope: CnbItemsEnvelopeWire<CnbAuthenticatedUserWire> -> envelope.items },
            bytes = body.toByteArray(StandardCharsets.UTF_8),
            context = "test users response",
        )

    private fun throwableGraph(root: Throwable): List<Throwable> {
        val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>()
        val output = ArrayList<Throwable>()
        pending += root
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!visited.add(current)) continue
            output += current
            current.cause?.let(pending::addLast)
            current.suppressed.forEach(pending::addLast)
        }
        return output
    }
}
