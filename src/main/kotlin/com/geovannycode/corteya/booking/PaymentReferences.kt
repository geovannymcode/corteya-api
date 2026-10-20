package com.geovannycode.corteya.booking

import org.springframework.stereotype.Component

@Component
open class PaymentReferences {
    open fun next(): String = BookingCodes.paymentReference()
}
