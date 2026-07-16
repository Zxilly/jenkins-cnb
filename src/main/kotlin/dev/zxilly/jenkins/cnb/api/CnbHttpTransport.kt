package dev.zxilly.jenkins.cnb.api

import dev.zxilly.jenkins.cnb.api.wire.CnbJsonCodec
import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.security.CnbEndpointPolicy
import hudson.ProxyConfiguration
import hudson.util.ClassLoaderSanityThreadFactory
import hudson.util.DaemonThreadFactory
import hudson.util.NamingThreadFactory
import io.ktor.client.HttpClient
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.engine.apache5.Apache5
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentConverterException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.contentnegotiation.exclude
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.utils.EmptyContent
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writeFully
import jenkins.model.Jenkins
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.apache.hc.client5.http.DnsResolver
import org.apache.hc.client5.http.HttpRoute
import org.apache.hc.client5.http.auth.AuthScope
import org.apache.hc.client5.http.auth.Credentials
import org.apache.hc.client5.http.auth.CredentialsProvider
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials
import org.apache.hc.client5.http.impl.DefaultSchemePortResolver
import org.apache.hc.client5.http.impl.auth.BasicScheme
import org.apache.hc.client5.http.protocol.HttpClientContext
import org.apache.hc.client5.http.routing.HttpRoutePlanner
import org.apache.hc.client5.http.routing.RoutingSupport
import org.apache.hc.core5.http.HttpException
import org.apache.hc.core5.http.HttpHost
import org.apache.hc.core5.http.HttpRequest
import org.apache.hc.core5.http.protocol.HttpContext
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.channels.ClosedSelectorException
import java.util.Arrays
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import org.apache.hc.core5.http.HttpHeaders as ApacheHttpHeaders

/** The typed HTTP seam used by [HttpCnbClient]. */
internal interface CnbHttpTransport : AutoCloseable {
    @Throws(IOException::class, InterruptedException::class)
    fun <T> execute(
        request: CnbHttpRequest,
        reader: CnbHttpResponseReader<T>,
    ): CnbHttpResponse<T>
}

/** A response reader runs while the Ktor response and its network channel are still open. */
internal fun interface CnbHttpResponseReader<T> {
    suspend fun read(response: CnbHttpResponseContext): T
}

internal data class CnbHttpRequest(
    val method: String,
    val uri: URI,
    val headers: CnbHttpHeaders = CnbHttpHeaders.EMPTY,
    val body: CnbHttpRequestBody = CnbHttpRequestBody.Empty,
    val negotiateCnbJson: Boolean = true,
) {
    init {
        require(method.isNotBlank()) { "CNB HTTP method must not be blank" }
        require(uri.isAbsolute && !uri.host.isNullOrBlank()) { "CNB HTTP URI must be absolute and have a host" }
    }
}

internal sealed interface CnbHttpRequestBody {
    data object Empty : CnbHttpRequestBody

    /** The array is intentionally not copied so the caller can zero sensitive JSON after execute returns. */
    class Bytes(
        internal val bytes: ByteArray,
    ) : CnbHttpRequestBody

    /** Opens a fresh stream every time this body is sent, including an explicitly followed redirect. */
    class RepeatableStream(
        val contentLength: Long,
        internal val openStream: () -> InputStream,
    ) : CnbHttpRequestBody {
        init {
            require(contentLength >= 0) { "CNB HTTP request body length must not be negative" }
        }
    }
}

internal data class CnbHttpResponse<T>(
    val statusCode: Int,
    val headers: CnbHttpHeaders,
    val body: T,
)

/** An immutable, order-preserving, case-insensitive multi-value header collection. */
internal class CnbHttpHeaders private constructor(
    values: List<Pair<String, String>>,
) {
    private val values = values.toList()

    fun allValues(name: String): List<String> =
        values
            .asSequence()
            .filter { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
            .map { it.second }
            .toList()

    fun firstValue(name: String): String? = values.firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }?.second

    internal fun forEach(block: (String, String) -> Unit) {
        values.forEach { (name, value) -> block(name, value) }
    }

    companion object {
        val EMPTY = CnbHttpHeaders(emptyList())

        fun of(vararg values: Pair<String, String>): CnbHttpHeaders = CnbHttpHeaders(values.toList())

        fun fromMap(values: Map<String, List<String>>): CnbHttpHeaders {
            val flattened = ArrayList<Pair<String, String>>()
            values.forEach { (name, headerValues) ->
                headerValues.forEach { value -> flattened += name to value }
            }
            return CnbHttpHeaders(flattened)
        }

        internal fun fromKtor(headers: Headers): CnbHttpHeaders {
            val flattened = ArrayList<Pair<String, String>>()
            headers.entries().forEach { (name, headerValues) ->
                headerValues.forEach { value -> flattened += name to value }
            }
            return CnbHttpHeaders(flattened)
        }
    }
}

