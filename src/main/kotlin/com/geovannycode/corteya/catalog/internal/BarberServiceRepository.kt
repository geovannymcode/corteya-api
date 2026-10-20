package com.geovannycode.corteya.catalog.internal

import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.stereotype.Repository

/** Concrete, tenant-scoped persistence. No unscoped CRUD methods. */
@Repository
class BarberServiceRepository(private val entityManager: EntityManager) {
    fun findById(barbershopId: UUID, id: UUID): BarberServiceEntity? = entityManager
        .createQuery("select e from BarberServiceEntity e where e.barbershopId = :barbershopId and e.id = :id", BarberServiceEntity::class.java)
        .setParameter("barbershopId", barbershopId).setParameter("id", id).resultList.singleOrNull()

    fun findAll(barbershopId: UUID): List<BarberServiceEntity> = entityManager
        .createQuery("select e from BarberServiceEntity e where e.barbershopId = :barbershopId", BarberServiceEntity::class.java)
        .setParameter("barbershopId", barbershopId).resultList
}
