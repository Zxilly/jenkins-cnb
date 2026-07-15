package dev.zxilly.jenkins.cnb.webhook

import dev.zxilly.jenkins.cnb.config.CnbServer
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.Locale

internal class CnbWebhookValidationException(
    message: String,
) : IllegalArgumentException(message)

internal object CnbWebhookValidator {
    private val serverIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
    private val deliveryPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")
    private val objectIdPattern = Regex("[0-9a-f]{40}")
    private val pullRequestNumberPattern = Regex("[1-9][0-9]{0,19}")

    fun validate(
        pathServerId: String,
        server: CnbServer,
        payload: CnbWebhookPayload,
        now: Instant,
    ) {
        requireValid(serverIdPattern.matches(pathServerId), "Invalid server ID")
        requireValid(payload.schema == CnbWebhookPayload.SCHEMA_V1, "Unsupported webhook schema")
        requireValid(payload.installationId == pathServerId && server.id == pathServerId, "Installation mismatch")
        requireValid(deliveryPattern.matches(payload.deliveryId), "Invalid delivery ID")
        requireSafeText(payload.buildId, MAX_IDENTIFIER_LENGTH, allowEmpty = true, label = "build_id")

        val oldest = now.minusSeconds(server.maxWebhookAgeSeconds.toLong())
        val newest = now.plusSeconds(MAX_FUTURE_SKEW_SECONDS)
        requireValid(!payload.occurredAt.isBefore(oldest), "Webhook delivery is stale")
        requireValid(!payload.occurredAt.isAfter(newest), "Webhook delivery timestamp is in the future")

        requireValid(
            canonicalEndpoint(payload.instance.webUrl) == canonicalEndpoint(server.webUrl),
            "CNB web endpoint mismatch",
        )
        requireValid(
            canonicalEndpoint(payload.instance.apiUrl) == canonicalEndpoint(server.apiUrl),
            "CNB API endpoint mismatch",
        )

        validateRepository(payload.repository, server.webUrl)
        validateActor(payload.actor)
        requireSafeUrl(payload.eventUrl, server.webUrl, allowEmpty = true, label = "event_url")
        validateRef(payload)
        validatePullRequest(payload)
    }

    private fun validateRepository(
        repository: CnbWebhookRepository,
        webUrl: String,
    ) {
        requireSafeText(repository.id, MAX_IDENTIFIER_LENGTH, label = "repository.id")
        validateRepositoryPath(repository.slug, "repository.slug")
        requireSafeUrl(repository.url, webUrl, allowEmpty = false, label = "repository.url")
        val path = parseUri(repository.url, "repository.url").path.trimEnd('/')
        requireValid(path == "/${repository.slug}", "Repository URL path does not match repository slug")
    }

    private fun validateActor(actor: CnbWebhookActor) {
        requireSafeText(actor.id, MAX_IDENTIFIER_LENGTH, allowEmpty = true, label = "actor.id")
        requireSafeText(actor.username, MAX_ACTOR_LENGTH, allowEmpty = true, label = "actor.username")
        requireSafeText(actor.nickname, MAX_ACTOR_LENGTH, allowEmpty = true, label = "actor.nickname")
        requireSafeText(actor.email, MAX_ACTOR_LENGTH, allowEmpty = true, label = "actor.email")
    }

    private fun validateRef(payload: CnbWebhookPayload) {
        validateGitRef(payload.ref.name, "ref.name")
        validateOptionalObjectId(payload.ref.sha, "ref.sha")
        validateOptionalObjectId(payload.ref.before, "ref.before")
        validateOptionalObjectId(payload.ref.commit, "ref.commit")

        when (payload.event) {
            CnbWebhookEvent.BRANCH_DELETE -> {
                // A deletion deliberately carries no surviving object ID.
            }

            CnbWebhookEvent.TAG_PUSH -> {
                requireValid(payload.ref.tag, "tag_push payload must identify a tag")
                requireValid(effectiveObjectId(payload.ref).isNotEmpty(), "tag_push payload is missing a commit")
            }

            else -> {
                requireValid(effectiveObjectId(payload.ref).isNotEmpty(), "Webhook payload is missing a commit")
            }
        }
    }

    private fun validatePullRequest(payload: CnbWebhookPayload) {
        if (!payload.event.trustedPullRequestEvent) {
            requireValid(payload.pullRequest == null, "Non-PR event must not include pull_request")
            return
        }
        val pullRequest = payload.pullRequest ?: throw CnbWebhookValidationException("Missing pull_request")
        requireSafeText(pullRequest.id, MAX_IDENTIFIER_LENGTH, label = "pull_request.id")
        requireValid(pullRequestNumberPattern.matches(pullRequest.number), "Invalid pull request number")
        requireSafeText(pullRequest.title, MAX_TITLE_LENGTH, allowEmpty = true, label = "pull_request.title")
        requireSafeText(
            pullRequest.description,
            MAX_DESCRIPTION_LENGTH,
            allowEmpty = true,
            allowLineBreaks = true,
            label = "pull_request.description",
        )
        requireSafeText(pullRequest.proposer, MAX_ACTOR_LENGTH, allowEmpty = true, label = "pull_request.proposer")
        validateRepositoryPath(pullRequest.sourceRepository, "pull_request.source_repo")
        validateGitRef(pullRequest.sourceBranch, "pull_request.source_branch")
        validateGitRef(pullRequest.targetBranch, "pull_request.target_branch")
        requireValid(objectIdPattern.matches(pullRequest.sourceSha), "Invalid pull request source SHA")
        requireValid(objectIdPattern.matches(pullRequest.targetSha), "Invalid pull request target SHA")
        pullRequest.mergeSha?.let {
            requireValid(objectIdPattern.matches(it), "Invalid pull request merge SHA")
        }
        requireSafeText(pullRequest.action, MAX_ACTION_LENGTH, allowEmpty = true, label = "pull_request.action")
        requireValid(
            pullRequest.targetBranch == payload.ref.name,
            "Pull request target branch does not match ref.name",
        )
    }

