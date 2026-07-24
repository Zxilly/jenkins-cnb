package dev.zxilly.jenkins.cnb.trigger

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClientFactory
import dev.zxilly.jenkins.cnb.api.model.CnbCommit
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryEvent
import dev.zxilly.jenkins.cnb.api.model.CnbRepositoryRefType
import dev.zxilly.jenkins.cnb.config.CnbGlobalConfiguration
import dev.zxilly.jenkins.cnb.health.CnbOperationalHealth
import dev.zxilly.jenkins.cnb.scm.CnbSCMSource
import dev.zxilly.jenkins.cnb.webhook.CnbWebhookEvent
import hudson.Extension
import hudson.model.Action
import hudson.model.AsyncPeriodicWork
import hudson.model.Cause
import hudson.model.CauseAction
import hudson.model.Item
import hudson.model.Job
import hudson.model.Queue
import hudson.model.TaskListener
import jenkins.model.Jenkins
import jenkins.model.ParameterizedJobMixIn
import jenkins.scm.api.SCMSourceOwner
import jenkins.scm.api.SCMSourceOwners
import java.io.Closeable
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Loss-recovery polling for CNB's repository event archive.
 *
 * Completed UTC hours are checkpointed independently for every server, repository, and Jenkins
 * authorization context. Current-hour events are deliberately re-read until the hour closes, with
 * per-consumer persistent event-ID deduplication preventing duplicate refreshes and builds.
 */
