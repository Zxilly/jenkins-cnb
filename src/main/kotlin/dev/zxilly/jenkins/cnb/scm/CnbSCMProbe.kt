package dev.zxilly.jenkins.cnb.scm

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbContent
import dev.zxilly.jenkins.cnb.api.model.CnbContentEncoding
import dev.zxilly.jenkins.cnb.api.model.CnbContentEntry
import dev.zxilly.jenkins.cnb.api.model.CnbContentType
import jenkins.scm.api.SCMFile
import jenkins.scm.api.SCMProbe
import jenkins.scm.api.SCMProbeStat
import jenkins.scm.api.SCMRevision
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

/** REST-backed criteria probe. A shared probe never closes its scan-scoped client. */
class CnbSCMProbe private constructor(
    private val client: CnbClient,
    private val repositoryPath: String,
    private val ref: String,
    private val fallbackRepositoryPath: String?,
    private val fallbackRef: String?,
    private val displayName: String,
    private val revision: SCMRevision?,
    private val ownsClient: Boolean,
    private val modified: Long,
) : SCMProbe() {
    @Volatile
    private var open = true

    override fun name(): String = displayName

    override fun lastModified(): Long = modified

    override fun stat(path: String): SCMProbeStat {
        checkOpen()
        val normalized = normalize(path)
        val content =
            client.getContent(repositoryPath, normalized, ref)
                ?: fallbackRepositoryPath?.let { fallbackRepository ->
                    client.getContent(fallbackRepository, normalized, requireNotNull(fallbackRef))
                }
        return SCMProbeStat.fromType(content.toFileType())
    }

    override fun getRoot(): SCMFile? =
        if (open) {
            CnbSCMFile.root(
                client,
                repositoryPath,
                ref,
                modified,
                fallbackRepositoryPath,
                fallbackRef,
            )
        } else {
            null
        }

    override fun close() {
        if (!open) {
            return
        }
        synchronized(this) {
            if (!open) {
                return
            }
            open = false
            if (ownsClient) {
                client.close()
            }
        }
    }

    private fun checkOpen() {
        if (!open) {
            throw IOException("CNB probe is closed")
        }
    }

    companion object {
        fun shared(
            client: CnbClient,
            repositoryPath: String,
            ref: String,
            displayName: String,
            revision: SCMRevision?,
            modified: Long = 0,
        ): CnbSCMProbe = CnbSCMProbe(client, repositoryPath, ref, null, null, displayName, revision, false, modified)

        fun sharedWithFallback(
            client: CnbClient,
            repositoryPath: String,
            ref: String,
            fallbackRepositoryPath: String,
            fallbackRef: String,
            displayName: String,
            revision: SCMRevision?,
            modified: Long = 0,
        ): CnbSCMProbe =
            CnbSCMProbe(
                client,
                repositoryPath,
                ref,
                fallbackRepositoryPath,
                fallbackRef,
                displayName,
                revision,
                false,
                modified,
            )

        fun owned(
            client: CnbClient,
            repositoryPath: String,
            ref: String,
            displayName: String,
            revision: SCMRevision?,
            modified: Long = 0,
        ): CnbSCMProbe = CnbSCMProbe(client, repositoryPath, ref, null, null, displayName, revision, true, modified)

        fun ownedWithFallback(
            client: CnbClient,
            repositoryPath: String,
            ref: String,
            fallbackRepositoryPath: String,
            fallbackRef: String,
            displayName: String,
            revision: SCMRevision?,
            modified: Long = 0,
        ): CnbSCMProbe =
            CnbSCMProbe(
                client,
                repositoryPath,
                ref,
                fallbackRepositoryPath,
                fallbackRef,
                displayName,
                revision,
                true,
                modified,
            )

        internal fun normalize(path: String): String = path.replace('\\', '/').trim('/')
    }
}

/** A lazily fetched file at a fixed CNB commit. */
class CnbSCMFile : SCMFile {
    private val client: CnbClient
    private val repositoryPath: String
    private val ref: String
    private val fallbackRepositoryPath: String?
    private val fallbackRef: String?
    private val modified: Long

    private var loaded = false
    private var apiContent: CnbContent? = null
    private var fallbackContent: CnbContent? = null

    private constructor(
        client: CnbClient,
        repositoryPath: String,
        ref: String,
        modified: Long,
        fallbackRepositoryPath: String?,
        fallbackRef: String?,
    ) : super() {
        this.client = client
        this.repositoryPath = repositoryPath
        this.ref = ref
        this.fallbackRepositoryPath = fallbackRepositoryPath
        this.fallbackRef = fallbackRef
        this.modified = modified
        apiContent = CnbContent("", "", CnbContentType.TREE, 0)
        if (fallbackRepositoryPath != null) {
            fallbackContent = CnbContent("", "", CnbContentType.TREE, 0)
        }
    }

