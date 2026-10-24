package com.geovannycode.corteya.booking.dto

import com.geovannycode.corteya.booking.*
import java.time.OffsetDateTime

data class MyAppointmentResponse(
    val publicToken: String, val serviceName: String, val barberName: String,
    val barbershopName: String, val barbershopSlug: String, val barbershopLogoUrl: String?,
    val startsAt: OffsetDateTime, val endsAt: OffsetDateTime, val priceCop: Long, val depositCop: Long,
    val status: AppointmentStatus, val depositStatus: DepositStatus?, val refundStatus: RefundStatus,
    val cancellable: Boolean, val cancellableWithRefundUntil: OffsetDateTime,
    val paymentReference: String, val holdExpiresAt: OffsetDateTime?, val cancelPolicyText: String,
)
