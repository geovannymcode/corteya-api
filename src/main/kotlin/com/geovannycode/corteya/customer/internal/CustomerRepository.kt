package com.geovannycode.corteya.customer.internal

import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.stereotype.Repository

/** Concrete, tenant-scoped persistence. No unscoped CRUD methods. */
@Repository
class CustomerRepository(private val entityManager: EntityManager) {
    fun findById(barbershopId: UUID, id: UUID): CustomerEntity? = entityManager
        .createQuery("select e from CustomerEntity e where e.barbershopId = :barbershopId and e.id = :id", CustomerEntity::class.java)
        .setParameter("barbershopId", barbershopId).setParameter("id", id).resultList.singleOrNull()

    fun findAll(barbershopId: UUID): List<CustomerEntity> = entityManager
        .createQuery("select e from CustomerEntity e where e.barbershopId = :barbershopId", CustomerEntity::class.java)
        .setParameter("barbershopId", barbershopId).resultList
}
