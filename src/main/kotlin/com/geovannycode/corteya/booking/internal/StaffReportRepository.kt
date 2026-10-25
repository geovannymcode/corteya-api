package com.geovannycode.corteya.booking.internal

import com.geovannycode.corteya.booking.*
import com.geovannycode.corteya.booking.dto.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class StaffReportRepository(private val jdbc: JdbcTemplate) {
    /** Each aggregate reads only its period or the current pending debt, never the full history. */
    fun summary(shop: UUID, barber: UUID?, from: Instant, to: Instant): List<Pair<UUID, StaffMetrics>> =
        org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).query(SUMMARY_SQL,
            mapOf("shop" to shop, "barber" to barber, "from" to Timestamp.from(from), "to" to Timestamp.from(to)),
            { r, _ -> r.getObject("barber_id", UUID::class.java) to metrics(r.getLong("total"), r.getLong("completed"),
                r.getLong("no_shows"), r.getLong("received"), r.getLong("revenue"), r.getLong("refunds"), r.getLong("refund_cop"),
                r.getLong("done"), r.getLong("done_cop")) })

    fun actionItems(shop: UUID, barber: UUID?, now: Instant): List<ActionItem> = jdbc.query(
        """select d.*, a.public_token, a.barber_id, a.starts_at from deposit_payment d
            join appointment a on a.barbershop_id = d.barbershop_id and a.id = d.appointment_id
            where d.barbershop_id = ? and (?::uuid is null or a.barber_id = ?) and (
                (d.method = 'BREB_MANUAL' and d.status = 'PENDING' and a.status = 'PENDING_PAYMENT' and a.hold_expires_at > ?)
                or (d.status = 'LATE_PAYMENT' and d.refund_status <> 'DONE') or d.refund_status = 'PENDING') order by a.starts_at, d.id""",
        { r, _ -> ActionItem(r.getObject("id", UUID::class.java), r.getObject("appointment_id", UUID::class.java),
            r.getString("public_token"), r.getObject("barber_id", UUID::class.java),
            r.getTimestamp("starts_at").toInstant().atZone(ZoneId.of("America/Bogota")).toOffsetDateTime(),
            r.getLong("amount_cop"), r.getString("reference"), DepositStatus.valueOf(r.getString("status")),
            RefundStatus.valueOf(r.getString("refund_status")), DepositMode.valueOf(r.getString("method")),
            r.getString("provider_transaction_id"), r.getTimestamp("confirmed_at")?.toInstant()?.atZone(ZoneId.of("America/Bogota"))?.toOffsetDateTime()) }, shop, barber, barber, Timestamp.from(now))

    companion object {
        internal const val SUMMARY_SQL = """with activity as (
            select a.barber_id, count(*) as total, count(*) filter (where a.status = 'COMPLETED') as completed,
                count(*) filter (where a.status = 'NO_SHOW') as no_shows,
                coalesce(sum(a.price_cop) filter (where a.status = 'COMPLETED'), 0) as revenue
            from appointment a where a.barbershop_id = :shop and a.starts_at >= :from and a.starts_at < :to
                and (cast(:barber as uuid) is null or a.barber_id = :barber) group by a.barber_id
        ), received as (
            select a.barber_id, sum(d.amount_cop) as received from deposit_payment d
            join appointment a on a.barbershop_id = d.barbershop_id and a.id = d.appointment_id
            where d.barbershop_id = :shop and d.confirmed_at >= :from and d.confirmed_at < :to
                and d.status in ('APPROVED','LATE_PAYMENT')
                and (cast(:barber as uuid) is null or a.barber_id = :barber) group by a.barber_id
        ), done_dates as (
            select e.deposit_payment_id, max(e.created_at) as done_at from deposit_event e
            where e.barbershop_id = :shop and e.to_refund_status = 'DONE' and e.created_at >= :from and e.created_at < :to
                -- Preserve the original latest-DONE semantics if an audit contains repeated DONE events.
                and not exists (select 1 from deposit_event later where later.barbershop_id = :shop
                    and later.deposit_payment_id = e.deposit_payment_id and later.to_refund_status = 'DONE' and later.created_at >= :to)
            group by e.deposit_payment_id
        ), done as (
            select a.barber_id, count(*) as done, sum(d.amount_cop) as done_cop from done_dates e
            join deposit_payment d on d.barbershop_id = :shop and d.id = e.deposit_payment_id and d.refund_status = 'DONE'
            join appointment a on a.barbershop_id = d.barbershop_id and a.id = d.appointment_id
            where (cast(:barber as uuid) is null or a.barber_id = :barber) group by a.barber_id
        ), pending as (
            select a.barber_id, count(*) as refunds, sum(d.amount_cop) as refund_cop from deposit_payment d
            join appointment a on a.barbershop_id = d.barbershop_id and a.id = d.appointment_id
            where d.barbershop_id = :shop and d.refund_status = 'PENDING'
                and (cast(:barber as uuid) is null or a.barber_id = :barber) group by a.barber_id
        ), barbers as (
            select barber_id from activity union select barber_id from received
            union select barber_id from done union select barber_id from pending
        ) select b.barber_id, coalesce(a.total,0) as total, coalesce(a.completed,0) as completed, coalesce(a.no_shows,0) as no_shows,
            coalesce(a.revenue,0) as revenue, coalesce(r.received,0) as received, coalesce(p.refunds,0) as refunds,
            coalesce(p.refund_cop,0) as refund_cop, coalesce(d.done,0) as done, coalesce(d.done_cop,0) as done_cop
        from barbers b left join activity a on a.barber_id = b.barber_id left join received r on r.barber_id = b.barber_id
            left join done d on d.barber_id = b.barber_id left join pending p on p.barber_id = b.barber_id order by b.barber_id"""

        fun metrics(total: Long, completed: Long, noShows: Long, received: Long, revenue: Long, refunds: Long, refundCop: Long, done: Long, doneCop: Long): StaffMetrics {
            val attended = completed + noShows
            val rate = if (attended == 0L) BigDecimal.ZERO.setScale(2)
                else BigDecimal.valueOf(noShows).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(attended), 2, RoundingMode.HALF_UP)
            return StaffMetrics(total, completed, noShows, rate, received, revenue, refunds, refundCop, done, doneCop)
        }
    }
}
