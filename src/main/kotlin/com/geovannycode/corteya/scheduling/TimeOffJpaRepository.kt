package com.geovannycode.corteya.scheduling

import com.geovannycode.corteya.scheduling.TimeOffRepository
import java.time.Instant
import java.util.UUID
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Component

// Deliberately does not extend CrudRepository/JpaRepository: no unscoped operations.
internal interface TimeOffJpaRepository : Repository<TimeOffEntity, UUID> {
    fun findAllByBarbershopIdAndBarberIdAndStartsAtLessThanAndEndsAtGreaterThan(
        barbershopId: UUID, barberId: UUID, endsAt: Instant, startsAt: Instant,
    ): List<TimeOffEntity>

    fun findByBarbershopIdAndId(barbershopId: UUID, id: UUID): TimeOffEntity?
    fun findAllByBarbershopId(barbershopId: UUID): List<TimeOffEntity>
}

@Component
internal class TimeOffPersistenceAdapter(
    private val repository: TimeOffJpaRepository,
) : TimeOffRepository {
    override fun findOverlapping(barbershopId: UUID, barberId: UUID, startsAt: Instant, endsAt: Instant) =
        repository.findAllByBarbershopIdAndBarberIdAndStartsAtLessThanAndEndsAtGreaterThan(
            barbershopId, barberId, endsAt, startsAt,
        ).map { it.toDomain() }

    override fun findById(barbershopId: UUID, id: UUID) =
        repository.findByBarbershopIdAndId(barbershopId, id)?.toDomain()

    override fun findAll(barbershopId: UUID) =
        repository.findAllByBarbershopId(barbershopId).map { it.toDomain() }
}
