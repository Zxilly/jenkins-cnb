package dev.zxilly.jenkins.cnb.webhook

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.Serializable
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.Serializable as KotlinSerializable

enum class CnbWebhookEvent(
    val wireName: String,
    val pullRequestEvent: Boolean = false,
    val untrusted: Boolean = false,
) {
    PUSH("push"),
    COMMIT_ADD("commit.add"),
    BRANCH_CREATE("branch.create"),
    BRANCH_DELETE("branch.delete"),
    TAG_PUSH("tag_push"),
    PULL_REQUEST("pull_request", pullRequestEvent = true, untrusted = true),
    PULL_REQUEST_UPDATE("pull_request.update", pullRequestEvent = true, untrusted = true),
    PULL_REQUEST_APPROVED("pull_request.approved", pullRequestEvent = true, untrusted = true),
    PULL_REQUEST_CHANGES_REQUESTED("pull_request.changes_requested", pullRequestEvent = true, untrusted = true),
    PULL_REQUEST_COMMENT("pull_request.comment", pullRequestEvent = true, untrusted = true),
    PULL_REQUEST_TARGET("pull_request.target", pullRequestEvent = true),
    PULL_REQUEST_MERGEABLE("pull_request.mergeable", pullRequestEvent = true),
    PULL_REQUEST_MERGED("pull_request.merged", pullRequestEvent = true),
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
    val encoding: String = "",
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
        const val SCHEMA_V1 = "dev.zxilly.jenkins.cnb.webhook.v1"
        const val CNBCOOL_WEBHOOK_V1_0_2_JSON_FRAGMENT = "cnbcool.webhook.v1.0.2-json-fragment"
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
    val newCommitsCount: String = "",
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
    val reviewers: String = "",
    val reviewState: String = "",
    val reviewedBy: String = "",
    val lastReviewedBy: String = "",
    val commentId: String = "",
    val commentBody: String = "",
    val commentType: String = "",
    val commentFilePath: String = "",
    val commentRange: String = "",
    val reviewId: String = "",
    val reviewDescription: String = "",
) : Serializable

internal class CnbWebhookFormatException(
    message: String,
) : IllegalArgumentException(message)

internal object CnbWebhookPayloadParser {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
            coerceInputValues = false
            allowSpecialFloatingPointValues = false
        }

    fun parse(rawBody: ByteArray): CnbWebhookPayload =
        try {
            val wire =
                json.decodeFromString(
                    CnbWebhookPayloadWire.serializer(),
                    rawBody.toString(StandardCharsets.UTF_8),
                )
            when (wire.encoding.orEmpty()) {
                "" -> wire
                CnbWebhookPayload.CNBCOOL_WEBHOOK_V1_0_2_JSON_FRAGMENT -> wire.decodeFragments(::decodeFragment)
                else -> throw CnbWebhookFormatException("Unsupported webhook payload encoding")
            }.toDomain()
        } catch (failure: CnbWebhookFormatException) {
            throw failure
        } catch (_: Exception) {
            throw CnbWebhookFormatException("Malformed JSON payload")
        }

    fun parseOccurredAt(value: String): Instant =
        runCatching { Instant.parse(value) }
            .recoverCatching { OffsetDateTime.parse(value).toInstant() }
            .getOrElse { throw CnbWebhookFormatException("occurred_at must be an ISO-8601 timestamp") }

    private fun decodeFragment(value: String): String =
        try {
            json.decodeFromString(String.serializer(), "\"$value\"")
        } catch (_: Exception) {
            throw CnbWebhookFormatException("Malformed encoded webhook string")
        }
}

@KotlinSerializable
private data class CnbWebhookPayloadWire(
    val schema: String,
    val encoding: String? = null,
    @SerialName("installation_id") val installationId: String,
    @SerialName("delivery_id") val deliveryId: String,
    @SerialName("build_id") val buildId: String? = null,
    @SerialName("occurred_at") val occurredAt: String,
    val event: String,
    @SerialName("event_url") val eventUrl: String? = null,
    @KotlinSerializable(with = CnbBooleanWireSerializer::class)
    @SerialName("is_retry")
    val retry: Boolean = false,
    val instance: CnbWebhookInstanceWire,
    val repository: CnbWebhookRepositoryWire,
    val actor: CnbWebhookActorWire? = null,
    val ref: CnbWebhookRefWire,
    @SerialName("pull_request") val pullRequest: CnbWebhookPullRequestWire? = null,
) {
    fun toDomain(): CnbWebhookPayload {
        val domainEvent =
            CnbWebhookEvent.fromWireName(event.required("event"))
                ?: throw CnbWebhookFormatException("Unsupported or untrusted CNB event")
        val domainPullRequest =
            when {
                domainEvent.pullRequestEvent -> {
                    pullRequest?.toDomain()
                        ?: throw CnbWebhookFormatException("Pull request event is missing pull_request")
                }

                pullRequest == null || pullRequest.number.isNullOrBlank() -> {
                    null
                }

                else -> {
                    throw CnbWebhookFormatException("Non-pull-request event must not include pull_request")
                }
            }
        return CnbWebhookPayload(
            schema = schema.required("schema"),
            installationId = installationId.required("installation_id"),
            deliveryId = deliveryId.required("delivery_id"),
            buildId = buildId.orEmpty(),
            occurredAt = CnbWebhookPayloadParser.parseOccurredAt(occurredAt.required("occurred_at")),
            event = domainEvent,
            eventUrl = eventUrl.orEmpty(),
            retry = retry,
            instance = instance.toDomain(),
            repository = repository.toDomain(),
            actor = actor?.toDomain() ?: CnbWebhookActor("", "", "", ""),
            ref = ref.toDomain(),
            pullRequest = domainPullRequest,
            encoding = encoding.orEmpty(),
        )
    }
}

