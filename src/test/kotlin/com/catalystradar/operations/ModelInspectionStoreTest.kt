package com.catalystradar.operations

import com.catalystradar.application.operations.*
import com.catalystradar.persistence.PostgresIntegrationTest
import com.catalystradar.persistence.operations.ModelInspectionStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.*

@Transactional
class ModelInspectionStoreTest : PostgresIntegrationTest() {
    @Autowired private lateinit var store: ModelInspectionStore
    @Autowired private lateinit var jdbc: JdbcClient
    private val at = Instant.parse("2026-10-04T10:00:00Z")
    private val window = ActivityWindow(at, at.plusSeconds(1))
    private val fixtures get() = OperationsFixtures(jdbc)

    @Test
    fun `model totals include every recorded call and expose missing cost`() {
        repeat(60) { fixtures.model(at, cost = BigDecimal("0.01"), input = 100, output = 10) }
        fixtures.model(at, cost = null, input = null, output = null, success = false)
        val query = ModelQuery(window, page = PageRequest(25))
        val summary = store.summary(query, window.to)
        assertEquals(61L, summary.totals.calls)
        assertEquals(60L, summary.totals.successfulCalls)
        assertEquals(60L, summary.totals.costKnownCalls)
        assertEquals(1L, summary.totals.failedCalls)
        assertEquals(6000L, summary.totals.inputTokens)
        assertEquals(600L, summary.totals.outputTokens)
        assertEquals(0, BigDecimal("0.60").compareTo(assertNotNull(summary.totals.estimatedCostUsd)))
        assertEquals(25, store.search(query, window.to).items.size)
        assertEquals(summary.totals, summary.groups.single().usage)
    }

    @Test
    fun `empty measures are zero while completely unknown measures stay null`() {
        val empty = store.summary(ModelQuery(window), window.to)
        assertEquals(ModelUsageSummary(0, 0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO, 0, 0, null, null), empty.totals)
        assertTrue(empty.groups.isEmpty())
        assertFalse(empty.groupsTruncated)
        fixtures.model(at, null, null, null, latency = null)
        val unknown = store.summary(ModelQuery(window), window.to).totals
        assertEquals(1L, unknown.calls)
        assertNull(unknown.inputTokens)
        assertNull(unknown.outputTokens)
        assertNull(unknown.estimatedCostUsd)
        assertEquals(0L, unknown.inputTokensKnownCalls)
        assertEquals(1L, unknown.outputTokensExpectedCalls)
        assertEquals(0L, unknown.outputTokensKnownCalls)
        assertEquals(0L, unknown.costKnownCalls)
        assertEquals(0L, unknown.latencyKnownCalls)
        assertNull(unknown.p50LatencyMs)
        assertNull(unknown.p95LatencyMs)
    }

    @Test
    fun `paid failures and partial known usage contribute their subtotals and latency percentiles`() {
        fixtures.model(at, BigDecimal("0.01"), 100, 10, success = false, latency = 10)
        fixtures.model(at, null, null, null, latency = 30)
        fixtures.model(at, BigDecimal("0.02"), 200, 20, operation = "embed", latency = 50)
        val usage = store.summary(ModelQuery(window), window.to).totals
        assertEquals(3L, usage.calls)
        assertEquals(2L, usage.successfulCalls)
        assertEquals(1L, usage.failedCalls)
        assertEquals(300L, usage.inputTokens)
        assertEquals(2L, usage.inputTokensKnownCalls)
        assertEquals(10L, usage.outputTokens)
        assertEquals(2L, usage.outputTokensExpectedCalls)
        assertEquals(1L, usage.outputTokensKnownCalls)
        assertEquals(0, BigDecimal("0.03").compareTo(assertNotNull(usage.estimatedCostUsd)))
        assertEquals(2L, usage.costKnownCalls)
        assertEquals(3L, usage.latencyKnownCalls)
        assertEquals(30.0, usage.p50LatencyMs)
        assertEquals(48.0, usage.p95LatencyMs)
        val embeds = store.summary(ModelQuery(window, operation = "embed"), window.to).totals
        assertEquals(0L, embeds.outputTokens)
        assertEquals(0L, embeds.outputTokensExpectedCalls)
        assertEquals(0L, embeds.outputTokensKnownCalls)
    }

