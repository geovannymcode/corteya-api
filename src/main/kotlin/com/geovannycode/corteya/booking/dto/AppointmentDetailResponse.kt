package com.geovannycode.corteya.booking.dto

import com.geovannycode.corteya.booking.*
import java.time.OffsetDateTime

data class AppointmentDetailResponse(
    val publicToken: String, val status: AppointmentStatus, val startsAt: OffsetDateTime,
    val endsAt: OffsetDateTime, val depositCop: Long, val paymentReference: String, val holdExpiresAt: OffsetDateTime?,
    val cancellableWithRefundUntil: OffsetDateTime, val cancelPolicyText: String,
    val deposit: AppointmentDepositResponse?,
)

data class AppointmentDepositResponse(
    val method: DepositMode, val status: DepositStatus, val amountCop: Long, val reference: String,
    val latestAttemptStatus: DepositStatus, val canRetry: Boolean, val holdExpiresAt: OffsetDateTime?,
    val refundStatus: RefundStatus, val paymentInstructions: PaymentInstructions?,
)
