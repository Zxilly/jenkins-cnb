package dev.zxilly.jenkins.cnb.health

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Operational component whose latest CNB interaction is tracked in memory. */
enum class CnbHealthComponent(
    val displayName: String,
) {
    WEBHOOK("webhook"),
    POLLING("polling"),
    REPORTING("reporting"),
}

/** Immutable, already-redacted view of one server/repository/component scope. */
data class CnbOperationalHealthEntry(
    val serverId: String,
    val repository: String,
    val component: CnbHealthComponent,
    val lastSuccessAt: Instant?,
    val lastFailureAt: Instant?,
    val summary: String,
) {
    val componentDisplayName: String
        get() = component.displayName

    val lastSuccessDisplay: String
        get() = lastSuccessAt?.toString() ?: NOT_RECORDED

    val lastFailureDisplay: String
        get() = lastFailureAt?.toString() ?: NOT_RECORDED

    internal companion object {
        const val NOT_RECORDED = "—"
    }
}

/** Point-in-time immutable copy of the bounded operational health registry. */
class CnbOperationalHealthSnapshot internal constructor(
    val generatedAt: Instant,
    val entries: List<CnbOperationalHealthEntry>,
) {
    @JvmOverloads
    fun hasRecentUnresolvedFailures(window: Duration = CnbOperationalHealth.DEFAULT_RECENT_FAILURE_WINDOW): Boolean {
        require(!window.isNegative) { "Failure window must not be negative" }
        val threshold = generatedAt.minus(window)
        return entries.any { entry ->
            val failure = entry.lastFailureAt ?: return@any false
            val success = entry.lastSuccessAt
            !failure.isBefore(threshold) && (success == null || failure.isAfter(success))
        }
    }
}

/**
 * Thread-safe, in-memory operational signal registry.
 *
 * Values are sanitized before they enter the map, snapshots never expose mutable state, and the access-order map
 * prevents a broken or malicious integration from growing controller memory without bound.
 */
