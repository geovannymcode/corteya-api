package com.geovannycode.corteya.customer.infrastructure.persistence

import com.geovannycode.corteya.customer.domain.Customer
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.Generated
import org.hibernate.generator.EventType

@Entity
@Table(name = "customer")
data class CustomerEntity(
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @field:Column(name = "barbershop_id", nullable = false, updatable = false)
    val barbershopId: UUID,

    @field:Column(name = "phone", nullable = false, length = 16)
    val phone: String,

    @field:Column(name = "name", nullable = false, length = 150)
    val name: String,

    @field:Generated(event = [EventType.INSERT])
    @field:Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @field:Generated(event = [EventType.INSERT, EventType.UPDATE])
    @field:Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    val updatedAt: Instant = Instant.now(),
) {
    fun toDomain(): Customer = Customer(
        id = id,
        barbershopId = barbershopId,
        phone = phone,
        name = name,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
