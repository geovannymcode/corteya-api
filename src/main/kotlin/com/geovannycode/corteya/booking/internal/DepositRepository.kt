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
        jdbc.update("""insert into deposit_payment (id, barbershop_id, appointment_id, method, amount_cop, reference, attempt, status, wompi_webhook_id, wompi_settings, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?::jsonb, ?, ?)""", payment.id, a.barbershopId, a.id, mode.name, payment.amountCop,
            payment.reference, attempt, settings.webhookId, if (mode == DepositMode.WOMPI) mapper.writeValueAsString(settings) else null, Timestamp.from(now), Timestamp.from(now))
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
        event(p, p.status, status, source, providerStatus, now)
    }

    fun expirePending(shop: UUID, now: Instant) {
        val rows = jdbc.query("""select d.* from deposit_payment d join appointment a
            on a.barbershop_id = d.barbershop_id and a.id = d.appointment_id
            where d.barbershop_id = ? and d.status = 'PENDING' and a.status = 'EXPIRED' for update of d""",
            { r, _ -> r.payment() }, shop)
        rows.forEach { transition(it, DepositStatus.EXPIRED, DepositEventSource.SYSTEM, now) }
    }

    private fun event(p: DepositPayment, from: DepositStatus?, to: DepositStatus, source: DepositEventSource,
        providerStatus: String?, now: Instant) {
        jdbc.update("""insert into deposit_event (id, barbershop_id, deposit_payment_id, from_status, to_status, source, provider_status, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), p.barbershopId, p.id, from?.name, to.name, source.name,
            providerStatus, Timestamp.from(now), Timestamp.from(now))
    }

    private fun ResultSet.payment() = DepositPayment(getObject("id", UUID::class.java), getObject("barbershop_id", UUID::class.java),
        getObject("appointment_id", UUID::class.java), DepositMode.valueOf(getString("method")), getLong("amount_cop"),
        getString("reference"), getInt("attempt"), DepositStatus.valueOf(getString("status")), getString("provider_transaction_id"),
        getTimestamp("confirmed_at")?.toInstant())
}
