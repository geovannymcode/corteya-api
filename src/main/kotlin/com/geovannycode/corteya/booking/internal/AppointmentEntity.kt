package com.geovannycode.corteya.booking.internal

import com.geovannycode.corteya.booking.AppointmentSource
import com.geovannycode.corteya.booking.AppointmentStatus
import com.geovannycode.corteya.booking.BookingCodes
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.Generated
import org.hibernate.generator.EventType

@Entity
@Table(name = "appointment")
data class AppointmentEntity(
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @field:Column(name = "barbershop_id", nullable = false, updatable = false)
    val barbershopId: UUID,

    @field:Column(name = "barber_id", nullable = false)
    val barberId: UUID,

    @field:Column(name = "service_id", nullable = false)
    val serviceId: UUID,

    @field:Column(name = "customer_id", nullable = false)
    val customerId: UUID,

    @field:Column(name = "starts_at", nullable = false)
    val startsAt: Instant,

    @field:Column(name = "ends_at", nullable = false)
    val endsAt: Instant,

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "status", nullable = false, length = 24)
    val status: AppointmentStatus = AppointmentStatus.PENDING_PAYMENT,

    @field:Column(name = "price_cop", nullable = false)
    val priceCop: Long,

    @field:Column(name = "deposit_cop", nullable = false)
    val depositCop: Long,

    @field:Column(name = "payment_reference", nullable = false, length = 32)
    val paymentReference: String,

    @field:Column(name = "hold_expires_at", nullable = true)
    val holdExpiresAt: Instant? = null,

    @field:Enumerated(EnumType.STRING)
    @field:Column(name = "source", nullable = false, length = 16)
    val source: AppointmentSource = AppointmentSource.APP,

    @field:Column(name = "public_token", nullable = false, length = 32, updatable = false)
    val publicToken: String = BookingCodes.publicToken(),

    @field:Version
    @field:Column(name = "version", nullable = false)
    var version: Long? = null,

    @field:Generated(event = [EventType.INSERT])
    @field:Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: Instant = Instant.EPOCH,

    @field:Generated(event = [EventType.INSERT, EventType.UPDATE])
    @field:Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    val updatedAt: Instant = Instant.EPOCH,

    @field:Column(name = "cancel_hours_snapshot")
    val cancelHoursSnapshot: Int? = null,
)
