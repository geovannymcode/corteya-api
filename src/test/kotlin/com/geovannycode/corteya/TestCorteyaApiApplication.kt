package com.geovannycode.corteya

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<CorteyaApiApplication>().with(TestcontainersConfiguration::class).run(*args)
}
