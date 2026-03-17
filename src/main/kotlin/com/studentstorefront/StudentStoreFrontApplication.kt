package com.studentstorefront

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class StudentStoreFrontApplication

fun main(args: Array<String>) {
    runApplication<StudentStoreFrontApplication>(*args)
}