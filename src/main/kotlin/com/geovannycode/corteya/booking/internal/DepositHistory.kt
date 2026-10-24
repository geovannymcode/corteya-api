package com.geovannycode.corteya.booking.internal

import com.geovannycode.corteya.booking.*
import java.time.Instant

/** A successful payment remains visible even if another checkout attempt failed later. */
object DepositHistory {
    fun effective(payments: List<DepositPayment>): DepositPayment? =
        payments.filter { it.status in listOf(DepositStatus.APPROVED, DepositStatus.LATE_PAYMENT) }.maxByOrNull { it.attempt }
            ?: payments.maxByOrNull { it.attempt }

    fun canRetry(appointment: AppointmentEntity, latest: DepositPayment, mode: DepositMode, now: Instant): Boolean =
        appointment.status == AppointmentStatus.PENDING_PAYMENT && appointment.holdExpiresAt?.isAfter(now) == true &&
            latest.method == DepositMode.WOMPI && mode == DepositMode.WOMPI &&
            latest.status in listOf(DepositStatus.DECLINED, DepositStatus.ERROR)
}