@Extension
class CnbRepositoryEventPollingWork
    @JvmOverloads
    constructor(
        private val health: CnbOperationalHealth = CnbOperationalHealth.get(),
    ) : AsyncPeriodicWork("CNB repository event polling") {
        private val nextDue = ConcurrentHashMap<String, Instant>()
        private val pollCycle = CnbRepositoryEventPollCycle(nextDue)
        private val dedupStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            CnbRepositoryEventDedupStore(dedupStorePath())
        }
        private val cursorStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            CnbRepositoryEventCursorStore(cursorStorePath())
        }

        override fun getRecurrencePeriod(): Long = POLL_TICK.toMillis()

        override fun getInitialDelay(): Long = POLL_TICK.toMillis()

        override fun execute(listener: TaskListener) = runOnce(listener)

        internal fun runOnce(listener: TaskListener) {
            val now = Clock.systemUTC().instant()
            val enabledServers = ArrayList<CnbRepositoryEventPollingServer>()
            for (server in CnbGlobalConfiguration.get().getServers()) {
                if (!server.eventPollingEnabled) continue
                enabledServers += CnbRepositoryEventPollingServer(server.id, server.eventPollingIntervalSeconds)
            }
            pollCycle.run(
                now = now,
                servers = enabledServers,
                discover = ::watchedRepositories,
            ) { repository ->
                if (Thread.interrupted()) throw InterruptedException()
                try {
                    val handled = poll(repository, now, cursorStore, dedupStore, listener)
                    health.recordPolling(
                        repository.serverId,
                        repository.healthRepository(),
                        successful = true,
                        summary = "processed=$handled",
                    )
                } catch (failure: IOException) {
                    recordPollingFailure(repository, failure)
                    reportFailure(repository, failure, listener)
                } catch (failure: RuntimeException) {
                    recordPollingFailure(repository, failure)
                    reportFailure(repository, failure, listener)
                }
            }
        }

        private fun poll(
            repository: CnbWatchedRepository,
            now: Instant,
            cursorStore: CnbRepositoryEventCursorStore,
            dedupStore: CnbRepositoryEventDedupStore,
            listener: TaskListener,
        ): Int {
            val currentHour = now.truncatedTo(ChronoUnit.HOURS)
            val latestCompletedHour = currentHour.minus(HOUR)
            val cursor = cursorStore.get(repository.cursorScope(), latestCompletedHour)
            val plan = repositoryEventPollPlan(cursor, now)
            var handledTotal = 0

            CnbRepositoryEventClientSelector(
                serverId = repository.serverId,
                candidates = repository.credentialCandidates,
                openSession = ::openSession,
            ).use { client ->
                for (hour in plan.completedHours) {
                    val handled = pollHour(repository, hour, now, client::listRepositoryEvents, dedupStore)
                    cursorStore.advance(repository.cursorScope(), hour)
                    logHandled(repository, hour, handled, listener)
                    handledTotal += handled
                }

                plan.currentHour?.let { hour ->
                    val handled = pollHour(repository, hour, now, client::listRepositoryEvents, dedupStore)
                    logHandled(repository, hour, handled, listener)
                    handledTotal += handled
                }
            }
            return handledTotal
        }

        private fun pollHour(
            repository: CnbWatchedRepository,
            hour: Instant,
            now: Instant,
            fetch: (String, ZonedDateTime) -> List<CnbRepositoryEvent>,
            dedupStore: CnbRepositoryEventDedupStore,
        ): Int {
            val events = fetch(repository.repositoryPath, ZonedDateTime.ofInstant(hour, ZoneOffset.UTC))
            return CnbRepositoryEventDispatcher.dispatch(repository, events, now, dedupStore)
        }

        private fun watchedRepositories(dueServerIds: Set<String>): List<CnbWatchedRepository> {
            val watches = ArrayList<CnbRepositoryEventWatch>()
            val commitCache = HashMap<Triple<String, String, String>, CnbCommit?>()
            val cloneUrlCache = HashMap<Pair<String, String>, String?>()
            for (owner in SCMSourceOwners.all()) {
                for (source in owner.scmSources) {
                    val cnb = source as? CnbSCMSource ?: continue
                    if (cnb.serverId !in dueServerIds) continue
                    val sourceId = cnb.id.takeIf { it.isNotBlank() } ?: cnb.repositoryPath
                    val origin = "source:${owner.fullName}"
                    val candidates =
                        listOf(
                            CnbRepositoryEventCredentialCandidate.item(
                                cnb.getApiCredentialsId(),
                                owner,
                                origin,
                            ),
                        )
                    watches +=
                        CnbRepositoryEventWatch(
                            serverId = cnb.serverId,
                            repositoryPath = cnb.repositoryPath,
                            consumer =
                                CnbRepositoryEventConsumer(
                                    stableKey = "0\u0000${owner.fullName}\u0000$sourceId",
                                    description = "SCM source ${owner.fullName}/$sourceId",
                                ) {
                                    CnbSourceRefresher.refresh(owner, cnb)
                                },
                            credentialCandidates = candidates,
                            authorizationScope =
                                itemAuthorizationScope(
                                    origin,
                                    cnb.getApiCredentialsId(),
                                ),
                        )
                }
            }
            for (candidate in Jenkins.get().getAllItems(Job::class.java)) {
                val trigger =
                    try {
                        ParameterizedJobMixIn.getTrigger(candidate, CnbPushTrigger::class.java)
                    } catch (failure: RuntimeException) {
                        LOGGER.log(Level.WARNING, "Could not inspect CNB trigger on ${candidate.fullName}", failure)
                        null
                    } ?: continue
                val serverId = trigger.serverId.trim()
                val repositoryPath = trigger.repositoryPath.trim().trim('/')
                if (serverId !in dueServerIds || repositoryPath.isEmpty()) continue
                watches +=
                    CnbRepositoryEventWatch(
                        serverId = serverId,
                        repositoryPath = repositoryPath,
                        consumer =
                            CnbRepositoryEventConsumer(
                                stableKey = classicJobEventConsumerScope(candidate),
                                description = "job ${candidate.fullName}",
                            ) { events ->
                                CnbPushTriggerRecovery.recover(
                                    serverId,
                                    repositoryPath,
                                    events,
                                    candidate,
                                    trigger,
                                    getCommit = { repository, commit ->
                                        val key = Triple(serverId, repository, commit.lowercase(Locale.ROOT))
                                        if (commitCache.containsKey(key)) {
                                            commitCache[key]
                                        } else {
                                            CnbPushTriggerRecovery
                                                .verifiedCommit(serverId, repository, commit)
                                                .also { commitCache[key] = it }
                                        }
                                    },
                                    loadSourceCloneUrl = {
                                        val key = serverId to repositoryPath
                                        if (cloneUrlCache.containsKey(key)) {
                                            cloneUrlCache[key]
                                        } else {
                                            CnbPushTriggerRecovery
                                                .verifiedCloneUrl(serverId, repositoryPath)
                                                .also { cloneUrlCache[key] = it }
                                        }
                                    },
                                )
                            },
                        credentialCandidates = listOf(CnbRepositoryEventCredentialCandidate.server()),
                        authorizationScope = SERVER_AUTHORIZATION_SCOPE,
                    )
            }
            return aggregateWatchedRepositories(watches)
        }

        private fun openSession(
            serverId: String,
            candidate: CnbRepositoryEventCredentialCandidate,
        ): CnbRepositoryEventSession {
            val client = CnbClientFactory.create(serverId, candidate.credentialsId, candidate.context)
            return object : CnbRepositoryEventSession {
                override fun listRepositoryEvents(
                    repositoryPath: String,
                    hour: ZonedDateTime,
                ): List<CnbRepositoryEvent> = client.listRepositoryEvents(repositoryPath, hour)

                override fun close() = client.close()
            }
        }

        private fun reportFailure(
            repository: CnbWatchedRepository,
            failure: Exception,
            listener: TaskListener,
        ) {
            listener.error(
                "CNB event polling failed for %s/%s: %s",
                repository.serverId,
                repository.repositoryPath,
                failure.javaClass.simpleName,
            )
            LOGGER.log(Level.FINE, "CNB repository event polling failure", failure)
        }

        private fun recordPollingFailure(
            repository: CnbWatchedRepository,
            failure: Exception,
        ) {
            health.recordPolling(
                repository.serverId,
                repository.healthRepository(),
                successful = false,
                summary = "class=${failure.javaClass.simpleName.replace(HEALTH_CLASS_BOUNDARY, ".")}",
            )
        }

        private fun logHandled(
            repository: CnbWatchedRepository,
            hour: Instant,
            handled: Int,
            listener: TaskListener,
        ) {
            if (handled == 0) return
            listener.logger.printf(
                "CNB event fallback handled %d event(s) for %s/%s from %s%n",
                handled,
                repository.serverId,
                repository.repositoryPath,
                hour,
            )
        }

        private fun dedupStorePath(): Path =
            Jenkins
                .get()
                .rootDir
                .toPath()
                .resolve("cnb")
                .resolve("repository-event-dedup.properties")

        private fun cursorStorePath(): Path =
            Jenkins
                .get()
                .rootDir
                .toPath()
                .resolve("cnb")
                .resolve("repository-event-cursors.properties")

        companion object {
            private val POLL_TICK = Duration.ofMinutes(1)
            private val HOUR = Duration.ofHours(1)
            private val HEALTH_CLASS_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
            private val LOGGER = Logger.getLogger(CnbRepositoryEventPollingWork::class.java.name)
            internal const val MAX_COMPLETED_HOURS_PER_RUN = 24

            internal fun eventKey(
                serverId: String,
                repositoryPath: String,
                event: CnbRepositoryEvent,
            ): String {
                val eventIdentity =
                    event.id.ifBlank {
                        listOf(event.type.wireValue, event.createdAt, event.payload.toString()).joinToString("\u0000")
                    }
                val input = "$serverId\u0000$repositoryPath\u0000$eventIdentity"
                return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8)),
                )
            }
        }
    }

