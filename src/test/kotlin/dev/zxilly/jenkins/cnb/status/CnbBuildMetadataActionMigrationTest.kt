package dev.zxilly.jenkins.cnb.status

import jenkins.model.Jenkins
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.jvnet.hudson.test.JenkinsRule
import org.jvnet.hudson.test.junit.jupiter.WithJenkins

@WithJenkins
class CnbBuildMetadataActionMigrationTest {
    @Test
    fun `legacy action without marker token loads with a fresh durable marker`(jenkins: JenkinsRule) {
        val action = CnbBuildMetadataAction("legacy:${jenkins.jenkins.fullName}")
        val currentXml = Jenkins.XSTREAM2.toXML(action)
        val legacyXml = currentXml.replace(Regex("<markerToken>.*?</markerToken>"), "")
        assertNotEquals(currentXml, legacyXml)

        val restored = Jenkins.XSTREAM2.fromXML(legacyXml) as CnbBuildMetadataAction

        assertFalse(restored.dispatchKey().isBlank())
    }
}
