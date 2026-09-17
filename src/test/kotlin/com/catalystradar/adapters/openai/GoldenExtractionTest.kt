package com.catalystradar.adapters.openai

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.springframework.core.io.DefaultResourceLoader
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals

/**
 * Golden extraction regression set. Each case pins a representative
 * article to its expected model output; the shared response parser must
 * keep accepting valid outputs and dropping invalid ones as prompts and
 * models evolve. Cases grow toward 50+ over time.
 */
class GoldenExtractionTest {

    private val mapper = ObjectMapper()
    private val parser = ExtractionResponseParser()

    @Test
    fun `golden cases parse to expected candidates`() {
        val dir = Paths.get(
            DefaultResourceLoader().getResource("classpath:golden").uri,
        )
        val cases = Files.list(dir).use { stream ->
            stream.filter { it.toString().endsWith(".json") }.sorted().toList()
        }

        assertAll(cases.map { path ->
            {
                val golden = mapper.readTree(path.toFile())
                val content = mapper.writeValueAsString(golden["modelOutput"])
                val response = OpenAiChatResponse(
                    id = null,
                    model = "golden",
                    choices = listOf(
                        OpenAiChoice(
                            message = OpenAiMessage(content = content, refusal = null),
                            finishReason = "stop",
                        ),
                    ),
                    usage = null,
                )

                val result = parser.parse(response)

                assertEquals(
                    golden["expectedRelevant"].booleanValue(),
                    result.documentRelevant,
                    "${golden["id"].asText()}: relevance",
                )
                assertEquals(
                    golden["expectedEventCount"].intValue(),
                    result.events.size,
                    "${golden["id"].asText()}: event count",
                )
                val expectedFirst = golden["expectedFirstType"].takeIf { it.isTextual }?.asText()
                assertEquals(
                    expectedFirst,
                    result.events.firstOrNull()?.type?.name,
                    "${golden["id"].asText()}: first type",
                )
            }
        })
    }
}
