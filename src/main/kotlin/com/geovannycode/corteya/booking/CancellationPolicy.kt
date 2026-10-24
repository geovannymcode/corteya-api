package com.geovannycode.corteya.booking

import java.time.Instant

/** Pure policy projection shared by creation, polling and cancellation. */
data class CancellationPolicy(val cancellableWithRefundUntil: Instant, val cancelPolicyText: String, val cancelHours: Int) {
    companion object {
        fun forAppointment(startsAt: Instant, cancelHours: Int, hasDeposit: Boolean): CancellationPolicy = CancellationPolicy(
            startsAt.minusSeconds(cancelHours.toLong() * 3600),
            if (hasDeposit) "Si cancelas con al menos $cancelHours horas de anticipación, la barbería te devolverá la señal. Si cancelas después, la señal no es reembolsable. Solo puedes cancelar antes del inicio."
            else "Puedes cancelar antes del inicio de la cita. Esta reserva no tiene señal que devolver.",
            cancelHours,
        )
    }
}
