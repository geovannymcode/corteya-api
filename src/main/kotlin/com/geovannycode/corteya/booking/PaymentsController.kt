package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.dto.*
import com.geovannycode.corteya.shared.ApiFailure
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import tools.jackson.databind.json.JsonMapper

@RestController
@RequestMapping("/api/v1")
class PaymentsController(private val settings: PaymentSettingsService, private val deposits: DepositService,
    private val webhook: WompiWebhookService, private val mapper: JsonMapper) {
    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/barbershops/{slug}/staff/payment-settings")
    fun settings(@PathVariable slug: String, @AuthenticationPrincipal jwt: Jwt): ResponseEntity<PaymentSettingsResponse> =
        noStore(settings.get(UUID.fromString(jwt.subject), slug))

    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/barbershops/{slug}/staff/payment-settings")
    fun update(@PathVariable slug: String, @AuthenticationPrincipal jwt: Jwt, @Valid @RequestBody body: PaymentSettingsRequest): ResponseEntity<PaymentSettingsResponse> =
        noStore(settings.update(UUID.fromString(jwt.subject), slug, body))

    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/appointments/{publicToken}/payment-attempts")
    fun retry(@PathVariable publicToken: String, @AuthenticationPrincipal jwt: Jwt): ResponseEntity<PaymentInstructions> =
        noStore(deposits.retry(UUID.fromString(jwt.subject), publicToken))

    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/barbershops/{slug}/staff/deposits")
    fun pending(@PathVariable slug: String, @RequestParam(defaultValue = "PENDING") status: DepositStatus,
        @AuthenticationPrincipal jwt: Jwt): ResponseEntity<List<DepositResponse>> = noStore(deposits.pending(UUID.fromString(jwt.subject), slug, status))

    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/barbershops/{slug}/staff/appointments/{id}/deposit/confirm")
    fun confirm(@PathVariable slug: String, @PathVariable id: UUID, @AuthenticationPrincipal jwt: Jwt): ResponseEntity<Void> {
        deposits.manual(UUID.fromString(jwt.subject), slug, id, true)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/barbershops/{slug}/staff/appointments/{id}/deposit/reject")
    fun reject(@PathVariable slug: String, @PathVariable id: UUID, @AuthenticationPrincipal jwt: Jwt): ResponseEntity<Void> {
        deposits.manual(UUID.fromString(jwt.subject), slug, id, false)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @PostMapping("/webhooks/wompi/{webhookId}", consumes = ["application/json"])
    fun webhook(@PathVariable webhookId: String, request: HttpServletRequest): ResponseEntity<Void> {
        // Enforce the bound while reading, including chunked requests without Content-Length.
        val maximum = 64 * 1024
        if (request.contentLengthLong > maximum) throw ApiFailure(413, "El evento excede el tamaño permitido")
        val bytes = request.inputStream.readNBytes(maximum + 1)
        if (bytes.size > maximum) throw ApiFailure(413, "El evento excede el tamaño permitido")
        val event = try { mapper.readTree(bytes) } catch (_: Exception) { throw ApiFailure(400, "Evento de pago inválido") }
        webhook.process(webhookId, event)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build()
    }

    private fun <T : Any> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}
