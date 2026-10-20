package com.geovannycode.corteya.catalog

import java.util.UUID

data class ServiceDetails(val id: UUID = UUID.randomUUID(), val barbershopId: UUID, val name: String, val description: String? = null, val durationMinutes: Int, val priceCop: Long, val active: Boolean = true)

@org.springframework.stereotype.Service
class ServiceCatalogService(private val repository: ServiceRepository) {
    fun findById(barbershopId: UUID, id: UUID): ServiceDetails? = repository.findById(barbershopId, id)?.details()
    fun findAll(barbershopId: UUID): List<ServiceDetails> = repository.findAll(barbershopId).map { it.details() }

    private fun ServiceEntity.details() = ServiceDetails(id, barbershopId, name, description, durationMinutes, priceCop, active)
}
