package com.geovannycode.corteya.identity

import java.time.Instant
import java.util.UUID

data class AppUser(val id: UUID, val phone: String?, val name: String?, val platformAdmin: Boolean, val deletedAt: Instant?) {
    override fun toString(): String = "AppUser(id=$id, phone=${phone?.let(PhoneNumbers::mask)}, name=[redacted])"
}
enum class StaffRole { OWNER, BARBER }
data class StaffMembership(val barbershopId: UUID, val role: StaffRole, val barberId: UUID?)
