package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.dto.AgendaAppointmentResponse
import com.geovannycode.corteya.booking.dto.MyAppointmentsResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import java.time.LocalDate
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@SecurityRequirement(name = "bearerAuth")
class AuthenticatedAppointmentsController(
    private val staffAgenda: StaffAgendaService, private val myAppointments: MyAppointmentsService,
) {
    @GetMapping("/me/appointments")
    @Operation(summary = "Citas propias de todas las barberías, agrupadas por fecha de fin")
    fun mine(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<MyAppointmentsResponse> {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            myAppointments.mine(UUID.fromString(jwt.subject)),
        )
    }

    @GetMapping("/barbershops/{slug}/staff/agenda")
    @Operation(summary = "Agenda local: BARBER solo su barbero; OWNER todos o filtro opcional barberId")
    fun agenda(@AuthenticationPrincipal jwt: Jwt, @PathVariable slug: String, @RequestParam date: LocalDate,
        @RequestParam(required = false) barberId: UUID?): ResponseEntity<List<AgendaAppointmentResponse>> {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            staffAgenda.agenda(UUID.fromString(jwt.subject), slug, date, barberId))
    }
}
