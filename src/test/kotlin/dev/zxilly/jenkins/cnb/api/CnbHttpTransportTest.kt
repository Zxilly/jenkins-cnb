package dev.zxilly.jenkins.cnb.api

import com.sun.net.httpserver.HttpServer
import dev.zxilly.jenkins.cnb.config.CnbServer
import hudson.ProxyConfiguration
import io.ktor.util.reflect.typeInfo
import kotlinx.serialization.Serializable
import org.apache.hc.client5.http.protocol.HttpClientContext
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class CnbHttpTransportTest {
    @Test
    fun `transport headers preserve repeated values case insensitively`() {
        val headers =
            CnbHttpHeaders.of(
                "Location" to "https://first.example/object",
                "location" to "https://second.example/object",
                "ETag" to "one",
            )

        assertEquals(
            listOf("https://first.example/object", "https://second.example/object"),
            headers.allValues("LOCATION"),
        )
        assertEquals("one", headers.firstValue("etag"))
        assertEquals(emptyList<String>(), headers.allValues("missing"))
    }

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

    @Test
    fun `bounded response reader fails when bytes exceed its limit`() {
        withTransport(responseBody = "123456".toByteArray(StandardCharsets.UTF_8)) { transport, uri ->
            val failure =
                assertThrows(CnbApiException::class.java) {
                    transport.execute(CnbHttpRequest("GET", uri)) { response -> response.readBoundedBytes(5) }
                }

            assertEquals("CNB response exceeded 5 bytes", failure.message)
        }
    }

    @Test
    fun `typed response decodes application json with unknown fields`() {
        assertTypedJsonResponse("application/json")
    }

    @Test
    fun `typed response decodes CNB vendor json with unknown fields`() {
        assertTypedJsonResponse(CNB_MEDIA_TYPE)
    }

    @Test
    fun `typed response rejects json missing a required field`() {
        val body = """{"future_field":true}""".toByteArray(StandardCharsets.UTF_8)
        withTransport(responseBody = body, responseContentType = "application/json") { transport, uri ->
            assertThrows(CnbApiException::class.java) {
                transport.execute(CnbHttpRequest("GET", uri)) { context ->
                    context.readJson<TypedResponse>(typeInfo<TypedResponse>())
                }
            }
        }
    }

    @Test
    fun `typed response rejects a non json media type`() {
        val body = """{"name":"CNB"}""".toByteArray(StandardCharsets.UTF_8)
        withTransport(responseBody = body, responseContentType = "text/plain") { transport, uri ->
            assertThrows(CnbApiException::class.java) {
                transport.execute(CnbHttpRequest("GET", uri)) { context ->
                    context.readJson<TypedResponse>(typeInfo<TypedResponse>())
                }
            }
        }
    }

    @Test
    fun `typed response rejects a missing media type`() {
        val body = """{"name":"CNB"}""".toByteArray(StandardCharsets.UTF_8)
        withTransport(responseBody = body) { transport, uri ->
            assertThrows(CnbApiException::class.java) {
                transport.execute(CnbHttpRequest("GET", uri)) { context ->
                    context.readJson<TypedResponse>(typeInfo<TypedResponse>())
                }
            }
        }
    }

    @Test
    fun `external request does not advertise CNB JSON negotiation`() {
        val acceptValues = AtomicReference<List<String>>(emptyList())
        withTransport(
            responseBody = ByteArray(0),
            requestAccept = acceptValues::set,
        ) { transport, uri ->
            val response =
                transport.execute(CnbHttpRequest("GET", uri, negotiateCnbJson = false)) { context ->
                    context.readBytes()
                }

            assertEquals(200, response.statusCode)
        }

        val advertised = acceptValues.get().joinToString(",").lowercase()
        assertFalse(advertised.contains("application/json"))
        assertFalse(advertised.contains(CNB_MEDIA_TYPE))
    }

    @Test
    fun `streaming response closes target when declared length does not match`() {
        withTransport(responseBody = "abc".toByteArray(StandardCharsets.UTF_8)) { transport, uri ->
            val closed = AtomicBoolean()
            val target =
                object : ByteArrayOutputStream() {
                    override fun close() {
                        closed.set(true)
                        super.close()
                    }
                }

            assertThrows(CnbApiException::class.java) {
                transport.execute(CnbHttpRequest("GET", uri)) { response ->
                    response.streamTo(maxBytes = 10, declaredLength = 4, openTarget = { target })
                }
            }

            assertTrue(closed.get())
            assertArrayEquals("abc".toByteArray(StandardCharsets.UTF_8), target.toByteArray())
        }
    }

    @Test
    fun `repeatable request body streams exactly its declared bytes`() {
        val received = arrayOfNulls<ByteArray>(1)
        val opened = AtomicInteger()
        withTransport(
            responseBody = ByteArray(0),
            requestBody = { bytes -> received[0] = bytes },
        ) { transport, uri ->
            val response =
                transport.execute(
                    CnbHttpRequest(
                        method = "PUT",
                        uri = uri,
                        body =
                            CnbHttpRequestBody.RepeatableStream(3) {
                                opened.incrementAndGet()
                                ByteArrayInputStream(byteArrayOf(1, 2, 3))
                            },
                    ),
                ) { it.readBytes() }

            assertEquals(200, response.statusCode)
            assertEquals(listOf("one", "two"), response.headers.allValues("x-cnb-value").sorted())
            assertEquals(1, opened.get())
            assertArrayEquals(byteArrayOf(1, 2, 3), received[0])
        }
    }

    @Test
    fun `Jenkins proxy planner honors no proxy hosts per target`() {
        val configuration = ProxyConfiguration("127.0.0.1", 8888).apply { setNoProxyHost("localhost") }
        val planner = CnbJenkinsProxyRoutePlanner(configuration)

        val direct = planner.determineRoute(HttpHost("http", "localhost", 8080), HttpClientContext.create())
        val proxied = planner.determineRoute(HttpHost("https", "cnb.cool", 443), HttpClientContext.create())

        assertNull(direct.proxyHost)
        assertEquals(8080, direct.targetHost.port)
        assertEquals("127.0.0.1", proxied.proxyHost.hostName)
        assertEquals(8888, proxied.proxyHost.port)
        assertEquals(443, proxied.targetHost.port)
        assertTrue(proxied.isTunnelled)
    }

    @Test
    fun `public transport never delegates target resolution to Jenkins proxy`() {
        val proxy = ProxyConfiguration("127.0.0.1", 8888)
        val publicServer = privateServer("https://cnb.cool").apply { setAllowPrivateNetwork(false) }
        val privateServer = privateServer("https://cnb.example.internal")

        assertNull(CnbHttpTransportFactory.proxyConfigurationFor(publicServer, proxy))
        assertSame(proxy, CnbHttpTransportFactory.proxyConfigurationFor(privateServer, proxy))
    }

    @Test
    fun `private transport authenticates only to the configured Jenkins proxy`() {
        val attempts = AtomicInteger()
        val requestTarget = AtomicReference<URI>()
        val proxyAuthorization = AtomicReference<String>()
        val proxyServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        proxyServer.createContext("/") { exchange ->
            attempts.incrementAndGet()
            requestTarget.set(exchange.requestURI)
            val authorization = exchange.requestHeaders.getFirst("Proxy-Authorization")
            if (authorization == null) {
                exchange.responseHeaders.add("Proxy-Authenticate", "Basic realm=\"jenkins\"")
                exchange.sendResponseHeaders(407, -1)
                exchange.close()
            } else {
                proxyAuthorization.set(authorization)
                val body = "proxied".toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        proxyServer.start()
        val proxy =
            ProxyConfiguration(
                proxyServer.address.hostString,
                proxyServer.address.port,
                "proxy-user",
                "proxy-password",
            )
        val target = URI("http://cnb.example.internal/resource")
        val transport = CnbHttpTransportFactory.create(privateServer("http://cnb.example.internal"), proxy)
        try {
            val response = transport.execute(CnbHttpRequest("GET", target)) { it.readBytes() }

            assertEquals(200, response.statusCode)
            assertEquals("proxied", response.body.toString(StandardCharsets.UTF_8))
            assertEquals(target, requestTarget.get())
            assertEquals(2, attempts.get())
            val expected = Base64.getEncoder().encodeToString("proxy-user:proxy-password".toByteArray(StandardCharsets.UTF_8))
            assertEquals("Basic $expected", proxyAuthorization.get())
        } finally {
            transport.close()
            proxyServer.stop(0)
        }
    }

    @Test
    fun `private transport preauthenticates a body request to its Jenkins proxy`() {
        val attempts = AtomicInteger()
        val proxyAuthorization = AtomicReference<String>()
        val received = AtomicReference<ByteArray>()
        val proxyServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        proxyServer.createContext("/") { exchange ->
            attempts.incrementAndGet()
            proxyAuthorization.set(exchange.requestHeaders.getFirst("Proxy-Authorization"))
            received.set(exchange.requestBody.readAllBytes())
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        proxyServer.start()
        val proxy =
            ProxyConfiguration(
                proxyServer.address.hostString,
                proxyServer.address.port,
                "proxy-user",
                "proxy-password",
            )
        val target = URI("http://cnb.example.internal/resource")
        val transport = CnbHttpTransportFactory.create(privateServer("http://cnb.example.internal"), proxy)
        try {
            val response =
                transport.execute(
                    CnbHttpRequest("PUT", target, body = CnbHttpRequestBody.Bytes("payload".toByteArray())),
                ) { it.readBytes() }

            assertEquals(204, response.statusCode)
            assertEquals(1, attempts.get())
            assertArrayEquals("payload".toByteArray(), received.get())
            val expected = Base64.getEncoder().encodeToString("proxy-user:proxy-password".toByteArray(StandardCharsets.UTF_8))
            assertEquals("Basic $expected", proxyAuthorization.get())
        } finally {
            transport.close()
            proxyServer.stop(0)
        }
    }

    @Test
    fun `private transport does not leak proxy credentials to a no proxy target`() {
        val targetProxyAuthorization = AtomicReference<String?>()
        val targetBody = AtomicReference<ByteArray>()
        val proxyAttempts = AtomicInteger()
        val targetServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        targetServer.createContext("/direct") { exchange ->
            targetProxyAuthorization.set(exchange.requestHeaders.getFirst("Proxy-Authorization"))
            targetBody.set(exchange.requestBody.readAllBytes())
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        val proxyServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        proxyServer.createContext("/") { exchange ->
            proxyAttempts.incrementAndGet()
            exchange.sendResponseHeaders(502, -1)
            exchange.close()
        }
        targetServer.start()
        proxyServer.start()
        val proxy =
            ProxyConfiguration(
                proxyServer.address.hostString,
                proxyServer.address.port,
                "proxy-user",
                "proxy-password",
            ).apply { setNoProxyHost("localhost") }
        val baseUrl = "http://localhost:${targetServer.address.port}"
        val transport = CnbHttpTransportFactory.create(privateServer(baseUrl), proxy)
        try {
            val response =
                transport.execute(
                    CnbHttpRequest(
                        "PUT",
                        URI("$baseUrl/direct"),
                        body = CnbHttpRequestBody.Bytes("direct-body".toByteArray(StandardCharsets.UTF_8)),
                    ),
                ) { it.readBytes() }

            assertEquals(204, response.statusCode)
            assertEquals(0, proxyAttempts.get())
            assertNull(targetProxyAuthorization.get())
            assertArrayEquals("direct-body".toByteArray(StandardCharsets.UTF_8), targetBody.get())
        } finally {
            transport.close()
            proxyServer.stop(0)
            targetServer.stop(0)
        }
    }

    @Test
    fun `request deadline closes an upload source that ignores interrupts`() {
        val source = CloseReleasedInputStream()
        val httpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        httpServer.createContext("/upload") { exchange ->
            try {
                exchange.requestBody.use { it.readAllBytes() }
                exchange.sendResponseHeaders(204, -1)
            } finally {
                exchange.close()
            }
        }
        httpServer.start()
        val baseUrl = "http://${httpServer.address.hostString}:${httpServer.address.port}"
        val configured = privateServer(baseUrl).apply { setRequestTimeoutSeconds(1) }
        val transport = CnbHttpTransportFactory.create(configured, proxyConfiguration = null)
        try {
            val startedAt = System.nanoTime()
            assertThrows(SocketTimeoutException::class.java) {
                transport.execute(
                    CnbHttpRequest(
                        "PUT",
                        URI("$baseUrl/upload"),
                        body = CnbHttpRequestBody.RepeatableStream(1) { source },
                    ),
                ) { it.readBytes() }
            }

            assertTrue(source.readStarted.await(1, TimeUnit.SECONDS))
            assertTrue(source.closed.get())
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt) < 4)
        } finally {
            transport.close()
            httpServer.stop(0)
        }
    }

    @Test
    fun `request deadline does not wait for an upload source that blocks while opening`() {
        val openStarted = CountDownLatch(1)
        val releaseOpen = AtomicBoolean()
        val lateStreamClosed = CountDownLatch(1)
        val lateStream =
            object : ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() {
                    lateStreamClosed.countDown()
                    super.close()
                }
            }
        val httpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        httpServer.createContext("/upload") { exchange ->
            runCatching { exchange.requestBody.use { it.readAllBytes() } }
            exchange.close()
        }
        httpServer.start()
        val baseUrl = "http://${httpServer.address.hostString}:${httpServer.address.port}"
        val configured = privateServer(baseUrl).apply { setRequestTimeoutSeconds(1) }
        val transport = CnbHttpTransportFactory.create(configured, proxyConfiguration = null)
        try {
            val startedAt = System.nanoTime()
            assertThrows(SocketTimeoutException::class.java) {
                transport.execute(
                    CnbHttpRequest(
                        "PUT",
                        URI("$baseUrl/upload"),
                        body =
                            CnbHttpRequestBody.RepeatableStream(1) {
                                openStarted.countDown()
                                while (!releaseOpen.get()) {
                                    try {
                                        Thread.sleep(10)
                                    } catch (_: InterruptedException) {
                                        // Simulates a Remoting call that cannot be interrupted while opening.
                                    }
                                }
                                lateStream
                            },
                    ),
                ) { it.readBytes() }
            }

            assertTrue(openStarted.await(1, TimeUnit.SECONDS))
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(elapsedMillis < 4_000, "blocking source open exceeded the request deadline: ${elapsedMillis}ms")
            releaseOpen.set(true)
            assertTrue(lateStreamClosed.await(2, TimeUnit.SECONDS))
        } finally {
            releaseOpen.set(true)
            transport.close()
            httpServer.stop(0)
        }
    }

    @Test
    fun `request deadline fails closed while a download target is still opening`() {
        val openStarted = CountDownLatch(1)
        val releaseOpen = AtomicBoolean()
        val lateTargetClosed = CountDownLatch(1)
        val lateTargetCloseCalls = AtomicInteger()
        val lateTargetWriteCalls = AtomicInteger()
        val lateTarget =
            object : ByteArrayOutputStream() {
                override fun write(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ) {
                    lateTargetWriteCalls.incrementAndGet()
                    super.write(buffer, offset, length)
                }

                override fun close() {
                    lateTargetCloseCalls.incrementAndGet()
                    lateTargetClosed.countDown()
                    super.close()
                }
            }
        val body = "abc".toByteArray(StandardCharsets.UTF_8)
        withTransport(responseBody = body, requestTimeoutSeconds = 1) { transport, uri ->
            try {
                val startedAt = System.nanoTime()
                val failure =
                    assertThrows(CnbApiException::class.java) {
                        transport.execute(CnbHttpRequest("GET", uri)) { response ->
                            response.streamTo(10, body.size.toLong()) {
                                openStarted.countDown()
                                while (!releaseOpen.get()) {
                                    try {
                                        Thread.sleep(10)
                                    } catch (_: InterruptedException) {
                                        // Simulates a Remoting target open that cannot be interrupted.
                                    }
                                }
                                lateTarget
                            }
                        }
                    }

                assertTrue(openStarted.await(1, TimeUnit.SECONDS))
                assertFalse(failure.retryable)
                assertTrue(failure.message.orEmpty().contains("cleanup exceeded"))
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                assertTrue(elapsedMillis in 2_500..5_000, "pending target open was not bounded: ${elapsedMillis}ms")

                releaseOpen.set(true)
                assertTrue(lateTargetClosed.await(2, TimeUnit.SECONDS))
                assertEquals(1, lateTargetCloseCalls.get())
                assertEquals(0, lateTargetWriteCalls.get())
            } finally {
                releaseOpen.set(true)
            }
        }
    }

    @Test
    fun `request deadline closes a download target that ignores interrupts`() {
        val target = CloseReleasedOutputStream()
        val body = "abc".toByteArray(StandardCharsets.UTF_8)
        withTransport(responseBody = body, requestTimeoutSeconds = 1) { transport, uri ->
            val startedAt = System.nanoTime()

            assertThrows(SocketTimeoutException::class.java) {
                transport.execute(CnbHttpRequest("GET", uri)) { response ->
                    response.streamTo(10, body.size.toLong()) { target }
                }
            }

            assertTrue(target.writeStarted.await(1, TimeUnit.SECONDS))
            assertTrue(target.closed.get())
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            assertTrue(elapsedMillis < 4_000, "blocking download exceeded the request deadline: ${elapsedMillis}ms")
        }
    }

    @Test
    fun `request deadline bounds a download target whose close also blocks`() {
        val target = BlockingCloseOutputStream()
        val markerClassLoader = object : ClassLoader(Thread.currentThread().contextClassLoader) {}
        val body = "abc".toByteArray(StandardCharsets.UTF_8)
        withTransport(responseBody = body, requestTimeoutSeconds = 1) { transport, uri ->
            try {
                val originalClassLoader = Thread.currentThread().contextClassLoader
                val startedAt = System.nanoTime()
                val failure =
                    try {
                        Thread.currentThread().contextClassLoader = markerClassLoader
                        assertThrows(CnbApiException::class.java) {
                            transport.execute(CnbHttpRequest("GET", uri)) { response ->
                                response.streamTo(10, body.size.toLong()) { target }
                            }
                        }
                    } finally {
                        Thread.currentThread().contextClassLoader = originalClassLoader
                    }

                assertTrue(target.writeStarted.await(1, TimeUnit.SECONDS))
                assertTrue(target.closeStarted.await(1, TimeUnit.SECONDS))
                assertFalse(failure.retryable)
                assertTrue(failure.message.orEmpty().contains("cleanup exceeded"))
                val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                assertTrue(elapsedMillis in 2_500..5_000, "blocking close was not bounded: ${elapsedMillis}ms")
                val closeThreads =
                    Thread.getAllStackTraces().keys.filter { thread ->
                        thread.name.contains("KtorCnbHttpTransport.resource-close") && thread.isAlive
                    }
                assertTrue(closeThreads.isNotEmpty())
                assertTrue(closeThreads.all(Thread::isDaemon))
                assertTrue(closeThreads.none { it.contextClassLoader === markerClassLoader })
            } finally {
                target.release.set(true)
                assertTrue(target.closed.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `fresh close executor creates a classloader safe daemon worker`() {
        val closeExecutor = CnbResourceCloseExecutor(maxConcurrency = 1)
        val markerClassLoader = object : ClassLoader(Thread.currentThread().contextClassLoader) {}
        val worker = AtomicReference<Thread>()
        val closeStarted = CountDownLatch(1)
        val releaseClose = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val tracked =
            CnbTrackedCloseable(
                Closeable {
                    worker.set(Thread.currentThread())
                    closeStarted.countDown()
                    releaseClose.await()
                    closed.countDown()
                },
                closeExecutor,
            )
        val originalClassLoader = Thread.currentThread().contextClassLoader
        try {
            try {
                Thread.currentThread().contextClassLoader = markerClassLoader
                tracked.requestClose()
                assertTrue(closeStarted.await(1, TimeUnit.SECONDS))

                val closeThread = worker.get()
                assertTrue(closeThread.isDaemon)
                assertFalse(closeThread.contextClassLoader === markerClassLoader)
            } finally {
                Thread.currentThread().contextClassLoader = originalClassLoader
                releaseClose.countDown()
            }
            assertNull(tracked.awaitClose(System.nanoTime() + TimeUnit.SECONDS.toNanos(2)))
            assertTrue(closed.await(1, TimeUnit.SECONDS))
        } finally {
            releaseClose.countDown()
            closeExecutor.close()
        }
    }

    @Test
    fun `saturated close executor fails closed without starting another worker`() {
        val closeExecutor = CnbResourceCloseExecutor(maxConcurrency = 1)
        val firstCloseStarted = CountDownLatch(1)
        val releaseFirstClose = CountDownLatch(1)
        val firstClosed = CountDownLatch(1)
        val first =
            CnbTrackedCloseable(
                Closeable {
                    firstCloseStarted.countDown()
                    releaseFirstClose.await()
                    firstClosed.countDown()
                },
                closeExecutor,
            )
        val secondCloseCalls = AtomicInteger()
        val second = CnbTrackedCloseable(Closeable { secondCloseCalls.incrementAndGet() }, closeExecutor)
        var firstCloseCompleted = false
        try {
            first.requestClose()
            assertTrue(firstCloseStarted.await(1, TimeUnit.SECONDS))

            second.requestClose()
            val failure = second.awaitClose(System.nanoTime() + TimeUnit.SECONDS.toNanos(1))

            assertTrue(failure is CnbApiException)
            assertTrue(failure?.cause is RejectedExecutionException)
            assertEquals(0, secondCloseCalls.get())
        } finally {
            releaseFirstClose.countDown()
            firstCloseCompleted = firstClosed.await(2, TimeUnit.SECONDS)
            closeExecutor.close()
        }
        assertTrue(firstCloseCompleted)
        assertNull(first.awaitClose(System.nanoTime() + TimeUnit.SECONDS.toNanos(1)))
    }

    @Test
    fun `close releases a blocking download target before stopping Ktor threads`() {
        val existingThreadIds = Thread.getAllStackTraces().keys.mapTo(HashSet(), Thread::getId)
        val markerClassLoader = object : ClassLoader(Thread.currentThread().contextClassLoader) {}
        val target = CloseReleasedOutputStream()
        val httpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val body = "abc".toByteArray(StandardCharsets.UTF_8)
        httpServer.createContext("/download") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        httpServer.start()
        val baseUrl = "http://${httpServer.address.hostString}:${httpServer.address.port}"
        val transport = CnbHttpTransportFactory.create(privateServer(baseUrl), proxyConfiguration = null)
        val caller =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "cnb-http-test-caller").apply {
                    isDaemon = true
                    contextClassLoader = markerClassLoader
                }
            }
        try {
            val request =
                caller.submit {
                    runCatching {
                        transport.execute(CnbHttpRequest("GET", URI("$baseUrl/download"))) { response ->
                            response.streamTo(10, body.size.toLong()) { target }
                        }
                    }
                }
            assertTrue(target.writeStarted.await(2, TimeUnit.SECONDS))

            val startedAt = System.nanoTime()
            val transportThreads = newTransportThreads(existingThreadIds)
            assertTrue(transportThreads.isNotEmpty())
            assertTrue(transportThreads.all(Thread::isDaemon))
            assertTrue(transportThreads.none { it.contextClassLoader === markerClassLoader })
            transport.close()

            request.get(3, TimeUnit.SECONDS)
            assertTrue(target.closed.get())
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt) < 4)
            assertTrue(awaitCondition(2, TimeUnit.SECONDS) { newTransportThreads(existingThreadIds).none(Thread::isAlive) })
        } finally {
            transport.close()
            caller.shutdownNow()
            httpServer.stop(0)
        }
    }

    @Test
    fun `closed transport rejects new requests`() {
        val server = privateServer("http://127.0.0.1:1")
        val transport = CnbHttpTransportFactory.create(server, proxyConfiguration = null)
        transport.close()

        assertThrows(IOException::class.java) {
            transport.execute(CnbHttpRequest("GET", URI("http://127.0.0.1:1/test"))) { it.readBytes() }
        }
    }

    private fun withTransport(
        responseBody: ByteArray,
        responseContentType: String? = null,
        requestTimeoutSeconds: Int = 5,
        requestBody: (ByteArray) -> Unit = {},
        requestAccept: (List<String>) -> Unit = {},
        test: (CnbHttpTransport, URI) -> Unit,
    ) {
        val httpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        httpServer.createContext("/test") { exchange ->
            requestAccept(exchange.requestHeaders["Accept"].orEmpty().toList())
            exchange.requestBody.use { input -> requestBody(input.readAllBytes()) }
            exchange.responseHeaders.add("X-CNB-Value", "one")
            exchange.responseHeaders.add("X-CNB-Value", "two")
            responseContentType?.let { exchange.responseHeaders.add("Content-Type", it) }
            exchange.sendResponseHeaders(200, responseBody.size.toLong())
            exchange.responseBody.use { output -> output.write(responseBody) }
        }
        httpServer.start()
        val baseUrl = "http://${httpServer.address.hostString}:${httpServer.address.port}"
        val configured = privateServer(baseUrl).apply { setRequestTimeoutSeconds(requestTimeoutSeconds) }
        val transport = CnbHttpTransportFactory.create(configured, proxyConfiguration = null)
        try {
            test(transport, URI("$baseUrl/test"))
        } finally {
            transport.close()
            httpServer.stop(0)
        }
    }

    private fun assertTypedJsonResponse(contentType: String) {
        val body = """{"name":"CNB","future_field":true}""".toByteArray(StandardCharsets.UTF_8)
        withTransport(responseBody = body, responseContentType = contentType) { transport, uri ->
            val response =
                transport.execute(CnbHttpRequest("GET", uri)) { context ->
                    context.readJson<TypedResponse>(typeInfo<TypedResponse>())
                }

            assertEquals(TypedResponse("CNB"), response.body)
        }
    }

    private fun newTransportThreads(existingThreadIds: Set<Long>): List<Thread> =
        Thread.getAllStackTraces().keys.filter { thread ->
            thread.id !in existingThreadIds &&
                thread.name.startsWith("dev.zxilly.jenkins.cnb.api.KtorCnbHttpTransport") &&
                !thread.name.contains(".resource-close")
        }

    private fun awaitCondition(
        timeout: Long,
        unit: TimeUnit,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        do {
            if (condition()) return true
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        return condition()
    }

    private fun privateServer(baseUrl: String): CnbServer =
        CnbServer("test", "Test", baseUrl, baseUrl).apply {
            setAllowInsecureHttp(true)
            setAllowPrivateNetwork(true)
            setConnectTimeoutSeconds(2)
            setRequestTimeoutSeconds(5)
        }

    private class CloseReleasedInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val closed = AtomicBoolean()

        override fun read(): Int = waitUntilClosed()

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = waitUntilClosed()

        override fun close() {
            closed.set(true)
        }

        private fun waitUntilClosed(): Int {
            readStarted.countDown()
            while (!closed.get()) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    // Deliberately ignore interrupts to prove cancellation also closes the stream.
                }
            }
            return -1
        }
    }

    private class CloseReleasedOutputStream : ByteArrayOutputStream() {
        val writeStarted = CountDownLatch(1)
        val closed = AtomicBoolean()

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writeStarted.countDown()
            while (!closed.get()) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    // Deliberately ignore interrupts to prove cancellation also closes the stream.
                }
            }
        }

        override fun close() {
            closed.set(true)
        }
    }

    private class BlockingCloseOutputStream : ByteArrayOutputStream() {
        val writeStarted = CountDownLatch(1)
        val closeStarted = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val release = AtomicBoolean()

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            writeStarted.countDown()
            waitForRelease()
            super.write(buffer, offset, length)
        }

        override fun close() {
            closeStarted.countDown()
            waitForRelease()
            try {
                super.close()
            } finally {
                closed.countDown()
            }
        }

        private fun waitForRelease() {
            while (!release.get()) {
                try {
                    Thread.sleep(10)
                } catch (_: InterruptedException) {
                    // Simulates a Remoting stream that ignores interruption in both write and close.
                }
            }
        }
    }

    @Serializable
    private data class TypedResponse(
        val name: String,
    )
}
