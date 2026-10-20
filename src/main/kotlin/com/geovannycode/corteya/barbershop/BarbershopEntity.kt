package com.geovannycode.corteya.barbershop

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.Generated
import org.hibernate.generator.EventType

@Entity
@Table(name = "barbershop")
data class BarbershopEntity(
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @field:Column(name = "slug", nullable = false, length = 100)
    val slug: String,

    @field:Column(name = "name", nullable = false, length = 150)
    val name: String,

    @field:Column(name = "logo_url", nullable = true, length = 2048)
    val logoUrl: String? = null,

    @field:Column(name = "primary_color", nullable = false, length = 7)
    val primaryColor: String = "#1F2937",

    @field:Column(name = "secondary_color", nullable = false, length = 7)
    val secondaryColor: String = "#F59E0B",

    @field:Column(name = "timezone", nullable = false, length = 64)
    val timezone: String = "America/Bogota",

    @field:Column(name = "deposit_percent", nullable = false)
    val depositPercent: Int = 30,

    @field:Column(name = "cancel_hours", nullable = false)
    val cancelHours: Int = 24,

    @field:Column(name = "active", nullable = false)
    val active: Boolean = true,

    @field:Column(name = "slot_step_minutes", nullable = false)
    val slotStepMinutes: Int = 15,

    @field:Column(name = "min_lead_minutes", nullable = false)
    val minLeadMinutes: Int = 30,

    @field:Column(name = "booking_window_days", nullable = false)
    val bookingWindowDays: Int = 14,

    @field:Column(name = "hold_minutes", nullable = false)
    val holdMinutes: Int = 15,

    @field:Generated(event = [EventType.INSERT])
    @field:Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: Instant = Instant.EPOCH,

    @field:Generated(event = [EventType.INSERT, EventType.UPDATE])
    @field:Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    val updatedAt: Instant = Instant.EPOCH,
)
