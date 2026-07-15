package dev.zxilly.jenkins.cnb.webhook

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.Serializable
import java.time.Instant
import java.time.OffsetDateTime

enum class CnbWebhookEvent(
    val wireName: String,
    val trustedPullRequestEvent: Boolean = false,
) {
    PUSH("push"),
    BRANCH_CREATE("branch.create"),
    BRANCH_DELETE("branch.delete"),
    TAG_PUSH("tag_push"),
    PULL_REQUEST_TARGET("pull_request.target", true),
    PULL_REQUEST_MERGEABLE("pull_request.mergeable", true),
    PULL_REQUEST_MERGED("pull_request.merged", true),
    ;

    companion object {
        fun fromWireName(value: String): CnbWebhookEvent? = entries.firstOrNull { it.wireName == value }
    }
}

data class CnbWebhookPayload(
    val schema: String,
    val installationId: String,
    val deliveryId: String,
    val buildId: String,
    val occurredAt: Instant,
    val event: CnbWebhookEvent,
    val eventUrl: String,
    val retry: Boolean,
    val instance: CnbWebhookInstance,
    val repository: CnbWebhookRepository,
    val actor: CnbWebhookActor,
    val ref: CnbWebhookRef,
    val pullRequest: CnbWebhookPullRequest?,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
        const val SCHEMA_V1 = "dev.zxilly.jenkins.cnb.webhook.v1"
    }
}

data class CnbWebhookInstance(
    val webUrl: String,
    val apiUrl: String,
) : Serializable

data class CnbWebhookRepository(
    val id: String,
    val slug: String,
    val url: String,
) : Serializable {
    val name: String
        get() = slug.substringAfterLast('/')
}

data class CnbWebhookActor(
    val id: String,
    val username: String,
    val nickname: String,
    val email: String,
) : Serializable

data class CnbWebhookRef(
    val name: String,
    val sha: String,
    val before: String,
    val commit: String,
    val tag: Boolean,
) : Serializable

data class CnbWebhookPullRequest(
    val id: String,
    val number: String,
    val title: String,
    val description: String,
    val proposer: String,
    val sourceRepository: String,
    val sourceBranch: String,
    val sourceSha: String,
    val targetBranch: String,
    val targetSha: String,
    val mergeSha: String?,
    val action: String,
    val wip: Boolean,
) : Serializable

internal class CnbWebhookFormatException(
    message: String,
) : IllegalArgumentException(message)

internal object CnbWebhookPayloadParser {
    private val mapper =
        ObjectMapper(
            JsonFactory
                .builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(
                    StreamReadConstraints
                        .builder()
                        .maxDocumentLength(MAX_DOCUMENT_BYTES.toLong())
                        .maxNestingDepth(MAX_NESTING_DEPTH)
                        .maxStringLength(MAX_STRING_LENGTH)
                        .maxNameLength(MAX_FIELD_NAME_LENGTH)
                        .maxNumberLength(MAX_NUMBER_LENGTH)
                        .build(),
                ).build(),
        )

