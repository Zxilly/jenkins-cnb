package dev.zxilly.jenkins.cnb.pipeline

import dev.zxilly.jenkins.cnb.api.CnbClient
import dev.zxilly.jenkins.cnb.api.model.CnbApiCapabilities
import dev.zxilly.jenkins.cnb.api.model.CnbBadge
import dev.zxilly.jenkins.cnb.api.model.CnbBadgeGroup
import dev.zxilly.jenkins.cnb.api.model.CnbBadgeSummary
import dev.zxilly.jenkins.cnb.api.model.CnbBadgeUploadRequest
import dev.zxilly.jenkins.cnb.api.model.CnbBadgeUploadResult
import dev.zxilly.jenkins.cnb.api.model.CnbBuildNpcName
import dev.zxilly.jenkins.cnb.api.model.CnbBuildRequest
import dev.zxilly.jenkins.cnb.api.model.CnbBuildResult
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStage
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStageStatus
import dev.zxilly.jenkins.cnb.api.model.CnbBuildState
import dev.zxilly.jenkins.cnb.api.model.CnbBuildStatus
import dev.zxilly.jenkins.cnb.api.model.CnbBuildTriggerEvent
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatus
import dev.zxilly.jenkins.cnb.api.model.CnbCommitStatusState
import dev.zxilly.jenkins.cnb.api.model.CnbLabel
import dev.zxilly.jenkins.cnb.api.model.CnbMergePullResult
import dev.zxilly.jenkins.cnb.api.model.CnbPipelineStatus
import dev.zxilly.jenkins.cnb.api.model.CnbPullComment
import dev.zxilly.jenkins.cnb.api.model.CnbPullMergeStyle
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequest
import dev.zxilly.jenkins.cnb.api.model.CnbPullRequestState
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewComment
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewEvent
import dev.zxilly.jenkins.cnb.api.model.CnbPullReviewRequest
import hudson.AbortException
import hudson.EnvVars
import hudson.model.Run
import hudson.model.TaskListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class CnbPipelineStepsTest {
    private val context = CnbRunContext("cnb-cool", "team/project", "42", "a".repeat(40), null)

    @Test
    fun `protected pull request mutations reject a stale source before every write`() {
        val writes = mutableListOf<String>()
        val client =
            client(
                handlers =
                    mapOf(
                        "getPullRequest" to { pullRequest("b".repeat(40)) },
                        "addPullLabel" to { writes += "add" },
                        "replacePullLabels" to { writes += "replace" },
                        "removePullLabel" to { writes += "remove" },
                        "createPullReview" to { writes += "review" },
                        "mergePullRequest" to { writes += "merge" },
                    ),
            )
        val protectedRequests =
            listOf(
                CnbStepRequest.PullRequestLabels(CnbPullRequestLabelMode.ADD, listOf("ready"), null),
                CnbStepRequest.PullRequestLabels(CnbPullRequestLabelMode.REPLACE, listOf("ready"), "42"),
                CnbStepRequest.PullRequestLabels(CnbPullRequestLabelMode.REMOVE, listOf("ready"), "42"),
                CnbStepRequest.ReviewPullRequest(CnbPullReviewEvent.APPROVE, "", emptyList()),
                CnbStepRequest.ReviewPullRequest(CnbPullReviewEvent.REQUEST_CHANGES, "changes required", emptyList()),
                CnbStepRequest.ReviewPullRequest(
                    CnbPullReviewEvent.COMMENT,
                    "inline review",
                    listOf(CnbPullReviewComment("fix this", "src/App.kt")),
                ),
                CnbStepRequest.MergePullRequest(CnbPullMergeStyle.SQUASH, "title", "message"),
            )

        protectedRequests.forEach { request ->
            val failure =
                assertThrows(AbortException::class.java) {
                    CnbStepDispatcher.execute(request, context, client)
                }
            assertTrue(failure.message.orEmpty().contains("no longer matches this build"))
        }
        assertEquals(emptyList<String>(), writes)
    }

    @Test
    fun `protected mutation rejects missing and abbreviated source identities before writing`() {
        fun assertRejected(
            buildSha: String?,
            currentSha: String,
            expectedMessage: String,
            expectedReads: Int,
        ) {
            var reads = 0
            var writes = 0
            val client =
                client(
                    handlers =
                        mapOf(
                            "getPullRequest" to {
                                reads++
                                pullRequest(currentSha)
                            },
                            "addPullLabel" to {
                                writes++
                                CnbLabel("ready", "ready")
                            },
                        ),
                )

            val failure =
                assertThrows(AbortException::class.java) {
                    CnbStepDispatcher.execute(
                        CnbStepRequest.PullRequestLabels(CnbPullRequestLabelMode.ADD, listOf("ready"), null),
                        context.copy(sha = buildSha),
                        client,
                    )
                }

            assertTrue(failure.message.orEmpty().contains(expectedMessage))
            assertEquals(expectedReads, reads)
            assertEquals(0, writes)
        }

        assertRejected(null, "a".repeat(40), "could not be resolved", expectedReads = 0)
        assertRejected("a".repeat(7), "a".repeat(40), "requires a full 40- or 64-character", expectedReads = 0)
        assertRejected("a".repeat(40), "a".repeat(7), "missing or non-full source SHA", expectedReads = 1)
        assertRejected("a".repeat(40), "", "missing or non-full source SHA", expectedReads = 1)
    }

    @Test
    fun `protected mutation accepts case insensitive full 40 and 64 character SHAs`() {
        listOf(40, 64).forEach { length ->
            var writes = 0
            val client =
                client(
                    handlers =
                        mapOf(
                            "getPullRequest" to { pullRequest("A".repeat(length)) },
                            "addPullLabel" to {
                                writes++
                                CnbLabel("ready", "ready")
                            },
                        ),
                )

            CnbStepDispatcher.execute(
                CnbStepRequest.PullRequestLabels(CnbPullRequestLabelMode.ADD, listOf("ready"), null),
                context.copy(sha = "a".repeat(length)),
                client,
            )

            assertEquals(1, writes)
        }
    }

    @Test
    fun `comment operation returns stable map and never needs to expose credentials`() {
        val client =
            client(
                handlers =
                    mapOf(
                        "getPullRequest" to { pullRequest("a".repeat(40)) },
                        "createPullComment" to { _ ->
                            CnbPullComment("91", "reviewed", "jenkins", "created", "updated")
                        },
                    ),
            )

        val result =
            CnbStepDispatcher.execute(
                CnbStepRequest.PullRequestComment("reviewed"),
                context,
                client,
            ) as Map<*, *>

        assertEquals("91", result["id"])
        assertEquals("reviewed", result["body"])
        assertEquals("jenkins", result["author"])
        assertFalse(result.containsKey("token"))
    }

    @Test
    fun `plain review comment also verifies the current source SHA`() {
        var reads = 0
        var review: CnbPullReviewRequest? = null
        val client =
            client(
                handlers =
                    mapOf(
                        "getPullRequest" to {
                            reads++
                            pullRequest("a".repeat(40))
                        },
                        "createPullReview" to { args ->
                            review = args?.get(2) as CnbPullReviewRequest
                            Unit
                        },
                    ),
            )

        assertTrue(
            CnbStepDispatcher.execute(
                CnbStepRequest.ReviewPullRequest(CnbPullReviewEvent.COMMENT, "general feedback", emptyList()),
                context,
                client,
            ) as Boolean,
        )
        assertEquals(1, reads)
        assertEquals("general feedback", review?.body)
    }

    @Test
    fun `label and commit status operations are read only and return Pipeline values`() {
        val calls = mutableListOf<String>()
        val client =
            client(
                handlers =
                    mapOf(
                        "listPullLabels" to { _ ->
                            calls += "labels"
                            listOf(CnbLabel("1", "ready"), CnbLabel("2", "security"))
                        },
                        "listCommitStatuses" to { _ ->
                            calls += "statuses"
                            listOf(
                                CnbCommitStatus(
                                    "ci/jenkins",
                                    CnbCommitStatusState.SUCCESS,
                                    "green",
                                    "https://jenkins.example/1",
                                ),
                            )
                        },
                    ),
            )

        assertTrue(
            CnbStepDispatcher.execute(
                CnbStepRequest.PullRequestLabelExists("security"),
                context,
                client,
            ) as Boolean,
        )
        val statuses = CnbStepDispatcher.execute(CnbStepRequest.CommitStatuses, context, client) as List<*>
        assertEquals("ci/jenkins", (statuses.single() as Map<*, *>)["context"])
        assertEquals(listOf("labels", "statuses"), calls)
    }

    @Test
    fun `badge list returns CPS safe Pipeline values`() {
        val client =
            client(
                handlers =
                    mapOf(
                        "listBadges" to {
                            listOf(
                                CnbBadgeSummary(
                                    name = "security/tca",
                                    description = "Tencent Code Analysis",
                                    type = "git",
                                    group = CnbBadgeGroup("available", "Security", "Security"),
                                    url = "https://cnb.cool/team/project/-/badge/git/latest/security/tca",
                                ),
                            )
                        },
                    ),
            )

        val result = CnbStepDispatcher.execute(CnbStepRequest.Badges, context, client) as List<*>
        val badge = result.single() as Map<*, *>
        val group = badge["group"] as Map<*, *>

        assertEquals("security/tca", badge["name"])
        assertEquals("https://cnb.cool/team/project/-/badge/git/latest/security/tca", badge["url"])
        assertEquals("Security", group["type"])
        assertFalse(badge.containsKey("token"))
    }

    @Test
    fun `badge read returns CPS safe Pipeline values`() {
        var arguments: List<Any?> = emptyList()
        val client =
            client(
                handlers =
                    mapOf(
                        "getBadge" to { args ->
                            arguments = args.orEmpty().toList()
                            CnbBadge(
                                color = "#2cbe4e",
                                label = "build",
                                message = "passing",
                                link = "https://jenkins.example/job/1",
                                links = listOf("https://jenkins.example/job/1"),
                            )
                        },
                    ),
            )

        val result =
            CnbStepDispatcher.execute(
                CnbStepRequest.Badge("security/tca", "latest", "master"),
                context,
                client,
            ) as Map<*, *>

        assertEquals(listOf("team/project", "security/tca", "latest", "master"), arguments)
        assertEquals("build", result["label"])
        assertEquals("passing", result["message"])
        assertEquals(listOf("https://jenkins.example/job/1"), result["links"])
        assertFalse(result.containsKey("token"))
    }

    @Test
    fun `badge upload uses the resolved commit and returns README URLs`() {
        var uploaded: CnbBadgeUploadRequest? = null
        val client =
            client(
                handlers =
                    mapOf(
                        "uploadBadge" to { args ->
                            uploaded = args?.get(1) as CnbBadgeUploadRequest
                            CnbBadgeUploadResult(
                                url = "https://cnb.cool/team/project/-/badge/git/${"a".repeat(40)}/security/tca",
                                latestUrl = "https://cnb.cool/team/project/-/badge/git/latest/security/tca",
                            )
                        },
                    ),
            )

        val result =
            CnbStepDispatcher.execute(
                CnbStepRequest.UploadBadge(
                    key = "security/tca",
                    message = "passing",
                    link = "https://jenkins.example/job/1",
                    latest = true,
                    value = 0,
                ),
                context,
                client,
            ) as Map<*, *>

        assertEquals("a".repeat(40), uploaded?.sha)
        assertEquals("security/tca", uploaded?.key)
        assertEquals(0L, uploaded?.value)
        assertEquals("https://cnb.cool/team/project/-/badge/git/latest/security/tca", result["latestUrl"])
        assertFalse(result.containsKey("token"))
    }

    @Test
    fun `label mutation supports add replace and individual remove with stable results`() {
        val calls = mutableListOf<String>()
        val client =
            client(
                handlers =
                    mapOf(
                        "getPullRequest" to {
                            calls += "preflight"
                            pullRequest("A".repeat(40))
                        },
                        "addPullLabel" to { args ->
                            val name = args?.get(2).toString()
                            calls += "add:$name"
                            CnbLabel(name, name)
                        },
                        "replacePullLabels" to { args ->
                            calls += "replace:${args?.get(2)}"
                            CnbLabel("set", "ready")
                        },
                        "removePullLabel" to { args ->
                            val name = args?.get(2).toString()
                            calls += "remove:$name"
                            CnbLabel(name, name)
                        },
                    ),
            )

        val added =
            CnbStepDispatcher.execute(
                CnbStepRequest.PullRequestLabels(CnbPullRequestLabelMode.ADD, listOf("ready", "security"), null),
                context,
                client,
            ) as List<*>
        assertEquals(2, added.size)
        CnbStepDispatcher.execute(
            CnbStepRequest.PullRequestLabels(CnbPullRequestLabelMode.REPLACE, listOf("only"), "42"),
            context,
            client,
        )
        CnbStepDispatcher.execute(
            CnbStepRequest.PullRequestLabels(CnbPullRequestLabelMode.REMOVE, listOf("ready", "security"), "42"),
            context,
            client,
        )
        assertEquals(
            listOf(
                "preflight",
                "add:ready",
                "add:security",
                "preflight",
                "replace:[only]",
                "preflight",
                "remove:ready",
                "remove:security",
            ),
            calls,
        )

        assertThrows(IllegalArgumentException::class.java) { CnbPullRequestLabelsStep("delete", listOf("x")) }
        assertThrows(IllegalArgumentException::class.java) { CnbPullRequestLabelsStep("remove", emptyList()) }
        assertEquals(emptyList<String>(), CnbPullRequestLabelsStep("clear", emptyList()).labels)
    }

    @Test
    fun `review and merge operations validate actions and expose explicit results`() {
        var review: CnbPullReviewRequest? = null
        val client =
            client(
                handlers =
                    mapOf(
                        "getPullRequest" to { pullRequest("A".repeat(40)) },
                        "createPullReview" to { args ->
                            review = args?.get(2) as CnbPullReviewRequest
                            Unit
                        },
                        "mergePullRequest" to { CnbMergePullResult(true, "merged", "b".repeat(40)) },
                    ),
            )

        assertEquals(
            true,
            CnbStepDispatcher.execute(
                CnbStepRequest.ReviewPullRequest(
                    CnbPullReviewEvent.REQUEST_CHANGES,
                    "please add tests",
                    listOf(CnbPullReviewComment("fix this", "src/App.kt")),
                ),
                context,
                client,
            ),
        )
        assertEquals("request_changes", review?.event?.wireValue)
        assertEquals("please add tests", review?.body)
        assertEquals("src/App.kt", review?.comments?.single()?.path)
        val merged =
            CnbStepDispatcher.execute(
                CnbStepRequest.MergePullRequest(CnbPullMergeStyle.SQUASH, "title", "message"),
                context,
                client,
            ) as Map<*, *>
        assertEquals(true, merged["merged"])
        assertEquals("b".repeat(40), merged["sha"])

        assertEquals("pending", CnbReviewPullRequestStep("pending").action)
        assertThrows(IllegalArgumentException::class.java) { CnbMergePullRequestStep("fast-forward") }

        val line = CnbPullRequestReviewComment("fix this", "src/App.kt")
        line.setSubjectType("line")
        line.setEndLine(12)
        line.setEndSide("right")
        assertEquals(12, line.toApi().endLine)
        assertThrows(IllegalArgumentException::class.java) {
            CnbPullRequestReviewComment("body", "file").apply { setSubjectType("line") }.toApi()
        }
    }

    @Test
    fun `merge false response fails the Pipeline operation`() {
        val client =
            client(
                handlers =
                    mapOf(
                        "getPullRequest" to { pullRequest("A".repeat(40)) },
                        "mergePullRequest" to { CnbMergePullResult(false, "conflict") },
                    ),
            )
        val failure =
            assertThrows(AbortException::class.java) {
                CnbStepDispatcher.execute(
                    CnbStepRequest.MergePullRequest(CnbPullMergeStyle.MERGE, "", ""),
                    context,
                    client,
                )
            }
        assertEquals("CNB did not merge the pull request: conflict", failure.message)
    }

    @Test
    fun `build operations expose stable serializable values and restrict trigger events`() {
        var started: CnbBuildRequest? = null
        val client =
            client(
                handlers =
                    mapOf(
                        "startBuild" to { args ->
                            started = args?.get(1) as CnbBuildRequest
                            CnbBuildResult("cnb-123", "https://cnb.cool/build/123", "started", true)
                        },
                        "getBuildStatus" to {
                            CnbBuildStatus(
                                CnbBuildState.RUNNING,
                                mapOf(
                                    "test" to
                                        CnbPipelineStatus(
                                            "p1",
                                            "test",
                                            CnbBuildState.RUNNING,
                                            stages = listOf(CnbBuildStage("s1", "unit", CnbBuildStageStatus.SUCCESS, 7)),
                                        ),
                                ),
                            )
                        },
                        "stopBuild" to { CnbBuildResult("cnb-123", message = "stopped", success = true) },
                    ),
            )

        val start =
            CnbStepDispatcher.execute(
                CnbStepRequest.StartBuild(
                    CnbBuildTriggerEvent("api_trigger_jenkins"),
                    "master",
                    null,
                    "Jenkins build",
                    null,
                    false,
                    mapOf("SOURCE" to "jenkins"),
                    "CodeBuddy",
                    true,
                ),
                context,
                client,
            ) as Map<*, *>
        assertEquals("cnb-123", start["sn"])
        assertEquals("api_trigger_jenkins", started?.event?.wireValue)
        assertEquals("a".repeat(40), started?.sha)
        assertEquals("jenkins", started?.env?.get("SOURCE"))
        assertEquals(CnbBuildNpcName.CODE_BUDDY, started?.npc?.name)

        val status =
            CnbStepDispatcher.execute(CnbStepRequest.BuildStatus("cnb-123"), context, client) as Map<*, *>
        assertEquals("running", status["status"])
        val pipelines = status["pipelinesStatus"] as Map<*, *>
        assertEquals("running", (pipelines["test"] as Map<*, *>)["status"])
        val stopped = CnbStepDispatcher.execute(CnbStepRequest.StopBuild("cnb-123"), context, client) as Map<*, *>
        assertEquals(true, stopped["success"])

        assertThrows(IllegalArgumentException::class.java) { CnbStartBuildStep("push") }
        assertThrows(IllegalArgumentException::class.java) { CnbBuildStatusStep(" ") }
        assertThrows(IllegalArgumentException::class.java) { CnbStopBuildStep("") }
    }

    @Test
    fun `operations have explicit validation and missing context failures`() {
        assertThrows(IllegalArgumentException::class.java) { CnbPullRequestCommentStep("   ") }
        assertThrows(IllegalArgumentException::class.java) { CnbPullRequestLabelExistsStep("") }
        assertThrows(AbortException::class.java) {
            CnbStepDispatcher.execute(
                CnbStepRequest.PullRequestComment("body"),
                context.copy(pullRequestNumber = null),
                client(),
            )
        }
        assertThrows(AbortException::class.java) {
            CnbStepDispatcher.execute(
                CnbStepRequest.CommitStatuses,
                context.copy(sha = null),
                client(),
            )
        }
    }

    @Test
    fun `descriptors publish exact Pipeline symbols and required contexts`() {
        val descriptors =
            listOf(
                CnbPullRequestCommentStep.DescriptorImpl(),
                CnbPullRequestLabelExistsStep.DescriptorImpl(),
                CnbPullRequestLabelsStep.DescriptorImpl(),
                CnbReviewPullRequestStep.DescriptorImpl(),
                CnbMergePullRequestStep.DescriptorImpl(),
                CnbCommitStatusesStep.DescriptorImpl(),
                CnbStartBuildStep.DescriptorImpl(),
                CnbBuildStatusStep.DescriptorImpl(),
                CnbStopBuildStep.DescriptorImpl(),
                CnbBadgesStep.DescriptorImpl(),
                CnbBadgeStep.DescriptorImpl(),
                CnbUploadBadgeStep.DescriptorImpl(),
            )
        assertEquals(
            listOf(
                "cnbPullRequestComment",
                "cnbPullRequestLabelExists",
                "cnbPullRequestLabels",
                "cnbReviewPullRequest",
                "cnbMergePullRequest",
                "cnbCommitStatuses",
                "cnbStartBuild",
                "cnbBuildStatus",
                "cnbStopBuild",
                "cnbBadges",
                "cnbBadge",
                "cnbUploadBadge",
            ),
            descriptors.map { it.functionName },
        )
        assertTrue(
            descriptors.all {
                it.requiredContext == setOf(Run::class.java, TaskListener::class.java, EnvVars::class.java) &&
                    !it.takesImplicitBlockArgument()
            },
        )
    }

    @Test
    fun `badge steps publish help for every Pipeline parameter`() {
        val resources =
            listOf(
                "CnbBadgesStep/help.html",
                "CnbBadgeStep/help.html",
                "CnbBadgeStep/help-badge.html",
                "CnbBadgeStep/help-revision.html",
                "CnbBadgeStep/help-branch.html",
                "CnbUploadBadgeStep/help.html",
                "CnbUploadBadgeStep/help-key.html",
                "CnbUploadBadgeStep/help-message.html",
                "CnbUploadBadgeStep/help-link.html",
                "CnbUploadBadgeStep/help-latest.html",
                "CnbUploadBadgeStep/help-value.html",
            )
        val root = "dev/zxilly/jenkins/cnb/pipeline"

        resources.forEach { resource ->
            assertTrue(javaClass.classLoader.getResource("$root/$resource") != null, resource)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun client(
        capabilities: CnbApiCapabilities = CnbApiCapabilities(),
        handlers: Map<String, (Array<out Any?>?) -> Any?> = emptyMap(),
    ): CnbClient =
        Proxy.newProxyInstance(CnbClient::class.java.classLoader, arrayOf(CnbClient::class.java)) { proxy, method, args ->
            when (method.name) {
                "getCapabilities" -> capabilities
                "close" -> Unit
                "toString" -> "PipelineTestCnbClient"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> handlers[method.name]?.invoke(args) ?: throw UnsupportedOperationException(method.name)
            }
        } as CnbClient

    private fun pullRequest(sourceSha: String): CnbPullRequest =
        CnbPullRequest(
            number = "42",
            title = "Change",
            state = CnbPullRequestState.OPEN,
            sourceRepo = "team/project",
            sourceBranch = "feature",
            sourceSha = sourceSha,
            targetRepo = "team/project",
            targetBranch = "master",
            targetSha = "c".repeat(40),
        )
}
