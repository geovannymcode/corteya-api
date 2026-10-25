package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.dto.*
import com.geovannycode.corteya.booking.internal.AppointmentEntity
import com.geovannycode.corteya.booking.internal.AppointmentRepository
import com.geovannycode.corteya.booking.internal.DepositRepository
import com.geovannycode.corteya.booking.internal.ExpiredHoldCleanup
import com.geovannycode.corteya.customer.CustomerService
import com.geovannycode.corteya.identity.AuthorizationPolicy
import com.geovannycode.corteya.identity.PhoneNumbers
import com.geovannycode.corteya.scheduling.TimeOffService
import com.geovannycode.corteya.shared.ApiFailure
import com.geovannycode.corteya.shared.TenantTransaction
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

@Service
class StaffAppointmentService(private val guard: AuthorizationPolicy, private val tenants: TenantTransaction,
    private val availability: AvailabilityService, private val customers: CustomerService,
    private val bookings: AppointmentRepository, private val deposits: DepositRepository, private val blocks: TimeOffService,
    private val codes: PaymentReferences, private val clock: Clock, private val events: ApplicationEventPublisher,
    private val expiredHolds: ExpiredHoldCleanup) {
    fun manual(user: UUID, slug: String, request: ManualAppointmentRequest): StaffAppointmentResult {
        val scope = guard.agenda(user, slug, request.barberId)
        val phone = request.customerPhone?.let { PhoneNumbers.normalize(it) ?: throw ApiFailure(400, "Revisa el celular colombiano") }
        return tenants.execute(scope.barbershopId) {
            val selection = availability.select(slug, request.barberId, request.serviceId)
            availability.lockSchedule(selection)
            val now = clock.instant()
            val from = request.startsAt.toInstant()
            val to = from.plusSeconds(selection.service.durationMinutes.toLong() * 60)
            if (blocks.findOverlapping(scope.barbershopId, request.barberId, from, to).isNotEmpty())
                throw ApiFailure(409, "El barbero tiene un bloqueo en ese horario")
            expiredHolds.forBarber(scope.barbershopId, request.barberId, now)
            val customerId = customers.manualCustomer(scope.barbershopId, phone, request.customerName.trim(), now)
            val candidate = AppointmentEntity(barbershopId = scope.barbershopId, barberId = request.barberId,
                serviceId = request.serviceId, customerId = customerId, startsAt = from, endsAt = to,
                status = AppointmentStatus.CONFIRMED, source = AppointmentSource.MANUAL,
                priceCop = selection.service.priceCop, depositCop = 0, paymentReference = "", holdExpiresAt = null,
                createdAt = now, updatedAt = now, cancelHoursSnapshot = selection.shop.cancelHours)
            repeat(32) {
                val a = candidate.copy(paymentReference = codes.next())
                if (bookings.insert(a)) return@execute a.result()
            }
            throw ApiFailure(409, "No pudimos asignar una referencia a tu reserva, intenta de nuevo")
        }
    }

    fun complete(user: UUID, slug: String, id: UUID) = attendance(user, slug, id, AppointmentStatus.COMPLETED)
    fun noShow(user: UUID, slug: String, id: UUID) = attendance(user, slug, id, AppointmentStatus.NO_SHOW)
    private fun attendance(user: UUID, slug: String, id: UUID, target: AppointmentStatus) {
        val scope = guard.agenda(user, slug)
        tenants.execute(scope.barbershopId) {
            val a = bookings.lock(scope.barbershopId, id) ?: BookingFailure.notFound()
            guard.requireBarber(scope, a.barberId)
            if (a.status == target) return@execute
            if (a.status != AppointmentStatus.CONFIRMED) throw ApiFailure(409, "Solo puedes marcar asistencia de una cita confirmada")
            val now = clock.instant()
            if (target == AppointmentStatus.COMPLETED && now < a.startsAt.minusSeconds(15 * 60))
                throw ApiFailure(409, "Puedes marcar asistencia desde 15 minutos antes del inicio")
            if (target == AppointmentStatus.NO_SHOW && now <= a.startsAt)
                throw ApiFailure(409, "Puedes marcar inasistencia después de la hora de inicio")
            if (target == AppointmentStatus.NO_SHOW) deposits.forAppointment(a.barbershopId, a.id)
                .filter { it.status == DepositStatus.APPROVED }.forEach {
                    deposits.refund(it, RefundStatus.FORFEITED, DepositEventSource.STAFF, now, user)
                }
            bookings.setStatus(a.barbershopId, a.id, target)
            events.publishEvent(if (target == AppointmentStatus.COMPLETED) AppointmentCompleted(a.barbershopId, a.id, a.publicToken, now)
                else AppointmentNoShow(a.barbershopId, a.id, a.publicToken, now))
        }
    }

    fun cancel(user: UUID, slug: String, id: UUID, reason: String) {
        val scope = guard.agenda(user, slug)
        tenants.execute(scope.barbershopId) {
            val a = bookings.lock(scope.barbershopId, id) ?: BookingFailure.notFound()
            guard.requireBarber(scope, a.barberId)
            if (a.status == AppointmentStatus.CANCELLED) return@execute
            val payments = deposits.forAppointment(a.barbershopId, a.id)
            val expiredLatePayment = a.status == AppointmentStatus.EXPIRED && payments.any { it.status == DepositStatus.LATE_PAYMENT }
            if (a.status !in listOf(AppointmentStatus.PENDING_PAYMENT, AppointmentStatus.CONFIRMED) && !expiredLatePayment)
                throw ApiFailure(409, "El estado de esta cita no permite cancelarla")
            val now = clock.instant()
            payments.filter { it.status == DepositStatus.PENDING }.forEach {
                deposits.transition(it, DepositStatus.VOIDED, DepositEventSource.STAFF, now, user)
            }
            val received = payments.filter { it.status in listOf(DepositStatus.APPROVED, DepositStatus.LATE_PAYMENT) }
            received.forEach { deposits.refund(it, RefundStatus.PENDING, DepositEventSource.STAFF, now, user) }
            bookings.cancel(a.barbershopId, a.id, CancellationOrigin.STAFF, reason.trim())
            val amount = received.fold(0L) { total, p -> Math.addExact(total, p.amountCop) }
            events.publishEvent(AppointmentCancelled(a.barbershopId, a.id, a.publicToken,
                if (received.isEmpty()) RefundStatus.NONE else RefundStatus.PENDING, amount, now,
                CancellationOrigin.STAFF, "cancelada por la barbería"))
        }
    }
    private fun AppointmentEntity.result() = StaffAppointmentResult(id, publicToken, status, source,
        startsAt.atZone(ZoneId.of("America/Bogota")).toOffsetDateTime(), endsAt.atZone(ZoneId.of("America/Bogota")).toOffsetDateTime())
}
