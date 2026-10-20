package com.geovannycode.corteya.shared

import java.time.Clock
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class TimeConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock::class)
    fun clock(): Clock = Clock.systemUTC()
}
