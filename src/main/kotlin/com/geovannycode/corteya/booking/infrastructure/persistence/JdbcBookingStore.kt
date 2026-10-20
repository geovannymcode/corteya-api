package com.geovannycode.corteya.booking.infrastructure.persistence

import com.geovannycode.corteya.booking.application.BookingStore
import com.geovannycode.corteya.booking.domain.Appointment
import com.geovannycode.corteya.booking.domain.AppointmentSource
import com.geovannycode.corteya.booking.domain.AppointmentStatus
import com.geovannycode.corteya.scheduling.domain.OccupyingAppointment
import com.geovannycode.corteya.scheduling.domain.TimeInterval
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcBookingStore(private val jdbc: JdbcTemplate) : BookingStore {
    override fun occupying(barbershopId: UUID, barberId: UUID, startsAt: Instant, endsAt: Instant) = jdbc.query(
        """select starts_at, ends_at, status, hold_expires_at from appointment
            where barbershop_id = ? and barber_id = ? and starts_at < ? and ends_at > ?
              and status in ('PENDING_PAYMENT', 'CONFIRMED')""",
        { row, _ -> OccupyingAppointment(TimeInterval(row.getTimestamp("starts_at").toInstant(), row.getTimestamp("ends_at").toInstant()),
            AppointmentStatus.valueOf(row.getString("status")), row.getTimestamp("hold_expires_at")?.toInstant()) },
        barbershopId, barberId, Timestamp.from(endsAt), Timestamp.from(startsAt),
    )

    override fun upsertAndLockCustomer(barbershopId: UUID, phone: String, name: String, userId: UUID, now: Instant): UUID =
        checkNotNull(jdbc.queryForObject(
            """insert into customer (id, barbershop_id, phone, name, user_id, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (barbershop_id, phone) do update set name = excluded.name, user_id = excluded.user_id
                where customer.user_id is null or customer.user_id = excluded.user_id
                returning id""",
            UUID::class.java, UUID.randomUUID(), barbershopId, phone, name, userId, Timestamp.from(now), Timestamp.from(now),
        ))

    override fun pendingCount(barbershopId: UUID, customerId: UUID): Int = checkNotNull(jdbc.queryForObject(
        """select count(*) from appointment where barbershop_id = ? and customer_id = ?
            and status = 'PENDING_PAYMENT'""",
        Int::class.java, barbershopId, customerId,
    ))

    override fun insert(appointment: Appointment): Boolean = appointment.let { a ->
        jdbc.query(
            """insert into appointment (id, barbershop_id, barber_id, service_id, customer_id,
                starts_at, ends_at, status, price_cop, deposit_cop, payment_reference,
                public_token, hold_expires_at, source, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                on conflict on constraint uq_appointment_shop_reference do nothing returning id""",
            { row, _ -> row.getObject("id", UUID::class.java) },
            a.id, a.barbershopId, a.barberId, a.serviceId, a.customerId,
            Timestamp.from(a.startsAt), Timestamp.from(a.endsAt), a.status.name,
            a.priceCop, a.depositCop, a.paymentReference, a.publicToken,
            a.holdExpiresAt?.let(Timestamp::from), a.source.name, Timestamp.from(a.createdAt), Timestamp.from(a.updatedAt),
        ).isNotEmpty()
    }

    override fun expireForBarber(barbershopId: UUID, barberId: UUID, now: Instant): Int = jdbc.update(
        """update appointment set status = 'EXPIRED', version = version + 1
            where barbershop_id = ? and barber_id = ? and status = 'PENDING_PAYMENT' and hold_expires_at < ?""",
        barbershopId, barberId, Timestamp.from(now),
    )

    override fun expireAll(now: Instant): Int = jdbc.update(
        """update appointment set status = 'EXPIRED', version = version + 1
            where status = 'PENDING_PAYMENT' and hold_expires_at < ?""", Timestamp.from(now),
    )

    override fun findByUser(userId: UUID): List<Appointment> = jdbc.query(
        """select a.* from appointment a join customer c on c.barbershop_id = a.barbershop_id and c.id = a.customer_id
            where c.user_id = ? order by a.starts_at, a.id""", { row, _ -> row.toAppointment() }, userId,
    )

    override fun agenda(barbershopId: UUID, barberId: UUID?, startsAt: Instant, endsAt: Instant): List<Appointment> = jdbc.query(
        """select * from appointment where barbershop_id = ? and starts_at < ? and ends_at > ?
            and (?::uuid is null or barber_id = ?) order by starts_at, id""",
        { row, _ -> row.toAppointment() }, barbershopId, Timestamp.from(endsAt), Timestamp.from(startsAt), barberId, barberId,
    )

    override fun findByPublicToken(publicToken: String): Appointment? = jdbc.query(
        "select * from appointment where public_token = ?", { row, _ -> row.toAppointment() }, publicToken,
    ).singleOrNull()

    private fun ResultSet.toAppointment() = Appointment(
        id = getObject("id", UUID::class.java), barbershopId = getObject("barbershop_id", UUID::class.java),
        barberId = getObject("barber_id", UUID::class.java), serviceId = getObject("service_id", UUID::class.java),
        customerId = getObject("customer_id", UUID::class.java), startsAt = getTimestamp("starts_at").toInstant(),
        endsAt = getTimestamp("ends_at").toInstant(), status = AppointmentStatus.valueOf(getString("status")),
        priceCop = getLong("price_cop"), depositCop = getLong("deposit_cop"), paymentReference = getString("payment_reference"),
        publicToken = getString("public_token"), holdExpiresAt = getTimestamp("hold_expires_at")?.toInstant(),
        source = AppointmentSource.valueOf(getString("source")), version = getLong("version"),
        createdAt = getTimestamp("created_at").toInstant(), updatedAt = getTimestamp("updated_at").toInstant(),
    )
}
