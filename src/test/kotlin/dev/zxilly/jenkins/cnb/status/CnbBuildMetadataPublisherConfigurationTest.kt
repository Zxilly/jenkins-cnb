package dev.zxilly.jenkins.cnb.status

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbBuildMetadataPublisherConfigurationTest {
    @Test
    fun `freestyle publisher survives a configuration round trip`(jenkins: JenkinsRule) {
        val project = jenkins.createFreeStyleProject()
        val publisher = CnbBuildMetadataPublisher()
        publisher.setServerId("cnb-cool")
        publisher.setRepository("acme/application")
        publisher.setCommitRepository("acme/application-fork")
        publisher.setSha("0123456789abcdef0123456789abcdef01234567")
        publisher.setPullRequestNumber("42")
        publisher.setContext("jenkins/freestyle")
        project.publishersList.add(publisher)

        jenkins.configRoundtrip(project)

        val reloaded = requireNotNull(project.publishersList.get(CnbBuildMetadataPublisher::class.java))
        assertEquals("cnb-cool", reloaded.serverId)
        assertEquals("acme/application", reloaded.repository)
        assertEquals("acme/application-fork", reloaded.commitRepository)
        assertEquals("0123456789abcdef0123456789abcdef01234567", reloaded.sha)
        assertEquals("42", reloaded.pullRequestNumber)
        assertEquals("jenkins/freestyle", reloaded.context)
    }
}
