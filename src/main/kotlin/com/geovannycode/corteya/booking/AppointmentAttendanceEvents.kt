package com.geovannycode.corteya.booking

import java.time.Instant
import java.util.UUID

/** Consumers should listen after commit; these events contain no customer contact details. */
data class AppointmentCompleted(val barbershopId: UUID, val appointmentId: UUID, val publicToken: String, val completedAt: Instant)
data class AppointmentNoShow(val barbershopId: UUID, val appointmentId: UUID, val publicToken: String, val markedAt: Instant)
