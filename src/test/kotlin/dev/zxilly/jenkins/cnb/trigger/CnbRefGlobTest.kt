package dev.zxilly.jenkins.cnb.trigger

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CnbRefGlobTest {
    @Test
    fun `supports segment and recursive branch globs without regex injection`() {
        assertTrue(CnbRefGlob.matches("feature/*", "feature/one"))
        assertFalse(CnbRefGlob.matches("feature/*", "feature/team/one"))
        assertTrue(CnbRefGlob.matches("feature/**", "feature/team/one"))
        assertTrue(CnbRefGlob.matches("release/1.0", "release/1.0"))
        assertFalse(CnbRefGlob.matches("release/1.0", "release/1x0"))
    }
}
