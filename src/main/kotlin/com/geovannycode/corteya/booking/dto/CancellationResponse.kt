package com.geovannycode.corteya.booking.dto

import com.geovannycode.corteya.booking.AppointmentStatus
import com.geovannycode.corteya.booking.RefundStatus

data class CancellationResponse(
    val publicToken: String, val status: AppointmentStatus, val refundStatus: RefundStatus,
    val refundAmountCop: Long, val message: String,
)
