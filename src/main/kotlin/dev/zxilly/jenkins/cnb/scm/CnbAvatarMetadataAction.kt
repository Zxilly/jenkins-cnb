package dev.zxilly.jenkins.cnb.scm

import jenkins.scm.api.metadata.AvatarMetadataAction
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Exposes a CNB-hosted image through Jenkins' cached SCM avatar endpoint. */
class CnbAvatarMetadataAction(
    val avatarUrl: String,
    private val description: String,
) : AvatarMetadataAction() {
    override fun getAvatarImageOf(size: String): String = cachedResizedImageOf(avatarUrl, size)

    override fun getAvatarDescription(): String = description

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is CnbAvatarMetadataAction && avatarUrl == other.avatarUrl && description == other.description)

    override fun hashCode(): Int = 31 * avatarUrl.hashCode() + description.hashCode()

    companion object {
        private const val serialVersionUID = 1L
        private const val NAMESPACE_DESCRIPTION = "CNB namespace avatar"
        private const val REPOSITORY_DESCRIPTION = "CNB repository namespace avatar"
        private const val INSTANCE_DESCRIPTION = "CNB instance avatar"

        internal fun forNamespace(
            webUrl: String,
            namespace: String,
        ): CnbAvatarMetadataAction =
            CnbAvatarMetadataAction(
                "${webUrl.trimEnd('/')}/${pathSegments(namespace)}/-/logos/s",
                NAMESPACE_DESCRIPTION,
            )

        internal fun forRepository(repositoryWebUrl: String): CnbAvatarMetadataAction =
            CnbAvatarMetadataAction(
                "${repositoryWebUrl.trimEnd('/').substringBeforeLast('/')}/-/logos/s",
                REPOSITORY_DESCRIPTION,
            )

        internal fun forInstance(webUrl: String): CnbAvatarMetadataAction =
            CnbAvatarMetadataAction("${webUrl.trimEnd('/')}/images/favicon.svg", INSTANCE_DESCRIPTION)

        private fun pathSegments(value: String): String =
            value.split('/').joinToString("/") { URLEncoder.encode(it, StandardCharsets.UTF_8).replace("+", "%20") }
    }
}
