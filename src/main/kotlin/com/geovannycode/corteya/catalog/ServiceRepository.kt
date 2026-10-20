package com.geovannycode.corteya.catalog

import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.stereotype.Repository

/** Concrete, tenant-scoped persistence. No unscoped CRUD methods. */
@Repository
class ServiceRepository(private val entityManager: EntityManager) {
    fun findById(barbershopId: UUID, id: UUID): ServiceEntity? = entityManager
        .createQuery("select e from ServiceEntity e where e.barbershopId = :barbershopId and e.id = :id", ServiceEntity::class.java)
        .setParameter("barbershopId", barbershopId).setParameter("id", id).resultList.singleOrNull()

    fun findAll(barbershopId: UUID): List<ServiceEntity> = entityManager
        .createQuery("select e from ServiceEntity e where e.barbershopId = :barbershopId", ServiceEntity::class.java)
        .setParameter("barbershopId", barbershopId).resultList
}
