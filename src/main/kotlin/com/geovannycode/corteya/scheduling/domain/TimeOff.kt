package com.geovannycode.corteya.scheduling.domain

import java.time.Instant
import java.util.UUID

data class TimeOff(
    val id: UUID = UUID.randomUUID(),
    val barbershopId: UUID,
    val barberId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val reason: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
