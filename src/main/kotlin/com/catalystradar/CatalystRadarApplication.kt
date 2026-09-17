package com.catalystradar

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@SpringBootApplication
class CatalystRadarApplication

fun main(args: Array<String>) {
	runApplication<CatalystRadarApplication>(*args)
}
