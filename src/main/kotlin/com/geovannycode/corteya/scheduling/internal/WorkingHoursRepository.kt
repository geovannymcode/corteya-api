package com.geovannycode.corteya.scheduling.internal

import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.stereotype.Repository

/** Concrete, tenant-scoped persistence. No unscoped CRUD methods. */
@Repository
class WorkingHoursRepository(private val entityManager: EntityManager) {
    fun findById(barbershopId: UUID, id: UUID): WorkingHoursEntity? = entityManager
        .createQuery("select e from WorkingHoursEntity e where e.barbershopId = :barbershopId and e.id = :id", WorkingHoursEntity::class.java)
        .setParameter("barbershopId", barbershopId).setParameter("id", id).resultList.singleOrNull()

    fun findAll(barbershopId: UUID): List<WorkingHoursEntity> = entityManager
        .createQuery("select e from WorkingHoursEntity e where e.barbershopId = :barbershopId", WorkingHoursEntity::class.java)
        .setParameter("barbershopId", barbershopId).resultList

    fun findForBarber(barbershopId: UUID, barberId: UUID): List<WorkingHoursEntity> = entityManager
        .createQuery("select e from WorkingHoursEntity e where e.barbershopId = :barbershopId and e.barberId = :barberId", WorkingHoursEntity::class.java)
        .setParameter("barbershopId", barbershopId).setParameter("barberId", barberId).resultList
}
