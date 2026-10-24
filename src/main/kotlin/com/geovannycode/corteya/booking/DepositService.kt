package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.dto.DepositResponse
import com.geovannycode.corteya.booking.dto.PaymentInstructions
import com.geovannycode.corteya.booking.internal.*
import com.geovannycode.corteya.identity.AuthorizationPolicy
import com.geovannycode.corteya.shared.ApiFailure
import com.geovannycode.corteya.shared.TenantTransaction
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class DepositService(
    private val settings: PaymentSettingsService, private val deposits: DepositRepository,
    private val bookings: AppointmentRepository, private val gateways: List<PaymentGateway>,
    private val policy: AuthorizationPolicy, private val queries: BookingQueries,
    private val confirmation: DepositConfirmationService, private val tenants: TenantTransaction, private val clock: Clock,
) {
    /** Called inside the appointment's transaction so booking and deposit commit together. */
    fun initialize(appointment: AppointmentEntity, configuration: PaymentSettings): PaymentInstructions? {
        if (configuration.mode == DepositMode.NONE) return null
        val payment = deposits.create(appointment, configuration, clock.instant())
        return gateway(payment.method).instructions(configuration, payment, appointment)
    }

    fun retry(user: UUID, token: String): PaymentInstructions {
        val visible = queries.appointment(user, token)
        policy.appointmentCustomer(user, visible.barbershopId, visible.customerId)
        return tenants.execute(visible.barbershopId) {
            val a = bookings.lock(visible.barbershopId, visible.id) ?: BookingFailure.notFound()
            val previous = deposits.latest(a.barbershopId, a.id) ?: BookingFailure.notFound()
            val configuration = settings.current(a.barbershopId)
            if (a.status != AppointmentStatus.PENDING_PAYMENT || a.holdExpiresAt?.isAfter(clock.instant()) != true ||
                previous.method != DepositMode.WOMPI || configuration.mode != DepositMode.WOMPI ||
                previous.status !in listOf(DepositStatus.DECLINED, DepositStatus.ERROR)) {
                throw ApiFailure(409, "No puedes reintentar este pago o la reserva ya venció")
            }
            gateway(DepositMode.WOMPI).instructions(configuration, deposits.create(a, configuration, clock.instant(), previous.attempt + 1), a)
        }
    }

    fun pending(user: UUID, slug: String, status: DepositStatus): List<DepositResponse> {
        val scope = policy.agenda(user, slug)
        return tenants.execute(scope.barbershopId) { deposits.list(scope.barbershopId, scope.barberId, status).map(::response) }
    }

    fun manual(user: UUID, slug: String, appointmentId: UUID, approve: Boolean) {
        val scope = policy.agenda(user, slug)
        tenants.execute(scope.barbershopId) {
            val a = bookings.lock(scope.barbershopId, appointmentId) ?: BookingFailure.notFound()
            if (scope.barberId != null && a.barberId != scope.barberId) BookingFailure.notFound()
            val p = deposits.latest(a.barbershopId, a.id)?.takeIf { it.method == DepositMode.BREB_MANUAL } ?: BookingFailure.notFound()
            if (approve) confirmation.confirm(a.barbershopId, p.id, DepositEventSource.STAFF, user)
            else if (a.status in listOf(AppointmentStatus.PENDING_PAYMENT, AppointmentStatus.EXPIRED) &&
                p.status !in listOf(DepositStatus.APPROVED, DepositStatus.LATE_PAYMENT)) {
                deposits.transition(p, DepositStatus.DECLINED, DepositEventSource.STAFF, clock.instant(), user)
                bookings.setStatus(a.barbershopId, a.id, AppointmentStatus.CANCELLED)
            }
        }
    }

    private fun gateway(mode: DepositMode): PaymentGateway = gateways.single { it.mode == mode }
    private fun response(p: DepositPayment) = DepositResponse(p.id, p.appointmentId, p.method, p.amountCop, p.reference, p.status,
        p.confirmedAt?.atZone(ZoneId.of("America/Bogota"))?.toOffsetDateTime(), p.refundStatus)
}
