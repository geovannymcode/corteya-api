package com.geovannycode.corteya.booking

import java.time.Instant
import java.util.UUID

/** Published in the transaction; notification consumers should listen AFTER_COMMIT. */
data class AppointmentCancelled(
    val barbershopId: UUID, val appointmentId: UUID, val publicToken: String,
    val refundStatus: RefundStatus, val refundAmountCop: Long, val cancelledAt: Instant,
)