@KotlinSerializable
private data class CnbWebhookInstanceWire(
    @SerialName("web_url") val webUrl: String,
    @SerialName("api_url") val apiUrl: String,
) {
    fun toDomain(): CnbWebhookInstance = CnbWebhookInstance(webUrl.required("web_url"), apiUrl.required("api_url"))
}

@KotlinSerializable
private data class CnbWebhookRepositoryWire(
    val id: String,
    val slug: String,
    val url: String,
) {
    fun toDomain(): CnbWebhookRepository = CnbWebhookRepository(id.required("id"), slug.required("slug"), url.required("url"))
}

@KotlinSerializable
private data class CnbWebhookActorWire(
    val id: String? = null,
    val username: String? = null,
    val nickname: String? = null,
    val email: String? = null,
) {
    fun toDomain(): CnbWebhookActor = CnbWebhookActor(id.orEmpty(), username.orEmpty(), nickname.orEmpty(), email.orEmpty())
}

@KotlinSerializable
private data class CnbWebhookRefWire(
    val name: String,
    val sha: String? = null,
    val before: String? = null,
    val commit: String? = null,
    @KotlinSerializable(with = CnbBooleanWireSerializer::class)
    @SerialName("is_tag")
    val tag: Boolean = false,
    @SerialName("new_commits_count")
    val newCommitsCount: CnbStringOrIntegerWire = CnbStringOrIntegerWire(""),
) {
    fun toDomain(): CnbWebhookRef =
        CnbWebhookRef(
            name = name.required("name"),
            sha = sha.orEmpty(),
            before = before.orEmpty(),
            commit = commit.orEmpty(),
            tag = tag,
            newCommitsCount = newCommitsCount.value,
        )
}

@KotlinSerializable
private data class CnbWebhookPullRequestWire(
    val id: String? = null,
    val number: String? = null,
    val title: String? = null,
    val description: String? = null,
    val proposer: String? = null,
    @SerialName("source_repo") val sourceRepository: String? = null,
    @SerialName("source_branch") val sourceBranch: String? = null,
    @SerialName("source_sha") val sourceSha: String? = null,
    @SerialName("target_branch") val targetBranch: String? = null,
    @SerialName("target_sha") val targetSha: String? = null,
    @SerialName("merge_sha") val mergeSha: String? = null,
    val action: String? = null,
    @KotlinSerializable(with = CnbBooleanWireSerializer::class)
    val wip: Boolean = false,
    val reviewers: String? = null,
    @SerialName("review_state") val reviewState: String? = null,
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    @SerialName("last_reviewed_by") val lastReviewedBy: String? = null,
    @SerialName("comment_id") val commentId: String? = null,
    @SerialName("comment_body") val commentBody: String? = null,
    @SerialName("comment_type") val commentType: String? = null,
    @SerialName("comment_file_path") val commentFilePath: String? = null,
    @SerialName("comment_range") val commentRange: String? = null,
    @SerialName("review_id") val reviewId: String? = null,
    @SerialName("review_description") val reviewDescription: String? = null,
) {
    fun toDomain(): CnbWebhookPullRequest =
        CnbWebhookPullRequest(
            id = id.required("id"),
            number = number.required("number"),
            title = title.orEmpty(),
            description = description.orEmpty(),
            proposer = proposer.orEmpty(),
            sourceRepository = sourceRepository.required("source_repo"),
            sourceBranch = sourceBranch.required("source_branch"),
            sourceSha = sourceSha.required("source_sha"),
            targetBranch = targetBranch.required("target_branch"),
            targetSha = targetSha.required("target_sha"),
            mergeSha = mergeSha?.takeIf(String::isNotBlank),
            action = action.orEmpty(),
            wip = wip,
            reviewers = reviewers.orEmpty(),
            reviewState = reviewState.orEmpty(),
            reviewedBy = reviewedBy.orEmpty(),
            lastReviewedBy = lastReviewedBy.orEmpty(),
            commentId = commentId.orEmpty(),
            commentBody = commentBody.orEmpty(),
            commentType = commentType.orEmpty(),
            commentFilePath = commentFilePath.orEmpty(),
            commentRange = commentRange.orEmpty(),
            reviewId = reviewId.orEmpty(),
            reviewDescription = reviewDescription.orEmpty(),
        )
}

