package com.geovannycode.corteya.booking

import com.geovannycode.corteya.shared.TenantTransaction
import com.geovannycode.corteya.booking.internal.AppointmentEntity
import com.geovannycode.corteya.booking.internal.AppointmentRepository
import com.geovannycode.corteya.customer.CustomerService
import com.geovannycode.corteya.identity.AuthorizationPolicy
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

data class CreateAppointmentCommand(
    val barberId: UUID, val serviceId: UUID, val startsAt: Instant,
    val userId: UUID,
)

@Service
class CreateAppointment(
    private val availability: AvailabilityService,
    private val bookings: AppointmentRepository,
    private val codes: PaymentReferences,
    private val clock: Clock,
    private val policy: AuthorizationPolicy,
    private val customers: CustomerService,
    private val tenants: TenantTransaction,
    private val paymentSettings: PaymentSettingsService,
    private val deposits: DepositService,
    private val depositRepository: com.geovannycode.corteya.booking.internal.DepositRepository,
) {
    fun create(slug: String, command: CreateAppointmentCommand): CreatedBooking {
        val shop = availability.shop(slug)
        return tenants.execute(shop.id) { createInTenant(slug, command) }
    }

    private fun createInTenant(slug: String, command: CreateAppointmentCommand): CreatedBooking {
        val selection = availability.select(slug, command.barberId, command.serviceId)
        val (shop, barber, service) = selection
        availability.lockSchedule(selection)
        val customer = policy.bookingCustomer(command.userId)
        val phone = checkNotNull(customer.phone)
        val name = checkNotNull(customer.name)
        val now = clock.instant()
        val payment = paymentSettings.requireConfigured(shop.id)
        bookings.expireForBarber(shop.id, barber.id, now)
        depositRepository.expirePending(shop.id, now)
        availability.requireSlot(selection, command.startsAt, now)
        val terms = BookingTerms.calculate(service.priceCop, payment.mode, shop.depositPercent, payment.holdMinutes, command.startsAt, shop.cancelHours)

        // The upsert locks this tenant + phone until commit, serializing the pending count.
        val customerId = customers.upsertAndLockCustomer(shop.id, phone, name, customer.id, now)
        if (payment.mode != DepositMode.NONE && bookings.pendingCount(shop.id, customerId) >= 2) {
            throw BookingFailure(409, "Ya tienes 2 reservas pendientes de pago en esta barbería")
        }
        val candidate = AppointmentEntity(
            barbershopId = shop.id, barberId = barber.id, serviceId = service.id, customerId = customerId,
            startsAt = command.startsAt, endsAt = command.startsAt.plusSeconds(service.durationMinutes.toLong() * 60),
            priceCop = terms.priceCop, depositCop = terms.depositCop,
            status = if (payment.mode == DepositMode.NONE) AppointmentStatus.CONFIRMED else AppointmentStatus.PENDING_PAYMENT,
            paymentReference = "", holdExpiresAt = if (payment.mode == DepositMode.NONE) null else now.plusSeconds(terms.holdMinutes.toLong() * 60),
            createdAt = now, updatedAt = now, cancelHoursSnapshot = shop.cancelHours,
        )
        repeat(32) {
            val appointment = candidate.copy(paymentReference = codes.next())
            if (bookings.insert(appointment)) {
                return CreatedBooking(appointment, deposits.initialize(appointment, payment),
                    terms.cancellationPolicy)
            }
        }
        throw BookingFailure(409, "No pudimos asignar una referencia a tu reserva, intenta de nuevo")
    }
}

data class CreatedBooking(val appointment: AppointmentEntity, val paymentInstructions: com.geovannycode.corteya.booking.dto.PaymentInstructions?,
    val cancellationPolicy: CancellationPolicy)
