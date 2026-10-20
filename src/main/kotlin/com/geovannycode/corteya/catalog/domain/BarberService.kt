package com.geovannycode.corteya.catalog.domain

import java.time.Instant
import java.util.UUID

data class BarberService(
    val id: UUID = UUID.randomUUID(),
    val barbershopId: UUID,
    val barberId: UUID,
    val serviceId: UUID,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
)
