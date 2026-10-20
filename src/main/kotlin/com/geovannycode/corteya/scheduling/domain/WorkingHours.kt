package com.geovannycode.corteya.scheduling.domain

import java.time.Instant
import java.time.LocalTime
import java.util.UUID

data class WorkingHours(
    val id: UUID = UUID.randomUUID(),
    val barbershopId: UUID,
    val barberId: UUID,
    val dayOfWeek: Int,
    val startTime: LocalTime,
    val endTime: LocalTime,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
)
