package dev.zxilly.jenkins.cnb.api

import dev.zxilly.jenkins.cnb.config.CnbServer
import dev.zxilly.jenkins.cnb.security.CnbEndpointPolicy
import hudson.ProxyConfiguration
import hudson.util.ClassLoaderSanityThreadFactory
import hudson.util.DaemonThreadFactory
import hudson.util.NamingThreadFactory
import org.apache.hc.client5.http.DnsResolver
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase
import org.apache.hc.client5.http.config.ConnectionConfig
import org.apache.hc.client5.http.config.RequestConfig
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
import org.apache.hc.core5.http.ClassicHttpResponse
import org.apache.hc.core5.http.io.entity.AbstractHttpEntity
import org.apache.hc.core5.util.Timeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Flow
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSession

/** The small HTTP seam used by [HttpCnbClient]. */
internal interface CnbHttpTransport : AutoCloseable {
    fun <T> sendAsync(
        request: HttpRequest,
        bodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>>
}

internal object CnbHttpTransportFactory {
    fun create(server: CnbServer): CnbHttpTransport =
        if (server.allowPrivateNetwork) {
            JdkCnbHttpTransport(
                ProxyConfiguration
                    .newHttpClientBuilder()
                    .connectTimeout(Duration.ofSeconds(server.connectTimeoutSeconds.toLong()))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .version(HttpClient.Version.HTTP_2)
                    .build(),
            )
        } else {
            PinnedCnbHttpTransport(server.connectTimeoutSeconds, server.requestTimeoutSeconds)
        }
}

/**
 * Resolver used at Apache HttpClient's actual socket-connection seam. The same address array is
 * validated and returned to the connection operator, eliminating a validate-then-resolve race.
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

private class JdkCnbHttpTransport(
    private val delegate: HttpClient,
) : CnbHttpTransport {
    override fun <T> sendAsync(
        request: HttpRequest,
        bodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> = delegate.sendAsync(request, bodyHandler)

    override fun close() = delegate.close()
}

private class PinnedCnbHttpTransport(
    connectTimeoutSeconds: Int,
    private val requestTimeoutSeconds: Int,
) : CnbHttpTransport {
    private val client: CloseableHttpClient

    init {
        val timeout = Timeout.ofSeconds(connectTimeoutSeconds.toLong())
        val connectionConfig =
            ConnectionConfig
                .custom()
                .setConnectTimeout(timeout)
                .setSocketTimeout(Timeout.ofSeconds(requestTimeoutSeconds.toLong()))
                .build()
        val connections =
            PoolingHttpClientConnectionManagerBuilder
                .create()
                .setDnsResolver(CnbPublicDnsResolver())
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnTotal(MAX_CONNECTIONS)
                .setMaxConnPerRoute(MAX_CONNECTIONS)
                .build()
        client =
            HttpClients
                .custom()
                .setConnectionManager(connections)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableContentCompression()
                .build()
    }

    override fun <T> sendAsync(
        request: HttpRequest,
        bodyHandler: HttpResponse.BodyHandler<T>,
    ): CompletableFuture<HttpResponse<T>> {
        val apacheRequest = apacheRequest(request)
        val result = TransportFuture<HttpResponse<T>>(apacheRequest)
        result.task =
            EXECUTOR.submit {
                try {
                    result.complete(
                        client.execute(apacheRequest) { response ->
                            adaptResponse(request, bodyHandler, response)
                        },
                    )
                } catch (failure: Throwable) {
                    result.completeExceptionally(failure)
                }
            }
        return result
    }

    private fun apacheRequest(request: HttpRequest): HttpUriRequestBase {
        val converted = HttpUriRequestBase(request.method(), request.uri())
        request.headers().map().forEach { (name, values) -> values.forEach { converted.addHeader(name, it) } }
        val timeout = request.timeout().orElse(Duration.ofSeconds(requestTimeoutSeconds.toLong()))
        converted.config =
            RequestConfig
                .custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(requestTimeoutSeconds.toLong()))
                .setResponseTimeout(Timeout.ofMilliseconds(timeout.toMillis()))
                .build()
        request.bodyPublisher().ifPresent { publisher ->
            if (publisher.contentLength() != 0L || request.method() !in NO_BODY_METHODS) {
                converted.entity = ReactiveBodyEntity(publisher, requestTimeoutSeconds)
            }
        }
        return converted
    }

    private fun <T> adaptResponse(
        request: HttpRequest,
        bodyHandler: HttpResponse.BodyHandler<T>,
        response: ClassicHttpResponse,
    ): HttpResponse<T> {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        response.headers.forEach { header -> grouped.getOrPut(header.name) { ArrayList() }.add(header.value) }
        val headers = HttpHeaders.of(grouped) { _, _ -> true }
        val subscriber =
            bodyHandler.apply(
                object : HttpResponse.ResponseInfo {
                    override fun statusCode(): Int = response.code

                    override fun headers(): HttpHeaders = headers

                    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
                },
            )
        val subscription = ResponseSubscription()
        subscriber.onSubscribe(subscription)
        try {
            if (!subscription.cancelled.get()) {
                response.entity?.content?.use { input -> feed(input, subscriber, subscription) }
            }
            if (!subscription.cancelled.get()) subscriber.onComplete()
        } catch (failure: Throwable) {
            subscriber.onError(failure)
        }
        val body = awaitBody(subscriber)
        return AdaptedHttpResponse(request, response.code, headers, body)
    }

    private fun <T> feed(
        input: InputStream,
        subscriber: HttpResponse.BodySubscriber<T>,
        subscription: ResponseSubscription,
    ) {
        val buffer = ByteArray(RESPONSE_BUFFER_BYTES)
        while (!subscription.cancelled.get()) {
            val count = input.read(buffer)
            if (count < 0) return
            subscriber.onNext(listOf(ByteBuffer.wrap(buffer.copyOf(count))))
        }
    }

    private fun <T> awaitBody(subscriber: HttpResponse.BodySubscriber<T>): T =
        try {
            subscriber.body.toCompletableFuture().get(requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (failure: ExecutionException) {
            when (val cause = failure.cause ?: failure) {
                is RuntimeException -> throw cause
                is IOException -> throw cause
                else -> throw IOException("CNB HTTP response processing failed")
            }
        } catch (_: TimeoutException) {
            throw IOException("CNB HTTP response processing timed out")
        }

    override fun close() = client.close()

    private class ResponseSubscription : Flow.Subscription {
        val cancelled = AtomicBoolean()

        override fun request(n: Long) {
            if (n <= 0) cancel()
        }

        override fun cancel() {
            cancelled.set(true)
        }
    }

    private class ReactiveBodyEntity(
        private val publisher: HttpRequest.BodyPublisher,
        private val timeoutSeconds: Int,
    ) : AbstractHttpEntity(null as String?, null, false) {
        override fun getContentLength(): Long = publisher.contentLength()

        override fun getContent(): InputStream = throw UnsupportedOperationException("Reactive request body is write-only")

        override fun isStreaming(): Boolean = true

        override fun writeTo(output: OutputStream) {
            val completed = CompletableFuture<Unit>()
            val written = longArrayOf(0L)
            publisher.subscribe(
                object : Flow.Subscriber<ByteBuffer> {
                    private var subscription: Flow.Subscription? = null

                    override fun onSubscribe(value: Flow.Subscription) {
                        subscription = value
                        value.request(Long.MAX_VALUE)
                    }

                    override fun onNext(buffer: ByteBuffer) {
                        try {
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            output.write(bytes)
                            written[0] += bytes.size
                        } catch (failure: Throwable) {
                            subscription?.cancel()
                            completed.completeExceptionally(failure)
                        }
                    }

                    override fun onError(failure: Throwable) {
                        completed.completeExceptionally(failure)
                    }

                    override fun onComplete() {
                        completed.complete(Unit)
                    }
                },
            )
            try {
                completed.get(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            } catch (failure: Exception) {
                throw IOException("CNB HTTP request body publishing failed")
            }
            val expected = publisher.contentLength()
            if (expected >= 0 && written[0] != expected) {
                throw IOException("CNB HTTP request body length did not match its declaration")
            }
        }

        override fun close() = Unit
    }

    private class TransportFuture<T>(
        private val request: HttpUriRequestBase,
    ) : CompletableFuture<T>() {
        @Volatile
        var task: Future<*>? = null

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            request.cancel()
            task?.cancel(mayInterruptIfRunning)
            return super.cancel(mayInterruptIfRunning)
        }
    }

    private class AdaptedHttpResponse<T>(
        private val originalRequest: HttpRequest,
        private val status: Int,
        private val responseHeaders: HttpHeaders,
        private val responseBody: T,
    ) : HttpResponse<T> {
        override fun statusCode(): Int = status

        override fun request(): HttpRequest = originalRequest

        override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()

        override fun headers(): HttpHeaders = responseHeaders

        override fun body(): T = responseBody

        override fun sslSession(): Optional<SSLSession> = Optional.empty()

        override fun uri() = originalRequest.uri()

        override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
    }

    companion object {
        private const val MAX_CONNECTIONS = 8
        private const val RESPONSE_BUFFER_BYTES = 16 * 1024
        private val NO_BODY_METHODS = setOf("GET", "HEAD", "DELETE")
        private val EXECUTOR: ExecutorService =
            Executors.newCachedThreadPool(
                NamingThreadFactory(
                    ClassLoaderSanityThreadFactory(DaemonThreadFactory()),
                    PinnedCnbHttpTransport::class.java.name,
                ),
            )
    }
}
