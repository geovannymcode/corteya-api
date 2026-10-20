package com.geovannycode.corteya.catalog

import com.geovannycode.corteya.catalog.ServiceRepository
import java.util.UUID
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Component

// Deliberately does not extend CrudRepository/JpaRepository: no unscoped operations.
internal interface ServiceJpaRepository : Repository<ServiceEntity, UUID> {
    fun findByBarbershopIdAndId(barbershopId: UUID, id: UUID): ServiceEntity?
    fun findAllByBarbershopId(barbershopId: UUID): List<ServiceEntity>
}

@Component
internal class ServicePersistenceAdapter(
    private val repository: ServiceJpaRepository,
) : ServiceRepository {
    override fun findById(barbershopId: UUID, id: UUID) =
        repository.findByBarbershopIdAndId(barbershopId, id)?.toDomain()

    override fun findAll(barbershopId: UUID) =
        repository.findAllByBarbershopId(barbershopId).map { it.toDomain() }
}
