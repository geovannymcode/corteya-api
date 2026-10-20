package com.geovannycode.corteya.booking.dto

import java.util.UUID

data class MyAppointmentsResponse(val upcoming: List<AppointmentStatusResponse>, val past: List<AppointmentStatusResponse>)
data class AgendaAppointmentResponse(val barberId: UUID, val serviceId: UUID, val appointment: AppointmentStatusResponse)
