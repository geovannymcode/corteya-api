package com.geovannycode.corteya.barbershop

import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.stereotype.Repository

/** Concrete, tenant-scoped persistence. No unscoped CRUD methods. */
@Repository
class BarbershopRepository(private val entityManager: EntityManager) {
    fun findBySlug(slug: String): BarbershopEntity? = entityManager
        .createQuery("select e from BarbershopEntity e where e.slug = :slug and e.active = true", BarbershopEntity::class.java)
        .setParameter("slug", slug).resultList.singleOrNull()

    fun findById(barbershopId: UUID): BarbershopEntity? = entityManager
        .createQuery("select e from BarbershopEntity e where e.id = :barbershopId", BarbershopEntity::class.java)
        .setParameter("barbershopId", barbershopId).resultList.singleOrNull()
}
