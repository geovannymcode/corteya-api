package com.geovannycode.corteya.identity.application

import com.geovannycode.corteya.identity.domain.AppUser
import com.geovannycode.corteya.identity.domain.StaffMembership

interface AccessTokens {
    fun issue(user: AppUser, memberships: List<StaffMembership>): String
}

data class TokenPair(val accessToken: String, val refreshToken: String, val expiresIn: Long = 900, val tokenType: String = "Bearer") {
    override fun toString(): String = "TokenPair([redacted])"
}