    @Test
    fun `known zero usage and cost are retained in feed and summary`() {
        val id = fixtures.model(at, BigDecimal.ZERO, 0, 0, latency = 0)
        val usage = store.summary(ModelQuery(window), window.to).totals
        assertEquals(0L, usage.inputTokens)
        assertEquals(0L, usage.outputTokens)
        assertEquals(1L, usage.inputTokensKnownCalls)
        assertEquals(1L, usage.outputTokensKnownCalls)
        assertEquals(1L, usage.costKnownCalls)
        assertEquals(0, BigDecimal.ZERO.compareTo(assertNotNull(usage.estimatedCostUsd)))
        assertEquals(0.0, usage.p50LatencyMs)
        val call = assertNotNull(store.findById(id))
        assertEquals(0, call.inputTokens)
        assertEquals(0, call.outputTokens)
        assertEquals(0L, call.latencyMs)
        assertEquals(0, BigDecimal.ZERO.compareTo(assertNotNull(call.estimatedCost)))
    }

    @Test
    fun `feed and summary share exact model success provider and exclusive time filters`() {
        fixtures.model(at.minusSeconds(1), null, null, null, success = false)
        val match = fixtures.model(at, null, null, null, success = false)
        fixtures.model(window.to, null, null, null, success = false)
        fixtures.model(at, null, null, null)
        val otherModel = fixtures.model(at, null, null, null, success = false)
        setModel(otherModel, "gpt-4o-mini-extra")
        val otherProvider = fixtures.model(at, null, null, null, success = false)
        jdbc.sql("UPDATE model_runs SET provider='legacy' WHERE id=:id").param("id", otherProvider).update()
        val query = ModelQuery(window, provider = "openai", model = "gpt-4o-mini", success = false)
        assertEquals(listOf(match), store.search(query, window.to).items.map { it.id })
        assertEquals(1L, store.summary(query, window.to).totals.calls)
        assertEquals(1L, store.summary(query, window.to).groups.single().usage.calls)
        assertEquals(window, store.search(query, window.to).window)
        assertEquals(window.to, store.summary(query, window.to).generatedAt)
    }

    @Test
    fun `run scope derives only from explicit attempt even when document has multiple associations`() {
        val document = fixtures.document(at)
        fixtures.link(document, fixtures.company("AAA"))
        fixtures.link(document, fixtures.company("BBB"))
        val run = fixtures.run(at)
        val otherRun = fixtures.run(at)
        val attempt = fixtures.attempt(document, run, 1, at)
        val otherAttempt = fixtures.attempt(document, otherRun, 2, at)
        val linked = fixtures.model(at, null, null, null, document = document, attempt = attempt)
        fixtures.model(at, null, null, null, document = document, attempt = otherAttempt)
        val legacy = fixtures.model(at, null, null, null, document = document)
        val unlinked = fixtures.model(at, null, null, null)
        val query = ModelQuery(window, documentId = document, attemptId = attempt, runId = run)
        val call = store.search(query, window.to).items.single()
        assertEquals(linked, call.id)
        assertEquals(document, call.sourceDocumentId)
        assertEquals(attempt, call.attemptId)
        assertEquals(run, call.runId)
        assertEquals(call, store.findById(linked))
        assertEquals(1L, store.summary(query, window.to).totals.calls)
        assertEquals(3L, store.summary(ModelQuery(window, documentId = document), window.to).totals.calls)
        assertEquals(0L, store.summary(query.copy(runId = otherRun), window.to).totals.calls)
        assertNull(store.findById(legacy)?.attemptId)
        assertNull(store.findById(legacy)?.runId)
        assertNull(store.findById(unlinked)?.sourceDocumentId)
        assertNull(store.findById(unlinked)?.runId)
        assertNull(store.findById(UUID.randomUUID()))
    }

