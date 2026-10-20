package com.geovannycode.corteya.catalog.infrastructure.persistence

import com.geovannycode.corteya.catalog.application.BarberRepository
import java.util.UUID
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Component

// Deliberately does not extend CrudRepository/JpaRepository: no unscoped operations.
internal interface BarberJpaRepository : Repository<BarberEntity, UUID> {
    fun findByBarbershopIdAndId(barbershopId: UUID, id: UUID): BarberEntity?
    fun findAllByBarbershopId(barbershopId: UUID): List<BarberEntity>
}

@Component
internal class BarberPersistenceAdapter(
    private val repository: BarberJpaRepository,
) : BarberRepository {
    override fun findById(barbershopId: UUID, id: UUID) =
        repository.findByBarbershopIdAndId(barbershopId, id)?.toDomain()

    override fun findAll(barbershopId: UUID) =
        repository.findAllByBarbershopId(barbershopId).map { it.toDomain() }
}
