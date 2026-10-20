package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.dto.AgendaAppointmentResponse
import com.geovannycode.corteya.booking.dto.AppointmentStatusResponse
import com.geovannycode.corteya.booking.dto.MyAppointmentsResponse
import com.geovannycode.corteya.booking.internal.AppointmentEntity
import com.geovannycode.corteya.identity.AuthorizationPolicy
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
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
    private val policy: AuthorizationPolicy, private val queries: BookingQueries,
    private val availability: AvailabilityService, private val clock: Clock,
) {
    @GetMapping("/me/appointments")
    @Operation(summary = "Citas propias de todas las barberías, agrupadas por fecha de fin")
    fun mine(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<MyAppointmentsResponse> {
        val user = policy.user(UUID.fromString(jwt.subject))
        val now = clock.instant()
        val (upcoming, past) = queries.mine(user.id).partition { it.endsAt > now }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            MyAppointmentsResponse(upcoming.map { it.response() }, past.asReversed().map { it.response() }),
        )
    }

    @GetMapping("/barbershops/{slug}/staff/agenda")
    @Operation(summary = "Agenda local: BARBER solo su barbero; OWNER todos o filtro opcional barberId")
    fun agenda(@AuthenticationPrincipal jwt: Jwt, @PathVariable slug: String, @RequestParam date: LocalDate,
        @RequestParam(required = false) barberId: UUID?): ResponseEntity<List<AgendaAppointmentResponse>> {
        val scope = policy.agenda(UUID.fromString(jwt.subject), slug, barberId)
        val zone = ZoneId.of(availability.shop(slug).timezone)
        val rows = queries.agenda(scope, date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(rows.map { AgendaAppointmentResponse(it.barberId, it.serviceId, it.response()) })
    }

    private fun AppointmentEntity.response() = AppointmentStatusResponse(publicToken, status, startsAt.bogota(), endsAt.bogota(), depositCop, paymentReference, holdExpiresAt?.bogota())
    private fun Instant.bogota(): OffsetDateTime = atZone(ZoneId.of("America/Bogota")).toOffsetDateTime()
}
