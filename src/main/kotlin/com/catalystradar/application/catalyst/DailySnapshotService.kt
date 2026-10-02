package com.catalystradar.application.catalyst

import com.catalystradar.persistence.company.CompanyStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

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
) {

    private val log = LoggerFactory.getLogger(DailySnapshotService::class.java)

    fun recalculateActive(asOf: Instant = Instant.now()): DailySnapshotResult {
        val activeCompanies = companies.findAllActive().sortedBy { it.ticker }
        var recalculated = 0
        var failures = 0
        var firstError: String? = null
        for (company in activeCompanies) {
            try {
                catalyst.recalculate(company.id, asOf)
                recalculated++
            } catch (e: RuntimeException) {
                failures++
                val error = sanitize(e)
                if (firstError == null) firstError = error
                log.warn("daily snapshot failed for company {}: {}", company.id, error)
            }
        }
        return DailySnapshotResult(
            status = if (failures == 0) DailySnapshotStatus.SUCCESS else DailySnapshotStatus.PARTIAL,
            companiesConsidered = activeCompanies.size,
            companiesRecalculated = recalculated,
            failures = failures,
            error = firstError,
        )
    }

    private fun sanitize(error: Throwable): String =
        (error.message ?: error::class.simpleName ?: "daily snapshot failure")
            .replace(Regex("\\s+"), " ")
            .take(500)
}
