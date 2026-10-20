package com.geovannycode.corteya.identity.domain

import java.time.Instant
import java.util.UUID

data class AppUser(val id: UUID, val phone: String?, val name: String?, val platformAdmin: Boolean, val deletedAt: Instant?) {
    override fun toString(): String = "AppUser(id=$id, phone=${phone?.let(PhoneNumbers::mask)}, name=[redacted])"
}
enum class StaffRole { OWNER, BARBER }
data class StaffMembership(val barbershopId: UUID, val role: StaffRole, val barberId: UUID?)
data class OtpChallenge(val id: UUID, val codeHash: String, val expiresAt: Instant, val attempts: Int, val consumedAt: Instant?)
data class StoredRefreshToken(val id: UUID, val userId: UUID, val familyId: UUID, val expiresAt: Instant, val revokedAt: Instant?, val replacedBy: UUID?)
