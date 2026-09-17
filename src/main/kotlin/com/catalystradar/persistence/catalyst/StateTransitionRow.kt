package com.catalystradar.persistence.catalyst

import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.catalyst.CatalystState
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * state_transitions row. Every state change is an explicit record;
 * transitions are derived by the scoring service, never assigned directly.
 */
@Table("state_transitions")
data class StateTransitionRow(
    @Id val id: UUID,
    @Column("company_id") val companyId: UUID,
    @Column("from_state") val fromState: String,
    @Column("to_state") val toState: String,
    val score: Double,
    @Column("score_version") val scoreVersion: String,
    @Column("transitioned_at") val transitionedAt: Instant,
)

/**
 * Read projection of a transition. A richer domain type with transition
 * behavior arrives with CR-13; persistence needs no more than this.
 */
data class StateTransitionRecord(
    val companyId: UUID,
    val from: CatalystState,
    val to: CatalystState,
    val score: CatalystScore,
    val at: Instant,
)

fun StateTransitionRow.toRecord() = StateTransitionRecord(
    companyId = companyId,
    from = CatalystState.valueOf(fromState),
    to = CatalystState.valueOf(toState),
    score = CatalystScore(value = score, version = scoreVersion),
    at = transitionedAt,
)
