package com.geovannycode.corteya.identity

data class TokenPair(val accessToken: String, val refreshToken: String, val expiresIn: Long = 900, val tokenType: String = "Bearer") {
    override fun toString(): String = "TokenPair([redacted])"
}
