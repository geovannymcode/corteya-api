package com.geovannycode.corteya.booking.dto

import java.util.UUID
import java.time.OffsetDateTime
import com.geovannycode.corteya.booking.*

data class MyAppointmentsResponse(val upcoming: List<MyAppointmentResponse>, val past: List<MyAppointmentResponse>)
data class AgendaAppointmentResponse(val barberId: UUID, val serviceId: UUID, val appointment: AppointmentStatusResponse,
    val id: UUID, val serviceName: String, val barberName: String, val startsAt: OffsetDateTime, val endsAt: OffsetDateTime,
    val status: AppointmentStatus, val source: AppointmentSource, val depositStatus: DepositStatus?, val refundStatus: RefundStatus,
    val customerName: String, val customerPhone: String?)