/**
 * The response body can be consumed once. Implementations enforce limits while bytes arrive rather
 * than after allocating an unbounded intermediate buffer.
 */
internal interface CnbHttpResponseContext {
    val statusCode: Int
    val headers: CnbHttpHeaders

    suspend fun readBytes(): ByteArray

    suspend fun <T> readJson(typeInfo: TypeInfo): T

    suspend fun readBoundedBytes(maxBytes: Int): ByteArray

    suspend fun streamTo(
        maxBytes: Long,
        declaredLength: Long?,
        openTarget: () -> OutputStream,
    ): Long

    suspend fun discard()
}

internal object CnbHttpTransportFactory {
    fun create(server: CnbServer): CnbHttpTransport =
        create(server, if (server.allowPrivateNetwork) Jenkins.getInstanceOrNull()?.proxy else null)

    internal fun create(
        server: CnbServer,
        proxyConfiguration: ProxyConfiguration?,
    ): CnbHttpTransport =
        KtorCnbHttpTransport(
            server,
            proxyConfigurationFor(server, proxyConfiguration),
        )

    internal fun proxyConfigurationFor(
        server: CnbServer,
        proxyConfiguration: ProxyConfiguration?,
    ): ProxyConfiguration? = proxyConfiguration.takeIf { server.allowPrivateNetwork }
}

/**
 * Resolver installed at Apache5's connection-manager seam. The exact address set returned here is
 * the one used to open the socket, eliminating a validate-then-resolve DNS rebinding window.
 */
internal class CnbPublicDnsResolver(
    private val systemResolver: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) : DnsResolver {
    @Throws(UnknownHostException::class)
    override fun resolve(host: String): Array<InetAddress> =
        try {
            CnbEndpointPolicy.requirePublicAddresses(systemResolver(host))
        } catch (failure: IllegalArgumentException) {
            throw UnknownHostException("CNB endpoint did not resolve to public unicast addresses").apply {
                initCause(failure)
            }
        }

    @Throws(UnknownHostException::class)
    override fun resolveCanonicalHostname(host: String): String = host
}

/** Applies Jenkins' proxy and no-proxy rules independently for every request target. */
internal class CnbJenkinsProxyRoutePlanner(
    private val configuration: ProxyConfiguration,
) : HttpRoutePlanner {
    override fun determineRoute(
        target: HttpHost,
        context: HttpContext,
    ): HttpRoute {
        val normalizedTarget = RoutingSupport.normalize(target, DefaultSchemePortResolver.INSTANCE)
        if (configuration.name.isNullOrBlank()) return HttpRoute(normalizedTarget)
        val proxy = configuration.createProxy(target.hostName)
        if (proxy == Proxy.NO_PROXY || proxy.type() == Proxy.Type.DIRECT) return HttpRoute(normalizedTarget)
        val address =
            proxy.address() as? InetSocketAddress
                ?: throw HttpException("Jenkins configured an unsupported CNB proxy address")
        val proxyHost = HttpHost("http", address.hostString, address.port)
        return HttpRoute(
            normalizedTarget,
            null,
            proxyHost,
            normalizedTarget.schemeName.equals("https", ignoreCase = true),
        )
    }
}

