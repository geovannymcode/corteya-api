package com.geovannycode.corteya.booking.application

import com.geovannycode.corteya.booking.domain.Appointment
import com.geovannycode.corteya.booking.domain.BookingCodes
import com.geovannycode.corteya.identity.application.AuthorizationPolicy
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class CreateAppointmentCommand(
    val barberId: UUID, val serviceId: UUID, val startsAt: Instant,
    val userId: UUID,
)

@Service
class CreateAppointment(
    private val availability: AvailabilityService,
    private val bookings: BookingStore,
    private val codes: PaymentReferences,
    private val clock: Clock,
    private val policy: AuthorizationPolicy,
) {
    @Transactional
    fun create(slug: String, command: CreateAppointmentCommand): Appointment {
        val selection = availability.select(slug, command.barberId, command.serviceId)
        val (shop, barber, service) = selection
        val customer = policy.bookingCustomer(command.userId)
        val phone = checkNotNull(customer.phone)
        val name = checkNotNull(customer.name)
        val now = clock.instant()
        bookings.expireForBarber(shop.id, barber.id, now)
        val date = command.startsAt.atZone(ZoneId.of(shop.timezone)).toLocalDate()
        if (command.startsAt !in availability.slots(selection, date, now).all()) BookingFailure.slotTaken()

        // The upsert locks this tenant + phone until commit, serializing the pending count.
        val customerId = bookings.upsertAndLockCustomer(shop.id, phone, name, customer.id, now)
        if (bookings.pendingCount(shop.id, customerId) >= 2) {
            throw BookingFailure(409, "Ya tienes 2 reservas pendientes de pago en esta barbería")
        }
        val candidate = Appointment(
            barbershopId = shop.id, barberId = barber.id, serviceId = service.id, customerId = customerId,
            startsAt = command.startsAt, endsAt = command.startsAt.plusSeconds(service.durationMinutes.toLong() * 60),
            priceCop = service.priceCop, depositCop = BookingCodes.depositCop(service.priceCop, shop.depositPercent),
            paymentReference = "", holdExpiresAt = now.plusSeconds(shop.holdMinutes.toLong() * 60),
            createdAt = now, updatedAt = now,
        )
        repeat(32) {
            val appointment = candidate.copy(paymentReference = codes.next())
            if (bookings.insert(appointment)) return appointment
        }
        throw BookingFailure(409, "No pudimos asignar una referencia a tu reserva, intenta de nuevo")
    }
}
