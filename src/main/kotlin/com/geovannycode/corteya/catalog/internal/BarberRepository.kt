package com.geovannycode.corteya.catalog.internal

import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.stereotype.Repository

/** Concrete, tenant-scoped persistence. No unscoped CRUD methods. */
@Repository
class BarberRepository(private val entityManager: EntityManager) {
    fun findById(barbershopId: UUID, id: UUID): BarberEntity? = entityManager
        .createQuery("select e from BarberEntity e where e.barbershopId = :barbershopId and e.id = :id", BarberEntity::class.java)
        .setParameter("barbershopId", barbershopId).setParameter("id", id).resultList.singleOrNull()

    fun lock(barbershopId: UUID, id: UUID): Boolean = entityManager
        .createQuery("select e from BarberEntity e where e.barbershopId = :barbershopId and e.id = :id", BarberEntity::class.java)
        .setParameter("barbershopId", barbershopId).setParameter("id", id)
        .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE).resultList.isNotEmpty()

    fun findAll(barbershopId: UUID): List<BarberEntity> = entityManager
        .createQuery("select e from BarberEntity e where e.barbershopId = :barbershopId", BarberEntity::class.java)
        .setParameter("barbershopId", barbershopId).resultList
}
