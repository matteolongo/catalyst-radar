package com.catalystradar

import com.catalystradar.adapters.openai.OpenAiProperties
import com.catalystradar.adapters.polygon.PolygonProperties
import com.catalystradar.application.company.UsUniverseLoader
import com.catalystradar.application.ingestion.IngestionProperties
import com.catalystradar.application.pipeline.PipelineProperties
import com.catalystradar.domain.company.normalizeTicker
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
@Profile("production")
class ProductionStartup(
    private val universe: UsUniverseLoader,
    environment: Environment,
    polygon: PolygonProperties,
    openAi: OpenAiProperties,
    ingestion: IngestionProperties,
    pipeline: PipelineProperties,
) : SmartInitializingSingleton {
    private val log = LoggerFactory.getLogger(ProductionStartup::class.java)
    private val tickers = environment.getProperty("catalyst.universe.tickers", "").split(",")

    init {
        require(!environment.matchesProfiles("local")) { "Use the production profile by itself." }
        require(environment.getProperty("catalyst.api.auth-enabled", Boolean::class.java, false)) {
            "API-key authentication is required in production."
        }
        val adminKey = environment.getProperty("catalyst.internal.admin-key", "")
        require(adminKey.length >= 32 && adminKey.none(Char::isWhitespace)) {
            "Set CATALYST_INTERNAL_ADMIN_KEY to a random value of at least 32 characters."
        }
        val databasePassword = environment.getProperty("spring.datasource.password", "")
        require(databasePassword.isNotBlank() && databasePassword != "catalyst") {
            "Set CATALYST_DB_PASSWORD to a dedicated database password."
        }
        require(polygon.apiKey.isNotBlank()) { "Set POLYGON_API_KEY for production news ingestion." }
        require(openAi.apiKey.isNotBlank()) { "Set OPENAI_API_KEY for production event extraction." }
        require(ingestion.provider == "polygon" && ingestion.fallbackProvider == "none" && ingestion.tickerBatchSize == 1) {
            "Production ingestion requires Polygon, one ticker per request, and no fallback provider."
        }
        require(ingestion.pageSize in 1..20 && pipeline.batchSize in 1..20 && pipeline.maxEventsPerDocument in 1..5) {
            "Production allows up to 20 articles per ticker, 20 document attempts per cycle, and 5 events per document."
        }
    }

    override fun afterSingletonsInstantiated() {
        val normalized = tickers.filter { it.isNotBlank() }.map(::normalizeTicker).toSet()
        require(tickers.size in 1..5 && tickers.size == normalized.size) {
            "Set CATALYST_TICKERS to 1–5 distinct, comma-separated US stock tickers."
        }
        // Apply the watchlist before the web server or scheduled jobs start.
        val result = universe.loadWatchlist(normalized)
        log.info("Production watchlist ready tickers={} inserted={}", normalized, result.loaded)
    }
}
