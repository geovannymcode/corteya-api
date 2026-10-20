package com.geovannycode.corteya.identity.application

class AuthFailure(val status: Int, override val message: String) : RuntimeException(message) {
    companion object {
        fun unauthenticated(): Nothing = throw AuthFailure(401, "Inicia sesión de nuevo para continuar")
        fun invalidOtp(): Nothing = throw AuthFailure(401, "El código no es válido o ya venció; solicita uno nuevo")
    }
}
