package com.catalystradar.persistence.discovery

import com.catalystradar.common.InvalidInputException
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.TransitionDirection
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

data class TransitionFeedQuery(
    val since: Instant,
    val asOf: Instant,
    val direction: TransitionDirection? = null,
    val toStates: Set<CatalystState>? = null,
    val tickers: Set<String>? = null,
    val limit: Int = 50,
    val cursor: String? = null,
)

data class TransitionFeedRow(
    val id: UUID,
    val ticker: String,
    val name: String,
    val sector: String?,
    val from: CatalystState,
    val to: CatalystState,
    val score: Double,
    val scoreVersion: String,
    val transitionedAt: Instant,
) {
    val direction: TransitionDirection get() = TransitionDirection.of(from, to)
}

data class TransitionFeedPage(
    val transitions: List<TransitionFeedRow>,
    val nextCursor: String?,
)

/**
 * Cross-company state transitions, newest first, for active companies.
 * Keyset-paged on (transitioned_at, id) because the history only grows.
 * A transition is a band crossing; moves inside one band never appear here.
 */
@Repository
class TransitionFeedStore(private val jdbc: JdbcClient) {

    fun find(query: TransitionFeedQuery): TransitionFeedPage {
        require(query.limit in 1..100) { "limit must be within 1..100" }
        val conditions = mutableListOf(
            "c.active",
            "t.transitioned_at >= :since",
            "t.transitioned_at <= :asOf",
        )
        val params = mutableMapOf<String, Any>(
            "since" to Timestamp.from(query.since),
            "asOf" to Timestamp.from(query.asOf),
            "limit" to query.limit + 1,
        )
        query.direction?.let {
            conditions += when (it) {
                TransitionDirection.UPGRADE -> "${bandRank("t.to_state")} > ${bandRank("t.from_state")}"
                TransitionDirection.DOWNGRADE -> "${bandRank("t.to_state")} < ${bandRank("t.from_state")}"
            }
        }
        query.toStates?.takeIf { it.isNotEmpty() }?.let {
            conditions += "t.to_state IN (:toStates)"
            params["toStates"] = it.map(CatalystState::name)
        }
        query.tickers?.takeIf { it.isNotEmpty() }?.let {
            conditions += "c.ticker IN (:tickers)"
            params["tickers"] = it.toList()
        }
        query.cursor?.let {
            val (at, id) = decodeCursor(it)
            conditions += "(t.transitioned_at, t.id) < (CAST(:cursorAt AS TIMESTAMPTZ), CAST(:cursorId AS UUID))"
            params["cursorAt"] = Timestamp.from(at)
            params["cursorId"] = id
        }
        val sql = """
            SELECT t.id, c.ticker, c.name, c.sector, t.from_state, t.to_state,
                   t.score, t.score_version, t.transitioned_at
            FROM state_transitions t
            JOIN companies c ON c.id = t.company_id
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY t.transitioned_at DESC, t.id DESC
            LIMIT :limit
            """.trimIndent()
        var statement = jdbc.sql(sql)
        params.forEach { (name, value) -> statement = statement.param(name, value) }
        val rows = statement.query { rs, _ ->
            TransitionFeedRow(
                id = rs.getObject("id", UUID::class.java),
                ticker = rs.getString("ticker"),
                name = rs.getString("name"),
                sector = rs.getString("sector"),
                from = CatalystState.valueOf(rs.getString("from_state")),
                to = CatalystState.valueOf(rs.getString("to_state")),
                score = rs.getDouble("score"),
                scoreVersion = rs.getString("score_version"),
                transitionedAt = rs.getTimestamp("transitioned_at").toInstant(),
            )
        }.list()
        val page = rows.take(query.limit)
        val next = if (rows.size > query.limit) page.last().let { "${it.transitionedAt}|${it.id}" } else null
        return TransitionFeedPage(page, next)
    }

    /** SQL rank of a state column, generated from the enum order rather than user input. */
    private fun bandRank(column: String): String =
        CatalystState.entries.joinToString(" ", prefix = "CASE $column ", postfix = " END") {
            "WHEN '${it.name}' THEN ${it.ordinal}"
        }

    private fun decodeCursor(cursor: String): Pair<Instant, UUID> {
        val parts = cursor.split("|")
        if (parts.size != 2) throw InvalidInputException("invalid cursor")
        return try {
            Instant.parse(parts[0]) to UUID.fromString(parts[1])
        } catch (e: RuntimeException) {
            throw InvalidInputException("invalid cursor")
        }
    }
}
