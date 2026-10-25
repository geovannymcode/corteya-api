package com.geovannycode.corteya.catalog

import com.geovannycode.corteya.catalog.internal.BarberEntity
import com.geovannycode.corteya.catalog.internal.BarberRepository
import java.util.UUID

data class BarberDetails(val id: UUID = UUID.randomUUID(), val barbershopId: UUID, val name: String, val bio: String? = null, val photoUrl: String? = null, val active: Boolean = true)

@org.springframework.stereotype.Service
class BarberDirectoryService(private val repository: BarberRepository) {
    fun findById(barbershopId: UUID, id: UUID): BarberDetails? = repository.findById(barbershopId, id)?.details()
    /** All schedule writers acquire this lock before touching appointments or blocks. */
    fun lockSchedule(barbershopId: UUID, barberId: UUID): Boolean = repository.lock(barbershopId, barberId)
    fun findAll(barbershopId: UUID): List<BarberDetails> = repository.findAll(barbershopId).map { it.details() }

    private fun BarberEntity.details() = BarberDetails(id, barbershopId, name, bio, photoUrl, active)
}
