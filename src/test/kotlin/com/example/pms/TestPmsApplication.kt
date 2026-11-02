package com.example.pms

import org.springframework.boot.fromApplication
import org.springframework.boot.with


fun main(args: Array<String>) {
	fromApplication<PmsApplication>().with(TestcontainersConfiguration::class).run(*args)
}
