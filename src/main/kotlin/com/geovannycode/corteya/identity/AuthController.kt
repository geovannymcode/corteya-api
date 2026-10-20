package com.geovannycode.corteya.identity

import com.geovannycode.corteya.identity.dto.OtpRequestResponse
import com.geovannycode.corteya.identity.dto.RefreshBody
import com.geovannycode.corteya.identity.dto.RequestOtpBody
import com.geovannycode.corteya.identity.dto.TokenPair
import com.geovannycode.corteya.identity.dto.VerifyOtpBody
import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
