package com.geovannycode.corteya.booking.domain

import java.time.Instant
import java.util.UUID

data class Appointment(
    val id: UUID = UUID.randomUUID(),
    val barbershopId: UUID,
    val barberId: UUID,
    val serviceId: UUID,
    val customerId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val status: AppointmentStatus = AppointmentStatus.PENDING_PAYMENT,
    val priceCop: Long,
    val depositCop: Long,
    val paymentReference: String,
    val holdExpiresAt: Instant? = null,
    val source: AppointmentSource = AppointmentSource.APP,
    val version: Long? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)
