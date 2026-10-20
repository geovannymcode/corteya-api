package com.geovannycode.corteya.barbershop

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
    val slotStepMinutes: Int = 15,
    val minLeadMinutes: Int = 30,
    val bookingWindowDays: Int = 14,
    val holdMinutes: Int = 15,
    val createdAt: Instant = Instant.EPOCH,
    val updatedAt: Instant = Instant.EPOCH,
)
