package com.catalystradar.application.evaluation

import tools.jackson.databind.ObjectMapper
import java.time.LocalDate

/**
 * One positive benchmark case: a liquid US stock with a large forward
 * move starting at T0. Controls pair separately.
 */
data class BenchmarkCase(
    val id: String,
    val ticker: String,
    val t0: LocalDate,
)

data class BenchmarkDefinition(
    val cases: List<BenchmarkCase>,
    val controls: Map<String, List<String>>,
    val lookbacks: List<Int> = listOf(10, 7, 5, 3, 1),
    val horizons: List<Int> = listOf(1, 3, 5, 10),
)

/**
 * Benchmark sets live as data fixtures (CSV/JSON), never code. This
 * loader parses the JSON form; raw market documents stay in the
 * database, not source control.
 */
object BenchmarkDefinitionLoader {
    private val mapper = ObjectMapper()

    fun load(json: String): BenchmarkDefinition {
        val root = mapper.readTree(json)
        val cases = mutableListOf<BenchmarkCase>()
        for (node in root.path("cases")) {
            cases += BenchmarkCase(
                id = node.path("id").asText(),
                ticker = node.path("ticker").asText(),
                t0 = LocalDate.parse(node.path("t0").asText()),
            )
        }
        val controls = mutableMapOf<String, List<String>>()
        for ((caseId, tickers) in root.path("controls").properties()) {
            val list = mutableListOf<String>()
            for (ticker in tickers) list += ticker.asText()
            controls[caseId] = list
        }
        val lookbacksNode = root.path("lookbacks")
        val lookbacks = if (lookbacksNode.isArray) {
            val list = mutableListOf<Int>()
            for (value in lookbacksNode) list += value.intValue()
            list
        } else {
            listOf(10, 7, 5, 3, 1)
        }
        val horizonsNode = root.path("horizons")
        val horizons = if (horizonsNode.isArray) {
            val list = mutableListOf<Int>()
            for (value in horizonsNode) list += value.intValue()
            list
        } else {
            listOf(1, 3, 5, 10)
        }
        return BenchmarkDefinition(cases, controls, lookbacks, horizons)
    }
}