private class CnbProxyCredentialsProvider private constructor(
    private val proxyHost: HttpHost,
    userName: String,
    password: CharArray,
) : CredentialsProvider,
    AutoCloseable {
    private val password = password
    private val credentials = UsernamePasswordCredentials(userName, password)

    override fun getCredentials(
        authScope: AuthScope,
        context: HttpContext,
    ): Credentials? {
        val route = HttpClientContext.cast(context)?.httpRoute ?: return null
        val activeProxy = route.proxyHost ?: return null
        if (activeProxy != proxyHost || !matches(authScope, activeProxy)) return null
        return credentials
    }

    override fun close() {
        Arrays.fill(password, '\u0000')
    }

    fun addPreemptiveAuthorization(
        request: HttpRequest,
        context: HttpContext,
    ) {
        val route = HttpClientContext.cast(context)?.httpRoute ?: return
        val activeProxy = route.proxyHost ?: return
        if (route.isTunnelled || activeProxy != proxyHost || request.containsHeader(ApacheHttpHeaders.PROXY_AUTHORIZATION)) return
        val scheme = BasicScheme()
        scheme.initPreemptive(credentials)
        request.addHeader(
            ApacheHttpHeaders.PROXY_AUTHORIZATION,
            scheme.generateAuthResponse(activeProxy, request, context),
        )
    }

    companion object {
        fun create(configuration: ProxyConfiguration?): CnbProxyCredentialsProvider? {
            val proxyName = configuration?.name?.takeIf(String::isNotBlank) ?: return null
            val userName = configuration.userName?.takeIf(String::isNotBlank) ?: return null
            val password = configuration.secretPassword?.plainText?.toCharArray() ?: return null
            return CnbProxyCredentialsProvider(HttpHost("http", proxyName, configuration.port), userName, password)
        }

        private fun matches(
            scope: AuthScope,
            proxy: HttpHost,
        ): Boolean {
            if (!scope.host.equals(proxy.hostName, ignoreCase = true)) return false
            return scope.port < 0 || scope.port == proxy.port
        }
    }
}

