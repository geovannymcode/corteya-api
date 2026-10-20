package com.geovannycode.corteya.scheduling

import com.geovannycode.corteya.scheduling.TimeOff
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID
import org.hibernate.annotations.Generated
import org.hibernate.generator.EventType

@Entity
@Table(name = "time_off")
data class TimeOffEntity(
    @field:Id
    @field:Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @field:Column(name = "barbershop_id", nullable = false, updatable = false)
    val barbershopId: UUID,

    @field:Column(name = "barber_id", nullable = false)
    val barberId: UUID,

    @field:Column(name = "starts_at", nullable = false)
    val startsAt: Instant,

    @field:Column(name = "ends_at", nullable = false)
    val endsAt: Instant,

    @field:Column(name = "reason", nullable = true, length = 255)
    val reason: String? = null,

    @field:Generated(event = [EventType.INSERT])
    @field:Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: Instant = Instant.EPOCH,

    @field:Generated(event = [EventType.INSERT, EventType.UPDATE])
    @field:Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    val updatedAt: Instant = Instant.EPOCH,
) {
    fun toDomain(): TimeOff = TimeOff(
        id = id,
        barbershopId = barbershopId,
        barberId = barberId,
        startsAt = startsAt,
        endsAt = endsAt,
        reason = reason,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
