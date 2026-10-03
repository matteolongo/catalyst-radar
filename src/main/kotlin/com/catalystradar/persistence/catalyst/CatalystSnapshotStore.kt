package com.catalystradar.persistence.catalyst

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
) {

    fun save(snapshot: CatalystSnapshot): CatalystSnapshot =
        template.insert(snapshot.toRow()).toDomain()

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

    fun recordTransition(
        companyId: UUID,
        from: CatalystState,
        to: CatalystState,
        score: CatalystScore,
        at: Instant,
    ) {
        template.insert(
            StateTransitionRow(
                id = UUID.randomUUID(),
                companyId = companyId,
                fromState = from.name,
                toState = to.name,
                score = score.value,
                scoreVersion = score.version,
                transitionedAt = at,
            ),
        )
    }

    fun findTransitions(companyId: UUID): List<StateTransitionRecord> =
        transitions.findByCompanyIdOrderByTransitionedAt(companyId).map { it.toRecord() }
}
