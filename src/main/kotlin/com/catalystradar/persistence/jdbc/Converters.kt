package com.catalystradar.persistence.jdbc

import com.pgvector.PGvector
import org.postgresql.util.PGobject
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * Explicit JSON string wrapper: a global String <-> jsonb converter would
 * hijack every varchar column, so JSONB payloads use this type instead.
 * A plain class (not a value class) so the runtime type survives for
 * converter lookup.
 */
data class JsonB(val json: String) {
    init {
        require(json.isNotBlank()) { "json must not be blank" }
    }
}

private val jsonMapper: ObjectMapper = ObjectMapper()

@WritingConverter
class PGvectorToPGobject : Converter<PGvector, PGobject> {
    override fun convert(source: PGvector): PGobject = source
}

@ReadingConverter
class PGobjectToPGvector : Converter<PGobject, PGvector> {
    override fun convert(source: PGobject): PGvector = PGvector(source.value)
}

@WritingConverter
class StringMapToJsonb : Converter<Map<String, String>, PGobject> {
    override fun convert(source: Map<String, String>): PGobject =
        PGobject().apply {
            type = "jsonb"
            value = jsonMapper.writeValueAsString(source)
        }
}

@ReadingConverter
class JsonbToStringMap : Converter<PGobject, Map<String, String>> {
    override fun convert(source: PGobject): Map<String, String> =
        jsonMapper.readValue(source.value, object : TypeReference<Map<String, String>>() {})
}

@WritingConverter
class JsonBToPGobject : Converter<JsonB, PGobject> {
    override fun convert(source: JsonB): PGobject =
        PGobject().apply {
            type = "jsonb"
            value = source.json
        }
}

@ReadingConverter
class PGobjectToJsonB : Converter<PGobject, JsonB> {
    override fun convert(source: PGobject): JsonB =
        JsonB(requireNotNull(source.value) { "jsonb value must not be null" })
}

@Configuration
class JdbcConverterConfiguration {

    @Bean
    fun jdbcCustomConversions(): JdbcCustomConversions =
        JdbcCustomConversions(
            listOf(
                PGvectorToPGobject(),
                PGobjectToPGvector(),
                StringMapToJsonb(),
                JsonbToStringMap(),
                JsonBToPGobject(),
                PGobjectToJsonB(),
            ),
        )
}
