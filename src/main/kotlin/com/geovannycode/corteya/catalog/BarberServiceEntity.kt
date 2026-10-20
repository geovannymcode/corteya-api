package com.geovannycode.corteya.catalog

import com.geovannycode.corteya.catalog.BarberService
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.Generated
import org.hibernate.generator.EventType

@Entity
@Table(name = "barber_service")
data class BarberServiceEntity(
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @field:Column(name = "barbershop_id", nullable = false, updatable = false)
    val barbershopId: UUID,

    @field:Column(name = "barber_id", nullable = false)
    val barberId: UUID,

    @field:Column(name = "service_id", nullable = false)
    val serviceId: UUID,

    @field:Generated(event = [EventType.INSERT])
    @field:Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: Instant = Instant.EPOCH,

    @field:Generated(event = [EventType.INSERT, EventType.UPDATE])
    @field:Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    val updatedAt: Instant = Instant.EPOCH,
) {
    fun toDomain(): BarberService = BarberService(
        id = id,
        barbershopId = barbershopId,
        barberId = barberId,
        serviceId = serviceId,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
