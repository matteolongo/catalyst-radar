package com.catalystradar.application.operations

import com.catalystradar.application.ingestion.IngestionStatus
import com.catalystradar.domain.company.normalizeTicker
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID

enum class WindowPreset(val duration: Duration) { HOURS_24(Duration.ofHours(24)), DAYS_7(Duration.ofDays(7)) }
data class PageRequest(val limit: Int = 25, val cursor: String? = null) {
    init { require(limit in 1..100) { "limit must be between 1 and 100" } }
}
data class DocumentQuery(
    val states: Set<DocumentState> = emptySet(), val provider: String? = null, val ticker: String? = null,
    val title: String? = null, val dueOnly: Boolean = false, val runId: UUID? = null,
    val ingestionRunId: UUID? = null, val from: Instant? = null, val to: Instant? = null,
    val page: PageRequest = PageRequest(),
) {
    init {
        require(provider == null || provider in NEWS_PROVIDERS) { "unsupported provider" }
        require(from == null || to == null || from < to) { "from must precede to" }
        if (title != null) require(title.trim().let { it.isNotEmpty() && it.length <= 120 && it.none(Char::isISOControl) }) { "invalid title" }
    }
    val normalizedTicker: String? = ticker?.let(::normalizeTicker)
    val normalizedTitle: String? = title?.trim()
}
data class ModelQuery(
    val window: ActivityWindow?, val provider: String? = null, val operation: String? = null,
    val model: String? = null, val success: Boolean? = null, val documentId: UUID? = null,
    val attemptId: UUID? = null, val runId: UUID? = null, val page: PageRequest = PageRequest(),
) {
    init {
        require(provider == null || provider == "openai") { "unsupported provider" }
        require(operation == null || operation in MODEL_OPERATIONS) { "unsupported operation" }
        require(model == null || model.length in 1..128) { "invalid model" }
    }
}
data class RunQuery(val window: ActivityWindow, val kind: OperationKind? = null, val status: OperationStatus? = null, val page: PageRequest = PageRequest())
data class IngestionQuery(
    val window: ActivityWindow?, val provider: String? = null, val status: IngestionStatus? = null,
    val runId: UUID? = null, val ingestionRunId: UUID? = null, val page: PageRequest = PageRequest(),
) {
    init {
        require(provider == null || provider in NEWS_PROVIDERS) { "unsupported provider" }
        if (ingestionRunId != null) require(window == null && provider == null && status == null && runId == null && page.cursor == null) {
            "exact ingestion lookup cannot be combined with filters or a cursor"
        }
    }
}
data class CursorPosition(val at: Instant, val id: UUID)

class OperationsCursor {
    fun encode(kind: String, position: CursorPosition, filters: Map<String, String>): String {
        require(kind in CURSOR_KINDS) { "unsupported cursor kind" }
        val payload = "v1|$kind|${position.at.truncatedTo(ChronoUnit.MICROS)}|${position.id}|${filterHash(filters)}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(UTF_8))
    }

    fun decode(token: String, kind: String, filters: Map<String, String>): CursorPosition {
        require(token.length <= 1024 && kind in CURSOR_KINDS) { "invalid cursor" }
        return try {
            val parts = Base64.getUrlDecoder().decode(token).toString(UTF_8).split('|')
            require(parts.size == 5 && parts[0] == "v1" && parts[1] == kind && parts[4] == filterHash(filters)) { "invalid cursor" }
            CursorPosition(Instant.parse(parts[2]), UUID.fromString(parts[3]))
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("invalid cursor", e)
        }
    }

    private fun filterHash(filters: Map<String, String>): String {
        val canonical = filters.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

class ActivityWindowResolver {
    fun resolve(preset: WindowPreset?, from: Instant?, to: Instant?, now: Instant): ActivityWindow {
        require((from == null) == (to == null)) { "from and to must be supplied together" }
        require(preset == null || from == null) { "preset and explicit boundaries cannot be combined" }
        val current = now.truncatedTo(ChronoUnit.MICROS)
        val window = if (from != null && to != null) ActivityWindow(from, to)
        else ActivityWindow(current.minus(preset?.duration ?: WindowPreset.HOURS_24.duration), current)
        require(window.from < window.to) { "from must precede to" }
        require(Duration.between(window.from, window.to) <= Duration.ofDays(7)) { "window cannot exceed seven days" }
        require(window.to <= now.plusSeconds(5)) { "to cannot be more than five seconds in the future" }
        return window
    }
}

private val NEWS_PROVIDERS = setOf("polygon", "finnhub")
private val MODEL_OPERATIONS = setOf("extract", "embed")
private val CURSOR_KINDS = setOf("documents", "runs", "ingestion", "models", "attempts", "issues")
