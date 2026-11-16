package com.catalystradar.adapters.http

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Request budgets per provider, keyed by provider name (`polygon`,
 * `finnhub`, `openai`). Every HTTP client of a provider shares one budget,
 * because the provider counts requests per API key, not per endpoint.
 */
@Component
@ConfigurationProperties(prefix = "catalyst.rate-limits")
data class RateLimitProperties(
    var providers: MutableMap<String, ProviderRateLimit> = mutableMapOf(),
)

data class ProviderRateLimit(
    /** Requests started in any rolling minute. 0 leaves the provider unpaced. */
    var requestsPerMinute: Int = 0,
    /** Times a 429 is retried before it reaches the adapter as a rate-limit failure. */
    var maxRetries: Int = 2,
    /** Longest wait before one retry. A provider asking for longer is not retried. */
    var maxRetryWait: Duration = Duration.ofSeconds(65),
) {
    fun validate(provider: String) {
        require(requestsPerMinute in 0..100_000) { "rate-limits.$provider.requests-per-minute must be within 0..100000" }
        require(maxRetries in 0..10) { "rate-limits.$provider.max-retries must be within 0..10" }
        require(!maxRetryWait.isNegative) { "rate-limits.$provider.max-retry-wait must not be negative" }
    }
}

/** Blocks the calling thread; adapters call providers on Dispatchers.IO. */
fun interface Sleeper {
    fun sleep(duration: Duration)

    companion object {
        val THREAD = Sleeper { if (!it.isZero && !it.isNegative) Thread.sleep(it.toMillis().coerceAtLeast(1)) }
    }
}

/**
 * Rolling-window limiter: at most [requestsPerMinute] request starts in any
 * 60 seconds. Callers queue fairly, so a long wait delays later callers
 * instead of letting them overtake.
 */
class ProviderRateLimiter(
    private val requestsPerMinute: Int,
    private val nanoTime: () -> Long = System::nanoTime,
    private val sleeper: Sleeper = Sleeper.THREAD,
) {
    private val lock = ReentrantLock(true)
    private val starts = ArrayDeque<Long>()

    /** Returns how long the caller waited. */
    fun acquire(): Duration {
        if (requestsPerMinute <= 0) return Duration.ZERO
        lock.withLock {
            var waited = 0L
            while (true) {
                val now = nanoTime()
                while (starts.isNotEmpty() && now - starts.peekFirst() >= WINDOW_NANOS) starts.pollFirst()
                if (starts.size < requestsPerMinute) {
                    starts.addLast(now)
                    return Duration.ofNanos(waited)
                }
                val wait = starts.peekFirst() + WINDOW_NANOS - now
                sleeper.sleep(Duration.ofNanos(wait))
                waited += wait
            }
        }
    }

    private companion object {
        val WINDOW_NANOS = Duration.ofMinutes(1).toNanos()
    }
}

/**
 * Paces every request of one provider and retries a 429 within bounds.
 * After the last retry the 429 response is returned unchanged, so the
 * adapter still reports it as a rate-limit failure.
 */
class ProviderRateLimitInterceptor(
    private val provider: String,
    private val limit: ProviderRateLimit,
    private val limiter: ProviderRateLimiter,
    private val sleeper: Sleeper = Sleeper.THREAD,
    private val onPaced: (Duration) -> Unit = {},
    private val onRetry: () -> Unit = {},
) : ClientHttpRequestInterceptor {

    private val log = LoggerFactory.getLogger(ProviderRateLimitInterceptor::class.java)

    override fun intercept(request: HttpRequest, body: ByteArray, execution: ClientHttpRequestExecution): ClientHttpResponse {
        var retries = 0
        while (true) {
            val paced = limiter.acquire()
            if (!paced.isZero) onPaced(paced)
            val response = execution.execute(request, body)
            if (response.statusCode.value() != TOO_MANY_REQUESTS || retries >= limit.maxRetries) return response
            val wait = retryWait(response.headers)
            if (wait > limit.maxRetryWait) return response
            response.close()
            retries++
            onRetry()
            // Path only: Polygon and Finnhub carry the API key in the query string.
            log.warn("provider={} rate limited path={}; retry {} of {} in {}s", provider, request.uri.path, retries, limit.maxRetries, wait.toSeconds())
            sleeper.sleep(wait)
        }
    }

    /**
     * Retry-After in seconds when the provider sends it. Otherwise one full
     * window: a per-minute quota has always reset a minute later.
     */
    private fun retryWait(headers: HttpHeaders): Duration =
        headers.getFirst(HttpHeaders.RETRY_AFTER)?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.let(Duration::ofSeconds)
            ?: Duration.ofMinutes(1)

    private companion object {
        const val TOO_MANY_REQUESTS = 429
    }
}

/**
 * One limiter per provider name, shared by all of that provider's clients.
 * A provider with no configured limit gets no interceptor at all.
 */
@Component
class ProviderRateLimits(
    private val properties: RateLimitProperties,
    meters: ObjectProvider<MeterRegistry>,
) {
    private val registry: MeterRegistry? = meters.getIfAvailable()
    private val limiters = ConcurrentHashMap<String, ProviderRateLimiter>()

    init {
        properties.providers.forEach { (name, limit) -> limit.validate(name) }
    }

    fun interceptor(provider: String): ClientHttpRequestInterceptor? {
        val limit = properties.providers[provider] ?: return null
        if (limit.requestsPerMinute <= 0 && limit.maxRetries == 0) return null
        val limiter = limiters.computeIfAbsent(provider) { ProviderRateLimiter(limit.requestsPerMinute) }
        val paced = registry?.let { Counter.builder(PACED_METRIC).tag("provider", provider).register(it) }
        val retried = registry?.let { Counter.builder(RETRIED_METRIC).tag("provider", provider).register(it) }
        return ProviderRateLimitInterceptor(
            provider = provider,
            limit = limit,
            limiter = limiter,
            onPaced = { paced?.increment() },
            onRetry = { retried?.increment() },
        )
    }

    companion object {
        const val PACED_METRIC = "catalyst.provider.requests.paced"
        const val RETRIED_METRIC = "catalyst.provider.requests.rate.limit.retries"

        /** For adapters built outside Spring, such as in tests: no pacing, no retries. */
        fun none(): ProviderRateLimits = ProviderRateLimits(RateLimitProperties(), NoMeters)
    }

    private object NoMeters : ObjectProvider<MeterRegistry> {
        override fun getObject(): MeterRegistry = throw UnsupportedOperationException()
        override fun getObject(vararg args: Any?): MeterRegistry = throw UnsupportedOperationException()
        override fun getIfAvailable(): MeterRegistry? = null
        override fun getIfUnique(): MeterRegistry? = null
    }
}

/** Adds the provider's budget to a client builder, if one is configured. */
fun org.springframework.web.client.RestClient.Builder.rateLimited(
    limits: ProviderRateLimits,
    provider: String,
): org.springframework.web.client.RestClient.Builder =
    limits.interceptor(provider)?.let { requestInterceptor(it) } ?: this
