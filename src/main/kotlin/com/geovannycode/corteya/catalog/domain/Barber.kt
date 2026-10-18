package com.geovannycode.corteya.catalog.domain

import java.time.Instant
import java.util.UUID

data class Barber(
    val id: UUID = UUID.randomUUID(),
    val barbershopId: UUID,
    val name: String,
    val bio: String? = null,
    val photoUrl: String? = null,
    val active: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
