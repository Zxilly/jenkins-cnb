package dev.zxilly.jenkins.cnb.api.wire

import dev.zxilly.jenkins.cnb.api.CnbApiException
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    fun <T> decode(
        deserializer: DeserializationStrategy<T>,
        bytes: ByteArray,
        context: String,
    ): T = decodeString(deserializer, bytes.toString(StandardCharsets.UTF_8), context)

    fun <T, E> decodeArrayOrEnvelope(
        elementSerializer: KSerializer<T>,
        envelopeDeserializer: DeserializationStrategy<E>,
        extract: (E) -> List<T>,
        bytes: ByteArray,
        context: String,
    ): List<T> {
        val input = bytes.toString(StandardCharsets.UTF_8)
        val array =
            try {
                json.decodeFromString(ListSerializer(elementSerializer), input)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        return array ?: extract(decodeString(envelopeDeserializer, input, context))
    }

    fun <T> encode(
        serializer: SerializationStrategy<T>,
        value: T,
    ): ByteArray = json.encodeToString(serializer, value).toByteArray(StandardCharsets.UTF_8)

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

    private fun invalidJson(context: String): CnbApiException = CnbApiException("CNB $context contained invalid JSON")
}
