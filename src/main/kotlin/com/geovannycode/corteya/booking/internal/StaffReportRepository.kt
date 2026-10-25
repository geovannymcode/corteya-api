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
    /** Independent money and appointment aggregates avoid retry multiplication and use the relevant event date. */
    fun summary(shop: UUID, barber: UUID?, from: Instant, to: Instant): List<Pair<UUID, StaffMetrics>> = jdbc.query(
        """with scoped as (
            select id, barber_id, status, price_cop, starts_at from appointment
            where barbershop_id = ? and (?::uuid is null or barber_id = ?)
        ), activity as (
            select barber_id, count(*) as total, count(*) filter (where status = 'COMPLETED') as completed,
                count(*) filter (where status = 'NO_SHOW') as no_shows,
                coalesce(sum(price_cop) filter (where status = 'COMPLETED'), 0) as revenue
            from scoped where starts_at >= ? and starts_at < ? group by barber_id
        ), done_dates as (
            select deposit_payment_id, max(created_at) as done_at from deposit_event
            where barbershop_id = ? and to_refund_status = 'DONE' group by deposit_payment_id
        ), money as (
            select a.barber_id,
                coalesce(sum(d.amount_cop) filter (where d.status in ('APPROVED','LATE_PAYMENT') and d.confirmed_at >= ? and d.confirmed_at < ?), 0) as received,
                count(*) filter (where d.refund_status = 'PENDING') as refunds,
                coalesce(sum(d.amount_cop) filter (where d.refund_status = 'PENDING'), 0) as refund_cop,
                count(*) filter (where d.refund_status = 'DONE' and e.done_at >= ? and e.done_at < ?) as done,
                coalesce(sum(d.amount_cop) filter (where d.refund_status = 'DONE' and e.done_at >= ? and e.done_at < ?), 0) as done_cop
            from deposit_payment d join scoped a on a.id = d.appointment_id
            left join done_dates e on e.deposit_payment_id = d.id where d.barbershop_id = ? group by a.barber_id
        ), barbers as (select distinct barber_id from scoped)
        select b.barber_id, coalesce(a.total,0) as total, coalesce(a.completed,0) as completed, coalesce(a.no_shows,0) as no_shows,
            coalesce(a.revenue,0) as revenue, coalesce(m.received,0) as received, coalesce(m.refunds,0) as refunds,
            coalesce(m.refund_cop,0) as refund_cop, coalesce(m.done,0) as done, coalesce(m.done_cop,0) as done_cop
        from barbers b left join activity a on a.barber_id = b.barber_id left join money m on m.barber_id = b.barber_id order by b.barber_id""",
        { r, _ -> r.getObject("barber_id", UUID::class.java) to metrics(r.getLong("total"), r.getLong("completed"),
            r.getLong("no_shows"), r.getLong("received"), r.getLong("revenue"), r.getLong("refunds"), r.getLong("refund_cop"),
            r.getLong("done"), r.getLong("done_cop")) },
        shop, barber, barber, Timestamp.from(from), Timestamp.from(to), shop, Timestamp.from(from), Timestamp.from(to),
        Timestamp.from(from), Timestamp.from(to), Timestamp.from(from), Timestamp.from(to), shop)

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
        fun metrics(total: Long, completed: Long, noShows: Long, received: Long, revenue: Long, refunds: Long, refundCop: Long, done: Long, doneCop: Long): StaffMetrics {
            val attended = completed + noShows
            val rate = if (attended == 0L) BigDecimal.ZERO.setScale(2)
                else BigDecimal.valueOf(noShows).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(attended), 2, RoundingMode.HALF_UP)
            return StaffMetrics(total, completed, noShows, rate, received, revenue, refunds, refundCop, done, doneCop)
        }
    }
}