class CnbOperationalHealth
    @JvmOverloads
    constructor(
        private val clock: Clock = Clock.systemUTC(),
        private val maxScopes: Int = DEFAULT_MAX_SCOPES,
    ) {
        private val lock = ReentrantLock()
        private val states = LinkedHashMap<CnbHealthScope, MutableCnbHealthState>(16, 0.75f, true)

        init {
            require(maxScopes in 1..DEFAULT_MAX_SCOPES) {
                "maxScopes must be between 1 and $DEFAULT_MAX_SCOPES"
            }
        }

        @JvmOverloads
        fun recordWebhook(
            serverId: String?,
            repository: String?,
            successful: Boolean,
            summary: String? = null,
        ) = record(CnbHealthComponent.WEBHOOK, serverId, repository, successful, summary)

        @JvmOverloads
        fun recordPolling(
            serverId: String?,
            repository: String?,
            successful: Boolean,
            summary: String? = null,
        ) = record(CnbHealthComponent.POLLING, serverId, repository, successful, summary)

        @JvmOverloads
        fun recordReporting(
            serverId: String?,
            repository: String?,
            successful: Boolean,
            summary: String? = null,
        ) = record(CnbHealthComponent.REPORTING, serverId, repository, successful, summary)

        @SuppressFBWarnings(
            value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
            justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
        )
        fun snapshot(): CnbOperationalHealthSnapshot =
            lock.withLock {
                val now = clock.instant()
                val entries =
                    states
                        .map { (scope, state) -> state.snapshot(scope) }
                        .sortedWith(
                            compareBy<CnbOperationalHealthEntry>(
                                CnbOperationalHealthEntry::serverId,
                                CnbOperationalHealthEntry::repository,
                                { it.component.displayName },
                            ),
                        )
                CnbOperationalHealthSnapshot(
                    generatedAt = now,
                    entries = Collections.unmodifiableList(ArrayList(entries)),
                )
            }

        internal fun clear() {
            lock.withLock { states.clear() }
        }

        private fun record(
            component: CnbHealthComponent,
            serverId: String?,
            repository: String?,
            successful: Boolean,
            summary: String?,
        ) {
            val scope =
                CnbHealthScope(
                    serverId = sanitize(serverId, MAX_SERVER_ID_LENGTH, UNKNOWN_SCOPE),
                    repository = sanitize(repository, MAX_REPOSITORY_LENGTH, UNKNOWN_SCOPE),
                    component = component,
                )
            val safeSummary = sanitize(summary, MAX_SUMMARY_LENGTH, NO_DETAILS)
            val observedAt = clock.instant()

            lock.withLock {
                val state = states[scope] ?: MutableCnbHealthState().also { states[scope] = it }
                if (successful) {
                    state.lastSuccessAt = latest(state.lastSuccessAt, observedAt)
                } else {
                    state.lastFailureAt = latest(state.lastFailureAt, observedAt)
                }
                val lastObservedAt = state.lastObservedAt
                if (lastObservedAt == null || !observedAt.isBefore(lastObservedAt)) {
                    state.lastObservedAt = observedAt
                    state.summary = safeSummary
                }
                evictIfRequired()
            }
        }

        private fun latest(
            previous: Instant?,
            observed: Instant,
        ): Instant = if (previous == null || observed.isAfter(previous)) observed else previous

        private fun evictIfRequired() {
            while (states.size > maxScopes) {
                val iterator = states.entries.iterator()
                if (!iterator.hasNext()) return
                iterator.next()
                iterator.remove()
            }
        }

        private data class CnbHealthScope(
            val serverId: String,
            val repository: String,
            val component: CnbHealthComponent,
        )

        private class MutableCnbHealthState {
            var lastSuccessAt: Instant? = null
            var lastFailureAt: Instant? = null
            var lastObservedAt: Instant? = null
            var summary: String = NO_DETAILS

            fun snapshot(scope: CnbHealthScope): CnbOperationalHealthEntry =
                CnbOperationalHealthEntry(
                    serverId = scope.serverId,
                    repository = scope.repository,
                    component = scope.component,
                    lastSuccessAt = lastSuccessAt,
                    lastFailureAt = lastFailureAt,
                    summary = summary,
                )
        }

        companion object {
            const val DEFAULT_MAX_SCOPES = 500
            const val MAX_SERVER_ID_LENGTH = 128
            const val MAX_REPOSITORY_LENGTH = 512
            const val MAX_SUMMARY_LENGTH = 512
            const val UNKNOWN_SCOPE = "<unknown>"
            const val NO_DETAILS = "No details recorded"
            val DEFAULT_RECENT_FAILURE_WINDOW: Duration = Duration.ofHours(24)

            private const val MAX_INPUT_LENGTH = 8_192
            private const val REDACTED = "[REDACTED]"
            private val GLOBAL = CnbOperationalHealth()
            private val WHITESPACE = Regex("\\s+")
            private val URL_USER_INFO = Regex("(?i)\\b(https?://)[^/\\s@]+@")
            private val URL_WITH_QUERY = Regex("""(?i)\b(https?://[^\s?#"'<>]+)(?:\?[^\s#"'<>]*)?(?:#[^\s"'<>]*)?""")
            private val BEARER_CREDENTIAL = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
            private val SENSITIVE_FIELD =
                Regex(
                    """(?is)(["']?(?:access[_-]?token|api[_-]?token|token|secret|password|passwd|authorization|cookie)["']?\s*[:=]\s*)(?:"(?:\\.|[^"])*"|'(?:\\.|[^'])*'|[^\s,;&]+)""",
                )
            private val BODY_FIELD =
                Regex(
                    """(?is)(["']?(?:request[_-]?body|response[_-]?body|body|payload)["']?\s*[:=]\s*)(?:"(?:\\.|[^"])*"|'(?:\\.|[^'])*'|.+)""",
                )
            private val OPAQUE_CREDENTIAL = Regex("""(?<![A-Za-z0-9_-])[A-Za-z0-9_-]{20,}(?![A-Za-z0-9_-])""")

            @JvmStatic
            fun get(): CnbOperationalHealth = GLOBAL

            @SuppressFBWarnings(
                value = ["BC_BAD_CAST_TO_ABSTRACT_COLLECTION"],
                justification = "Kotlin's inline collection mapping allocates a concrete ArrayList that SpotBugs loses in SMAP bytecode.",
            )
            private fun sanitize(
                value: String?,
                maxLength: Int,
                fallback: String,
            ): String {
                var sanitized =
                    value
                        .orEmpty()
                        .take(MAX_INPUT_LENGTH)
                        .map { character -> if (character.isISOControl()) ' ' else character }
                        .joinToString("")
                sanitized = URL_USER_INFO.replace(sanitized) { match -> "${match.groupValues[1]}$REDACTED@" }
                sanitized = URL_WITH_QUERY.replace(sanitized) { match -> match.groupValues[1] }
                sanitized = BEARER_CREDENTIAL.replace(sanitized, "Bearer $REDACTED")
                sanitized = SENSITIVE_FIELD.replace(sanitized) { match -> "${match.groupValues[1]}$REDACTED" }
                sanitized = BODY_FIELD.replace(sanitized) { match -> "${match.groupValues[1]}$REDACTED" }
                sanitized = OPAQUE_CREDENTIAL.replace(sanitized, REDACTED)
                sanitized =
                    WHITESPACE
                        .replace(sanitized, " ")
                        .trim()
                        .take(maxLength)
                        .trim()
                return sanitized.ifBlank { fallback }
            }
        }
    }