private class KtorCnbHttpTransport(
    server: CnbServer,
    proxyConfiguration: ProxyConfiguration?,
) : CnbHttpTransport {
    private val closed = AtomicBoolean()
    private val lifetime = SupervisorJob()
    private val requestTimeoutMillis = server.requestTimeoutSeconds * 1_000L
    private val proxyCredentials = CnbProxyCredentialsProvider.create(proxyConfiguration)
    private val executor: ExecutorService = Executors.newCachedThreadPool(TRANSPORT_THREAD_FACTORY)
    private val dispatcher: ExecutorCoroutineDispatcher = executor.asCoroutineDispatcher()
    private val blockingIo = CnbBlockingIo(executor, dispatcher)
    private val client =
        HttpClient(Apache5) {
            expectSuccess = false
            followRedirects = false
            install(ContentNegotiation) {
                json(CnbJsonCodec.json, ContentType.Application.Json)
            }
            install(HttpTimeout) {
                requestTimeoutMillis = this@KtorCnbHttpTransport.requestTimeoutMillis
                connectTimeoutMillis = server.connectTimeoutSeconds * 1_000L
                socketTimeoutMillis = this@KtorCnbHttpTransport.requestTimeoutMillis
            }
            engine {
                dispatcher = this@KtorCnbHttpTransport.dispatcher
                followRedirects = false
                connectTimeout = server.connectTimeoutSeconds * 1_000L
                socketTimeout = requestTimeoutMillis.toInt()
                connectionRequestTimeout = requestTimeoutMillis
                if (!server.allowPrivateNetwork) {
                    dnsResolver = CnbPublicDnsResolver()
                }
                configureConnectionManager {
                    setMaxConnTotal(MAX_CONNECTIONS)
                    setMaxConnPerRoute(MAX_CONNECTIONS)
                }
                customizeClient {
                    disableAutomaticRetries()
                    disableRedirectHandling()
                    disableContentCompression()
                    setThreadFactory(TRANSPORT_THREAD_FACTORY)
                    setIoReactorExceptionCallback { failure ->
                        if (failure !is ClosedSelectorException || !closed.get()) {
                            LOGGER.log(Level.WARNING, "CNB HTTP I/O reactor failed", failure)
                        }
                    }
                    proxyConfiguration?.takeIf { !it.name.isNullOrBlank() }?.let {
                        setRoutePlanner(CnbJenkinsProxyRoutePlanner(it))
                    }
                    proxyCredentials?.let { credentials ->
                        setDefaultCredentialsProvider(credentials)
                        addRequestInterceptorFirst { request, entity, context ->
                            if (entity != null) credentials.addPreemptiveAuthorization(request, context)
                        }
                    }
                }
            }
        }

    override fun <T> execute(
        request: CnbHttpRequest,
        reader: CnbHttpResponseReader<T>,
    ): CnbHttpResponse<T> {
        if (closed.get()) throw IOException("CNB HTTP transport is closed")
        val cleanupFailure = AtomicReference<CnbApiException?>()
        val reportCleanupFailure: (CnbApiException) -> Unit = { failure ->
            cleanupFailure.compareAndSet(null, failure)
        }
        return try {
            runBlocking(lifetime) {
                withTimeout(requestTimeoutMillis) {
                    if (closed.get()) throw IOException("CNB HTTP transport is closed")
                    client
                        .prepareRequest {
                            method = HttpMethod.parse(request.method)
                            url(request.uri.toASCIIString())
                            request.headers.forEach { name, value -> headers.append(name, value) }
                            if (!request.negotiateCnbJson || request.headers.firstValue("Accept") != null) {
                                exclude(ContentType.Application.Json)
                            }
                            setBody(request.body.toOutgoingContent(blockingIo, reportCleanupFailure))
                        }.execute { response ->
                            val context =
                                KtorCnbHttpResponseContext(
                                    response,
                                    blockingIo,
                                    reportCleanupFailure,
                                )
                            try {
                                CnbHttpResponse(
                                    statusCode = context.statusCode,
                                    headers = context.headers,
                                    body = reader.read(context),
                                )
                            } finally {
                                context.cancelRemaining()
                            }
                        }
                }
            }
        } catch (failure: TimeoutCancellationException) {
            cleanupFailure.get()?.let { cleanup ->
                cleanup.addSuppressed(failure)
                throw cleanup
            }
            throw SocketTimeoutException("CNB response exceeded the configured request deadline").also { timeout ->
                timeout.initCause(failure)
                failure.suppressed.forEach(timeout::addSuppressed)
                findCleanupApiFailure(failure)?.let { cleanupFailure ->
                    if (timeout.suppressed.none { it === cleanupFailure }) timeout.addSuppressed(cleanupFailure)
                }
            }
        } catch (_: BadContentTypeFormatException) {
            throw CnbApiException("CNB response contained an invalid Content-Type")
        } catch (_: ContentConvertException) {
            throw CnbApiException("CNB response contained invalid JSON")
        } catch (_: ContentConverterException) {
            throw CnbApiException("CNB response could not be converted from JSON")
        } catch (_: NoTransformationFoundException) {
            throw CnbApiException("CNB response did not contain supported JSON")
        } catch (failure: CancellationException) {
            throw IOException(
                if (closed.get()) "CNB HTTP transport is closed" else "CNB HTTP transport was cancelled",
                failure,
            )
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifetime.cancel(CancellationException("CNB HTTP transport was closed"))
        blockingIo.cancelAll()
        try {
            client.close()
            awaitClientShutdown()
        } finally {
            try {
                proxyCredentials?.close()
            } finally {
                dispatcher.close()
                executor.shutdownNow()
                try {
                    if (!executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        LOGGER.warning("CNB HTTP executor did not terminate within the shutdown deadline")
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun awaitClientShutdown() {
        try {
            runBlocking {
                withTimeout(CLIENT_CLOSE_TIMEOUT_MILLIS) {
                    client.coroutineContext.job.join()
                }
            }
        } catch (_: TimeoutCancellationException) {
            LOGGER.warning("Ktor CNB client did not terminate within the shutdown deadline")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val MAX_CONNECTIONS = 8
        private const val CLIENT_CLOSE_TIMEOUT_MILLIS = 10_000L
        private const val EXECUTOR_CLOSE_TIMEOUT_SECONDS = 5L
        private val LOGGER = Logger.getLogger(KtorCnbHttpTransport::class.java.name)
        private val TRANSPORT_THREAD_FACTORY: ThreadFactory =
            NamingThreadFactory(
                ClassLoaderSanityThreadFactory(DaemonThreadFactory()),
                KtorCnbHttpTransport::class.java.name,
            )
    }
}

private fun CnbHttpRequestBody.toOutgoingContent(
    blockingIo: CnbBlockingIo,
    reportCleanupFailure: (CnbApiException) -> Unit,
): OutgoingContent =
    when (this) {
        CnbHttpRequestBody.Empty -> {
            EmptyContent
        }

        is CnbHttpRequestBody.Bytes -> {
            object : OutgoingContent.ByteArrayContent() {
                override val contentLength: Long = bytes.size.toLong()

                override fun bytes(): ByteArray = bytes
            }
        }

        is CnbHttpRequestBody.RepeatableStream -> {
            RepeatableStreamContent(this, blockingIo, reportCleanupFailure)
        }
    }

private class RepeatableStreamContent(
    private val body: CnbHttpRequestBody.RepeatableStream,
    private val blockingIo: CnbBlockingIo,
    private val reportCleanupFailure: (CnbApiException) -> Unit,
) : OutgoingContent.WriteChannelContent() {
    override val contentLength: Long = body.contentLength

    override suspend fun writeTo(channel: ByteWriteChannel) {
        blockingIo.useResource({ body.openStream() }, reportCleanupFailure) { input ->
            try {
                var remaining = body.contentLength
                val buffer = ByteArray(BUFFER_BYTES)
                while (remaining > 0) {
                    val requested = min(remaining, buffer.size.toLong()).toInt()
                    val count = blockingIo.interruptible(reportCleanupFailure) { input.read(buffer, 0, requested) }
                    if (count < 0) throw IOException("CNB HTTP request body ended before its declared size")
                    if (count == 0) continue
                    remaining -= count
                    if (remaining == 0L && blockingIo.interruptible(reportCleanupFailure) { input.read() } >= 0) {
                        throw IOException("CNB HTTP request body exceeded its declared size")
                    }
                    channel.writeFully(buffer, 0, count)
                }
                if (body.contentLength == 0L && blockingIo.interruptible(reportCleanupFailure) { input.read() } >= 0) {
                    throw IOException("CNB HTTP request body exceeded its declared size")
                }
            } catch (failure: InterruptedIOException) {
                throw failure
            } catch (failure: IOException) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("CNB HTTP upload was cancelled").apply { initCause(failure) }
                }
                throw failure
            }
        }
    }
}

private class KtorCnbHttpResponseContext(
    private val response: HttpResponse,
    private val blockingIo: CnbBlockingIo,
    private val reportCleanupFailure: (CnbApiException) -> Unit,
) : CnbHttpResponseContext {
    override val statusCode: Int = response.status.value
    override val headers = CnbHttpHeaders.fromKtor(response.headers)

    // Error, pagination, and transfer readers must bypass ContentNegotiation entirely; otherwise a
    // malformed Content-Type can hide the HTTP status before retry/error policy sees it.
    @OptIn(InternalAPI::class)
    private val responseChannel: ByteReadChannel = response.rawContent

    override suspend fun readBytes(): ByteArray = responseChannel.toByteArray()

    override suspend fun <T> readJson(typeInfo: TypeInfo): T = response.body(typeInfo)

    override suspend fun readBoundedBytes(maxBytes: Int): ByteArray {
        require(maxBytes >= 0) { "CNB HTTP response limit must not be negative" }
        val output = ByteArrayOutputStream(min(maxBytes, BUFFER_BYTES))
        val buffer = ByteArray(BUFFER_BYTES)
        var received = 0L
        val channel = responseChannel
        while (true) {
            val count = channel.readAvailable(buffer)
            if (count < 0) return output.toByteArray()
            if (count == 0) continue
            if (count.toLong() > maxBytes.toLong() - received) {
                throw CnbApiException("CNB response exceeded $maxBytes bytes")
            }
            output.write(buffer, 0, count)
            received += count
        }
    }

    override suspend fun streamTo(
        maxBytes: Long,
        declaredLength: Long?,
        openTarget: () -> OutputStream,
    ): Long {
        require(maxBytes >= 0) { "CNB HTTP response limit must not be negative" }
        if (declaredLength != null && declaredLength !in 0..maxBytes) {
            throw CnbApiException("CNB response contained an invalid Content-Length header")
        }
        val requestJob = coroutineContext.job
        val channel = responseChannel
        return blockingIo.execute(reportCleanupFailure) { operation ->
            val input = operation.openAndRegister { channel.toInputStream(requestJob) }
            val output = operation.openAndRegister(openTarget)
            try {
                val buffer = ByteArray(BUFFER_BYTES)
                var received = 0L
                while (true) {
                    requireBlockingThreadActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (
                        count.toLong() > maxBytes - received ||
                        (declaredLength != null && count.toLong() > declaredLength - received)
                    ) {
                        throw CnbApiException("CNB response exceeded its declared download limit")
                    }
                    output.write(buffer, 0, count)
                    received += count
                }
                output.flush()
                if (declaredLength != null && received != declaredLength) {
                    throw CnbApiException("CNB response did not match Content-Length")
                }
                received
            } catch (failure: InterruptedIOException) {
                throw failure
            } catch (failure: IOException) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedIOException("CNB HTTP download was cancelled").apply { initCause(failure) }
                }
                throw failure
            }
        }
    }

    override suspend fun discard() {
        response.cancel(CancellationException("CNB HTTP response body was discarded"))
        cancelRemaining()
    }

    fun cancelRemaining() {
        if (!responseChannel.isClosedForRead) responseChannel.cancel(null)
    }
}

/** Runs blocking Jenkins/Remoting streams without making Ktor's engine threads non-cancellable. */
private class CnbBlockingIo(
    private val executor: ExecutorService,
    private val dispatcher: ExecutorCoroutineDispatcher,
) {
    private val active = ConcurrentHashMap.newKeySet<CnbBlockingOperation>()
    private val activeResources = ConcurrentHashMap.newKeySet<CnbTrackedCloseable<out Closeable>>()

    suspend fun <T> interruptible(
        reportCleanupFailure: (CnbApiException) -> Unit,
        block: () -> T,
    ): T = execute(reportCleanupFailure) { block() }

    suspend fun <T : Closeable, R> useResource(
        openResource: () -> T,
        reportCleanupFailure: (CnbApiException) -> Unit,
        block: suspend (T) -> R,
    ): R =
        coroutineScope {
            val tracked = openTrackedResource(openResource)
            val completedNormally = AtomicBoolean()
            var operationFailure: Throwable? = null
            val cancellationCloser =
                launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        if (!completedNormally.get()) tracked.requestClose()
                    }
                }
            try {
                block(tracked.resource).also { completedNormally.set(true) }
            } catch (failure: Throwable) {
                operationFailure = failure
                throw failure
            } finally {
                cancellationCloser.cancel()
                try {
                    val closeFailure =
                        withContext(NonCancellable) {
                            withContext(dispatcher) { tracked.awaitClose(resourceCloseDeadline()) }
                        }
                    if (closeFailure != null) {
                        findCleanupApiFailure(closeFailure)?.let(reportCleanupFailure)
                        val primary = operationFailure
                        if (primary == null) {
                            throw closeFailure
                        }
                        if (primary is CancellationException && closeFailure is CnbApiException) {
                            closeFailure.addSuppressed(primary)
                            throw closeFailure
                        }
                        if (primary !== closeFailure) primary.addSuppressed(closeFailure)
                    }
                } finally {
                    activeResources -= tracked
                }
            }
        }

    private suspend fun <T : Closeable> openTrackedResource(openResource: () -> T): CnbTrackedCloseable<T> =
        suspendCancellableCoroutine { continuation ->
            val task =
                FutureTask {
                    try {
                        val tracked = CnbTrackedCloseable(openResource())
                        activeResources += tracked
                        continuation.resume(tracked) { _, lateResource, _ ->
                            lateResource.requestClose()
                            activeResources -= lateResource
                        }
                    } catch (failure: Throwable) {
                        continuation.resumeWith(Result.failure(failure))
                    }
                }
            continuation.invokeOnCancellation { task.cancel(true) }
            try {
                executor.execute(task)
            } catch (failure: RejectedExecutionException) {
                task.cancel(false)
                continuation.resumeWith(Result.failure(IOException("CNB HTTP transport is closed", failure)))
            }
        }

    suspend fun <T> execute(
        reportCleanupFailure: (CnbApiException) -> Unit,
        block: (CnbBlockingOperation) -> T,
    ): T {
        val operation = CnbBlockingOperation()
        active += operation
        var operationFailure: Throwable? = null
        try {
            return suspendCancellableCoroutine { continuation ->
                val future =
                    try {
                        executor.submit { continuation.resumeWith(runCatching { block(operation) }) }
                    } catch (failure: RejectedExecutionException) {
                        continuation.resumeWith(Result.failure(IOException("CNB HTTP transport is closed", failure)))
                        return@suspendCancellableCoroutine
                    }
                operation.attach(future)
                continuation.invokeOnCancellation { operation.cancel() }
            }
        } catch (failure: Throwable) {
            operationFailure = failure
            operation.cancel()
            throw failure
        } finally {
            try {
                val closeFailure =
                    withContext(NonCancellable) {
                        withContext(dispatcher) { operation.finish() }
                    }
                if (closeFailure != null) {
                    findCleanupApiFailure(closeFailure)?.let(reportCleanupFailure)
                    val primary = operationFailure
                    if (primary == null) {
                        throw closeFailure
                    }
                    if (primary is CancellationException && closeFailure is CnbApiException) {
                        closeFailure.addSuppressed(primary)
                        throw closeFailure
                    }
                    if (primary !== closeFailure) primary.addSuppressed(closeFailure)
                }
            } finally {
                active -= operation
            }
        }
    }

    fun cancelAll() {
        active.forEach(CnbBlockingOperation::cancel)
        activeResources.forEach(CnbTrackedCloseable<out Closeable>::requestClose)
    }
}

internal interface CnbCloseTicket {
    fun requestClose()

    fun awaitClose(deadlineNanos: Long): Throwable?
}

internal class CnbTrackedCloseable<T : Closeable>(
    val resource: T,
    private val closeExecutor: java.util.concurrent.Executor = SHARED_CLOSE_EXECUTOR,
) : CnbCloseTicket {
    private val started = AtomicBoolean()
    private val completed = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()

    override fun requestClose() {
        if (!started.compareAndSet(false, true)) return
        try {
            closeExecutor.execute {
                try {
                    resource.close()
                } catch (closeFailure: Throwable) {
                    failure.set(closeFailure)
                } finally {
                    completed.countDown()
                }
            }
        } catch (startFailure: Throwable) {
            failure.set(startFailure)
            completed.countDown()
        }
    }

    override fun awaitClose(deadlineNanos: Long): Throwable? {
        requestClose()
        try {
            if (completed.count != 0L) {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0 || !completed.await(remaining, TimeUnit.NANOSECONDS)) {
                    return CnbApiException("CNB blocking HTTP resource cleanup exceeded its deadline")
                }
            }
        } catch (interruption: InterruptedException) {
            Thread.currentThread().interrupt()
            return CnbApiException("CNB blocking HTTP resource cleanup was interrupted", cause = interruption)
        }
        return failure.get()?.let { closeFailure ->
            CnbApiException("CNB blocking HTTP resource cleanup failed", cause = closeFailure)
        }
    }
}

