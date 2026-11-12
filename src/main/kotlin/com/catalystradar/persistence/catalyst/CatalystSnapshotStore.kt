package com.catalystradar.persistence.catalyst

import com.catalystradar.application.catalyst.SnapshotLedger
import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.domain.catalyst.CatalystState
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class CatalystSnapshotStore(
    private val snapshots: CatalystSnapshotRepository,
    private val transitions: StateTransitionRepository,
    private val template: JdbcAggregateTemplate,
    private val jdbc: JdbcTemplate,
) : SnapshotLedger {

    override fun save(snapshot: CatalystSnapshot): CatalystSnapshot =
        template.insert(snapshot.toRow()).toDomain()

    override fun lockCompany(companyId: UUID) {
        check(jdbc.queryForList("SELECT id FROM companies WHERE id=? FOR UPDATE", companyId).size == 1) { "company not found" }
    }

    override fun latestAtOrBefore(companyId: UUID, asOf: Instant): CatalystSnapshot? = jdbc.query(
        "SELECT * FROM catalyst_snapshots WHERE company_id=? AND as_of<=? ORDER BY as_of DESC,created_at DESC,id DESC LIMIT 1",
        { rs, _ -> CatalystSnapshotRow(rs.getObject("id", UUID::class.java), rs.getObject("company_id", UUID::class.java),
            rs.getDouble("score"), rs.getString("score_version"), rs.getString("state"), rs.getDouble("velocity_1d"),
            rs.getDouble("velocity_3d"), rs.getDouble("velocity_7d"), rs.getString("taxonomy_version"), rs.getTimestamp("as_of").toInstant(), rs.getDouble("downside_score")).toDomain() },
        companyId, Timestamp.from(asOf),
    ).singleOrNull()

    fun latestSnapshot(companyId: UUID): CatalystSnapshot? =
        snapshots.findFirstByCompanyIdOrderByAsOfDesc(companyId)?.toDomain()

    fun createdAt(snapshotId: UUID): Instant = requireNotNull(
        jdbc.queryForObject(
            "SELECT created_at FROM catalyst_snapshots WHERE id = ?",
            Timestamp::class.java,
            snapshotId,
        ),
    ).toInstant()

    fun history(companyId: UUID): List<CatalystSnapshot> =
        snapshots.findByCompanyIdOrderByAsOfDesc(companyId).map { it.toDomain() }

    /** Newest-first snapshots inside an optional window, bounded in the query. */
    fun history(companyId: UUID, from: Instant?, to: Instant?, limit: Int): List<CatalystSnapshot> =
        snapshots.findWindow(companyId, from, to, limit).map { it.toDomain() }

    override fun recordTransition(
        companyId: UUID,
        from: CatalystState,
        to: CatalystState,
        score: CatalystScore,
        at: Instant,
    ): UUID {
        return template.insert(
            StateTransitionRow(
                id = UUID.randomUUID(),
                companyId = companyId,
                fromState = from.name,
                toState = to.name,
                score = score.value,
                scoreVersion = score.version,
                transitionedAt = at,
            ),
        ).id
    }

    /** The newest [limit] transitions in the window, returned oldest first. */
    fun findTransitions(companyId: UUID, from: Instant?, to: Instant?, limit: Int): List<StateTransitionRecord> =
        transitions.findWindow(companyId, from, to, limit).map { it.toRecord() }.reversed()

    fun findTransitions(companyId: UUID): List<StateTransitionRecord> =
        transitions.findByCompanyIdOrderByTransitionedAt(companyId).map { it.toRecord() }
}
