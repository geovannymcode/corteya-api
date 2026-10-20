package com.geovannycode.corteya.scheduling

import com.geovannycode.corteya.scheduling.WorkingHoursRepository
import java.util.UUID
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Component

// Deliberately does not extend CrudRepository/JpaRepository: no unscoped operations.
internal interface WorkingHoursJpaRepository : Repository<WorkingHoursEntity, UUID> {
    fun findAllByBarbershopIdAndBarberId(barbershopId: UUID, barberId: UUID): List<WorkingHoursEntity>

    fun findByBarbershopIdAndId(barbershopId: UUID, id: UUID): WorkingHoursEntity?
    fun findAllByBarbershopId(barbershopId: UUID): List<WorkingHoursEntity>
}

@Component
internal class WorkingHoursPersistenceAdapter(
    private val repository: WorkingHoursJpaRepository,
) : WorkingHoursRepository {
    override fun findForBarber(barbershopId: UUID, barberId: UUID) =
        repository.findAllByBarbershopIdAndBarberId(barbershopId, barberId).map { it.toDomain() }

    override fun findById(barbershopId: UUID, id: UUID) =
        repository.findByBarbershopIdAndId(barbershopId, id)?.toDomain()

    override fun findAll(barbershopId: UUID) =
        repository.findAllByBarbershopId(barbershopId).map { it.toDomain() }
}
