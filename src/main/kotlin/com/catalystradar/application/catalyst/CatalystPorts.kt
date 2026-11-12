package com.catalystradar.application.catalyst

import com.catalystradar.domain.catalyst.CatalystScore
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.domain.catalyst.CatalystState
import com.catalystradar.domain.event.CatalystEvent
import java.time.Instant
import java.util.UUID

/** Events a company score can depend on, bounded to the decay horizon. */
interface CompanyEventSource {
    /**
     * Events discovered by [asOf] whose own time, or a cluster sibling's,
     * falls at or after [since]. Siblings are included so canonical
     * selection sees whole clusters.
     */
    fun findScoringInputs(companyId: UUID, since: Instant, asOf: Instant): List<CatalystEvent>
}

/** Append-only snapshot and transition ledger the scoring service writes. */
interface SnapshotLedger {
    fun lockCompany(companyId: UUID)

    fun latestAtOrBefore(companyId: UUID, asOf: Instant): CatalystSnapshot?

    fun save(snapshot: CatalystSnapshot): CatalystSnapshot

    fun recordTransition(companyId: UUID, from: CatalystState, to: CatalystState, score: CatalystScore, at: Instant): UUID
}
