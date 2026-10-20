package com.geovannycode.corteya.customer

import com.geovannycode.corteya.shared.TenantTransaction
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/** Ownership, verified account linking and anonymization stay with the customer feature. */
@Service
class CustomerService(private val jdbc: JdbcTemplate, private val tenants: TenantTransaction) {
    fun upsertAndLockCustomer(barbershopId: UUID, phone: String, name: String, userId: UUID, now: Instant): UUID =
        checkNotNull(jdbc.queryForObject(
            """insert into customer (id, barbershop_id, phone, name, user_id, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                on conflict (barbershop_id, phone) do update set name = excluded.name, user_id = excluded.user_id
                where customer.user_id is null or customer.user_id = excluded.user_id
                returning id""",
            UUID::class.java, UUID.randomUUID(), barbershopId, phone, name, userId, Timestamp.from(now), Timestamp.from(now),
        ))

    fun linkVerifiedCustomers(userId: UUID, phone: String, barbershops: List<UUID>) {
        barbershops.forEach { id -> tenants.execute(id) {
            jdbc.update("update customer set user_id = ? where phone = ? and user_id is null", userId, phone)
        } }
    }

    fun ownsCustomer(userId: UUID, barbershopId: UUID, customerId: UUID): Boolean = tenants.execute(barbershopId) { jdbc.queryForObject(
        "select exists(select 1 from customer where user_id = ? and barbershop_id = ? and id = ?)", Boolean::class.java, userId, barbershopId, customerId,
    ) == true }

    fun anonymize(userId: UUID, barbershops: List<UUID>) {
        barbershops.forEach { id -> tenants.execute(id) {
            jdbc.update("update customer set name = 'Cliente eliminado', phone = null where user_id = ?", userId)
        } }
    }
}
