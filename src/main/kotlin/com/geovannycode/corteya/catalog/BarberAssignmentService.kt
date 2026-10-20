package com.geovannycode.corteya.catalog

import com.geovannycode.corteya.catalog.internal.BarberServiceEntity
import com.geovannycode.corteya.catalog.internal.BarberServiceRepository
import java.util.UUID

data class BarberAssignment(val barbershopId: UUID, val barberId: UUID, val serviceId: UUID)

@org.springframework.stereotype.Service
class BarberAssignmentService(private val repository: BarberServiceRepository) {
    fun findById(barbershopId: UUID, id: UUID): BarberAssignment? = repository.findById(barbershopId, id)?.details()
    fun findAll(barbershopId: UUID): List<BarberAssignment> = repository.findAll(barbershopId).map { it.details() }

    private fun BarberServiceEntity.details() = BarberAssignment(barbershopId, barberId, serviceId)
}
