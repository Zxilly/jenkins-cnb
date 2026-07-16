package dev.zxilly.jenkins.cnb.api

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class CnbHttpTransportTest {
    @Test
    fun `connection resolver returns the exact address set it validated`() {
        val calls = AtomicInteger()
        val public = InetAddress.getByAddress("api.example", byteArrayOf(8, 8, 8, 8))
        val resolver =
            CnbPublicDnsResolver {
                calls.incrementAndGet()
                arrayOf(public)
            }

        val connectedAddresses = resolver.resolve("api.example")

        assertEquals(1, calls.get())
        assertArrayEquals(arrayOf(public), connectedAddresses)
    }

    @Test
    fun `connection resolver fails closed when its connection-time answer is private`() {
        val privateAddress = InetAddress.getByAddress("api.example", byteArrayOf(127, 0, 0, 1))
        val resolver = CnbPublicDnsResolver { arrayOf(privateAddress) }

        assertThrows(UnknownHostException::class.java) { resolver.resolve("api.example") }
    }
}
