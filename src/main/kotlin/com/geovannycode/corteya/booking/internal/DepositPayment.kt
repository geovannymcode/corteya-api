package com.geovannycode.corteya.booking.internal

import com.geovannycode.corteya.booking.DepositMode
import com.geovannycode.corteya.booking.DepositStatus
import java.time.Instant
import java.util.UUID

data class DepositPayment(
    val id: UUID, val barbershopId: UUID, val appointmentId: UUID, val method: DepositMode,
    val amountCop: Long, val reference: String, val attempt: Int, val status: DepositStatus,
    val providerTransactionId: String? = null, val confirmedAt: Instant? = null,
)
