package com.geovannycode.corteya.booking.infrastructure.persistence

import com.geovannycode.corteya.booking.application.PaymentReferences
import com.geovannycode.corteya.booking.domain.BookingCodes
import org.springframework.stereotype.Component

@Component
class RandomPaymentReferences : PaymentReferences {
    override fun next(): String = BookingCodes.paymentReference()
}