internal data class CnbRepositoryEventPollingServer(
    val id: String,
    val intervalSeconds: Int,
)

internal const val SERVER_AUTHORIZATION_SCOPE = "server"

internal data class CnbWatchedRepository(
    val serverId: String,
    val repositoryPath: String,
    val consumers: List<CnbRepositoryEventConsumer> = emptyList(),
    val credentialCandidates: List<CnbRepositoryEventCredentialCandidate> =
        listOf(CnbRepositoryEventCredentialCandidate.server()),
    val authorizationScope: String = SERVER_AUTHORIZATION_SCOPE,
) {
    fun healthRepository(): String = "$repositoryPath [authorization context ${authorizationScopeHash()}]"

    fun cursorScope(): CnbRepositoryEventStateScope =
        CnbRepositoryEventStateScope(
            serverId,
            repositoryPath,
            authorizationScope,
            CURSOR_CONSUMER_SCOPE,
        )

    fun stateScope(consumer: CnbRepositoryEventConsumer): CnbRepositoryEventStateScope =
        CnbRepositoryEventStateScope(
            serverId,
            repositoryPath,
            authorizationScope,
            consumer.stableKey,
        )

    private fun authorizationScopeHash(): String =
        HexFormat
            .of()
            .formatHex(
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(authorizationScope.toByteArray(StandardCharsets.UTF_8)),
            ).take(8)

    private companion object {
        const val CURSOR_CONSUMER_SCOPE = "@cursor"
    }
}

internal data class CnbRepositoryEventConsumer(
    val stableKey: String,
    val description: String,
    val dispatch: (List<CnbRepositoryEvent>) -> Unit,
)

internal data class CnbRepositoryEventCredentialCandidate(
    val credentialsId: String?,
    val context: Item?,
    val origin: String,
    val itemScoped: Boolean,
) {
    companion object {
        fun item(
            credentialsId: String?,
            context: Item,
            origin: String,
        ): CnbRepositoryEventCredentialCandidate = CnbRepositoryEventCredentialCandidate(credentialsId, context, origin, itemScoped = true)

        fun server(): CnbRepositoryEventCredentialCandidate =
            CnbRepositoryEventCredentialCandidate(null, null, "server", itemScoped = false)
    }
}

internal data class CnbRepositoryEventWatch(
    val serverId: String,
    val repositoryPath: String,
    val consumer: CnbRepositoryEventConsumer,
    val credentialCandidates: List<CnbRepositoryEventCredentialCandidate>,
    val authorizationScope: String = SERVER_AUTHORIZATION_SCOPE,
)

internal fun aggregateWatchedRepositories(watches: Collection<CnbRepositoryEventWatch>): List<CnbWatchedRepository> {
    val grouped = LinkedHashMap<Triple<String, String, String>, MutableList<CnbRepositoryEventWatch>>()
    for (watch in watches) {
        val key = Triple(watch.serverId, watch.repositoryPath, watch.authorizationScope)
        grouped.getOrPut(key) { ArrayList() }.add(watch)
    }

    val keys = ArrayList(grouped.keys)
    keys.sortWith(
        compareBy<Triple<String, String, String>> { it.first }
            .thenBy { it.second }
            .thenBy { it.third },
    )

    val repositories = ArrayList<CnbWatchedRepository>(keys.size)
    for (key in keys) {
        val repositoryWatches = requireNotNull(grouped[key])
        val consumersByKey = LinkedHashMap<String, CnbRepositoryEventConsumer>()
        val credentialCandidates = ArrayList<CnbRepositoryEventCredentialCandidate>()
        for (watch in repositoryWatches) {
            consumersByKey.putIfAbsent(watch.consumer.stableKey, watch.consumer)
            credentialCandidates.addAll(watch.credentialCandidates)
        }

        val consumers = ArrayList(consumersByKey.values)
        consumers.sortWith(compareBy { it.stableKey })
        val authorizedCandidates =
            stableCredentialCandidates(credentialCandidates).ifEmpty {
                if (key.third == SERVER_AUTHORIZATION_SCOPE) {
                    listOf(CnbRepositoryEventCredentialCandidate.server())
                } else {
                    emptyList()
                }
            }
        require(authorizedCandidates.size == 1) {
            "CNB repository event authorization scope must resolve exactly one credential context"
        }
        repositories +=
            CnbWatchedRepository(
                serverId = key.first,
                repositoryPath = key.second,
                consumers = consumers,
                credentialCandidates = authorizedCandidates,
                authorizationScope = key.third,
            )
    }
    return repositories
}

