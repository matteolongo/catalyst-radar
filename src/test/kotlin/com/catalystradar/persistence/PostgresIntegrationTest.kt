package com.catalystradar.persistence

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Isolated PostgreSQL + pgvector database for integration tests.
 *
 * One container is shared by every subclass in the test JVM and Flyway
 * migrates it from empty on context startup. It never touches a
 * developer's local database. Tests that write rows must be
 * @Transactional so rolled-back data cannot leak between test classes.
 */
@SpringBootTest
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
