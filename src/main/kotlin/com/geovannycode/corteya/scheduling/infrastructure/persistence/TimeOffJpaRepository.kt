package com.geovannycode.corteya.scheduling.infrastructure.persistence

import com.geovannycode.corteya.scheduling.application.TimeOffRepository
import java.util.UUID
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Component

// Deliberately does not extend CrudRepository/JpaRepository: no unscoped operations.
internal interface TimeOffJpaRepository : Repository<TimeOffEntity, UUID> {
    fun findByBarbershopIdAndId(barbershopId: UUID, id: UUID): TimeOffEntity?
    fun findAllByBarbershopId(barbershopId: UUID): List<TimeOffEntity>
}

@Component
internal class TimeOffPersistenceAdapter(
    private val repository: TimeOffJpaRepository,
) : TimeOffRepository {
    override fun findById(barbershopId: UUID, id: UUID) =
        repository.findByBarbershopIdAndId(barbershopId, id)?.toDomain()

    override fun findAll(barbershopId: UUID) =
        repository.findAllByBarbershopId(barbershopId).map { it.toDomain() }
}
