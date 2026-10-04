package com.catalystradar.application.operations

import com.catalystradar.ports.ProviderException
import java.util.concurrent.CancellationException

object OperationalErrors {
    private val messages = mapOf(
        "RATE_LIMITED" to "Provider rate limit; wait for the scheduled retry.",
        "TEMPORARY_UNAVAILABLE" to "Provider temporarily unavailable; a retry may be scheduled.",
        "AUTHENTICATION_FAILED" to "Provider authentication failed; check server credentials.",
        "INVALID_RESPONSE" to "Provider output did not pass extraction/response validation.",
        "PERMANENT_FAILURE" to "Provider rejected the operation.",
        "PROCESSING_FAILURE" to "Local document processing failed.",
        "SCORING_FAILURE" to "Company recalculation failed.",
        "INGESTION_FAILURE" to "Ingestion could not complete.",
        "UNFINISHED_PREVIOUS_RUN" to "Earlier work did not record a final outcome.",
        "CANCELLED" to "Operation was interrupted by cancellation.",
        "UNKNOWN_FAILURE" to "Failure recorded without a classified reason.",
    )

    fun code(error: Throwable): String = when (error) {
        is CancellationException -> "CANCELLED"
        is ProviderException.RateLimited -> "RATE_LIMITED"
        is ProviderException.TemporaryUnavailable -> "TEMPORARY_UNAVAILABLE"
        is ProviderException.AuthenticationFailed -> "AUTHENTICATION_FAILED"
        is ProviderException.InvalidResponse -> "INVALID_RESPONSE"
        is ProviderException.PermanentFailure -> "PERMANENT_FAILURE"
        else -> "UNKNOWN_FAILURE"
    }

    fun message(code: String): String = messages[code] ?: messages.getValue("UNKNOWN_FAILURE")
}
