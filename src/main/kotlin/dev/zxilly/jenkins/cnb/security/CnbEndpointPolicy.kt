package dev.zxilly.jenkins.cnb.security

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.util.Locale

object CnbEndpointPolicy {
    fun validateAndNormalize(
        value: String,
        allowInsecureHttp: Boolean,
        allowPrivateNetwork: Boolean,
    ): URI {
        val raw = value.trim().removeSuffix("/")
        require(raw.isNotEmpty()) { "Endpoint must not be empty" }
        val uri = URI(raw).normalize()
        require(uri.isAbsolute && !uri.host.isNullOrBlank()) { "Endpoint must be an absolute URL with a host" }
        require(uri.rawUserInfo == null) { "Endpoint must not contain user information" }
        require(uri.rawQuery == null && uri.rawFragment == null) { "Endpoint must not contain a query or fragment" }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Endpoint must not contain a path" }

        val scheme = uri.scheme.lowercase(Locale.ROOT)
        require(scheme == "https" || (scheme == "http" && allowInsecureHttp)) {
            "Endpoint must use HTTPS"
        }

        if (!allowPrivateNetwork) {
            validatePublicAddress(uri.host)
        }

        val port =
            when {
                uri.port >= 0 -> uri.port
                scheme == "https" -> 443
                else -> 80
            }
        val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
        return URI(scheme, null, uri.host.lowercase(Locale.ROOT), if (defaultPort) -1 else port, null, null, null)
    }

    fun validatePublicAddress(host: String) {
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty()) { "Endpoint host did not resolve" }
        for (address in addresses) {
            require(
                !address.isAnyLocalAddress &&
                    !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress &&
                    !address.isSiteLocalAddress &&
                    !address.isMulticastAddress &&
                    isPublicUnicast(address),
            ) {
                "Endpoint resolves to a local or private address"
            }
        }
    }

    /** Rejects IANA special-use space not covered by the JDK's legacy address predicates. */
    private fun isPublicUnicast(address: InetAddress): Boolean =
        when (address) {
            is Inet4Address -> isPublicIpv4(address.address)
            is Inet6Address -> isPublicIpv6(address.address)
            else -> false
        }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        val third = bytes[2].toInt() and 0xff
        return when {
            first == 0 || first == 10 || first == 127 -> false

            first == 100 && second in 64..127 -> false

            // shared address space (CGNAT)
            first == 169 && second == 254 -> false

            first == 172 && second in 16..31 -> false

            first == 192 && second == 0 && third in setOf(0, 2) -> false

            first == 192 && second == 88 && third == 99 -> false

            first == 192 && second == 168 -> false

            first == 198 && second in 18..19 -> false

            first == 198 && second == 51 && third == 100 -> false

            first == 203 && second == 0 && third == 113 -> false

            first >= 224 -> false

            else -> true
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        // Public IPv6 unicast is 2000::/3. Explicitly remove transition/documentation ranges
        // inside that block which can tunnel to non-public IPv4 or are never routable.
        if ((bytes[0].toInt() and 0xe0) != 0x20) return false
        val first16 = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
        val second16 = ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
        return when {
            first16 == 0x2001 && second16 < 0x0200 -> false

            // 2001::/23 special-purpose
            first16 == 0x2001 && second16 == 0x0db8 -> false

            // documentation
            first16 == 0x2002 -> false

            // 6to4 embeds an arbitrary IPv4 destination
            first16 == 0x3fff && second16 < 0x1000 -> false

            // documentation 3fff::/20
            else -> true
        }
    }
}
