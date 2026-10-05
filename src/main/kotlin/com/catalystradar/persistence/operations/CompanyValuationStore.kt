package com.catalystradar.persistence.operations

import com.catalystradar.application.operations.*
import com.catalystradar.application.scoring.CalculatedScore
import com.catalystradar.domain.catalyst.CatalystSnapshot
import com.catalystradar.persistence.jdbc.jsonMapper
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Repository
class CompanyValuationStore(private val jdbc: NamedParameterJdbcTemplate, @Qualifier("operationsClock") private val clock: Clock) {
    private val cursor = OperationsCursor()

    /** Called inside CatalystService's snapshot transaction; failure rolls back all three records. */
    fun save(runId: UUID, snapshot: CatalystSnapshot, previous: CatalystSnapshot?, calculated: CalculatedScore, transitionId: UUID?, clusterIds: Map<UUID, UUID?>) {
        val id = UUID.randomUUID()
        val now = clock.instant()
        jdbc.update("""INSERT INTO company_valuation_records(id,operation_run_id,company_id,snapshot_id,as_of,created_at,
            score_version,taxonomy_version,previous_snapshot_id,before_score,before_state,after_score,after_state,
            velocity_1d,velocity_3d,velocity_7d,transition_id,contribution_sum,family_count,convergence_multiplier,
            raw_score,normalization_scale,contribution_cutoff)
            VALUES(:id,:run,:company,:snapshot,:asOf,:now,:scoreVersion,:taxonomy,:previous,:beforeScore,:beforeState,
                :afterScore,:afterState,:v1,:v3,:v7,:transition,:sum,:families,:convergence,:raw,:scale,:cutoff)""",
            mapOf("id" to id, "run" to runId, "company" to snapshot.companyId, "snapshot" to snapshot.id,
                "asOf" to Timestamp.from(snapshot.asOf), "now" to Timestamp.from(now), "scoreVersion" to snapshot.score.version,
                "taxonomy" to snapshot.taxonomyVersion, "previous" to previous?.id, "beforeScore" to previous?.score?.value,
                "beforeState" to previous?.state?.name, "afterScore" to snapshot.score.value, "afterState" to snapshot.state.name,
                "v1" to snapshot.velocity.velocity1d, "v3" to snapshot.velocity.velocity3d, "v7" to snapshot.velocity.velocity7d,
                "transition" to transitionId, "sum" to calculated.contributionSum, "families" to calculated.familyCount,
                "convergence" to calculated.convergenceMultiplier, "raw" to calculated.rawScore,
                "scale" to calculated.normalizationScale, "cutoff" to calculated.contributionCutoff))
        for (contribution in calculated.contributions) {
            val contributionId = UUID.randomUUID()
            check(clusterIds.containsKey(contribution.eventId)) { "contribution is not a calculation input" }
            // Freeze explicit evidence at capture: future cluster members cannot rewrite this valuation.
            jdbc.update("""INSERT INTO company_valuation_event_contributions(id,valuation_id,event_id,cluster_id,created_at,
                value,sign,base_weight,confidence,materiality_factor,surprise_factor,source_quality_factor,directness_factor,time_decay_factor,
                supporting_sources,supporting_documents_total)
                SELECT :id,:valuation,e.id,:cluster,:now,:value,:sign,:weight,:confidence,:materiality,:surprise,:sourceQuality,:directness,:decay,
                    '[]'::jsonb,0 FROM events e WHERE e.id=:event""",
                mapOf("id" to contributionId, "valuation" to id, "event" to contribution.eventId, "now" to Timestamp.from(now),
                    "cluster" to clusterIds[contribution.eventId], "value" to contribution.value, "sign" to contribution.sign,
                    "weight" to contribution.baseWeight, "confidence" to contribution.confidence, "materiality" to contribution.materialityFactor,
                    "surprise" to contribution.surpriseFactor, "sourceQuality" to contribution.sourceQualityFactor,
                    "directness" to contribution.directnessFactor, "decay" to contribution.timeDecayFactor)).also {
                check(it == 1) { "calculated event was not stored" }
                jdbc.update("""INSERT INTO company_valuation_contribution_sources(contribution_id,source_document_id,event_id)
                    SELECT DISTINCT ON (d.id) :contribution,d.id,report.id
                    FROM company_valuation_event_contributions contribution
                    JOIN events e ON e.id=contribution.event_id
                    JOIN events report ON (report.id=e.id OR (contribution.cluster_id IS NOT NULL AND report.cluster_id=contribution.cluster_id))
                    JOIN source_documents d ON d.id=report.source_document_id
                    WHERE contribution.id=:contribution AND report.company_id=e.company_id AND (report.id=e.id OR (report.discovered_at<=:asOf
                        AND d.discovered_at<=:asOf AND (d.published_at IS NULL OR d.published_at<=:asOf)))
                    ORDER BY d.id,report.discovered_at,report.id""",
                    mapOf("contribution" to contributionId, "asOf" to Timestamp.from(snapshot.asOf)))
                jdbc.update("""UPDATE company_valuation_event_contributions SET supporting_sources=captured.items,
                    supporting_documents_total=captured.total FROM (
                    SELECT COALESCE(jsonb_agg(jsonb_build_object('sourceDocumentId',linked.document_id,'eventId',linked.event_id,
                        'title',linked.title,'provider',linked.provider,'canonicalUrl',linked.canonical_url,
                        'publishedAt',linked.published_at,'discoveredAt',linked.discovered_at,
                        'evidence',linked.safe_evidence,'evidenceTruncated',linked.evidence_truncated) ORDER BY linked.document_id),'[]'::jsonb) AS items,
                        COALESCE(MAX(linked.total),0) AS total
                    FROM (
                        SELECT d.id AS document_id,report.id AS event_id,d.title,d.provider,d.canonical_url,d.published_at,d.discovered_at,
                            COUNT(*) OVER () AS total,
                            (SELECT COALESCE(jsonb_agg(left(fact->>'quoteOrFact',1000) ORDER BY ordinal),'[]'::jsonb)
                             FROM jsonb_array_elements(report.evidence) WITH ORDINALITY facts(fact,ordinal) WHERE ordinal<=20) AS safe_evidence,
                            (jsonb_array_length(report.evidence)>20 OR EXISTS
                             (SELECT 1 FROM jsonb_array_elements(report.evidence) fact WHERE length(fact->>'quoteOrFact')>1000)) AS evidence_truncated
                        FROM company_valuation_contribution_sources source
                        JOIN events report ON report.id=source.event_id JOIN source_documents d ON d.id=source.source_document_id
                        WHERE source.contribution_id=:contribution ORDER BY d.id LIMIT 100
                    ) linked
                ) captured WHERE id=:contribution""", mapOf("contribution" to contributionId))
            }
        }
    }

