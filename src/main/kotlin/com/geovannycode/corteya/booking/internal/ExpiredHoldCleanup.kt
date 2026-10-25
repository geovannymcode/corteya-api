package com.geovannycode.corteya.booking.internal

import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class ExpiredHoldCleanup(private val bookings: AppointmentRepository, private val deposits: DepositRepository) {
    /** Caller owns the tenant transaction and barber schedule lock; both updates use the same instant. */
    fun forBarber(shop: UUID, barber: UUID, now: Instant) {
        bookings.expireForBarber(shop, barber, now)
        deposits.expirePending(shop, now)
    }
}