/** A close ticket published before a potentially non-interruptible Remoting resource open begins. */
private class CnbPendingCloseable<T : Closeable> : CnbCloseTicket {
    private val stateLock = Any()
    private val openCompleted = CountDownLatch(1)
    private var openingFinished = false
    private var closeRequested = false
    private var tracked: CnbTrackedCloseable<T>? = null

    fun open(openResource: () -> T): T {
        synchronized(stateLock) {
            check(!openingFinished) { "CNB blocking resource opener was invoked more than once" }
            if (closeRequested) {
                openingFinished = true
                openCompleted.countDown()
                throw CancellationException("CNB blocking HTTP I/O was cancelled")
            }
        }

        val opened =
            try {
                openResource()
            } catch (failure: Throwable) {
                synchronized(stateLock) { openingFinished = true }
                openCompleted.countDown()
                throw failure
            }
        val published = CnbTrackedCloseable(opened)
        val closeAfterPublish =
            synchronized(stateLock) {
                tracked = published
                openingFinished = true
                closeRequested
            }
        if (closeAfterPublish) published.requestClose()
        openCompleted.countDown()
        if (closeAfterPublish) throw CancellationException("CNB blocking HTTP I/O was cancelled")
        return opened
    }

    override fun requestClose() {
        val published =
            synchronized(stateLock) {
                closeRequested = true
                tracked
            }
        published?.requestClose()
    }