internal fun itemAuthorizationScope(
    origin: String,
    credentialsId: String?,
): String = "item\u0000$origin\u0000${credentialsId.orEmpty()}"

internal fun stableCredentialCandidates(
    candidates: Collection<CnbRepositoryEventCredentialCandidate>,
): List<CnbRepositoryEventCredentialCandidate> {
    val itemCandidatesByKey = LinkedHashMap<Pair<String, String?>, CnbRepositoryEventCredentialCandidate>()
    var serverCandidate: CnbRepositoryEventCredentialCandidate? = null
    for (candidate in candidates) {
        if (candidate.itemScoped) {
            itemCandidatesByKey.putIfAbsent(candidate.origin to candidate.credentialsId, candidate)
        } else if (serverCandidate == null || candidate.origin < requireNotNull(serverCandidate).origin) {
            serverCandidate = candidate
        }
    }

    val result = ArrayList<CnbRepositoryEventCredentialCandidate>(itemCandidatesByKey.size + 1)
    result.addAll(itemCandidatesByKey.values)
    result.sortWith(
        compareBy<CnbRepositoryEventCredentialCandidate> { it.credentialsId == null }
            .thenBy { it.origin }
            .thenBy { it.credentialsId.orEmpty() },
    )
    serverCandidate?.let(result::add)
    return result
}

internal interface CnbRepositoryEventSession : Closeable {
    fun listRepositoryEvents(
        repositoryPath: String,
        hour: ZonedDateTime,
    ): List<CnbRepositoryEvent>
}