private fun CnbWebhookPayloadWire.decodeFragments(decode: (String) -> String): CnbWebhookPayloadWire =
    copy(
        schema = decode(schema),
        installationId = decode(installationId),
        deliveryId = decode(deliveryId),
        buildId = buildId.decodeWith(decode),
        occurredAt = decode(occurredAt),
        event = decode(event),
        eventUrl = eventUrl.decodeWith(decode),
        instance = instance.decodeFragments(decode),
        repository = repository.decodeFragments(decode),
        actor = actor?.decodeFragments(decode),
        ref = ref.decodeFragments(decode),
        pullRequest = pullRequest?.decodeFragments(decode),
    )

private fun CnbWebhookInstanceWire.decodeFragments(decode: (String) -> String): CnbWebhookInstanceWire =
    copy(webUrl = decode(webUrl), apiUrl = decode(apiUrl))

private fun CnbWebhookRepositoryWire.decodeFragments(decode: (String) -> String): CnbWebhookRepositoryWire =
    copy(id = decode(id), slug = decode(slug), url = decode(url))

private fun CnbWebhookActorWire.decodeFragments(decode: (String) -> String): CnbWebhookActorWire =
    copy(
        id = id.decodeWith(decode),
        username = username.decodeWith(decode),
        nickname = nickname.decodeWith(decode),
        email = email.decodeWith(decode),
    )

private fun CnbWebhookRefWire.decodeFragments(decode: (String) -> String): CnbWebhookRefWire =
    copy(
        name = decode(name),
        sha = sha.decodeWith(decode),
        before = before.decodeWith(decode),
        commit = commit.decodeWith(decode),
        newCommitsCount = CnbStringOrIntegerWire(decode(newCommitsCount.value)),
    )

private fun CnbWebhookPullRequestWire.decodeFragments(decode: (String) -> String): CnbWebhookPullRequestWire =
    copy(
        id = id.decodeWith(decode),
        number = number.decodeWith(decode),
        title = title.decodeWith(decode),
        description = description.decodeWith(decode),
        proposer = proposer.decodeWith(decode),
        sourceRepository = sourceRepository.decodeWith(decode),
        sourceBranch = sourceBranch.decodeWith(decode),
        sourceSha = sourceSha.decodeWith(decode),
        targetBranch = targetBranch.decodeWith(decode),
        targetSha = targetSha.decodeWith(decode),
        mergeSha = mergeSha.decodeWith(decode),
        action = action.decodeWith(decode),
        reviewers = reviewers.decodeWith(decode),
        reviewState = reviewState.decodeWith(decode),
        reviewedBy = reviewedBy.decodeWith(decode),
        lastReviewedBy = lastReviewedBy.decodeWith(decode),
        commentId = commentId.decodeWith(decode),
        commentBody = commentBody.decodeWith(decode),
        commentType = commentType.decodeWith(decode),
        commentFilePath = commentFilePath.decodeWith(decode),
        commentRange = commentRange.decodeWith(decode),
        reviewId = reviewId.decodeWith(decode),
        reviewDescription = reviewDescription.decodeWith(decode),
    )

private fun String?.decodeWith(decode: (String) -> String): String? = this?.let(decode)

@KotlinSerializable(with = CnbStringOrIntegerWireSerializer::class)
private data class CnbStringOrIntegerWire(
    val value: String,
)

private object CnbBooleanWireSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CnbBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val primitive =
            (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
                ?: throw SerializationException("Expected a boolean")
        return if (primitive.isString) {
            when (primitive.content) {
                "true" -> true
                "false", "" -> false
                else -> throw SerializationException("Expected a boolean")
            }
        } else {
            primitive.booleanOrNull ?: throw SerializationException("Expected a boolean")
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: Boolean,
    ) = encoder.encodeBoolean(value)
}

private object CnbStringOrIntegerWireSerializer : KSerializer<CnbStringOrIntegerWire> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("CnbStringOrInteger", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): CnbStringOrIntegerWire {
        val primitive =
            (decoder as? JsonDecoder)?.decodeJsonElement() as? JsonPrimitive
                ?: throw SerializationException("Expected a string or integer")
        if (!primitive.isString && !INTEGER_PATTERN.matches(primitive.content)) {
            throw SerializationException("Expected a string or integer")
        }
        return CnbStringOrIntegerWire(primitive.content)
    }

    override fun serialize(
        encoder: Encoder,
        value: CnbStringOrIntegerWire,
    ) = encoder.encodeString(value.value)

    private val INTEGER_PATTERN = Regex("-?(?:0|[1-9][0-9]*)")
}

private fun String?.required(field: String): String =
    this?.takeIf(String::isNotBlank) ?: throw CnbWebhookFormatException("$field is required")
