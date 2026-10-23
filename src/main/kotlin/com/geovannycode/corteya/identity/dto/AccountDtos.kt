package com.geovannycode.corteya.identity.dto

import com.geovannycode.corteya.identity.PhoneNumbers
import com.geovannycode.corteya.identity.StaffRole
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class RenameBody(@field:NotBlank @field:Size(max = 150) val name: String) {
    override fun toString(): String = "RenameBody([redacted])"
}
data class MembershipResponse(
    val barbershopId: UUID, val role: StaffRole, val barberId: UUID?,
    @field:Schema(description = "Slug actual de la barbería, incluso cuando está inactiva")
    val barbershopSlug: String,
    @field:Schema(description = "Nombre actual de la barbería")
    val barbershopName: String,
)

data class MeResponse(val id: UUID, val phone: String?, val name: String?, val platformAdmin: Boolean, val memberships: List<MembershipResponse>) {
    override fun toString(): String = "MeResponse(id=$id, phone=${phone?.let(PhoneNumbers::mask)}, name=[redacted])"
}
