package com.geovannycode.corteya.identity.infrastructure.web

import com.geovannycode.corteya.identity.application.AuthService
import com.geovannycode.corteya.identity.application.TokenPair
import com.geovannycode.corteya.identity.domain.PhoneNumbers
import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RequestOtpBody(@field:NotBlank @field:Size(max = 16) val phone: String) {
    override fun toString(): String = "RequestOtpBody(phone=${PhoneNumbers.mask(phone)})"
}
data class VerifyOtpBody(@field:NotBlank @field:Size(max = 16) val phone: String, @field:NotBlank @field:Size(max = 64) val code: String) {
    override fun toString(): String = "VerifyOtpBody(phone=${PhoneNumbers.mask(phone)}, code=[redacted])"
}
data class RefreshBody(@field:NotBlank @field:Size(max = 128) val refreshToken: String) {
    override fun toString(): String = "RefreshBody([redacted])"
}
data class OtpRequestResponse(val message: String = "Si el número es válido, recibirás un código de verificación")

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(private val auth: AuthService) {
    @PostMapping("/otp/request")
    @Operation(summary = "Solicitar OTP sin revelar si el número ya está registrado")
    fun request(@Valid @RequestBody body: RequestOtpBody, request: HttpServletRequest): ResponseEntity<OtpRequestResponse> {
        // Do not trust caller-supplied X-Forwarded-For. Configure a trusted edge before changing this.
        auth.request(body.phone, request.remoteAddr)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(OtpRequestResponse())
    }

    @PostMapping("/otp/verify")
    @Operation(summary = "Verificar OTP de un solo uso y abrir sesión")
    fun verify(@Valid @RequestBody body: VerifyOtpBody): ResponseEntity<TokenPair> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.verify(body.phone, body.code))

    @PostMapping("/refresh")
    @Operation(summary = "Rotar refresh token; el reuso revoca la familia completa")
    fun refresh(@Valid @RequestBody body: RefreshBody): ResponseEntity<TokenPair> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(auth.refresh(body.refreshToken))

    @PostMapping("/logout")
    @Operation(summary = "Revocar la familia del refresh token, de forma idempotente")
    fun logout(@Valid @RequestBody body: RefreshBody): ResponseEntity<Void> {
        auth.logout(body.refreshToken)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }
}
