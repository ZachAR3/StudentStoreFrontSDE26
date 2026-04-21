package com.studentstorefront

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class StudentStoreFrontApplication

fun main(args: Array<String>) {
    runApplication<StudentStoreFrontApplication>(*args)
}