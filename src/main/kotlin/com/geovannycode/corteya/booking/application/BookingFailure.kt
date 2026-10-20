package com.geovannycode.corteya.booking.application

/** Known use-case failure. No identifiers or customer data are included in messages. */
class BookingFailure(val status: Int, override val message: String) : RuntimeException(message) {
    companion object {
        fun notFound(): Nothing = throw BookingFailure(404, "No encontramos el recurso solicitado en esta barbería")
        fun slotTaken(): Nothing = throw BookingFailure(409, "Ese horario acaba de ser tomado, elige otro")
    }
}
