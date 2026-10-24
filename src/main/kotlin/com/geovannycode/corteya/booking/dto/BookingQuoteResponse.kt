package com.geovannycode.corteya.booking.dto

import com.geovannycode.corteya.booking.DepositMode
import java.time.OffsetDateTime

data class BookingQuoteResponse(
    val priceCop: Long, val depositMode: DepositMode, val depositCop: Long, val payAtShopCop: Long,
    val holdMinutes: Int, val cancellableWithRefundUntil: OffsetDateTime, val cancelPolicyText: String,
)