    fun parse(rawBody: ByteArray): CnbWebhookPayload {
        val root =
            try {
                mapper.readTree(rawBody)
            } catch (_: Exception) {
                throw CnbWebhookFormatException("Malformed JSON payload")
            }
        if (root == null || !root.isObject) throw CnbWebhookFormatException("Webhook payload must be a JSON object")

        val eventName = root.requiredText("event")
        val event =
            CnbWebhookEvent.fromWireName(eventName)
                ?: throw CnbWebhookFormatException("Unsupported or untrusted CNB event")
        val pullRequestNode = root.path("pull_request")
        val pullRequest =
            when {
                event.trustedPullRequestEvent -> parsePullRequest(pullRequestNode)
                pullRequestNode.isObject && pullRequestNode.optionalText("number") != null -> parsePullRequest(pullRequestNode)
                else -> null
            }

        return CnbWebhookPayload(
            schema = root.requiredText("schema"),
            installationId = root.requiredText("installation_id"),
            deliveryId = root.requiredText("delivery_id"),
            buildId = root.optionalText("build_id").orEmpty(),
            occurredAt = parseInstant(root.requiredText("occurred_at")),
            event = event,
            eventUrl = root.optionalText("event_url").orEmpty(),
            retry = root.boolean("is_retry"),
            instance =
                root.requiredObject("instance").let {
                    CnbWebhookInstance(
                        webUrl = it.requiredText("web_url"),
                        apiUrl = it.requiredText("api_url"),
                    )
                },
            repository =
                root.requiredObject("repository").let {
                    CnbWebhookRepository(
                        id = it.requiredText("id"),
                        slug = it.requiredText("slug"),
                        url = it.requiredText("url"),
                    )
                },
            actor =
                root.optionalObject("actor")?.let {
                    CnbWebhookActor(
                        id = it.optionalText("id").orEmpty(),
                        username = it.optionalText("username").orEmpty(),
                        nickname = it.optionalText("nickname").orEmpty(),
                        email = it.optionalText("email").orEmpty(),
                    )
                } ?: CnbWebhookActor("", "", "", ""),
            ref =
                root.requiredObject("ref").let {
                    CnbWebhookRef(
                        name = it.requiredText("name"),
                        sha = it.optionalText("sha").orEmpty(),
                        before = it.optionalText("before").orEmpty(),
                        commit = it.optionalText("commit").orEmpty(),
                        tag = it.boolean("is_tag"),
                    )
                },
            pullRequest = pullRequest,
        )
    }

    private fun parsePullRequest(node: JsonNode): CnbWebhookPullRequest {
        if (!node.isObject) throw CnbWebhookFormatException("Trusted pull request event is missing pull_request")
        return CnbWebhookPullRequest(
            id = node.requiredText("id"),
            number = node.requiredText("number"),
            title = node.optionalText("title").orEmpty(),
            description = node.optionalText("description").orEmpty(),
            proposer = node.optionalText("proposer").orEmpty(),
            sourceRepository = node.requiredText("source_repo"),
            sourceBranch = node.requiredText("source_branch"),
            sourceSha = node.requiredText("source_sha"),
            targetBranch = node.requiredText("target_branch"),
            targetSha = node.requiredText("target_sha"),
            mergeSha = node.optionalText("merge_sha"),
            action = node.optionalText("action").orEmpty(),
            wip = node.boolean("wip"),
        )
    }

    private fun parseInstant(value: String): Instant =
        runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .getOrElse { throw CnbWebhookFormatException("occurred_at must be an ISO-8601 timestamp") }

    private fun JsonNode.requiredObject(field: String): JsonNode {
        val value = path(field)
        if (!value.isObject) throw CnbWebhookFormatException("$field must be an object")
        return value
    }

    private fun JsonNode.optionalObject(field: String): JsonNode? {
        val value = path(field)
        if (value.isMissingNode || value.isNull) return null
        if (!value.isObject) throw CnbWebhookFormatException("$field must be an object")
        return value
    }

    private fun JsonNode.requiredText(field: String): String = optionalText(field) ?: throw CnbWebhookFormatException("$field is required")

    private fun JsonNode.optionalText(field: String): String? {
        val value = path(field)
        if (value.isMissingNode || value.isNull) return null
        if (!value.isTextual) throw CnbWebhookFormatException("$field must be a string")
        return value.asText().takeIf { it.isNotBlank() }
    }

    private fun JsonNode.boolean(field: String): Boolean {
        val value = path(field)
        if (value.isMissingNode || value.isNull) return false
        if (value.isBoolean) return value.asBoolean()
        if (value.isTextual) {
            return when (value.asText()) {
                "true" -> true
                "false", "" -> false
                else -> throw CnbWebhookFormatException("$field must be a boolean")
            }
        }
        throw CnbWebhookFormatException("$field must be a boolean")
    }

    private const val MAX_DOCUMENT_BYTES = 256 * 1024
    private const val MAX_NESTING_DEPTH = 16
    private const val MAX_STRING_LENGTH = 128 * 1024
    private const val MAX_FIELD_NAME_LENGTH = 128
    private const val MAX_NUMBER_LENGTH = 64
}
