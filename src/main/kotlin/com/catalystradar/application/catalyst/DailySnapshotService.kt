package com.catalystradar.application.catalyst

import com.catalystradar.application.operations.*
import com.catalystradar.persistence.company.CompanyStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

enum class DailySnapshotStatus {
    SUCCESS,
    PARTIAL,
}

data class DailySnapshotResult(
    val status: DailySnapshotStatus,
    val companiesConsidered: Int,
    val companiesRecalculated: Int,
    val failures: Int,
    val error: String? = null,
)

/**
 * Refreshes stored catalyst snapshots without fetching new documents. A single
 * explicit [asOf] keeps score decay and velocity calculation reproducible.
 */
@Service
class DailySnapshotService(
    private val companies: CompanyStore,
    private val catalyst: CatalystService,
    private val recorder: OperationRunRecorder,
) {

    private val log = LoggerFactory.getLogger(DailySnapshotService::class.java)

    fun recalculateActive(asOf: Instant = Instant.now(), trigger: OperationTrigger = OperationTrigger.MANUAL): DailySnapshotResult {
        val id = recorder.begin(OperationKind.DAILY_SNAPSHOTS, trigger, asOf)
        return try {
            val activeCompanies = companies.findAllActive().sortedBy { it.ticker }
            var counts = OperationCounts(companiesConsidered = activeCompanies.size)
            recorder.progress(id, counts)
            for (company in activeCompanies) {
                try {
                    catalyst.recalculate(company.id, asOf)
                    counts = counts.copy(companiesRescored = counts.companiesRescored + 1)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RuntimeException) {
                    counts = counts.copy(companiesFailed = counts.companiesFailed + 1)
                    recorder.issue(id, OperationPhase.SCORING, "SCORING_FAILURE", companyId = company.id)
                    log.warn("daily snapshot failed for company {} run {}", company.id, id)
                }
                recorder.progress(id, counts)
            }
            val status = if (counts.companiesFailed == 0) DailySnapshotStatus.SUCCESS else DailySnapshotStatus.PARTIAL
            val errorCode = if (counts.companiesFailed == 0) null else "SCORING_FAILURE"
            recorder.finish(id, OperationStatus.valueOf(status.name), counts, errorCode, captureComplete = true)
            DailySnapshotResult(
                status = status,
                companiesConsidered = counts.companiesConsidered,
                companiesRecalculated = counts.companiesRescored,
                failures = counts.companiesFailed,
                error = errorCode?.let(OperationalErrors::message),
            )
        } catch (e: CancellationException) {
            finishIncomplete(id, OperationStatus.CANCELLED, "CANCELLED")
            throw e
        } catch (e: RuntimeException) {
            finishIncomplete(id, OperationStatus.FAILED, "SCORING_FAILURE")
            throw e
        }
    }

    private fun finishIncomplete(id: UUID, status: OperationStatus, code: String) {
        runCatching { recorder.issue(id, OperationPhase.SCORING, code) }
            .onFailure { log.warn("daily snapshot issue recording failed for run {}", id) }
        runCatching { recorder.finish(id, status, null, code, captureComplete = false) }
            .onFailure { log.warn("daily snapshot final recording failed for run {}", id) }
    }
}
