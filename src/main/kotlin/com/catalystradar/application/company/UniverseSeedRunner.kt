package com.catalystradar.application.company

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * Seeds the initial universe on startup when explicitly enabled:
 * catalyst.universe.seed-on-startup=true (local development).
 * Off by default so deployments never mutate reference data implicitly.
 */
@Component
@ConditionalOnProperty(name = ["catalyst.universe.seed-on-startup"], havingValue = "true")
class UniverseSeedRunner(private val loader: UsUniverseLoader) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(UniverseSeedRunner::class.java)

    override fun run(args: ApplicationArguments) {
        val result = loader.load()
        log.info("Universe seed complete: loaded={} skipped={}", result.loaded, result.skipped)
    }
}
