package com.geovannycode.corteya.catalog.infrastructure.persistence

import com.geovannycode.corteya.catalog.domain.Service
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.Generated
import org.hibernate.generator.EventType

@Entity
@Table(name = "service")
data class ServiceEntity(
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @field:Column(name = "barbershop_id", nullable = false, updatable = false)
    val barbershopId: UUID,

    @field:Column(name = "name", nullable = false, length = 150)
    val name: String,

    @field:Column(name = "description", nullable = true, columnDefinition = "text")
    val description: String? = null,

    @field:Column(name = "duration_minutes", nullable = false)
    val durationMinutes: Int,

    @field:Column(name = "price_cop", nullable = false)
    val priceCop: Long,

    @field:Column(name = "active", nullable = false)
    val active: Boolean = true,

    @field:Generated(event = [EventType.INSERT])
    @field:Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @field:Generated(event = [EventType.INSERT, EventType.UPDATE])
    @field:Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    val updatedAt: Instant = Instant.now(),
) {
    fun toDomain(): Service = Service(
        id = id,
        barbershopId = barbershopId,
        name = name,
        description = description,
        durationMinutes = durationMinutes,
        priceCop = priceCop,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
