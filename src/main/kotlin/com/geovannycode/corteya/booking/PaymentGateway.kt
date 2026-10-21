package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.dto.PaymentInstructions
import com.geovannycode.corteya.booking.internal.AppointmentEntity
import com.geovannycode.corteya.booking.internal.DepositPayment
import com.geovannycode.corteya.booking.internal.PaymentSettings

/** Two real strategies produce instructions for the barber's receiving account. */
interface PaymentGateway {
    val mode: DepositMode
    fun instructions(settings: PaymentSettings, payment: DepositPayment, appointment: AppointmentEntity): PaymentInstructions
}
