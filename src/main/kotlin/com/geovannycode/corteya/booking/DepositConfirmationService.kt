package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.internal.AppointmentRepository
import com.geovannycode.corteya.booking.internal.DepositRepository
import com.geovannycode.corteya.shared.TenantTransaction
import java.sql.SQLException
import java.time.Clock
import java.util.UUID
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/** All approvals, including late payments, share the same locked state transition and audit trail. */
@Service
class DepositConfirmationService(
    private val bookings: AppointmentRepository, private val deposits: DepositRepository,
    private val tenants: TenantTransaction, private val jdbc: JdbcTemplate, private val clock: Clock,
    private val barbers: com.geovannycode.corteya.catalog.BarberDirectoryService,
    private val blocks: com.geovannycode.corteya.scheduling.TimeOffService,
) {
    fun confirm(shop: UUID, paymentId: UUID, source: DepositEventSource, user: UUID? = null, transactionId: String? = null) = tenants.execute(shop) {
        // Resolve payment first, then lock appointment before payment, matching retries and staff operations.
        val appointmentId = jdbc.queryForObject("select appointment_id from deposit_payment where barbershop_id = ? and id = ?",
            UUID::class.java, shop, paymentId) ?: BookingFailure.notFound()
        val visible = bookings.findById(shop, appointmentId) ?: BookingFailure.notFound()
        if (!barbers.lockSchedule(shop, visible.barberId)) BookingFailure.notFound()
        val appointment = bookings.lock(shop, appointmentId) ?: BookingFailure.notFound()
        val payment = deposits.find(shop, paymentId) ?: BookingFailure.notFound()
        if (payment.status in listOf(DepositStatus.APPROVED, DepositStatus.LATE_PAYMENT)) return@execute
        if (payment.providerTransactionId != null && payment.providerTransactionId != transactionId && transactionId != null) BookingFailure.notFound()
        val now = clock.instant()
        if (appointment.status == AppointmentStatus.CANCELLED) {
            if (source == DepositEventSource.WEBHOOK && payment.method == DepositMode.WOMPI) {
                deposits.transition(payment, DepositStatus.APPROVED, source, now, transactionId = transactionId, providerStatus = "APPROVED")
                deposits.refund(payment.copy(status = DepositStatus.APPROVED), RefundStatus.PENDING, DepositEventSource.SYSTEM, now)
            }
            return@execute
        }
        if (appointment.status in listOf(AppointmentStatus.COMPLETED, AppointmentStatus.NO_SHOW)) return@execute
        if (appointment.status == AppointmentStatus.PENDING_PAYMENT && appointment.holdExpiresAt?.isBefore(now) == true) {
            bookings.setStatus(shop, appointment.id, AppointmentStatus.EXPIRED)
        }
        // Release other stale holds before the exclusion constraint arbitrates a late approval.
        bookings.expireForBarber(shop, appointment.barberId, now)
        deposits.expirePending(shop, now)
        val current = deposits.find(shop, paymentId) ?: BookingFailure.notFound()
        val confirmed = blocks.findOverlapping(shop, appointment.barberId, appointment.startsAt, appointment.endsAt).isEmpty() &&
            tryConfirm(shop, appointment.id)
        deposits.transition(current, if (confirmed) DepositStatus.APPROVED else DepositStatus.LATE_PAYMENT,
            source, now, user, transactionId, if (source == DepositEventSource.WEBHOOK) "APPROVED" else null)
    }

    private fun tryConfirm(shop: UUID, appointmentId: UUID): Boolean = jdbc.execute(ConnectionCallback { connection ->
        val savepoint = connection.setSavepoint()
        try {
            connection.prepareStatement("update appointment set status = 'CONFIRMED', version = version + 1 where barbershop_id = ? and id = ?").use {
                it.setObject(1, shop); it.setObject(2, appointmentId); it.executeUpdate()
            }
            connection.releaseSavepoint(savepoint)
            true
        } catch (failure: SQLException) {
            connection.rollback(savepoint)
            connection.releaseSavepoint(savepoint)
            if (failure.sqlState != "23P01") throw failure
            false
        }
    }) == true
}
