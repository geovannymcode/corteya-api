package com.geovannycode.corteya.booking.dto

import java.util.UUID

data class MyAppointmentsResponse(val upcoming: List<MyAppointmentResponse>, val past: List<MyAppointmentResponse>)
data class AgendaAppointmentResponse(val barberId: UUID, val serviceId: UUID, val appointment: AppointmentStatusResponse)
