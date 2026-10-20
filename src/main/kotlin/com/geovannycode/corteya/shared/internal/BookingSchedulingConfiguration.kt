package com.geovannycode.corteya.shared.internal

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = ["booking.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class BookingSchedulingConfiguration
