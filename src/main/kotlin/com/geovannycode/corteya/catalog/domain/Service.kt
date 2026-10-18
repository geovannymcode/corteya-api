package com.geovannycode.corteya.catalog.domain

import java.time.Instant
import java.util.UUID

data class Service(
    val id: UUID = UUID.randomUUID(),
    val barbershopId: UUID,
    val name: String,
    val description: String? = null,
    val durationMinutes: Int,
    val priceCop: Long,
    val active: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
