package com.studentstorefront.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class TimeConfiguration {
    @Bean
    fun applicationClock(): Clock = Clock.systemUTC()
}
