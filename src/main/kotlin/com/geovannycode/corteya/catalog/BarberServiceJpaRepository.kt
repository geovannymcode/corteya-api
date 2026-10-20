package com.geovannycode.corteya.catalog

import com.geovannycode.corteya.catalog.BarberServiceRepository
import java.util.UUID
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Component

// Deliberately does not extend CrudRepository/JpaRepository: no unscoped operations.
internal interface BarberServiceJpaRepository : Repository<BarberServiceEntity, UUID> {
    fun findByBarbershopIdAndId(barbershopId: UUID, id: UUID): BarberServiceEntity?
    fun findAllByBarbershopId(barbershopId: UUID): List<BarberServiceEntity>
}

@Component
internal class BarberServicePersistenceAdapter(
    private val repository: BarberServiceJpaRepository,
) : BarberServiceRepository {
    override fun findById(barbershopId: UUID, id: UUID) =
        repository.findByBarbershopIdAndId(barbershopId, id)?.toDomain()

    override fun findAll(barbershopId: UUID) =
        repository.findAllByBarbershopId(barbershopId).map { it.toDomain() }
}