    override fun awaitClose(deadlineNanos: Long): Throwable? {
        requestClose()
        try {
            if (openCompleted.count != 0L) {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0 || !openCompleted.await(remaining, TimeUnit.NANOSECONDS)) {
                    return CnbApiException("CNB blocking HTTP resource cleanup exceeded its deadline")
                }
            }
        } catch (interruption: InterruptedException) {
            Thread.currentThread().interrupt()
            return CnbApiException("CNB blocking HTTP resource cleanup was interrupted", cause = interruption)
        }
        val published = synchronized(stateLock) { tracked }
        return published?.awaitClose(deadlineNanos)
    }
}

private class CnbBlockingOperation {
    private val cancelled = AtomicBoolean()
    private val future = AtomicReference<Future<*>?>()
    private val resourceLock = Any()
    private val resources = ArrayDeque<CnbCloseTicket>()
    private var closeTickets: List<CnbCloseTicket>? = null

    fun <T : Closeable> openAndRegister(openResource: () -> T): T {
        val pending = CnbPendingCloseable<T>()
        synchronized(resourceLock) {
            if (!cancelled.get() && closeTickets == null) {
                resources.addLast(pending)
            } else {
                throw CancellationException("CNB blocking HTTP I/O was cancelled")
            }
        }
        val resource = pending.open(openResource)
        if (cancelled.get()) {
            pending.requestClose()
            throw CancellationException("CNB blocking HTTP I/O was cancelled")
        }
        return resource
    }

