package dev.zxilly.jenkins.cnb.webhook

import dev.zxilly.jenkins.cnb.config.CnbServer
import java.time.Clock
import java.time.Duration

data class CnbWebhookDelivery(
    val serverId: String,
    val payload: CnbWebhookPayload,
    val origin: String,
)

internal fun interface CnbWebhookServerLookup {
    fun find(serverId: String): CnbServer?
}

internal fun interface CnbWebhookSecretProvider {
    fun secrets(
        server: CnbServer,
        repositoryPath: String,
    ): List<CharArray>
}

internal fun interface CnbWebhookDispatcher {
    fun dispatch(delivery: CnbWebhookDelivery)
}

internal data class CnbWebhookProcessingResult(
    val delivery: CnbWebhookDelivery,
    val duplicate: Boolean,
)

internal class CnbWebhookRequestException(
    val status: Int,
    val publicMessage: String,
    val auditReason: String,
    cause: Throwable? = null,
) : Exception(publicMessage, cause)

/**
 * Security-sensitive webhook module. Its interface accepts only the route identity, raw bytes, and
 * signature; secret lookup, validation, freshness, replay protection, and dispatch stay local.
 */
internal class CnbWebhookProcessor(
    private val serverLookup: CnbWebhookServerLookup,
    private val secretProvider: CnbWebhookSecretProvider,
    private val dispatcher: CnbWebhookDispatcher,
    private val replayCache: CnbReplayCache = CnbReplayCache(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun process(
        serverId: String,
        rawBody: ByteArray,
        signature: String?,
        origin: String,
    ): CnbWebhookProcessingResult {
        val server =
            serverLookup.find(serverId)
                ?: throw CnbWebhookRequestException(404, "Not found", "unknown server profile")
        // A strict, bounded parse is required before authentication solely to select the
        // repository-bound key. No value is trusted or dispatched until HMAC verification and
        // full semantic validation both succeed.
        val payload =
            try {
                CnbWebhookPayloadParser.parse(rawBody)
            } catch (e: CnbWebhookFormatException) {
                throw CnbWebhookRequestException(400, "Invalid webhook payload", e.message.orEmpty(), e)
            }
        val secrets =
            try {
                secretProvider.secrets(server, payload.repository.slug)
            } catch (e: NoSuchElementException) {
                throw CnbWebhookRequestException(401, "Unauthorized", "repository webhook key is not configured", e)
            } catch (e: Exception) {
                throw CnbWebhookRequestException(503, "Webhook is not configured", "secret lookup failed", e)
            }
        try {
            if (secrets.isEmpty() || secrets.first().isEmpty()) {
                throw CnbWebhookRequestException(503, "Webhook is not configured", "current webhook secret is absent")
            }
            if (!CnbHmac.verify(rawBody, signature, secrets)) {
                throw CnbWebhookRequestException(401, "Unauthorized", "signature verification failed")
            }

            try {
                CnbWebhookValidator.validate(serverId, server, payload, clock.instant())
            } catch (e: CnbWebhookValidationException) {
                throw CnbWebhookRequestException(401, "Unauthorized", e.message.orEmpty(), e)
            }

            val delivery = CnbWebhookDelivery(serverId, payload, origin)
            val replayScope = "$serverId:${payload.repository.slug}"
            val replayKey = payload.deliveryId
            val ttl = Duration.ofSeconds(server.maxWebhookAgeSeconds.toLong() + FUTURE_SKEW_SECONDS)
            val claim =
                try {
                    when (val result = replayCache.claim(replayScope, replayKey, clock.instant())) {
                        is CnbReplayClaimResult.Claimed -> {
                            result.token
                        }

                        CnbReplayClaimResult.Completed -> {
                            return CnbWebhookProcessingResult(delivery, duplicate = true)
                        }

                        CnbReplayClaimResult.InFlight -> {
                            throw CnbWebhookRequestException(
                                503,
                                "Webhook delivery is already being processed",
                                "delivery is currently in flight",
                            )
                        }
                    }
                } catch (e: CnbWebhookRequestException) {
                    throw e
                } catch (e: CnbReplayCapacityException) {
                    throw CnbWebhookRequestException(
                        503,
                        "Webhook replay protection is temporarily at capacity",
                        "replay cache capacity exhausted",
                        e,
                    )
                } catch (e: Exception) {
                    throw CnbWebhookRequestException(
                        503,
                        "Webhook replay protection is temporarily unavailable",
                        "replay claim could not be persisted",
                        e,
                    )
                }
            try {
                dispatcher.dispatch(delivery)
            } catch (e: Exception) {
                val failure = CnbWebhookRequestException(500, "Webhook dispatch failed", "event dispatch failed", e)
                try {
                    replayCache.release(claim)
                } catch (releaseFailure: Exception) {
                    failure.addSuppressed(releaseFailure)
                } finally {
                    replayCache.abandon(claim)
                }
                throw failure
            }
            try {
                replayCache.complete(claim, clock.instant(), ttl)
            } catch (e: Exception) {
                replayCache.abandon(claim)
                throw CnbWebhookRequestException(
                    503,
                    "Webhook replay protection is temporarily unavailable",
                    "completed delivery could not be persisted",
                    e,
                )
            }
            return CnbWebhookProcessingResult(delivery, duplicate = false)
        } finally {
            secrets.forEach { it.fill('\u0000') }
        }
    }

    companion object {
        private const val FUTURE_SKEW_SECONDS = 60L
    }
}
