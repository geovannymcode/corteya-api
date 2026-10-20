package com.geovannycode.corteya.catalog

import com.geovannycode.corteya.catalog.Barber
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.Generated
import org.hibernate.generator.EventType

@Entity
@Table(name = "barber")
data class BarberEntity(
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @field:Column(name = "barbershop_id", nullable = false, updatable = false)
    val barbershopId: UUID,

    @field:Column(name = "name", nullable = false, length = 150)
    val name: String,

    @field:Column(name = "bio", nullable = true, columnDefinition = "text")
    val bio: String? = null,

    @field:Column(name = "photo_url", nullable = true, length = 2048)
    val photoUrl: String? = null,

    @field:Column(name = "active", nullable = false)
    val active: Boolean = true,

    @field:Generated(event = [EventType.INSERT])
    @field:Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: Instant = Instant.EPOCH,

    @field:Generated(event = [EventType.INSERT, EventType.UPDATE])
    @field:Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    val updatedAt: Instant = Instant.EPOCH,
) {
    fun toDomain(): Barber = Barber(
        id = id,
        barbershopId = barbershopId,
        name = name,
        bio = bio,
        photoUrl = photoUrl,
        active = active,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