    private constructor(
        parent: CnbSCMFile,
        name: String,
        initialContent: CnbContent? = null,
        initialFallbackContent: CnbContent? = null,
    ) : super(parent, name) {
        client = parent.client
        repositoryPath = parent.repositoryPath
        ref = parent.ref
        fallbackRepositoryPath = parent.fallbackRepositoryPath
        fallbackRef = parent.fallbackRef
        modified = parent.modified
        apiContent = initialContent
        fallbackContent = initialFallbackContent
    }

    override fun newChild(
        name: String,
        assumeIsDirectory: Boolean,
    ): SCMFile =
        CnbSCMFile(
            this,
            name,
            if (assumeIsDirectory) CnbContent(joinPath(path, name), "", CnbContentType.TREE, 0) else null,
        )

    override fun children(): Iterable<SCMFile> {
        loadAll()
        val primary = apiContent?.takeIf { it.toFileType() == Type.DIRECTORY }
        val fallback = fallbackContent?.takeIf { it.toFileType() == Type.DIRECTORY }
        if (primary == null && fallback == null) {
            if (apiContent == null && fallbackContent == null) {
                throw IOException("'$path' does not exist at $ref")
            }
            return emptyList()
        }

        val entries = linkedMapOf<String, Pair<CnbContent?, CnbContent?>>()
        for (child in fallback?.entries.orEmpty()) {
            val name = child.name.ifBlank { child.path.substringAfterLast('/') }
            entries[name] = null to child.toContent()
        }
        for (child in primary?.entries.orEmpty()) {
            val name = child.name.ifBlank { child.path.substringAfterLast('/') }
            entries[name] = child.toContent() to entries[name]?.second
        }
        val children = ArrayList<SCMFile>(entries.size)
        for ((name, content) in entries) {
            children.add(
                CnbSCMFile(
                    this,
                    name,
                    content.first,
                    content.second,
                ),
            )
        }
        return children
    }

    override fun lastModified(): Long = modified

    override fun type(): Type = (apiContent ?: fallbackContent)?.toFileType() ?: load().toFileType()

    override fun content(): InputStream {
        val entry = load() ?: throw IOException("'$path' does not exist at $ref")
        if (entry.toFileType() != Type.REGULAR_FILE && entry.toFileType() != Type.LINK) {
            throw IOException("Cannot read content of non-file '$path'")
        }
        val value = entry.content ?: throw IOException("CNB did not return inline content for '$path' at $ref")
        val bytes =
            when (entry.encoding) {
                null -> {
                    value.toByteArray(StandardCharsets.UTF_8)
                }

                CnbContentEncoding.BASE64 -> {
                    try {
                        Base64.getDecoder().decode(value)
                    } catch (failure: IllegalArgumentException) {
                        throw IOException("CNB returned invalid base64 content for '$path'", failure)
                    }
                }
            }
        return ByteArrayInputStream(bytes)
    }

    private fun load(): CnbContent? {
        loadAll()
        return apiContent ?: fallbackContent
    }

    private fun loadAll() {
        if (!loaded) {
            apiContent = client.getContent(repositoryPath, CnbSCMProbe.normalize(path), ref)
            fallbackContent =
                fallbackRepositoryPath?.let { fallbackRepository ->
                    client.getContent(fallbackRepository, CnbSCMProbe.normalize(path), requireNotNull(fallbackRef))
                }
            loaded = true
        }
    }

    companion object {
        fun root(
            client: CnbClient,
            repositoryPath: String,
            ref: String,
            modified: Long = 0,
            fallbackRepositoryPath: String? = null,
            fallbackRef: String? = null,
        ): CnbSCMFile = CnbSCMFile(client, repositoryPath, ref, modified, fallbackRepositoryPath, fallbackRef)

        private fun joinPath(
            parent: String,
            child: String,
        ): String {
            val normalizedParent = parent.trim('/')
            val normalizedChild = child.trim('/')
            return when {
                normalizedParent.isEmpty() -> normalizedChild
                normalizedChild.isEmpty() -> normalizedParent
                else -> "$normalizedParent/$normalizedChild"
            }
        }
    }
}

private fun CnbContentEntry.toContent(): CnbContent = CnbContent(path = path, sha = sha, type = type, size = size)

private fun CnbContent?.toFileType(): SCMFile.Type =
    when (this?.type) {
        null -> SCMFile.Type.NONEXISTENT
        CnbContentType.BLOB, CnbContentType.LFS -> SCMFile.Type.REGULAR_FILE
        CnbContentType.TREE -> SCMFile.Type.DIRECTORY
        CnbContentType.LINK -> SCMFile.Type.LINK
        CnbContentType.EMPTY, CnbContentType.SUBMODULE -> SCMFile.Type.OTHER
    }
