package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.PaymentReferences
import com.geovannycode.corteya.booking.BookingCodes
import org.springframework.stereotype.Component

@Component
class RandomPaymentReferences : PaymentReferences {
    override fun next(): String = BookingCodes.paymentReference()
}
