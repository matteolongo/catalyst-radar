package com.catalystradar.persistence.discovery

import com.catalystradar.domain.catalyst.CatalystState
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

enum class DiscoverySort {
    SCORE,
    VELOCITY,
}

data class DiscoveryQuery(
    val states: Set<CatalystState>? = null,
    val minScore: Double? = null,
    val minVelocity7d: Double? = null,
    val sector: String? = null,
    val sort: DiscoverySort = DiscoverySort.SCORE,
    val limit: Int = 20,
    val offset: Int = 0,
)

data class DiscoveryRow(
    val companyId: UUID,
    val ticker: String,
    val name: String,
    val sector: String?,
    val score: Double,
    val scoreVersion: String,
    val state: CatalystState,
    val velocity7d: Double,
    val events7d: Int,
    val taxonomyVersion: String,
    val asOf: Instant,
)

data class DiscoveryPage(
    val results: List<DiscoveryRow>,
    val total: Int,
)

/**
 * Primary discovery read: latest snapshot per company, ranked and
 * filtered. One bounded page query (with windowed total) plus one
 * grouped recent-events count — never N+1. Offset paging is a
 * deliberate v0.1 choice for a small leaderboard.
 */
@Repository
class DiscoveryStore(private val jdbc: JdbcClient) {

    fun discover(query: DiscoveryQuery): DiscoveryPage {
        require(query.limit in 1..100) { "limit must be within 1..100" }
        require(query.offset >= 0) { "offset must be non-negative" }
        val conditions = mutableListOf("1 = 1")
        val params = mutableMapOf<String, Any>()
        query.states?.takeIf { it.isNotEmpty() }?.let {
            conditions += "s.state IN (:states)"
            params["states"] = it.map(CatalystState::name)
        }
        query.minScore?.let {
            conditions += "s.score >= :minScore"
            params["minScore"] = it
        }
        query.minVelocity7d?.let {
            conditions += "s.velocity_7d >= :minVelocity7d"
            params["minVelocity7d"] = it
        }
        query.sector?.let {
            conditions += "c.sector = :sector"
            params["sector"] = it
        }
        val order = when (query.sort) {
            DiscoverySort.SCORE -> "s.score DESC"
            DiscoverySort.VELOCITY -> "s.velocity_7d DESC"
        }
        params["limit"] = query.limit
        params["offset"] = query.offset
        val sql = """
            SELECT COUNT(*) OVER() AS total,
              c.id AS company_id, c.ticker AS ticker, c.name AS name, c.sector AS sector,
              s.score AS score, s.score_version AS score_version, s.state AS state,
              s.velocity_7d AS velocity_7d, s.taxonomy_version AS taxonomy_version, s.as_of AS as_of
            FROM (
              SELECT DISTINCT ON (company_id) *
              FROM catalyst_snapshots
              ORDER BY company_id, as_of DESC
            ) s
            JOIN companies c ON c.id = s.company_id
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY $order, c.ticker
            LIMIT :limit OFFSET :offset
            """.trimIndent()
        var statement = jdbc.sql(sql)
        params.forEach { (name, value) -> statement = statement.param(name, value) }
        val page = statement.query { rs, _ ->
            rs.getInt("total") to DiscoveryRow(
                companyId = rs.getObject("company_id", UUID::class.java),
                ticker = rs.getString("ticker"),
                name = rs.getString("name"),
                sector = rs.getString("sector"),
                score = rs.getDouble("score"),
                scoreVersion = rs.getString("score_version"),
                state = CatalystState.valueOf(rs.getString("state")),
                velocity7d = rs.getDouble("velocity_7d"),
                events7d = 0,
                taxonomyVersion = rs.getString("taxonomy_version"),
                asOf = rs.getTimestamp("as_of").toInstant(),
            )
        }.list()
        if (page.isEmpty()) return DiscoveryPage(emptyList(), 0)
        val counts = recentEventCounts(page.map { it.second.companyId })
        return DiscoveryPage(
            results = page.map { (_, row) -> row.copy(events7d = counts[row.companyId] ?: 0) },
            total = page.first().first,
        )
    }

    private fun recentEventCounts(companyIds: List<UUID>): Map<UUID, Int> {
        if (companyIds.isEmpty()) return emptyMap()
        val since = java.sql.Timestamp.from(Instant.now().minusSeconds(7L * 86_400))
        return jdbc.sql(
            """
            SELECT company_id, COUNT(*) AS events FROM events
            WHERE discovered_at >= :since AND company_id IN (:ids)
            GROUP BY company_id
            """.trimIndent(),
        )
            .param("since", since)
            .param("ids", companyIds)
            .query { rs, _ -> rs.getObject("company_id", UUID::class.java) to rs.getInt("events") }
            .list().toMap()
    }
}
