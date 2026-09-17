package com.catalystradar.adapters.openai

import com.catalystradar.domain.event.Directness
import com.catalystradar.domain.event.Direction
import com.catalystradar.domain.event.EventFamily
import com.catalystradar.domain.event.EventHorizon
import com.catalystradar.domain.event.EventType
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire schema is a versioned artifact: it must enumerate exactly the
 * taxonomy the code validates, or the model and the validator disagree.
 */
class ExtractionSchemaSyncTest {

    private val mapper = ObjectMapper()
    private val schema = DefaultResourceLoader().getResource(
        "classpath:prompts/event-extractor-schema-v1.json",
    ).inputStream.use { mapper.readTree(it) }

    @Test
    fun `prompt artifact exists and forbids scoring output`() {
        val prompt = DefaultResourceLoader().getResource(
            "classpath:prompts/event-extractor-v1.txt",
        ).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }

        assertTrue(prompt.contains("NEVER output catalyst scores", ignoreCase = true))
    }

    @Test
    fun `schema type enum matches the taxonomy exactly`() {
        assertEquals(EventType.entries.map { it.name }.toSet(), enumValues("type"))
    }

    @Test
    fun `schema attribute enums match the code`() {
        assertEquals(
            EventFamily.entries.map { it.name }.toSet(),
            enumValues("family"),
        )
        assertEquals(
            Direction.entries.map { it.name }.toSet(),
            enumValues("direction"),
        )
        assertEquals(
            EventHorizon.entries.map { it.name }.toSet(),
            enumValues("expected_horizon"),
        )
        assertEquals(
            Directness.entries.map { it.name }.toSet(),
            enumValues("directness"),
        )
    }

    private fun enumValues(field: String): Set<String> {
        val items = schema.path("properties").path("events").path("items").path("properties")
        return items.path(field).path("enum").values().map { it.asText() }.toSet()
    }
}