/** Keeps one successful credential session sticky while allowing only authentication-scope fallback. */
internal class CnbRepositoryEventClientSelector(
    private val serverId: String,
    candidates: Collection<CnbRepositoryEventCredentialCandidate>,
    private val openSession: (String, CnbRepositoryEventCredentialCandidate) -> CnbRepositoryEventSession,
) : Closeable {
    private val candidates = stableCredentialCandidates(candidates)
    private var nextCandidate = 0
    private var active: Pair<CnbRepositoryEventCredentialCandidate, CnbRepositoryEventSession>? = null
    private var closed = false

    init {
        require(this.candidates.size == 1) {
            "CNB repository event session must use exactly one Jenkins authorization context"
        }
    }

    fun listRepositoryEvents(
        repositoryPath: String,
        hour: ZonedDateTime,
    ): List<CnbRepositoryEvent> {
        check(!closed) { "CNB repository-event client selector is closed" }
        while (true) {
            val selected = active ?: openNextSession()
            try {
                return selected.second.listRepositoryEvents(repositoryPath, hour)
            } catch (failure: CnbApiException) {
                if (!canFallback(selected.first) || failure.statusCode !in CREDENTIAL_FALLBACK_STATUSES) throw failure
                rejectActive(failure)
                LOGGER.log(
                    Level.WARNING,
                    "CNB event credential from {0} was rejected with HTTP {1}; trying the next scoped candidate",
                    arrayOf<Any>(selected.first.origin, failure.statusCode),
                )
                LOGGER.log(Level.FINE, "CNB event credential rejection", failure)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        val session = active?.second
        active = null
        session?.close()
    }

    private fun openNextSession(): Pair<CnbRepositoryEventCredentialCandidate, CnbRepositoryEventSession> {
        while (true) {
            val candidate = candidates.getOrNull(nextCandidate++) ?: error("CNB event credential candidates were exhausted")
            try {
                return (candidate to openSession(serverId, candidate)).also { active = it }
            } catch (failure: IllegalArgumentException) {
                if (!canFallback(candidate)) throw failure
                LOGGER.log(
                    Level.WARNING,
                    "CNB event credential from {0} is unavailable; trying the next scoped candidate",
                    candidate.origin,
                )
                LOGGER.log(Level.FINE, "CNB event credential lookup failure", failure)
            }
        }
    }

    private fun canFallback(candidate: CnbRepositoryEventCredentialCandidate): Boolean =
        candidate.itemScoped && nextCandidate < candidates.size

    private fun rejectActive(failure: Exception) {
        val session = active?.second
        active = null
        try {
            session?.close()
        } catch (closeFailure: Exception) {
            failure.addSuppressed(closeFailure)
        }
    }

    companion object {
        private val CREDENTIAL_FALLBACK_STATUSES = setOf(401, 403, 404)
        private val LOGGER = Logger.getLogger(CnbRepositoryEventClientSelector::class.java.name)
    }
}

/** Selects due servers before the expensive Jenkins item enumeration and reuses one discovery snapshot. */
internal class CnbRepositoryEventPollCycle(
    private val nextDue: MutableMap<String, Instant>,
) {
    fun run(
        now: Instant,
        servers: Collection<CnbRepositoryEventPollingServer>,
        discover: (Set<String>) -> List<CnbWatchedRepository>,
        poll: (CnbWatchedRepository) -> Unit,
    ) {
        val seenServerIds = HashSet<String>()
        val dueServers = ArrayList<CnbRepositoryEventPollingServer>()
        for (server in servers) {
            if (!seenServerIds.add(server.id)) continue
            if (nextDue[server.id]?.let(now::isBefore) == true) continue
            dueServers += server
        }
        if (dueServers.isEmpty()) return
        dueServers.sortWith(compareBy { it.id })

        val dueServerIds = LinkedHashSet<String>(dueServers.size)
        for (server in dueServers) {
            nextDue[server.id] = now.plusSeconds(server.intervalSeconds.toLong())
            dueServerIds += server.id
        }

        val repositories = ArrayList<CnbWatchedRepository>()
        for (repository in discover(dueServerIds)) {
            if (repository.serverId in dueServerIds) repositories += repository
        }
        repositories.sortWith(
            compareBy<CnbWatchedRepository> { it.serverId }
                .thenBy { it.repositoryPath }
                .thenBy { it.authorizationScope },
        )
        for (repository in repositories) poll(repository)
    }
}

internal data class CnbRepositoryEventPollPlan(
    val completedHours: List<Instant>,
    val currentHour: Instant?,
)

internal fun repositoryEventPollPlan(
    cursor: Instant?,
    now: Instant,
    maxCompletedHours: Int = CnbRepositoryEventPollingWork.MAX_COMPLETED_HOURS_PER_RUN,
): CnbRepositoryEventPollPlan {
    require(maxCompletedHours > 0) { "maxCompletedHours must be positive" }
    val currentHour = now.truncatedTo(ChronoUnit.HOURS)
    val latestCompletedHour = currentHour.minus(1, ChronoUnit.HOURS)
    val safeCursor = cursor?.takeUnless { it.isAfter(latestCompletedHour) }
    var nextHour = safeCursor?.plus(1, ChronoUnit.HOURS) ?: latestCompletedHour
    val completed = mutableListOf<Instant>()
    while (!nextHour.isAfter(latestCompletedHour) && completed.size < maxCompletedHours) {
        completed += nextHour
        nextHour = nextHour.plus(1, ChronoUnit.HOURS)
    }
    val latestPlanned = completed.lastOrNull() ?: safeCursor
    return CnbRepositoryEventPollPlan(
        completedHours = completed,
        currentHour = currentHour.takeIf { latestPlanned == latestCompletedHour },
    )
}

internal object CnbRepositoryEventClassifier {
    enum class RefKind {
        BRANCH,
        TAG,
    }

    data class RefTransition(
        val event: CnbRepositoryEvent,
        val ref: String,
        val kind: RefKind,
        val commit: String?,
        val lifecycle: CnbRefLifecycleTransition,
    )

    fun isRelevant(event: CnbRepositoryEvent): Boolean =
        when (event.type.wireValue.lowercase(Locale.ROOT)) {
            "pushevent", "pullrequestevent" -> {
                true
            }

            "createevent", "deleteevent" -> {
                event.payload.refType in CODE_REF_TYPES
            }

            else -> {
                false
            }
        }

    fun pushRef(event: CnbRepositoryEvent): String? {
        if (!event.type.wireValue.equals("PushEvent", ignoreCase = true)) return null
        val raw = event.payload.ref.trim()
        val normalized =
            when {
                raw.startsWith("refs/heads/") -> raw.removePrefix("refs/heads/")
                raw.startsWith("refs/tags/") -> raw.removePrefix("refs/tags/")
                else -> raw
            }.trim()
        return normalized.takeIf {
            it.isNotEmpty() && it.length <= 1024 && it.none { character -> character.code < 0x20 || character.code == 0x7f }
        }
    }

    fun pushRefKind(event: CnbRepositoryEvent): RefKind? {
        if (!event.type.wireValue.equals("PushEvent", ignoreCase = true)) return null
        val raw = event.payload.ref.trim()
        return when {
            raw.startsWith("refs/tags/") -> RefKind.TAG
            raw.startsWith("refs/heads/") -> RefKind.BRANCH
            event.payload.refType == CnbRepositoryRefType.TAG -> RefKind.TAG
            event.payload.refType == null || event.payload.refType == CnbRepositoryRefType.BRANCH -> RefKind.BRANCH
            else -> null
        }
    }

    fun pushCommit(event: CnbRepositoryEvent): String? {
        if (!event.type.wireValue.equals("PushEvent", ignoreCase = true)) return null
        val head = event.payload.head.trim()
        return head.takeIf { value ->
            value.length in GIT_OBJECT_ID_LENGTHS &&
                value.any { it != '0' } &&
                value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
        }
    }

    fun isPushDeletion(event: CnbRepositoryEvent): Boolean {
        if (!event.type.wireValue.equals("PushEvent", ignoreCase = true)) return false
        val head = event.payload.head.trim()
        return head.length in GIT_OBJECT_ID_LENGTHS && head.all { it == '0' }
    }

    fun refTransition(
        serverId: String,
        repositoryPath: String,
        event: CnbRepositoryEvent,
    ): RefTransition? {
        val type = event.type.wireValue.lowercase(Locale.ROOT)
        val ref: String
        val kind: RefKind
        val commit: String?
        val present: Boolean
        when (type) {
            "pushevent" -> {
                ref = pushRef(event) ?: return null
                kind = pushRefKind(event) ?: return null
                commit = pushCommit(event)
                present = commit != null
                if (!present && !isPushDeletion(event)) return null
            }

            "deleteevent" -> {
                ref = normalizedRef(event.payload.ref) ?: return null
                kind =
                    when (event.payload.refType) {
                        CnbRepositoryRefType.BRANCH -> RefKind.BRANCH
                        CnbRepositoryRefType.TAG -> RefKind.TAG
                        else -> return null
                    }
                val raw = event.payload.ref.trim()
                if (raw.startsWith("refs/heads/") && kind != RefKind.BRANCH) return null
                if (raw.startsWith("refs/tags/") && kind != RefKind.TAG) return null
                commit = null
                present = false
            }

            else -> {
                return null
            }
        }
        val occurredAt = parseCreatedAt(event.createdAt) ?: return null
        val qualifiedRef = if (kind == RefKind.TAG) "refs/tags/$ref" else "refs/heads/$ref"
        return RefTransition(
            event,
            ref,
            kind,
            commit,
            CnbRefLifecycleTransition(
                qualifiedRef,
                present,
                occurredAt,
                stableLifecycleEventId(
                    event.id,
                    CnbRepositoryEventPollingWork.eventKey(serverId, repositoryPath, event),
                ),
            ),
        )
    }

    private fun normalizedRef(rawValue: String): String? {
        val raw = rawValue.trim()
        val normalized =
            when {
                raw.startsWith("refs/heads/") -> raw.removePrefix("refs/heads/")
                raw.startsWith("refs/tags/") -> raw.removePrefix("refs/tags/")
                else -> raw
            }.trim()
        return normalized.takeIf {
            it.isNotEmpty() && it.length <= 1024 && it.none { character -> character.code < 0x20 || character.code == 0x7f }
        }
    }

    private fun parseCreatedAt(value: String): Instant? =
        runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .getOrNull()

    private val CODE_REF_TYPES = setOf(CnbRepositoryRefType.BRANCH, CnbRepositoryRefType.TAG)
    private val GIT_OBJECT_ID_LENGTHS = setOf(40, 64)
}

internal object CnbRepositoryEventHourProcessor {
    fun process(
        serverId: String,
        repositoryPath: String,
        events: List<CnbRepositoryEvent>,
        now: Instant,
        store: CnbRepositoryEventDedupStore,
        dispatch: (List<CnbRepositoryEvent>) -> Unit,
    ): Int =
        process(
            serverId,
            repositoryPath,
            events,
            dispatch,
            unseen = { keys -> store.unseenKeys(serverId, repositoryPath, keys, now) },
            mark = { keys -> store.mark(serverId, repositoryPath, keys, now) },
        )

    fun process(
        scope: CnbRepositoryEventStateScope,
        events: List<CnbRepositoryEvent>,
        now: Instant,
        store: CnbRepositoryEventDedupStore,
        dispatch: (List<CnbRepositoryEvent>) -> Unit,
    ): Int =
        process(
            scope.serverId,
            scope.repositoryPath,
            events,
            dispatch,
            unseen = { keys -> store.unseenKeys(scope, keys, now) },
            mark = { keys -> store.mark(scope, keys, now) },
        )

    private fun process(
        serverId: String,
        repositoryPath: String,
        events: List<CnbRepositoryEvent>,
        dispatch: (List<CnbRepositoryEvent>) -> Unit,
        unseen: (Collection<String>) -> Set<String>,
        mark: (Collection<String>) -> Unit,
    ): Int {
        val eventsByKey = LinkedHashMap<String, CnbRepositoryEvent>()
        for (event in events) {
            if (event.repositoryPath.isNotBlank() && event.repositoryPath != repositoryPath) continue
            if (!CnbRepositoryEventClassifier.isRelevant(event)) continue
            val key = CnbRepositoryEventPollingWork.eventKey(serverId, repositoryPath, event)
            eventsByKey.putIfAbsent(key, event)
        }
        val unseenKeys = unseen(eventsByKey.keys)
        val unseenEvents = ArrayList<CnbRepositoryEvent>(unseenKeys.size)
        for (key in unseenKeys) {
            val event = eventsByKey[key] ?: continue
            unseenEvents += event
        }
        if (unseenEvents.isEmpty()) return 0

        try {
            dispatch(unseenEvents)
        } catch (failure: CnbPartialRepositoryEventDispatchException) {
            mark(unseenKeys - failure.retryKeys)
            throw failure
        }
        mark(unseenKeys)
        return unseenEvents.size
    }
}

internal class CnbPartialRepositoryEventDispatchException(
    val retryKeys: Set<String>,
    cause: RuntimeException,
) : RuntimeException("CNB repository event dispatch partially failed", cause)

/**
 * Dispatches and durably deduplicates a recovered batch independently for every authorized
 * consumer. Successful consumers are never replayed merely because a peer failed.
 */
internal object CnbRepositoryEventDispatcher {
    fun dispatch(
        repository: CnbWatchedRepository,
        events: List<CnbRepositoryEvent>,
        now: Instant,
        store: CnbRepositoryEventDedupStore,
    ): Int {
        var handled = 0
        var dispatchFailure: RuntimeException? = null
        for (consumer in repository.consumers) {
            try {
                handled +=
                    CnbRepositoryEventHourProcessor.process(
                        repository.stateScope(consumer),
                        events,
                        now,
                        store,
                        consumer.dispatch,
                    )
            } catch (failure: RuntimeException) {
                LOGGER.log(
                    Level.WARNING,
                    "CNB polling consumer {0} failed for {1}/{2}; its event scope will be retried",
                    arrayOf(consumer.description, repository.serverId, repository.repositoryPath),
                )
                LOGGER.log(Level.FINE, "CNB repository event consumer failure", failure)
                dispatchFailure =
                    combineFailure(
                        dispatchFailure,
                        "CNB repository event consumer ${consumer.description} failed",
                        failure,
                    )
            }
        }
        dispatchFailure?.let { throw it }
        return handled
    }

    private val LOGGER = Logger.getLogger(CnbRepositoryEventDispatcher::class.java.name)
}

internal object CnbSourceRefresher {
    @Suppress("DEPRECATION") // SCMSourceOwner exposes no non-deprecated exact-source notification replacement.
    fun refresh(
        owner: SCMSourceOwner,
        source: CnbSCMSource,
    ) = owner.onSCMSourceUpdated(source)
}

internal object CnbPushTriggerRecovery {
    fun recover(
        serverId: String,
        repositoryPath: String,
        events: List<CnbRepositoryEvent>,
        candidate: Job<*, *>,
        trigger: CnbPushTrigger,
        getCommit: (String, String) -> CnbCommit? = { repository, commit ->
            verifiedCommit(serverId, repository, commit)
        },
        loadSourceCloneUrl: () -> String? = { verifiedCloneUrl(serverId, repositoryPath) },
        lifecycleStore: CnbRefLifecycleStore = CnbRefLifecycleStores.current(),
    ) {
        val pushesByRef = linkedMapOf<String, MutableList<CnbRepositoryEventClassifier.RefTransition>>()
        events.forEach { event ->
            val transition = CnbRepositoryEventClassifier.refTransition(serverId, repositoryPath, event) ?: return@forEach
            pushesByRef.getOrPut(transition.lifecycle.qualifiedRef) { ArrayList() } += transition
        }
        if (pushesByRef.isEmpty()) return

        var schedulingFailure: RuntimeException? = null
        val retryKeys = linkedSetOf<String>()
        val lifecycleScope = classicJobRefLifecycleScope(serverId, repositoryPath, candidate)
        for ((_, unordered) in pushesByRef) {
            val recovered =
                unordered.sortedWith(
                    Comparator { left, right ->
                        val time = left.lifecycle.occurredAt.compareTo(right.lifecycle.occurredAt)
                        if (time != 0) {
                            time
                        } else {
                            compareLifecycleEventIds(left.lifecycle.stableEventId, right.lifecycle.stableEventId)
                        }
                    },
                )
            try {
                val scopedTransitions =
                    ArrayList<CnbScopedRefLifecycleTransition>(recovered.size)
                for (transition in recovered) {
                    scopedTransitions += CnbScopedRefLifecycleTransition(lifecycleScope, transition.lifecycle)
                }
                val lifecycleResults =
                    lifecycleStore.apply(scopedTransitions)
                val push = recovered.last()
                val lifecycle = lifecycleResults.last()
                val pushCommit = push.commit
                if (!lifecycle.current || !lifecycle.present || pushCommit == null || !candidate.isBuildable) continue
                val event = if (push.kind == CnbRepositoryEventClassifier.RefKind.TAG) CnbWebhookEvent.TAG_PUSH else CnbWebhookEvent.PUSH
                if (!CnbEventFilter.matches(trigger.getEventFilter(), event)) continue
                val matches =
                    try {
                        CnbRefGlob.matches(trigger.getRefFilter(), push.ref)
                    } catch (failure: IllegalArgumentException) {
                        LOGGER.log(Level.WARNING, "Invalid CNB branch filter on ${candidate.fullName}", failure)
                        false
                    }
                if (!matches) continue
                val identity =
                    CnbQueueIdentity(
                        serverId,
                        repositoryPath,
                        push.lifecycle.qualifiedRef,
                        pushCommit.lowercase(Locale.ROOT),
                        refGeneration = lifecycle.generation,
                    )
                if (trigger.isCiSkip()) {
                    val commit = getCommit(repositoryPath, identity.sha) ?: continue
                    if (!commit.sha.equals(identity.sha, ignoreCase = true) || CnbCiSkip.matches(commit.message)) continue
                }
                val requiresCheckout = CnbClassicGitRevisionAction.supports(candidate)
                val checkoutAction =
                    if (requiresCheckout) {
                        loadSourceCloneUrl()
                            ?.let { CnbClassicGitRevisionAction.create(candidate, identity.sha, it) }
                            ?: continue
                    } else {
                        null
                    }
                val historyQuery =
                    CnbDeliveryHistory.Query(
                        job = candidate,
                        incoming = identity,
                        deliveryId = null,
                        deliveryScope = null,
                        deduplicateRevision = true,
                    )
                CnbDeliveryHistory.withStableQueue(listOf(historyQuery)) { queue, histories ->
                    if (CnbDeliveryHistory.contains(queue, histories.single())) return@withStableQueue
                    val actions =
                        arrayListOf<Action>(
                            CauseAction(
                                CnbRepositoryEventCause(
                                    serverId,
                                    repositoryPath,
                                    push.event,
                                    push.ref,
                                    pushCommit,
                                    tag = push.kind == CnbRepositoryEventClassifier.RefKind.TAG,
                                ),
                            ),
                            CnbQueueAction(identity),
                        )
                    checkoutAction?.let(actions::add)
                    ParameterizedJobMixIn.scheduleBuild2(candidate, 0, *actions.toTypedArray())
                        ?: throw IllegalStateException("Jenkins refused the recovered CNB event build")
                    if (trigger.isCancelPendingBuildsOnUpdate()) {
                        val queueTask = candidate as? Queue.Task ?: return@withStableQueue
                        CnbPendingBuilds.cancelSuperseded(queue, queueTask, identity)
                    }
                }
            } catch (failure: RuntimeException) {
                for (attempt in recovered) {
                    retryKeys += CnbRepositoryEventPollingWork.eventKey(serverId, repositoryPath, attempt.event)
                }
                schedulingFailure =
                    combineFailure(
                        schedulingFailure,
                        "Could not schedule CNB event recovery for ${candidate.fullName}",
                        failure,
                    )
            }
        }
        schedulingFailure?.let { throw CnbPartialRepositoryEventDispatchException(retryKeys, it) }
    }

    internal fun verifiedCommit(
        serverId: String,
        repositoryPath: String,
        commit: String,
    ): CnbCommit? =
        try {
            CnbClientFactory.create(serverId).use { client -> client.getCommit(repositoryPath, commit) }
        } catch (failure: CnbApiException) {
            if (failure.statusCode !in MISSING_OR_UNAUTHORIZED_STATUS_CODES) throw failure
            null
        }

    internal fun verifiedCloneUrl(
        serverId: String,
        repositoryPath: String,
    ): String? {
        val server = CnbGlobalConfiguration.get().getServers().firstOrNull { it.id == serverId } ?: return null
        val repository =
            try {
                CnbClientFactory.create(serverId).use { client -> client.getRepository(repositoryPath) }
            } catch (failure: CnbApiException) {
                if (failure.statusCode !in MISSING_OR_UNAUTHORIZED_STATUS_CODES) throw failure
                return null
            }
        if (repository.path != repositoryPath || !repository.cloneable || repository.cloneUrl.isBlank()) return null
        return CnbOpenPullRequestTargetPushResolver.secureCloneUrl(repository.cloneUrl, repositoryPath, server.webUrl)
    }

    private val LOGGER = Logger.getLogger(CnbPushTriggerRecovery::class.java.name)
    private val MISSING_OR_UNAUTHORIZED_STATUS_CODES = setOf(401, 403, 404)
}

internal class CnbRepositoryEventCause(
    val serverId: String,
    val repositoryPath: String,
    event: CnbRepositoryEvent,
    val ref: String,
    val commit: String,
    val tag: Boolean = false,
) : Cause() {
    val eventId: String = safePersistedValue(event.id, MAX_EVENT_ID_LENGTH)
    val eventType: String = safePersistedValue(event.type.wireValue, MAX_EVENT_TYPE_LENGTH)
    val createdAt: String = safePersistedValue(event.createdAt, MAX_TIMESTAMP_LENGTH)

    override fun getShortDescription(): String = "Recovered CNB $eventType event for $repositoryPath at $ref"

    fun buildVariables(): Map<String, String> =
        linkedMapOf(
            "CNB_SERVER_ID" to serverId,
            "CNB_EVENT" to if (tag) "tag_push" else "push",
            "CNB_REPOSITORY" to repositoryPath,
            "CNB_REPO_SLUG" to repositoryPath,
            "CNB_BRANCH" to ref,
            "CNB_BRANCH_SHA" to commit,
            "CNB_COMMIT" to commit,
            "CNB_IS_TAG" to tag.toString(),
        ).mapValues { (_, value) -> safePersistedValue(value, MAX_ENVIRONMENT_VALUE_LENGTH) }

    companion object {
        private const val serialVersionUID = 1L
        private const val MAX_ENVIRONMENT_VALUE_LENGTH = 4 * 1024
        private const val MAX_EVENT_ID_LENGTH = 256
        private const val MAX_EVENT_TYPE_LENGTH = 64
        private const val MAX_TIMESTAMP_LENGTH = 128

        private fun safePersistedValue(
            value: String,
            maximumLength: Int,
        ): String =
            value
                .asSequence()
                .filter { character -> character.code >= 0x20 && character.code != 0x7f }
                .take(maximumLength)
                .joinToString("")
    }
}

private fun combineFailure(
    current: RuntimeException?,
    message: String,
    failure: RuntimeException,
): RuntimeException {
    val wrapped = IllegalStateException(message, failure)
    if (current == null) return wrapped
    current.addSuppressed(wrapped)
    return current
}
