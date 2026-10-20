package com.geovannycode.corteya.identity

import java.util.UUID

interface AuthSecrets {
    fun otpCode(): String
    fun otpHash(id: UUID, phone: String, code: String): String
    fun matchesOtp(id: UUID, phone: String, code: String, expected: String): Boolean
    fun refreshToken(): String
    fun refreshHash(token: String): String
}
