package com.geovannycode.corteya.catalog

import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.stereotype.Repository

/** Concrete, tenant-scoped persistence. No unscoped CRUD methods. */
@Repository
class BarberRepository(private val entityManager: EntityManager) {
    fun findById(barbershopId: UUID, id: UUID): BarberEntity? = entityManager
        .createQuery("select e from BarberEntity e where e.barbershopId = :barbershopId and e.id = :id", BarberEntity::class.java)
        .setParameter("barbershopId", barbershopId).setParameter("id", id).resultList.singleOrNull()

    fun findAll(barbershopId: UUID): List<BarberEntity> = entityManager
        .createQuery("select e from BarberEntity e where e.barbershopId = :barbershopId", BarberEntity::class.java)
        .setParameter("barbershopId", barbershopId).resultList
}
