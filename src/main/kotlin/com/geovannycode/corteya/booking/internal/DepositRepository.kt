package com.geovannycode.corteya.booking.internal

import com.geovannycode.corteya.booking.*
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class DepositRepository(private val jdbc: JdbcTemplate, private val mapper: tools.jackson.databind.json.JsonMapper) {
    fun create(a: AppointmentEntity, settings: PaymentSettings, now: Instant, attempt: Int = 1): DepositPayment {
        val mode = settings.mode
        val payment = DepositPayment(UUID.randomUUID(), a.barbershopId, a.id, mode, a.depositCop,
            if (mode == DepositMode.WOMPI) "${a.paymentReference}-$attempt" else a.paymentReference, attempt, DepositStatus.PENDING)
        jdbc.update("""insert into deposit_payment (id, barbershop_id, appointment_id, method, amount_cop, reference, attempt, status, wompi_webhook_id, wompi_settings, breb_settings, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?::jsonb, ?::jsonb, ?, ?)""", payment.id, a.barbershopId, a.id, mode.name, payment.amountCop,
            payment.reference, attempt, settings.webhookId, if (mode == DepositMode.WOMPI) mapper.writeValueAsString(settings) else null,
            if (mode == DepositMode.BREB_MANUAL) mapper.writeValueAsString(PaymentSettings(a.barbershopId, mode,
                brebKeyType = settings.brebKeyType, brebKey = settings.brebKey, brebHolderName = settings.brebHolderName)) else null,
            Timestamp.from(now), Timestamp.from(now))
        event(payment, null, DepositStatus.PENDING, DepositEventSource.SYSTEM, null, now)
        return payment
    }

    fun configurationByWebhook(shop: UUID, webhookId: String): PaymentSettings? = jdbc.query(
        """select wompi_settings::text from deposit_payment where barbershop_id = ? and wompi_webhook_id = ?
            and method = 'WOMPI' order by created_at desc, id limit 1""",
        { r, _ -> mapper.readValue(r.getString(1), PaymentSettings::class.java) }, shop, webhookId).singleOrNull()

    fun latest(shop: UUID, appointment: UUID): DepositPayment? = jdbc.query(
        "select * from deposit_payment where barbershop_id = ? and appointment_id = ? order by attempt desc limit 1",
        { r, _ -> r.payment() }, shop, appointment).singleOrNull()

    fun readForAppointments(shop: UUID, appointments: List<UUID>): List<DepositPayment> {
        if (appointments.isEmpty()) return emptyList()
        val placeholders = appointments.joinToString(",") { "?" }
        return jdbc.query("select * from deposit_payment where barbershop_id = ? and appointment_id in ($placeholders) order by attempt",
            { r, _ -> r.payment() }, *listOf(shop, *appointments.toTypedArray()).toTypedArray())
    }

    fun brebSettings(shop: UUID, payment: UUID): PaymentSettings? = jdbc.query(
        "select breb_settings::text from deposit_payment where barbershop_id = ? and id = ?",
        { r, _ -> r.getString(1)?.let { mapper.readValue(it, PaymentSettings::class.java) } }, shop, payment).singleOrNull()

    fun forAppointment(shop: UUID, appointment: UUID): List<DepositPayment> = jdbc.query(
        "select * from deposit_payment where barbershop_id = ? and appointment_id = ? order by attempt for update",
        { r, _ -> r.payment() }, shop, appointment)

    fun byReference(shop: UUID, reference: String): DepositPayment? = jdbc.query(
        "select * from deposit_payment where barbershop_id = ? and reference = ?", { r, _ -> r.payment() }, shop, reference).singleOrNull()

    fun find(shop: UUID, id: UUID): DepositPayment? = jdbc.query(
        "select * from deposit_payment where barbershop_id = ? and id = ? for update", { r, _ -> r.payment() }, shop, id).singleOrNull()

    fun list(shop: UUID, barber: UUID?, status: DepositStatus): List<DepositPayment> = jdbc.query(
        """select d.* from deposit_payment d join appointment a on a.barbershop_id = d.barbershop_id and a.id = d.appointment_id
            where d.barbershop_id = ? and d.status = ? and (?::uuid is null or a.barber_id = ?) order by d.created_at, d.id""",
        { r, _ -> r.payment() }, shop, status.name, barber, barber)

    fun transition(p: DepositPayment, status: DepositStatus, source: DepositEventSource, now: Instant,
        user: UUID? = null, transactionId: String? = null, providerStatus: String? = null) {
        if (p.status == status) return
        jdbc.update("""update deposit_payment set status = ?, provider_transaction_id = coalesce(?, provider_transaction_id),
            confirmed_by_user_id = coalesce(?, confirmed_by_user_id), confirmed_at = coalesce(?, confirmed_at)
            where barbershop_id = ? and id = ?""", status.name, transactionId, user,
            if (status in listOf(DepositStatus.APPROVED, DepositStatus.LATE_PAYMENT)) Timestamp.from(now) else null, p.barbershopId, p.id)
        event(p, p.status, status, source, providerStatus, now, user = user)
    }

    fun refund(payment: DepositPayment, status: RefundStatus, source: DepositEventSource, now: Instant, user: UUID? = null) {
        if (payment.refundStatus == status) return
        jdbc.update("""update deposit_payment set refund_status = ?, confirmed_by_user_id = coalesce(?, confirmed_by_user_id)
            where barbershop_id = ? and id = ?""", status.name, user, payment.barbershopId, payment.id)
        event(payment, payment.status, payment.status, source, null, now,
            user = user, fromRefund = payment.refundStatus, toRefund = status)
    }

    fun discrepancy(payment: DepositPayment, reason: DepositDiscrepancyReason, now: Instant) {
        event(payment, payment.status, payment.status, DepositEventSource.WEBHOOK, null, now, reason)
    }

    fun expirePending(shop: UUID, now: Instant) {
        val rows = jdbc.query("""select d.* from deposit_payment d join appointment a
            on a.barbershop_id = d.barbershop_id and a.id = d.appointment_id
            where d.barbershop_id = ? and d.status = 'PENDING' and a.status = 'EXPIRED' for update of d""",
            { r, _ -> r.payment() }, shop)
        rows.forEach { transition(it, DepositStatus.EXPIRED, DepositEventSource.SYSTEM, now) }
    }

    private fun event(p: DepositPayment, from: DepositStatus?, to: DepositStatus, source: DepositEventSource,
        providerStatus: String?, now: Instant, reason: DepositDiscrepancyReason? = null, user: UUID? = null,
        fromRefund: RefundStatus? = null, toRefund: RefundStatus? = null) {
        jdbc.update("""insert into deposit_event (id, barbershop_id, deposit_payment_id, from_status, to_status, source, provider_status, created_at, updated_at, reason, confirmed_by_user_id, from_refund_status, to_refund_status)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), p.barbershopId, p.id, from?.name, to.name, source.name,
            providerStatus, Timestamp.from(now), Timestamp.from(now), reason?.name, user, fromRefund?.name, toRefund?.name)
    }

    private fun ResultSet.payment() = DepositPayment(getObject("id", UUID::class.java), getObject("barbershop_id", UUID::class.java),
        getObject("appointment_id", UUID::class.java), DepositMode.valueOf(getString("method")), getLong("amount_cop"),
        getString("reference"), getInt("attempt"), DepositStatus.valueOf(getString("status")), getString("provider_transaction_id"),
        getTimestamp("confirmed_at")?.toInstant(), RefundStatus.valueOf(getString("refund_status")))
}
