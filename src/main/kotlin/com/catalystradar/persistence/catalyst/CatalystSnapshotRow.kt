package com.catalystradar.persistence.catalyst

import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.catalyst.ScoreVelocity
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * catalyst_snapshots row: a versioned point-in-time score/state record.
 * History is append-only; nothing updates a snapshot after writing it.
 */
@Table("catalyst_snapshots")
data class CatalystSnapshotRow(
    @Id val id: UUID,
    @Column("company_id") val companyId: UUID,
    val score: Double,
    @Column("score_version") val scoreVersion: String,
    val state: String,
    @Column("velocity_1d") val velocity1d: Double,
    @Column("velocity_3d") val velocity3d: Double,
    @Column("velocity_7d") val velocity7d: Double,
    @Column("taxonomy_version") val taxonomyVersion: String,
    @Column("as_of") val asOf: Instant,
)

fun CatalystSnapshotRow.toDomain() = CatalystSnapshot(
    id = id,
    companyId = companyId,
    score = CatalystScore(value = score, version = scoreVersion),
    state = CatalystState.valueOf(state),
    velocity = ScoreVelocity(velocity1d = velocity1d, velocity3d = velocity3d, velocity7d = velocity7d),
    asOf = asOf,
    taxonomyVersion = taxonomyVersion,
)

fun CatalystSnapshot.toRow() = CatalystSnapshotRow(
    id = id,
    companyId = companyId,
    score = score.value,
    scoreVersion = score.version,
    state = state.name,
    velocity1d = velocity.velocity1d,
    velocity3d = velocity.velocity3d,
    velocity7d = velocity.velocity7d,
    taxonomyVersion = taxonomyVersion,
    asOf = asOf,
)
