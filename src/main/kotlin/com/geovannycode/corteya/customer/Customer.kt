package com.geovannycode.corteya.customer

import java.time.Instant
import java.util.UUID

data class Customer(
    val id: UUID = UUID.randomUUID(),
    val barbershopId: UUID,
    val phone: String?,
    val name: String,
    val userId: UUID? = null,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
)