    @Test
    fun `document feed includes all dates and global feed and summary require a window`() {
        val document = fixtures.document(at)
        val old = fixtures.model(at.minusSeconds(864000), null, null, null, document = document)
        val current = fixtures.model(at, null, null, null, document = document)
        fixtures.model(at, null, null, null)
        val query = ModelQuery(null, documentId = document, page = PageRequest(1))
        val first = store.search(query, window.to)
        val second = store.search(query.copy(page = PageRequest(1, first.nextCursor)), window.to)
        assertEquals(listOf(current), first.items.map { it.id })
        assertEquals(listOf(old), second.items.map { it.id })
        assertNull(first.window)
        assertNull(second.nextCursor)
        assertEquals(old, store.findById(old)?.id)
        assertFailsWith<IllegalArgumentException> { store.search(ModelQuery(null), window.to) }
        assertFailsWith<IllegalArgumentException> { store.summary(ModelQuery(null), window.to) }
        assertFailsWith<IllegalArgumentException> { store.summary(query, window.to) }
    }

    @Test
    fun `tied timestamps page by descending UUID with cursors bound to every filter`() {
        val document = fixtures.document(at)
        val run = fixtures.run(at)
        val attempt = fixtures.attempt(document, run, 1, at)
        val ids = List(5) { fixtures.model(at, null, null, null, document = document, attempt = attempt) }
        val query = ModelQuery(window, provider = "openai", operation = "extract", model = "gpt-4o-mini", success = true,
            documentId = document, attemptId = attempt, runId = run, page = PageRequest(2))
        val first = store.search(query, window.to)
        val token = assertNotNull(first.nextCursor)
        val second = store.search(query.copy(page = PageRequest(3, token)), window.to)
        assertEquals(ids.map(UUID::toString).sortedDescending(), (first.items + second.items).map { it.id.toString() })
        assertNull(second.nextCursor)
        listOf(query.copy(provider = null), query.copy(operation = "embed"), query.copy(model = "other"), query.copy(success = false),
            query.copy(documentId = UUID.randomUUID()), query.copy(attemptId = null), query.copy(runId = null),
            query.copy(window = ActivityWindow(at.minusSeconds(1), window.to)), query.copy(window = null)).forEach { changed ->
            assertFailsWith<IllegalArgumentException> { store.search(changed.copy(page = PageRequest(2, token)), window.to) }
        }
        assertEquals(5L, store.summary(query.copy(page = PageRequest(2, token)), window.to).totals.calls)
    }

    @Test
    fun `group breakdown has a deterministic fifty group cap without truncating totals`() {
        repeat(51) { index ->
            setModel(fixtures.model(at, BigDecimal("0.01"), 1, 1), "model-${index.toString().padStart(2, '0')}")
        }
        val summary = store.summary(ModelQuery(window), window.to)
        assertEquals(51L, summary.totals.calls)
        assertEquals(50, summary.groups.size)
        assertTrue(summary.groupsTruncated)
        assertEquals("model-00", summary.groups.first().model)
        assertEquals("model-49", summary.groups.last().model)
        assertEquals(0, BigDecimal("0.51").compareTo(assertNotNull(summary.totals.estimatedCostUsd)))
    }

    @Test
    fun `model details expose safe errors and versions without raw error payloads`() {
        val failed = fixtures.model(at, null, null, null, success = false)
        jdbc.sql("UPDATE model_runs SET error='secret raw payload',prompt_version='prompt-v1',extractor_version='extract-v1' WHERE id=:id")
            .param("id", failed).update()
        val call = assertNotNull(store.findById(failed))
        assertEquals("RATE_LIMITED", call.errorCode)
        assertEquals(OperationalErrors.message("RATE_LIMITED"), call.errorMessage)
        assertEquals("prompt-v1", call.promptVersion)
        assertEquals("extract-v1", call.extractorVersion)
        jdbc.sql("UPDATE model_runs SET error_code=NULL WHERE id=:id").param("id", failed).update()
        val legacy = store.search(ModelQuery(window), window.to).items.single()
        assertEquals("UNKNOWN_FAILURE", legacy.errorCode)
        assertEquals(OperationalErrors.message("UNKNOWN_FAILURE"), legacy.errorMessage)
        val success = fixtures.model(at, null, null, null)
        assertNull(store.findById(success)?.errorCode)
        assertNull(store.findById(success)?.errorMessage)
    }

    private fun setModel(id: UUID, model: String) {
        jdbc.sql("UPDATE model_runs SET model=:model WHERE id=:id").param("model", model).param("id", id).update()
    }
}