    fun attach(value: Future<*>) {
        future.set(value)
        if (cancelled.get()) value.cancel(true)
    }

    fun cancel() {
        if (!cancelled.compareAndSet(false, true)) return
        future.get()?.cancel(true)
        initiateClose()
    }

    fun finish(): Throwable? {
        val tickets = initiateClose()
        val deadline = resourceCloseDeadline()
        var firstFailure: Throwable? = null
        tickets.forEach { ticket ->
            val closeFailure = ticket.awaitClose(deadline)
            if (closeFailure != null) firstFailure = mergeFailures(firstFailure, closeFailure)
        }
        return firstFailure
    }

    private fun initiateClose(): List<CnbCloseTicket> {
        val tickets =
            synchronized(resourceLock) {
                closeTickets ?: resources.reversed().also {
                    resources.clear()
                    closeTickets = it
                }
            }
        tickets.forEach(CnbCloseTicket::requestClose)
        return tickets
    }
}

private fun resourceCloseDeadline(): Long = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RESOURCE_CLOSE_TIMEOUT_MILLIS)

private fun mergeFailures(
    first: Throwable?,
    next: Throwable,
): Throwable =
    if (first == null) {
        next
    } else {
        if (first !== next) first.addSuppressed(next)
        first
    }

private fun findCleanupApiFailure(root: Throwable): CnbApiException? {
    val pending = ArrayDeque<Throwable>()
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    pending.add(root)
    repeat(MAX_CLEANUP_FAILURE_GRAPH_NODES) {
        val current = pending.removeFirstOrNull() ?: return null
        if (!seen.add(current)) return@repeat
        if (current is CnbApiException) return current
        current.cause?.let(pending::addLast)
        current.suppressed.forEach(pending::addLast)
    }
    return null
}

