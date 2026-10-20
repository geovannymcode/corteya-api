package com.geovannycode.corteya.identity.dto

import com.geovannycode.corteya.identity.PhoneNumbers
import com.geovannycode.corteya.identity.StaffMembership
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

data class RenameBody(@field:NotBlank @field:Size(max = 150) val name: String) {
    override fun toString(): String = "RenameBody([redacted])"
}
data class MeResponse(val id: UUID, val phone: String?, val name: String?, val platformAdmin: Boolean, val memberships: List<StaffMembership>) {
    override fun toString(): String = "MeResponse(id=$id, phone=${phone?.let(PhoneNumbers::mask)}, name=[redacted])"
}
