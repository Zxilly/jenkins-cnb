package dev.zxilly.jenkins.cnb.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CnbEndpointPolicyTest {
    @Test
    fun `normalizes a public HTTPS endpoint`() {
        val uri = CnbEndpointPolicy.validateAndNormalize("https://API.CNB.COOL:443/", false, true)
        assertEquals("https://api.cnb.cool", uri.toString())
    }

    @Test
    fun `rejects insecure HTTP by default`() {
        assertThrows(IllegalArgumentException::class.java) {
            CnbEndpointPolicy.validateAndNormalize("http://api.cnb.cool", false, false)
        }
    }

    @Test
    fun `rejects loopback and private endpoints by default`() {
        assertThrows(IllegalArgumentException::class.java) {
            CnbEndpointPolicy.validateAndNormalize("https://127.0.0.1", false, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CnbEndpointPolicy.validateAndNormalize("https://10.0.0.1", false, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CnbEndpointPolicy.validateAndNormalize("https://100.64.0.1", false, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CnbEndpointPolicy.validateAndNormalize("https://[fd00::1]", false, false)
        }
    }

    @Test
    fun `allows an explicitly configured private HTTP endpoint`() {
        val uri = CnbEndpointPolicy.validateAndNormalize("http://127.0.0.1:8080", true, true)
        assertEquals("http://127.0.0.1:8080", uri.toString())
    }

    @Test
    fun `rejects user info paths queries and fragments`() {
        listOf(
            "https://user:pass@api.cnb.cool",
            "https://api.cnb.cool/openapi",
            "https://api.cnb.cool?redirect=https://evil.example",
            "https://api.cnb.cool#fragment",
        ).forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                CnbEndpointPolicy.validateAndNormalize(endpoint, false, false)
            }
        }
    }
}
