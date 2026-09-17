package com.catalystradar.persistence.jdbc

import com.pgvector.PGvector
import org.junit.jupiter.api.Test
import org.postgresql.util.PGobject
import kotlin.test.assertEquals

class ConvertersTest {

    @Test
    fun `vector survives a write read round trip`() {
        val vector = PGvector(floatArrayOf(1.0f, 0.0f, -0.5f))

        val stored = PGvectorToPGobject().convert(vector)
        val reloaded = PGobjectToPGvector().convert(stored)

        assertEquals(vector.toString(), reloaded.toString())
    }

    @Test
    fun `string map survives a jsonb round trip`() {
        val attributes = mapOf("guidance" to "raised", "period" to "FY2026")

        val stored = StringMapToJsonb().convert(attributes)
        val reloaded = JsonbToStringMap().convert(stored)

        assertEquals("jsonb", stored.type)
        assertEquals(attributes, reloaded)
    }

    @Test
    fun `raw payload survives a jsonb round trip`() {
        val payload = JsonB("""{"provider":"polygon","id":"poly-1"}""")

        val stored = JsonBToPGobject().convert(payload)
        val reloaded = PGobjectToJsonB().convert(stored)

        assertEquals("jsonb", stored.type)
        assertEquals(payload, reloaded)
    }

    @Test
    fun `reading converter accepts database jsonb objects`() {
        val fromDatabase = PGobject().apply {
            type = "jsonb"
            value = """{"a":"b"}"""
        }

        assertEquals(mapOf("a" to "b"), JsonbToStringMap().convert(fromDatabase))
        assertEquals(JsonB("""{"a":"b"}"""), PGobjectToJsonB().convert(fromDatabase))
    }
}
