package com.catalystradar

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class CatalystRadarApplication

fun main(args: Array<String>) {
	runApplication<CatalystRadarApplication>(*args)
}