private fun requireBlockingThreadActive() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("CNB blocking HTTP I/O was cancelled")
}

private const val BUFFER_BYTES = 64 * 1024
private const val RESOURCE_CLOSE_TIMEOUT_MILLIS = 2_000L
private const val MAX_CLOSE_THREADS = 32
private const val MAX_CLEANUP_FAILURE_GRAPH_NODES = 16
internal const val CNB_MEDIA_TYPE = "application/vnd.cnb.api+json"

/**
 * A broken Remoting close cannot be forcibly stopped by the JVM. Bound the number of native threads
 * it can retain; saturation causes subsequent cleanup attempts to fail closed instead of growing
 * without limit.
 */
internal class CnbResourceCloseExecutor(
    maxConcurrency: Int,
    keepAliveSeconds: Long = 30,
) : java.util.concurrent.Executor,
    AutoCloseable {
    private val executor: ExecutorService

    init {
        require(maxConcurrency > 0) { "CNB resource close concurrency must be positive" }
        require(keepAliveSeconds >= 0) { "CNB resource close keep-alive must not be negative" }
        executor =
            ThreadPoolExecutor(
                0,
                maxConcurrency,
                keepAliveSeconds,
                TimeUnit.SECONDS,
                SynchronousQueue(),
                NamingThreadFactory(
                    ClassLoaderSanityThreadFactory(DaemonThreadFactory()),
                    "${KtorCnbHttpTransport::class.java.name}.resource-close",
                ),
                ThreadPoolExecutor.AbortPolicy(),
            )
    }

    override fun execute(command: Runnable) {
        executor.execute(command)
    }

    override fun close() {
        executor.shutdownNow()
        try {
            executor.awaitTermination(RESOURCE_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

private val SHARED_CLOSE_EXECUTOR = CnbResourceCloseExecutor(MAX_CLOSE_THREADS)
