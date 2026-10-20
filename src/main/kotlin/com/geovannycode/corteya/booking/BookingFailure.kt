package com.geovannycode.corteya.booking

import com.geovannycode.corteya.shared.ApiFailure

/** Known use-case failure. No identifiers or customer data are included in messages. */
class BookingFailure(status: Int, message: String) : ApiFailure(status, message) {
    companion object {
        fun notFound(): Nothing = throw BookingFailure(404, "No encontramos el recurso solicitado en esta barbería")
        fun slotTaken(): Nothing = throw BookingFailure(409, "Ese horario acaba de ser tomado, elige otro")
    }
}
