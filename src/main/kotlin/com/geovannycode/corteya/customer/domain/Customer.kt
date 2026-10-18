package com.geovannycode.corteya.customer.domain

import java.time.Instant
import java.util.UUID

data class Customer(
    val id: UUID = UUID.randomUUID(),
    val barbershopId: UUID,
    val phone: String,
    val name: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
