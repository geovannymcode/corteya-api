package com.geovannycode.corteya.identity

import com.geovannycode.corteya.shared.ApiFailure

class AuthFailure(status: Int, message: String) : ApiFailure(status, message) {
    companion object {
        fun unauthenticated(): Nothing = throw AuthFailure(401, "Inicia sesión de nuevo para continuar")
        fun invalidOtp(): Nothing = throw AuthFailure(401, "El código no es válido o ya venció; solicita uno nuevo")
    }
}