    fun detail(id: UUID): CompanyValuation? = jdbc.query("$VALUATIONS WHERE v.id=:id", mapOf("id" to id)) { rs, _ -> valuation(rs) }.singleOrNull()

    fun search(runId: UUID?, companyId: UUID?, window: ActivityWindow?, page: PageRequest, generatedAt: Instant, documentId: UUID? = null): OperationsPage<CompanyValuation> {
        require(runId != null || companyId != null || documentId != null) { "valuation feed requires a parent" }
        val filters = mapOf("documentId" to (documentId?.toString() ?: ""), "runId" to (runId?.toString() ?: ""), "companyId" to (companyId?.toString() ?: ""),
            "from" to (window?.from?.toString() ?: ""), "to" to (window?.to?.toString() ?: ""))
        val params = mutableMapOf<String, Any?>("limit" to page.limit + 1)
        val where = mutableListOf<String>()
        runId?.let { where += "v.operation_run_id=:run"; params["run"] = it }
        documentId?.let {
            where += """EXISTS (SELECT 1 FROM company_valuation_event_contributions contribution
                JOIN company_valuation_contribution_sources source ON source.contribution_id=contribution.id
                WHERE contribution.valuation_id=v.id AND source.source_document_id=:document)"""
            params["document"] = it
        }
        companyId?.let { where += "v.company_id=:company"; params["company"] = it }
        window?.let { where += "v.as_of>=:from AND v.as_of<:to"; params["from"] = Timestamp.from(it.from); params["to"] = Timestamp.from(it.to) }
        page.cursor?.let { val p = cursor.decode(it, "valuations", filters); where += "(v.as_of,v.id)<(:at,:id)"; params["at"] = Timestamp.from(p.at); params["id"] = p.id }
        val rows = jdbc.query("$VALUATIONS WHERE ${where.joinToString(" AND ")} ORDER BY v.as_of DESC,v.id DESC LIMIT :limit", params) { rs, _ -> valuation(rs) }
        val items = rows.take(page.limit)
        val next = if (rows.size > page.limit) items.last().let { cursor.encode("valuations", CursorPosition(it.asOf, it.id), filters) } else null
        return OperationsPage(generatedAt, window, items, page.limit, next)
    }

