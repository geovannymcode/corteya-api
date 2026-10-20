package com.geovannycode.corteya.identity

import com.geovannycode.corteya.identity.AccountService
import com.geovannycode.corteya.identity.AuthorizationPolicy
import com.geovannycode.corteya.identity.IdentityRepository
import com.geovannycode.corteya.identity.PhoneNumbers
import com.geovannycode.corteya.identity.StaffMembership
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RenameBody(@field:NotBlank @field:Size(max = 150) val name: String) {
    override fun toString(): String = "RenameBody([redacted])"
}
data class MeResponse(val id: UUID, val phone: String?, val name: String?, val platformAdmin: Boolean, val memberships: List<StaffMembership>) {
    override fun toString(): String = "MeResponse(id=$id, phone=${phone?.let(PhoneNumbers::mask)}, name=[redacted])"
}

@RestController
@RequestMapping("/api/v1/me")
@SecurityRequirement(name = "bearerAuth")
class AccountController(private val accounts: AccountService, private val policy: AuthorizationPolicy, private val store: IdentityRepository) {
    @GetMapping
    @Operation(summary = "Perfil y membresías actuales del usuario autenticado")
    fun me(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<MeResponse> {
        val user = policy.user(UUID.fromString(jwt.subject))
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(MeResponse(user.id, user.phone, user.name, user.platformAdmin, store.memberships(user.id)))
    }

    @PatchMapping
    @Operation(summary = "Completar o actualizar nombre")
    fun rename(@AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody body: RenameBody): ResponseEntity<Void> {
        accounts.rename(UUID.fromString(jwt.subject), body.name)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @DeleteMapping
    @Operation(summary = "Borrar cuenta, anonimizar clientes y conservar sus citas")
    fun delete(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<Void> {
        accounts.delete(UUID.fromString(jwt.subject))
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }
}
