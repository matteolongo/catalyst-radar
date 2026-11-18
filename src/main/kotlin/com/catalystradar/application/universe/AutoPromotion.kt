package com.catalystradar.application.universe

import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.universe.CandidateQuery
import com.catalystradar.persistence.universe.UnresolvedTickerStore
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Component
@ConfigurationProperties(prefix = "catalyst.universe.auto-promotion")
data class AutoPromotionProperties(
    var enabled: Boolean = false,
    /** Stored documents naming the ticker within [window]; documents, not events. */
    var minDocuments: Int = 5,
    var window: Duration = Duration.ofDays(7),
    /** Promotions per pipeline cycle; each one adds provider and model cost to later cycles. */
    var maxPerCycle: Int = 3,
    /**
     * History fetched per symbol, in the cycle that promotes it, before its
     * documents are extracted. Zero turns the deep fetch off.
     */
    var deepFetchLookback: Duration = Duration.ofDays(7),
)

/**
 * Deterministic rule from ADR 0002: a candidate named by at least
 * `minDocuments` stored documents in `window` is promoted through the same
 * service as a manual promotion, at most `maxPerCycle` times per cycle.
 * Disabled by default. Candidates the provider rejects stay candidates and
 * are checked again in later cycles, bounded by the per-cycle lookup limit.
 */
@Service
class AutoPromotionService(
    private val properties: AutoPromotionProperties,
    private val unresolved: UnresolvedTickerStore,
    private val promotions: UniversePromotionService,
    private val metrics: CatalystMetrics,
) {

    private val log = LoggerFactory.getLogger(AutoPromotionService::class.java)

    /** Null while auto-promotion is off, so nothing is fetched for it. */
    val deepFetchLookback: Duration?
        get() = properties.deepFetchLookback.takeIf { properties.enabled }

    suspend fun promoteEligible(now: Instant): List<PromotionResult> {
        if (!properties.enabled) return emptyList()
        require(properties.minDocuments >= 1) { "auto-promotion minDocuments must be positive" }
        require(properties.maxPerCycle in 1..20) { "auto-promotion maxPerCycle must be within 1..20" }
        require(!properties.window.isNegative && !properties.window.isZero) { "auto-promotion window must be positive" }
        require(!properties.deepFetchLookback.isNegative && properties.deepFetchLookback <= Duration.ofDays(30)) {
            "auto-promotion deepFetchLookback must be within 0..P30D"
        }
        val eligible = unresolved.candidates(
            CandidateQuery(
                since = now.minus(properties.window),
                minDocuments = properties.minDocuments,
                limit = properties.maxPerCycle * LOOKUPS_PER_PROMOTION,
            ),
        ).candidates
        val promoted = mutableListOf<PromotionResult>()
        for (candidate in eligible) {
            if (promoted.size >= properties.maxPerCycle) break
            try {
                val result = promotions.promote(candidate.ticker, now)
                if (result.created) {
                    promoted += result
                    metrics.universePromotion("promoted")
                }
            } catch (e: NotUsEquityException) {
                metrics.universePromotion("not_us_equity")
            } catch (e: UniverseCapReachedException) {
                metrics.universePromotion("cap_reached")
                log.info("auto-promotion stopped: universe cap {} reached", e.cap)
                break
            } catch (e: ReferenceLookupFailedException) {
                metrics.universePromotion("lookup_failed")
                log.warn("auto-promotion stopped: reference lookup failed for {}", e.ticker)
                break
            }
        }
        if (promoted.isNotEmpty()) log.info("auto-promotion promoted tickers={}", promoted.map { it.company.ticker })
        return promoted
    }

    companion object {
        /** Bounds reference lookups per cycle when top candidates keep being rejected. */
        const val LOOKUPS_PER_PROMOTION = 3
    }
}
