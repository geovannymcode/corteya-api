package com.geovannycode.corteya.booking.dto

import com.geovannycode.corteya.booking.AppointmentStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class BarbershopResponse(
    val slug: String, val name: String, val logoUrl: String?, val primaryColor: String, val secondaryColor: String,
    val open: Boolean, val statusMessage: String, val nextChangeAt: OffsetDateTime?,
)

data class ServiceResponse(val id: UUID, val name: String, val description: String?, val durationMinutes: Int, val priceCop: Long)
data class BarberResponse(val id: UUID, val name: String, val bio: String?, val photoUrl: String?, val nextAvailableAt: OffsetDateTime?)
data class AvailabilityResponse(val date: LocalDate, val morning: List<OffsetDateTime>, val afternoon: List<OffsetDateTime>)

data class CreateAppointmentRequest(
    val barberId: UUID,
    val serviceId: UUID,
    @field:Schema(type = "string", format = "date-time", example = "2026-10-20T09:00:00-05:00", description = "Instante ISO-8601 con offset obligatorio")
    val startsAt: OffsetDateTime,
)

data class CreatedAppointmentResponse(
    val publicToken: String, val status: AppointmentStatus, val depositCop: Long,
    val paymentReference: String, val holdExpiresAt: OffsetDateTime,
)

/** Public capability response deliberately omits customer data and internal UUIDs. */
data class AppointmentStatusResponse(
    val publicToken: String, val status: AppointmentStatus, val startsAt: OffsetDateTime,
    val endsAt: OffsetDateTime, val depositCop: Long, val paymentReference: String, val holdExpiresAt: OffsetDateTime?,
)
