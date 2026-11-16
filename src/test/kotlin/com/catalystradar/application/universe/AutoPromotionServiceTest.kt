package com.catalystradar.application.universe

import com.catalystradar.domain.company.Company
import com.catalystradar.observability.CatalystMetrics
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.company.CompanyStore
import com.catalystradar.persistence.universe.UnresolvedTickerStore
import com.catalystradar.application.ingestion.SourceDocumentRegistrationService
import com.catalystradar.ports.CompanyLookupProvider
import com.catalystradar.ports.CompanyReference
import com.catalystradar.ports.ProviderException
import com.catalystradar.ports.RawArticle
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

@Transactional
class AutoPromotionServiceTest : PostgresIntegrationTest() {

    @Autowired
    private lateinit var companies: CompanyStore

    @Autowired
    private lateinit var writer: UniversePromotionWriter

    @Autowired
    private lateinit var unresolved: UnresolvedTickerStore

    @Autowired
    private lateinit var registrations: SourceDocumentRegistrationService

    private val now = Instant.parse("2026-11-16T12:00:00Z")
    private val meters = SimpleMeterRegistry()
    private val lookups = mutableListOf<String>()

    private fun lookup(rejected: Set<String> = emptySet(), failing: Boolean = false) = object : CompanyLookupProvider {
        override suspend fun findUsEquity(ticker: String): CompanyReference? {
            lookups += ticker
            if (failing) throw ProviderException.TemporaryUnavailable("down")
            return if (ticker in rejected) null else CompanyReference(ticker = ticker, name = "$ticker Inc.")
        }
    }

    private fun service(
        properties: AutoPromotionProperties,
        lookup: CompanyLookupProvider = lookup(),
        cap: Int = 1000,
    ) = AutoPromotionService(
        properties,
        unresolved,
        UniversePromotionService(companies, lookup, writer, UniverseProperties(maxActiveCompanies = cap)),
        CatalystMetrics(meters),
    )

    @Test
    fun `disabled by default and then calls nothing`() = runTest {
        seed("SMCI" to 5)

        assertEquals(emptyList(), service(AutoPromotionProperties()).promoteEligible(now))
        assertEquals(emptyList(), lookups)
    }

    @Test
    fun `promotes candidates over the threshold up to the per-cycle limit`() = runTest {
        seed("SMCI" to 4, "ARM" to 3, "PLTR" to 2, "LOW" to 1)

        val promoted = service(AutoPromotionProperties(enabled = true, minDocuments = 2, maxPerCycle = 2)).promoteEligible(now)

        assertEquals(listOf("SMCI", "ARM"), promoted.map { it.company.ticker })
        assertNull(companies.findByTicker("PLTR"))
    }

    @Test
    fun `old documents do not count toward the threshold`() = runTest {
        seed("SMCI" to 3, at = now.minusSeconds(10L * 86_400))

        assertEquals(emptyList(), service(AutoPromotionProperties(enabled = true, minDocuments = 2)).promoteEligible(now))
    }

    @Test
    fun `rejected candidates are skipped and provider failures stop the cycle`() = runTest {
        seed("SPY" to 5, "SMCI" to 4, "ARM" to 3)

        val promoted = service(AutoPromotionProperties(enabled = true, minDocuments = 2), lookup(rejected = setOf("SPY"))).promoteEligible(now)
        assertEquals(listOf("SMCI", "ARM"), promoted.map { it.company.ticker })
        assertEquals(1.0, meters.counter("catalyst_universe_auto_promotions_total", "outcome", "not_us_equity").count())

        lookups.clear()
        seed("NEW" to 5, "TWO" to 4, prefix = "later")
        val none = service(AutoPromotionProperties(enabled = true, minDocuments = 2), lookup(failing = true)).promoteEligible(now)
        assertEquals(emptyList(), none)
        assertEquals(1, lookups.size, "a failing provider is not asked again in the same cycle")
    }

    @Test
    fun `the universe cap stops promotion`() = runTest {
        companies.save(Company(ticker = "DELL", name = "Dell"))
        seed("SMCI" to 5)

        assertEquals(emptyList(), service(AutoPromotionProperties(enabled = true, minDocuments = 2), cap = 1).promoteEligible(now))
        assertEquals(1.0, meters.counter("catalyst_universe_auto_promotions_total", "outcome", "cap_reached").count())
    }

    private fun seed(vararg counts: Pair<String, Int>, at: Instant = now.minusSeconds(3_600), prefix: String = "doc") {
        counts.forEach { (ticker, n) ->
            repeat(n) { i ->
                registrations.registerIfNew(
                    "polygon",
                    RawArticle("polygon", "$prefix-$ticker-$i", null, "$ticker story $i", "$ticker body $i", at, listOf(ticker)),
                    at,
                )
            }
        }
    }
}
