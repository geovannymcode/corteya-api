package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.AppointmentRepository
import java.util.UUID
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Component

// Deliberately does not extend CrudRepository/JpaRepository: no unscoped operations.
internal interface AppointmentJpaRepository : Repository<AppointmentEntity, UUID> {
    fun findByBarbershopIdAndId(barbershopId: UUID, id: UUID): AppointmentEntity?
    fun findAllByBarbershopId(barbershopId: UUID): List<AppointmentEntity>
}

@Component
internal class AppointmentPersistenceAdapter(
    private val repository: AppointmentJpaRepository,
) : AppointmentRepository {
    override fun findById(barbershopId: UUID, id: UUID) =
        repository.findByBarbershopIdAndId(barbershopId, id)?.toDomain()

    override fun findAll(barbershopId: UUID) =
        repository.findAllByBarbershopId(barbershopId).map { it.toDomain() }
}
