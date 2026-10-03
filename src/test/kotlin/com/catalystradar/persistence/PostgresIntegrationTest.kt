package com.catalystradar.persistence

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated PostgreSQL + pgvector database for integration tests.
 *
 * One container is shared by every subclass in the test JVM and Flyway
 * migrates it from empty on context startup. It never touches a
 * developer's local database. Tests that write rows must be
 * @Transactional so rolled-back data cannot leak between test classes.
 *
 * Background schedulers are pinned off here. Spring's relaxed binding maps
 * `CATALYST_INGESTION_ENABLED`/`CATALYST_SNAPSHOTS_ENABLED` onto these
 * flags, so an inherited environment would otherwise start a real pipeline
 * against real provider keys and race the triggers a test owns. Tests that
 * must exercise a scheduler declare their own minimal context instead.
 */
@SpringBootTest
@TestPropertySource(
    properties = [
        "catalyst.ingestion.enabled=false",
        "catalyst.snapshots.enabled=false",
    ],
)
abstract class PostgresIntegrationTest {

    companion object {
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"),
            ).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun datasourceProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
