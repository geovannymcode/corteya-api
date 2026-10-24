package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.dto.CancellationResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
class CancellationController(private val cancellations: CancellationService) {
    @PostMapping("/appointments/{publicToken}/cancel")
    @Operation(summary = "Cancelar una cita propia antes de su inicio", description = "Devuelve si hay devolución pendiente o si la señal no es reembolsable")
    fun cancel(@PathVariable publicToken: String, @AuthenticationPrincipal jwt: Jwt): ResponseEntity<CancellationResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cancellations.cancel(UUID.fromString(jwt.subject), publicToken))

    @PostMapping("/barbershops/{slug}/staff/appointments/{id}/deposit/refunded")
    @Operation(summary = "Registrar que el barbero ya devolvió la señal", description = "OWNER o BARBER asignado; no ejecuta transferencias ni reembolsos automáticos")
    fun refunded(@PathVariable slug: String, @PathVariable id: UUID, @AuthenticationPrincipal jwt: Jwt): ResponseEntity<Void> {
        cancellations.refunded(UUID.fromString(jwt.subject), slug, id)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }
}
