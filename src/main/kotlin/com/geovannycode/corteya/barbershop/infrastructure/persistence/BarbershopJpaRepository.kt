package com.geovannycode.corteya.barbershop.infrastructure.persistence

import com.geovannycode.corteya.barbershop.application.BarbershopRepository
import java.util.UUID
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Component

// Deliberately does not extend CrudRepository/JpaRepository: no unscoped operations.
internal interface BarbershopJpaRepository : Repository<BarbershopEntity, UUID> {
    @Query("select b from BarbershopEntity b where b.id = :barbershopId")
    fun findByBarbershopId(barbershopId: UUID): BarbershopEntity?
}

@Component
internal class BarbershopPersistenceAdapter(
    private val repository: BarbershopJpaRepository,
) : BarbershopRepository {
    override fun findById(barbershopId: UUID) = repository.findByBarbershopId(barbershopId)?.toDomain()
}
