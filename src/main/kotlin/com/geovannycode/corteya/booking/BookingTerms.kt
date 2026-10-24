package com.geovannycode.corteya.booking

import java.time.Instant

/** The quote and insertion use this single calculation, including rounding and cancellation terms. */
data class BookingTerms(
    val priceCop: Long, val depositMode: DepositMode, val depositCop: Long, val payAtShopCop: Long,
    val holdMinutes: Int, val cancellationPolicy: CancellationPolicy,
) {
    companion object {
        fun calculate(priceCop: Long, mode: DepositMode, percent: Int, holdMinutes: Int, startsAt: Instant, cancelHours: Int): BookingTerms {
            val deposit = if (mode == DepositMode.NONE) 0 else BookingCodes.depositCop(priceCop, percent)
            if (deposit > Long.MAX_VALUE / 100) throw BookingFailure(400, "El monto supera el máximo permitido")
            return BookingTerms(priceCop, mode, deposit, priceCop - deposit, if (mode == DepositMode.NONE) 0 else holdMinutes,
                CancellationPolicy.forAppointment(startsAt, cancelHours, deposit > 0))
        }
    }
}
