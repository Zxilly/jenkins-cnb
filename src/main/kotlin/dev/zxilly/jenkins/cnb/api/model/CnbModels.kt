package dev.zxilly.jenkins.cnb.api.model

import java.io.Serializable

data class CnbRepository(
    val path: String,
    val name: String,
    val webUrl: String,
    val cloneUrl: String,
    val defaultBranch: String,
    val archived: Boolean,
    val visibility: String,
    val id: String = path,
) : Serializable {
    val cloneable: Boolean
        get() = !visibility.equals("Secret", ignoreCase = true)
}

data class CnbBranch(
    val name: String,
    val sha: String,
    val protected: Boolean = false,
    val locked: Boolean = false,
) : Serializable

data class CnbTag(
    val name: String,
    val sha: String,
    val timestamp: Long = 0,
) : Serializable

data class CnbPullRequest(
    val number: String,
    val title: String,
    val state: String,
    val sourceRepo: String,
    val sourceBranch: String,
    val sourceSha: String,
    val targetRepo: String,
    val targetBranch: String,
    val targetSha: String,
    val mergeSha: String? = null,
    val author: String = "",
    val fromFork: Boolean = sourceRepo != targetRepo,
    val draft: Boolean = false,
    val updatedAt: Long = 0,
) : Serializable

data class CnbContent(
    val path: String,
    val sha: String,
    val type: String,
    val size: Long,
    val content: String? = null,
    val encoding: String? = null,
    val entries: List<CnbContentEntry> = arrayListOf(),
) : Serializable

data class CnbContentEntry(
    val name: String,
    val path: String,
    val sha: String,
    val type: String,
    val size: Long = 0,
) : Serializable

data class CnbAuthenticatedUser(
    val username: String,
    val nickname: String = "",
    val email: String = "",
) : Serializable

data class CnbCommitStatus(
    val context: String,
    val state: String,
    val description: String = "",
    val targetUrl: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
) : Serializable

data class CnbCommitAnnotation(
    val key: String,
    val value: String,
) : Serializable

data class CnbPullComment(
    val id: String,
    val body: String,
    val author: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
) : Serializable

data class CnbRepositoryEvent(
    val id: String,
    val type: String,
    val repositoryPath: String,
    val createdAt: String,
    val payload: Map<String, Any?>,
) : Serializable

data class CnbApiCapabilities(
    val supportsRepositoryEvents: Boolean = true,
    val supportsCommitAnnotations: Boolean = true,
    val supportsPullComments: Boolean = true,
    val supportsCommitStatusWrite: Boolean = false,
    val supportsWebhookManagement: Boolean = false,
) : Serializable
