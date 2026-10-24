package com.geovannycode.corteya.booking

import com.geovannycode.corteya.barbershop.BarbershopService
import com.geovannycode.corteya.booking.dto.CancellationResponse
import com.geovannycode.corteya.booking.internal.AppointmentEntity
import com.geovannycode.corteya.booking.internal.AppointmentRepository
import com.geovannycode.corteya.booking.internal.DepositRepository
import com.geovannycode.corteya.identity.AuthorizationPolicy
import com.geovannycode.corteya.shared.ApiFailure
import com.geovannycode.corteya.shared.TenantTransaction
import java.text.NumberFormat
import java.time.Clock
import java.util.Locale
import java.util.UUID
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

@Service
class CancellationService(
    private val bookings: AppointmentRepository, private val deposits: DepositRepository,
    private val queries: BookingQueries, private val policy: AuthorizationPolicy,
    private val shops: BarbershopService, private val tenants: TenantTransaction,
    private val clock: Clock, private val events: ApplicationEventPublisher,
) {
    fun policy(appointment: AppointmentEntity): CancellationPolicy = policies(listOf(appointment)).getValue(appointment.id)

    fun policies(appointments: List<AppointmentEntity>): Map<UUID, CancellationPolicy> {
        val legacyHours = appointments.filter { it.cancelHoursSnapshot == null }.map { it.barbershopId }.distinct().associateWith { shopId ->
            tenants.execute(shopId) { (shops.findById(shopId) ?: BookingFailure.notFound()).cancelHours }
        }
        return appointments.associate { appointment ->
            appointment.id to CancellationPolicy.forAppointment(appointment.startsAt,
                appointment.cancelHoursSnapshot ?: legacyHours.getValue(appointment.barbershopId), appointment.depositCop > 0)
        }
    }

    fun cancel(user: UUID, publicToken: String): CancellationResponse {
        val visible = queries.appointment(user, publicToken)
        policy.appointmentCustomer(user, visible.barbershopId, visible.customerId)
        return tenants.execute(visible.barbershopId) {
            val a = bookings.lock(visible.barbershopId, visible.id) ?: BookingFailure.notFound()
            policy.appointmentCustomer(user, a.barbershopId, a.customerId)
            val now = clock.instant()
            if (!CancellationPolicy.canCancel(a.status, a.startsAt, now)) {
                throw ApiFailure(409, "Esta cita ya empezó o su estado no permite cancelarla")
            }
            val cancellation = policy(a)
            val payments = deposits.forAppointment(a.barbershopId, a.id)
            val approved = payments.filter { it.status == DepositStatus.APPROVED }
            val refundStatus = if (a.status == AppointmentStatus.CONFIRMED && approved.isNotEmpty()) {
                if (now <= cancellation.cancellableWithRefundUntil) RefundStatus.PENDING else RefundStatus.FORFEITED
            } else RefundStatus.NONE
            payments.filter { it.status == DepositStatus.PENDING }.forEach {
                deposits.transition(it, DepositStatus.VOIDED, DepositEventSource.SYSTEM, now)
            }
            if (refundStatus != RefundStatus.NONE) approved.forEach {
                deposits.refund(it, refundStatus, DepositEventSource.SYSTEM, now)
            }
            bookings.setStatus(a.barbershopId, a.id, AppointmentStatus.CANCELLED)
            val amount = if (refundStatus == RefundStatus.PENDING) approved.fold(0L) { total, payment -> Math.addExact(total, payment.amountCop) } else 0L
            val message = when (refundStatus) {
                RefundStatus.PENDING -> "Cancelaste a tiempo: la barbería te devolverá \$${NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-CO")).format(amount)}"
                RefundStatus.FORFEITED -> "Cancelaste con menos de ${cancellation.cancelHours} horas: la señal no es reembolsable"
                else -> "Cancelaste tu cita. No tienes una señal por devolver"
            }
            events.publishEvent(AppointmentCancelled(a.barbershopId, a.id, a.publicToken, refundStatus, amount, now))
            CancellationResponse(a.publicToken, AppointmentStatus.CANCELLED, refundStatus, amount, message)
        }
    }

    fun refunded(user: UUID, slug: String, appointmentId: UUID) {
        val scope = policy.agenda(user, slug)
        tenants.execute(scope.barbershopId) {
            val a = bookings.lock(scope.barbershopId, appointmentId) ?: BookingFailure.notFound()
            if (scope.barberId != null && a.barberId != scope.barberId) BookingFailure.notFound()
            val approved = deposits.forAppointment(a.barbershopId, a.id).filter { it.status == DepositStatus.APPROVED }
            if (a.status != AppointmentStatus.CANCELLED || approved.isEmpty() ||
                approved.none { it.refundStatus in listOf(RefundStatus.PENDING, RefundStatus.DONE) }) {
                throw ApiFailure(409, "Esta cita no tiene devoluciones pendientes")
            }
            approved.filter { it.refundStatus == RefundStatus.PENDING }.forEach {
                deposits.refund(it, RefundStatus.DONE, DepositEventSource.STAFF, clock.instant(), user)
            }
        }
    }
}