    fun contributions(id: UUID, page: PageRequest, generatedAt: Instant): OperationsPage<ValuationContribution> {
        val filters = mapOf("valuationId" to id.toString())
        val params = mutableMapOf<String, Any?>("valuation" to id, "limit" to page.limit + 1)
        var position = ""
        page.cursor?.let { val p = cursor.decode(it, "contributions", filters); position = "AND (v.created_at,v.id)<(:at,:id)"; params["at"] = Timestamp.from(p.at); params["id"] = p.id }
        val rows = jdbc.query("""SELECT v.*,c.ticker,e.event_type,e.family,e.direction,e.event_timestamp,e.discovered_at
            FROM company_valuation_event_contributions v JOIN events e ON e.id=v.event_id JOIN companies c ON c.id=e.company_id
            WHERE v.valuation_id=:valuation $position ORDER BY v.created_at DESC,v.id DESC LIMIT :limit""", params) { rs, _ ->
            val sources = jsonMapper.readTree(rs.getString("supporting_sources")).mapNotNull { node ->
                fun optional(name: String): String? = node.get(name)?.takeUnless { it.isNull }?.asString()
                ContributionSource(UUID.fromString(node.get("sourceDocumentId").asString()), UUID.fromString(node.get("eventId").asString()),
                    node.get("title").asString(), node.get("provider").asString(), optional("canonicalUrl"), optional("publishedAt")?.let(Instant::parse),
                    Instant.parse(node.get("discoveredAt").asString()), node.get("evidence").mapNotNull { it.asString() }, node.get("evidenceTruncated").asBoolean())
            }
            val total = rs.getLong("supporting_documents_total")
            ValuationContribution(rs.uuid("id"), rs.uuid("valuation_id"), rs.uuid("event_id"), rs.getObject("cluster_id", UUID::class.java), rs.instant("created_at"),
                rs.getString("ticker"), rs.getString("event_type"), rs.getString("family"), rs.getString("direction"), rs.getTimestamp("event_timestamp")?.toInstant(), rs.instant("discovered_at"),
                rs.getDouble("value"), rs.getDouble("sign"), rs.getDouble("base_weight"), rs.getDouble("confidence"), rs.getDouble("materiality_factor"), rs.getDouble("surprise_factor"),
                rs.getDouble("source_quality_factor"), rs.getDouble("directness_factor"), rs.getDouble("time_decay_factor"), sources, total, total>sources.size)
        }
        val items = rows.take(page.limit)
        val next = if (rows.size > page.limit) items.last().let { cursor.encode("contributions", CursorPosition(it.createdAt, it.id), filters) } else null
        return OperationsPage(generatedAt, null, items, page.limit, next)
    }

    private fun valuation(rs: ResultSet) = CompanyValuation(
        rs.uuid("id"), rs.uuid("operation_run_id"), rs.uuid("company_id"), rs.getString("ticker"), rs.getString("company_name"), rs.uuid("snapshot_id"),
        rs.getObject("previous_snapshot_id", UUID::class.java), rs.instant("as_of"), rs.instant("created_at"), rs.getString("score_version"), rs.getString("taxonomy_version"),
        rs.getObject("before_score", Double::class.javaObjectType), rs.getString("before_state"), rs.getDouble("after_score"), rs.getString("after_state"),
        rs.getDouble("velocity_1d"), rs.getDouble("velocity_3d"), rs.getDouble("velocity_7d"), rs.getObject("transition_id", UUID::class.java),
        rs.getDouble("contribution_sum"), rs.getInt("family_count"), rs.getDouble("convergence_multiplier"), rs.getDouble("raw_score"),
        rs.getDouble("normalization_scale"), rs.getDouble("contribution_cutoff"), rs.getLong("contribution_count"))

    private fun ResultSet.uuid(name: String): UUID = getObject(name, UUID::class.java)
    private fun ResultSet.instant(name: String): Instant = getTimestamp(name).toInstant()

    companion object {
        private const val VALUATIONS = """SELECT v.*,c.ticker,c.name AS company_name,
            (SELECT COUNT(*) FROM company_valuation_event_contributions ec WHERE ec.valuation_id=v.id) AS contribution_count
            FROM company_valuation_records v JOIN companies c ON c.id=v.company_id"""
    }
}
