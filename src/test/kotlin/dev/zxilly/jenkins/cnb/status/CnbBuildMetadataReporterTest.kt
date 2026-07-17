package dev.zxilly.jenkins.cnb.status

import dev.zxilly.jenkins.cnb.api.CnbApiException
import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbCommitAnnotation
import dev.zxilly.jenkins.cnb.api.model.CnbPullComment
import dev.zxilly.jenkins.cnb.api.model.CnbTagAnnotation
import dev.zxilly.jenkins.cnb.config.CnbStatusReportingMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class CnbBuildMetadataReporterTest {
    @Test
    fun `lost response recovery chooses the original earliest marker comment`() {
        val marker = "<!-- jenkins-cnb:v1:unpredictable -->"
        val attackerCopy =
            CnbPullComment(
                id = "101",
                body = "$marker copied",
                author = "attacker",
                createdAt = "2026-07-15T10:00:02Z",
            )
        val original =
            CnbPullComment(
                id = "100",
                body = "$marker original",
                author = "jenkins",
                createdAt = "2026-07-15T10:00:01Z",
            )

        val selected = CnbBuildMetadataReporter.selectPullComment(listOf(attackerCopy, original), marker, null)

        assertEquals("100", selected?.id)
    }

    @Test
    fun `persisted comment ID wins regardless of list ordering`() {
        val marker = "<!-- marker -->"
        val comments =
            listOf(
                CnbPullComment("1", marker, createdAt = "2026-07-15T10:00:01Z"),
                CnbPullComment("2", marker, createdAt = "2026-07-15T10:00:02Z"),
            )

        assertEquals("2", CnbBuildMetadataReporter.selectPullComment(comments, marker, "2")?.id)
    }

    @Test
    fun `both mode writes namespaced annotations and creates an escaped pull comment`() {
        val recording = RecordingClient()
        val snapshot =
            snapshot(
                displayName = "build <42>|[main]",
                buildUrl = "https://jenkins.example/job/a b/(42)",
                context = "Team / Release `Pipeline`",
            )

        val result =
            CnbBuildMetadataReporter.reportWithClient(
                snapshot,
                recording.client(),
                CnbStatusReportingMode.BOTH,
            )

        assertEquals("created-1", result.commentId)
        val annotations = requireNotNull(recording.annotations)
        assertEquals(6, annotations.size)
        assertTrue(annotations.all { it.key.matches(Regex("[A-Za-z0-9_-]+")) })
        assertTrue(annotations.all { it.key.startsWith("jenkins_team-release-pipeline-") })
        assertEquals("success", annotations.single { it.key.endsWith("state") }.value)
        assertEquals("contributor/repo", recording.annotationRepository)
        val body = requireNotNull(recording.createdBody)
        assertTrue(body.contains("<!-- jenkins-cnb:v1:marker-token -->"))
        assertTrue(body.contains("build &lt;42&gt;\\|\\[main\\]"))
        assertTrue(body.contains("https://jenkins.example/job/a%20b/%2842%29"))
        assertEquals("team/project", recording.commentRepository)
        assertEquals("42", recording.commentNumber)
    }

    @Test
    fun `tag builds write namespaced annotations to the tag endpoint`() {
        val recording = RecordingClient()

        CnbBuildMetadataReporter.reportWithClient(
            snapshot(tag = "v1.0.0"),
            recording.client(),
            CnbStatusReportingMode.COMMIT_ANNOTATION,
        )

        assertEquals(null, recording.annotations)
        assertEquals("team/project", recording.tagAnnotationRepository)
        assertEquals("v1.0.0", recording.tagAnnotationName)
        val annotations = requireNotNull(recording.tagAnnotations)
        assertEquals(6, annotations.size)
        assertTrue(annotations.all { it.key.matches(Regex("[A-Za-z0-9_-]+")) })
        assertTrue(annotations.all { it.key.startsWith("jenkins_folder-job-") })
        assertEquals("success", annotations.single { it.key.endsWith("state") }.value)
    }

    @Test
    fun `an identical durable comment is reused without another remote write`() {
        val first = RecordingClient()
        val snapshot = snapshot()
        CnbBuildMetadataReporter.reportWithClient(snapshot, first.client(), CnbStatusReportingMode.PULL_REQUEST_COMMENT)
        val existing = CnbPullComment("existing-7", requireNotNull(first.createdBody), createdAt = "2026-07-15T10:00:00Z")
        val retry = RecordingClient(comments = listOf(existing))

        val result =
            CnbBuildMetadataReporter.reportWithClient(
                snapshot.copy(knownCommentId = "existing-7"),
                retry.client(),
                CnbStatusReportingMode.PULL_REQUEST_COMMENT,
            )

        assertEquals("existing-7", result.commentId)
        assertEquals(1, retry.listCalls)
        assertEquals(null, retry.createdBody)
        assertEquals(null, retry.updatedBody)
    }

    @Test
    fun `an outdated marker comment is updated in place`() {
        val old = CnbPullComment("17", "<!-- jenkins-cnb:v1:marker-token --> old")
        val recording = RecordingClient(comments = listOf(old))

        val result =
            CnbBuildMetadataReporter.reportWithClient(
                snapshot(knownCommentId = "17"),
                recording.client(),
                CnbStatusReportingMode.PULL_REQUEST_COMMENT,
            )

        assertEquals("17", result.commentId)
        assertEquals("17", recording.updatedCommentId)
        assertTrue(requireNotNull(recording.updatedBody).contains("Jenkins build metadata"))
        assertEquals(null, recording.createdBody)
    }

    @Test
    fun `reporting modes and advertised capabilities suppress unsupported writes`() {
        val disabledByMode = RecordingClient()
        val annotationOnly =
            CnbBuildMetadataReporter.reportWithClient(
                snapshot(knownCommentId = "known"),
                disabledByMode.client(),
                CnbStatusReportingMode.DISABLED,
            )
        assertEquals("known", annotationOnly.commentId)
        assertFalse(disabledByMode.called)

        val unsupported =
            RecordingClient(
                capabilities =
                    CnbApiCapabilities(
                        supportsCommitAnnotations = false,
                        supportsPullComments = false,
                    ),
            )
        CnbBuildMetadataReporter.reportWithClient(snapshot(), unsupported.client(), CnbStatusReportingMode.BOTH)
        assertFalse(unsupported.called)

        val noPullRequest = RecordingClient()
        CnbBuildMetadataReporter.reportWithClient(
            snapshot().copy(target = snapshot().target.copy(pullRequestNumber = null)),
            noPullRequest.client(),
            CnbStatusReportingMode.PULL_REQUEST_COMMENT,
        )
        assertFalse(noPullRequest.called)
    }

    @Test
    fun `all independent destinations are attempted and a retryable failure wins`() {
        val annotationFailure = IllegalStateException("annotation rejected")
        val retryable = CnbApiException("temporarily unavailable", 503, retryable = true)
        val recording =
            RecordingClient(
                failures = mapOf("putCommitAnnotations" to annotationFailure, "listPullComments" to retryable),
            )

        val thrown =
            assertThrows(CnbApiException::class.java) {
                CnbBuildMetadataReporter.reportWithClient(snapshot(), recording.client(), CnbStatusReportingMode.BOTH)
            }

        assertSame(retryable, thrown)
        assertEquals(1, recording.annotationCalls)
        assertEquals(1, recording.listCalls)
    }

    @Test
    fun `first non retryable destination failure is propagated after later success`() {
        val failure = IllegalArgumentException("bad annotation")
        val recording = RecordingClient(failures = mapOf("putCommitAnnotations" to failure))

        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                CnbBuildMetadataReporter.reportWithClient(snapshot(), recording.client(), CnbStatusReportingMode.BOTH)
            }

        assertSame(failure, thrown)
        assertEquals("created-1", recording.createdCommentId)
    }

    @Test
    fun `comment selection is deterministic for missing timestamps and non numeric IDs`() {
        val marker = "<!-- marker -->"
        val comments =
            listOf(
                CnbPullComment("zeta", "$marker z"),
                CnbPullComment("11", "$marker numeric"),
                CnbPullComment("alpha", "$marker a"),
                CnbPullComment("1", "unrelated"),
            )

        assertEquals("11", CnbBuildMetadataReporter.selectPullComment(comments, marker, null)?.id)
        assertEquals(null, CnbBuildMetadataReporter.selectPullComment(comments, "<!-- absent -->", null))
    }

    private fun snapshot(
        displayName: String = "folder/job #42",
        buildUrl: String = "https://jenkins.example/job/folder/job/42/",
        context: String = "folder/job",
        knownCommentId: String? = null,
        tag: String? = null,
        state: CnbBuildMetadataState = CnbBuildMetadataState.SUCCESS,
    ) = CnbBuildMetadataSnapshot(
        version = 7,
        markerToken = "marker-token",
        target =
            CnbBuildMetadataTarget(
                serverId = "cnb-cool",
                repository = "team/project",
                commitRepository = "contributor/repo",
                sha = "a".repeat(40),
                pullRequestNumber = "42",
                context = context,
                credentialsId = null,
                tag = tag,
            ),
        state = state,
        stateChangedAt = "2026-07-15T10:00:00Z",
        buildDisplayName = displayName,
        buildUrl = buildUrl,
        knownCommentId = knownCommentId,
    )

    private class RecordingClient(
        private val capabilities: CnbApiCapabilities = CnbApiCapabilities(),
        private val comments: List<CnbPullComment> = emptyList(),
        private val failures: Map<String, Exception> = emptyMap(),
    ) {
        var annotations: List<CnbCommitAnnotation>? = null
        var annotationRepository: String? = null
        var tagAnnotations: List<CnbTagAnnotation>? = null
        var tagAnnotationRepository: String? = null
        var tagAnnotationName: String? = null
        var commentRepository: String? = null
        var commentNumber: String? = null
        var createdBody: String? = null
        var createdCommentId: String? = null
        var updatedBody: String? = null
        var updatedCommentId: String? = null
        var annotationCalls = 0
        var listCalls = 0
        val called: Boolean
            get() = annotationCalls > 0 || listCalls > 0 || createdBody != null || updatedBody != null

        @Suppress("UNCHECKED_CAST")
        fun client(): CnbClient {
            val unsupported =
                Proxy.newProxyInstance(CnbClient::class.java.classLoader, arrayOf(CnbClient::class.java)) { _, method, _ ->
                    when (method.name) {
                        "close" -> Unit
                        "toString" -> "RecordingCnbClient"
                        else -> throw UnsupportedOperationException(method.name)
                    }
                } as CnbClient
            return object : CnbClient by unsupported {
                override val capabilities: CnbApiCapabilities
                    get() = this@RecordingClient.capabilities

                override fun putCommitAnnotations(
                    repo: String,
                    sha: String,
                    annotations: List<CnbCommitAnnotation>,
                ) {
                    annotationCalls++
                    failures["putCommitAnnotations"]?.let { throw it }
                    annotationRepository = repo
                    this@RecordingClient.annotations = annotations.toList()
                }

                override fun putTagAnnotations(
                    repo: String,
                    tag: String,
                    annotations: List<CnbTagAnnotation>,
                ) {
                    annotationCalls++
                    failures["putTagAnnotations"]?.let { throw it }
                    tagAnnotationRepository = repo
                    tagAnnotationName = tag
                    this@RecordingClient.tagAnnotations = annotations.toList()
                }

                override fun listPullComments(
                    repo: String,
                    number: String,
                ): List<CnbPullComment> {
                    listCalls++
                    commentRepository = repo
                    commentNumber = number
                    failures["listPullComments"]?.let { throw it }
                    return comments
                }

                override fun createPullComment(
                    repo: String,
                    number: String,
                    body: String,
                ): CnbPullComment {
                    createdBody = body
                    createdCommentId = "created-1"
                    return CnbPullComment(requireNotNull(createdCommentId), requireNotNull(createdBody))
                }

                override fun updatePullComment(
                    repo: String,
                    number: String,
                    commentId: String,
                    body: String,
                ): CnbPullComment {
                    updatedCommentId = commentId
                    updatedBody = body
                    return CnbPullComment(requireNotNull(updatedCommentId), requireNotNull(updatedBody))
                }
            }
        }
    }
}