    private fun validateRepositoryPath(
        value: String,
        label: String,
    ) {
        requireSafeText(value, MAX_REPOSITORY_PATH_LENGTH, label = label)
        val segments = value.split('/')
        requireValid(
            segments.size >= 2 && segments.none { it.isBlank() || it == "." || it == ".." },
            "$label is not a canonical repository path",
        )
    }

    private fun validateGitRef(
        value: String,
        label: String,
    ) {
        requireSafeText(value, MAX_REF_LENGTH, label = label)
        val forbiddenCharacters = charArrayOf(' ', '~', '^', ':', '?', '*', '[', '\\')
        requireValid(
            !value.startsWith('/') &&
                !value.endsWith('/') &&
                !value.startsWith('.') &&
                !value.endsWith('.') &&
                !value.contains("//") &&
                !value.contains("..") &&
                !value.contains("@{") &&
                value.none { it in forbiddenCharacters } &&
                value.split('/').none { it.endsWith(".lock") },
            "$label is not a valid Git ref name",
        )
    }

    private fun validateOptionalObjectId(
        value: String,
        label: String,
    ) {
        if (value.isNotEmpty()) requireValid(objectIdPattern.matches(value), "$label is not a Git object ID")
    }

    private fun requireSafeUrl(
        value: String,
        configuredOrigin: String,
        allowEmpty: Boolean,
        label: String,
    ) {
        if (value.isEmpty() && allowEmpty) return
        requireSafeText(value, MAX_URL_LENGTH, allowEmpty = allowEmpty, label = label)
        val uri = parseUri(value, label)
        requireValid(uri.rawUserInfo == null && uri.rawFragment == null, "$label contains forbidden URL data")
        requireValid(sameOrigin(uri, parseUri(configuredOrigin, "configured endpoint")), "$label has an unexpected origin")
    }

    private fun parseUri(
        value: String,
        label: String,
    ): URI =
        try {
            URI(value).normalize().also {
                requireValid(it.isAbsolute && !it.host.isNullOrBlank(), "$label must be an absolute URL")
            }
        } catch (e: CnbWebhookValidationException) {
            throw e
        } catch (_: Exception) {
            throw CnbWebhookValidationException("$label is not a valid URL")
        }

    private fun canonicalEndpoint(value: String): String {
        val uri = parseUri(value.trimEnd('/'), "CNB endpoint")
        requireValid(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null, "Invalid CNB endpoint")
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val port = effectivePort(uri)
        val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
        return URI(
            scheme,
            null,
            uri.host.lowercase(Locale.ROOT),
            if (defaultPort) -1 else port,
            uri.path.takeUnless { it == "/" },
            null,
            null,
        ).toASCIIString().trimEnd('/')
    }

    private fun sameOrigin(
        left: URI,
        right: URI,
    ): Boolean =
        left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            effectivePort(left) == effectivePort(right)

    private fun effectivePort(uri: URI): Int =
        if (uri.port >= 0) {
            uri.port
        } else if (uri.scheme.equals("https", ignoreCase = true)) {
            443
        } else {
            80
        }

    private fun requireSafeText(
        value: String,
        maxLength: Int,
        allowEmpty: Boolean = false,
        allowLineBreaks: Boolean = false,
        label: String,
    ) {
        requireValid(allowEmpty || value.isNotEmpty(), "$label is required")
        requireValid(value.length <= maxLength, "$label is too long")
        requireValid(
            value.none {
                when {
                    it == '\n' || it == '\r' -> !allowLineBreaks
                    else -> it.code < 0x20 || it.code == 0x7f
                }
            },
            "$label contains control characters",
        )
    }

    private fun requireValid(
        condition: Boolean,
        message: String,
    ) {
        if (!condition) throw CnbWebhookValidationException(message)
    }

    fun effectiveObjectId(ref: CnbWebhookRef): String = ref.commit.ifEmpty { ref.sha }

    private const val MAX_FUTURE_SKEW_SECONDS = 60L
    private const val MAX_IDENTIFIER_LENGTH = 256
    private const val MAX_ACTOR_LENGTH = 512
    private const val MAX_REPOSITORY_PATH_LENGTH = 1024
    private const val MAX_REF_LENGTH = 1024
    private const val MAX_URL_LENGTH = 4096
    private const val MAX_TITLE_LENGTH = 4096
    private const val MAX_DESCRIPTION_LENGTH = 128 * 1024
    private const val MAX_ACTION_LENGTH = 128
}
