package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.dto.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/barbershops/{slug}/staff")
@SecurityRequirement(name = "bearerAuth")
class StaffBookingController(private val appointments: StaffAppointmentService, private val blocks: StaffTimeOffService,
    private val reports: StaffReportService) {
    @PostMapping("/time-off")
    @Operation(summary = "Bloquear horas propias o de cualquier barbero si eres OWNER; conflictos devuelven 409 y conflicts")
    fun block(@AuthenticationPrincipal jwt: Jwt, @PathVariable slug: String, @Valid @RequestBody body: TimeOffRequest) =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(blocks.create(user(jwt), slug, body))

    @GetMapping("/time-off")
    @Operation(summary = "Consultar bloqueos que se cruzan con [from,to), con offset obligatorio")
    fun blocks(@AuthenticationPrincipal jwt: Jwt, @PathVariable slug: String, @RequestParam from: OffsetDateTime, @RequestParam to: OffsetDateTime) =
        noStore(blocks.list(user(jwt), slug, from.toInstant(), to.toInstant()))

    @DeleteMapping("/time-off/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteBlock(@AuthenticationPrincipal jwt: Jwt, @PathVariable slug: String, @PathVariable id: UUID) = blocks.delete(user(jwt), slug, id)

    @PostMapping("/appointments")
    @Operation(summary = "Crear cita manual confirmada, sin señal; fuera de grilla pero sin cruces de citas ni bloqueos")
    fun manual(@AuthenticationPrincipal jwt: Jwt, @PathVariable slug: String, @Valid @RequestBody body: ManualAppointmentRequest) =
        ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(appointments.manual(user(jwt), slug, body))

    @PostMapping("/appointments/{id}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun complete(@AuthenticationPrincipal jwt: Jwt, @PathVariable slug: String, @PathVariable id: UUID) = appointments.complete(user(jwt), slug, id)

    @PostMapping("/appointments/{id}/no-show")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun noShow(@AuthenticationPrincipal jwt: Jwt, @PathVariable slug: String, @PathVariable id: UUID) = appointments.noShow(user(jwt), slug, id)

    @PostMapping("/appointments/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(@AuthenticationPrincipal jwt: Jwt, @PathVariable slug: String, @PathVariable id: UUID, @Valid @RequestBody body: StaffCancelRequest) =
        appointments.cancel(user(jwt), slug, id, body.reason)

    @GetMapping("/summary")
    @Operation(summary = "Resumen en Bogotá: citas por inicio, cobros por confirmación, devoluciones por DONE y deuda pendiente actual")
    fun summary(@AuthenticationPrincipal jwt: Jwt, @PathVariable slug: String, @RequestParam period: SummaryPeriod, @RequestParam date: LocalDate) =
        noStore(reports.summary(user(jwt), slug, period, date))

    @GetMapping("/action-items")
    @Operation(summary = "Señales Bre-B vigentes por confirmar, pagos tardíos y devoluciones pendientes")
    fun actions(@AuthenticationPrincipal jwt: Jwt, @PathVariable slug: String) = noStore(reports.actions(user(jwt), slug))

    private fun user(jwt: Jwt) = UUID.fromString(jwt.subject)
    private fun <T> noStore(value: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
}
