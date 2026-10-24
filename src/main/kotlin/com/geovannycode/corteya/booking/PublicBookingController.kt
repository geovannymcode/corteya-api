package com.geovannycode.corteya.booking

import com.geovannycode.corteya.barbershop.ShopStatusService
import com.geovannycode.corteya.booking.dto.AppointmentDetailResponse
import com.geovannycode.corteya.booking.dto.BookingQuoteResponse
import com.geovannycode.corteya.booking.dto.AvailabilityResponse
import com.geovannycode.corteya.booking.dto.BarberResponse
import com.geovannycode.corteya.booking.dto.BarbershopResponse
import com.geovannycode.corteya.booking.dto.CreateAppointmentRequest
import com.geovannycode.corteya.booking.dto.CreatedAppointmentResponse
import com.geovannycode.corteya.booking.dto.ServiceResponse
import com.geovannycode.corteya.identity.AuthorizationPolicy
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import jakarta.validation.Valid
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1")
@ApiResponses(value = [
    ApiResponse(responseCode = "400", description = "Datos inválidos", content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))]),
    ApiResponse(responseCode = "404", description = "Recurso no disponible en esta barbería", content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))]),
])
class PublicBookingController(
    private val availability: AvailabilityService,
    private val shopStatus: ShopStatusService,
    private val createAppointment: CreateAppointment,
    private val queries: BookingQueries,
    private val policy: AuthorizationPolicy,
    private val paymentSettings: PaymentSettingsService,
    private val cancellations: CancellationService,
    private val quotes: BookingQuoteService,
    private val deposits: DepositService,
) {
    @GetMapping("/barbershops/{slug}")
    @Operation(summary = "Identidad y estado de apertura de la barbería")
    @ApiResponse(responseCode = "200", description = "Identidad pública y reglas de reserva sin UUID de barbería",
        content = [Content(schema = Schema(implementation = BarbershopResponse::class))])
    fun shop(@PathVariable slug: String): BarbershopResponse {
        val shop = availability.shop(slug)
        val status = shopStatus.status(shop)
        return BarbershopResponse(shop.slug, shop.name, shop.logoUrl, shop.primaryColor, shop.secondaryColor,
            status.open, status.message, status.nextChangeAt?.bogota(), paymentSettings.bookingEnabled(shop.id), shop.bookingWindowDays, shop.address, shop.city)
    }

    @GetMapping("/barbershops/{slug}/services")
    @Operation(summary = "Servicios activos con precios en COP enteros")
    fun services(@PathVariable slug: String): List<ServiceResponse> = availability.services(slug).map {
        ServiceResponse(it.id, it.name, it.description, it.durationMinutes, it.priceCop)
    }

    @GetMapping("/barbershops/{slug}/barbers")
    @Operation(summary = "Barberos que prestan el servicio y su primer hueco dentro de la ventana de reserva")
    fun barbers(@PathVariable slug: String, @RequestParam serviceId: UUID): List<BarberResponse> =
        availability.barbers(slug, serviceId).map {
            BarberResponse(it.barber.id, it.barber.name, it.barber.bio, it.barber.photoUrl, it.nextAvailableAt?.bogota())
        }

    @GetMapping("/barbershops/{slug}/availability")
    @Operation(summary = "Huecos válidos agrupados antes y después de las 12:00 en la zona de la barbería")
    fun availability(@PathVariable slug: String, @RequestParam barberId: UUID, @RequestParam serviceId: UUID,
        @RequestParam date: LocalDate): AvailabilityResponse {
        val slots = availability.availability(slug, barberId, serviceId, date)
        return AvailabilityResponse(date, slots.morning.map { it.bogota() }, slots.afternoon.map { it.bogota() })
    }

    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/barbershops/{slug}/booking-quote")
    @Operation(summary = "Cotizar un hueco sin reservarlo; la creación vuelve a validar disponibilidad")
    @ApiResponse(responseCode = "409", description = "Horario no disponible o reservas no habilitadas")
    fun quote(@PathVariable slug: String, @RequestParam serviceId: UUID, @RequestParam barberId: UUID,
        @RequestParam startsAt: OffsetDateTime, @AuthenticationPrincipal jwt: Jwt): ResponseEntity<BookingQuoteResponse> {
        val terms = quotes.quote(UUID.fromString(jwt.subject), slug, barberId, serviceId, startsAt.toInstant())
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(BookingQuoteResponse(
            terms.priceCop, terms.depositMode, terms.depositCop, terms.payAtShopCop, terms.holdMinutes,
            terms.cancellationPolicy.cancellableWithRefundUntil.bogota(), terms.cancellationPolicy.cancelPolicyText,
        ))
    }

    @SecurityRequirement(name = "bearerAuth")
    @PostMapping("/barbershops/{slug}/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Reservar un hueco y retenerlo hasta el vencimiento de la señal")
    @ApiResponse(responseCode = "409", description = "Horario ocupado, límite de pendientes o referencia agotada",
        content = [Content(mediaType = "application/problem+json", schema = Schema(implementation = ProblemDetail::class))])
    fun create(@PathVariable slug: String, @Valid @RequestBody body: CreateAppointmentRequest, @AuthenticationPrincipal jwt: Jwt): ResponseEntity<CreatedAppointmentResponse> {
        val created = createAppointment.create(slug, CreateAppointmentCommand(
            body.barberId, body.serviceId, body.startsAt.toInstant(), UUID.fromString(jwt.subject),
        ))
        val result = created.appointment
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(
            CreatedAppointmentResponse(result.publicToken, result.status, result.depositCop, result.paymentReference,
                result.holdExpiresAt?.bogota(), created.paymentInstructions,
                created.cancellationPolicy.cancellableWithRefundUntil.bogota(), created.cancellationPolicy.cancelPolicyText),
        )
    }

    @SecurityRequirement(name = "bearerAuth")
    @GetMapping("/appointments/{publicToken}")
    @Operation(summary = "Consultar el estado con sesión: propietario de la cita o staff de su barbería")
    @ApiResponse(responseCode = "200", description = "Cita y señal sin secretos de pago",
        content = [Content(schema = Schema(implementation = AppointmentDetailResponse::class))])
    fun appointment(@PathVariable publicToken: String, @AuthenticationPrincipal jwt: Jwt): ResponseEntity<AppointmentDetailResponse> {
        if (!publicToken.matches(Regex("[0-9a-f]{32}"))) BookingFailure.notFound()
        val result = queries.appointment(UUID.fromString(jwt.subject), publicToken)
        val cancellationPolicy = cancellations.policy(result)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            AppointmentDetailResponse(result.publicToken, result.status, result.startsAt.bogota(), result.endsAt.bogota(),
                result.depositCop, result.paymentReference, result.holdExpiresAt?.bogota(),
                cancellationPolicy.cancellableWithRefundUntil.bogota(), cancellationPolicy.cancelPolicyText, deposits.detail(result)),
        )
    }

    private fun Instant.bogota(): OffsetDateTime = atZone(ZoneId.of("America/Bogota")).toOffsetDateTime()
}
