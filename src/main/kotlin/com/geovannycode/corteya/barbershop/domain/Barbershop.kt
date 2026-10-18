package com.geovannycode.corteya.barbershop.domain

import java.time.Instant
import java.util.UUID

data class Barbershop(
    val id: UUID = UUID.randomUUID(),
    val slug: String,
    val name: String,
    val logoUrl: String? = null,
    val primaryColor: String = "#1F2937",
    val secondaryColor: String = "#F59E0B",
    val timezone: String = "America/Bogota",
    val depositPercent: Int = 30,
    val cancelHours: Int = 24,
    val active: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
