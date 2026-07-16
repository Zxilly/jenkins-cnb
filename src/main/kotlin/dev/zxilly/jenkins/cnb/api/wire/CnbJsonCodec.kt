package dev.zxilly.jenkins.cnb.api.wire

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import dev.zxilly.jenkins.cnb.api.CnbApiException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.charset.StandardCharsets

internal object CnbJsonCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
            explicitNulls = false
            coerceInputValues = false
            allowSpecialFloatingPointValues = false
        }

    private val lexicalFactory =
        JsonFactory
            .builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints
                    .builder()
                    .maxDocumentLength(MAX_DOCUMENT_BYTES.toLong())
                    .maxNestingDepth(MAX_NESTING_DEPTH)
                    .maxStringLength(MAX_STRING_LENGTH)
                    .maxNumberLength(MAX_NUMBER_LENGTH)
                    .build(),
            ).build()

    fun <T> decode(
        deserializer: DeserializationStrategy<T>,
        bytes: ByteArray,
        context: String,
    ): T {
        validateLexically(bytes, context)
        return decodeString(deserializer, bytes.toString(StandardCharsets.UTF_8), context)
    }

    fun <T, E> decodeArrayOrEnvelope(
        elementSerializer: KSerializer<T>,
        envelopeDeserializer: DeserializationStrategy<E>,
        extract: (E) -> List<T>,
        bytes: ByteArray,
        context: String,
    ): List<T> {
        val root = validateLexically(bytes, context)
        val input = bytes.toString(StandardCharsets.UTF_8)
        return when (root) {
            CnbJsonRoot.ARRAY -> decodeString(ListSerializer(elementSerializer), input, context)
            CnbJsonRoot.OBJECT -> extract(decodeString(envelopeDeserializer, input, context))
            CnbJsonRoot.OTHER -> throw invalidJson(context)
        }
    }

    fun <T> encode(
        serializer: SerializationStrategy<T>,
        value: T,
    ): ByteArray {
        val bytes = json.encodeToString(serializer, value).toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_REQUEST_BYTES) { "CNB JSON request body is too large" }
        return bytes
    }

    fun <T> canonicalBytes(
        serializer: SerializationStrategy<T>,
        value: T,
    ): ByteArray = json.encodeToString(serializer, value).toByteArray(StandardCharsets.UTF_8)

    private fun <T> decodeString(
        deserializer: DeserializationStrategy<T>,
        value: String,
        context: String,
    ): T =
        try {
            json.decodeFromString(deserializer, value)
        } catch (_: SerializationException) {
            throw invalidJson(context)
        } catch (_: IllegalArgumentException) {
            throw invalidJson(context)
        }

    private fun validateLexically(
        bytes: ByteArray,
        context: String,
    ): CnbJsonRoot {
        try {
            return lexicalFactory.createParser(bytes).use { parser ->
                var tokens = 0
                var first: JsonToken? = null
                while (true) {
                    val token = parser.nextToken() ?: break
                    if (first == null) first = token
                    tokens++
                    if (tokens > MAX_TOKENS) throw CnbApiException("CNB $context exceeded the JSON token limit")
                }
                when (first) {
                    JsonToken.START_ARRAY -> CnbJsonRoot.ARRAY
                    JsonToken.START_OBJECT -> CnbJsonRoot.OBJECT
                    else -> CnbJsonRoot.OTHER
                }
            }
        } catch (failure: CnbApiException) {
            throw failure
        } catch (_: JacksonException) {
            throw invalidJson(context)
        } catch (_: IOException) {
            throw invalidJson(context)
        }
    }

    private fun invalidJson(context: String): CnbApiException = CnbApiException("CNB $context contained invalid JSON")

    private enum class CnbJsonRoot {
        ARRAY,
        OBJECT,
        OTHER,
    }

    private const val MAX_DOCUMENT_BYTES = 4 * 1024 * 1024
    private const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
    private const val MAX_NESTING_DEPTH = 64
    private const val MAX_STRING_LENGTH = 1024 * 1024
    private const val MAX_NUMBER_LENGTH = 1_000
    private const val MAX_TOKENS = 500_000
}
