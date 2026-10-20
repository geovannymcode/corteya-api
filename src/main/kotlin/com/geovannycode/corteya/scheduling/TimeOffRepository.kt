package com.geovannycode.corteya.scheduling

import jakarta.persistence.EntityManager
import java.util.UUID
import java.time.Instant
import org.springframework.stereotype.Repository

/** Concrete, tenant-scoped persistence. No unscoped CRUD methods. */
@Repository
class TimeOffRepository(private val entityManager: EntityManager) {
    fun findById(barbershopId: UUID, id: UUID): TimeOffEntity? = entityManager
        .createQuery("select e from TimeOffEntity e where e.barbershopId = :barbershopId and e.id = :id", TimeOffEntity::class.java)
        .setParameter("barbershopId", barbershopId).setParameter("id", id).resultList.singleOrNull()

    fun findAll(barbershopId: UUID): List<TimeOffEntity> = entityManager
        .createQuery("select e from TimeOffEntity e where e.barbershopId = :barbershopId", TimeOffEntity::class.java)
        .setParameter("barbershopId", barbershopId).resultList

    fun findOverlapping(barbershopId: UUID, barberId: UUID, startsAt: Instant, endsAt: Instant): List<TimeOffEntity> = entityManager
        .createQuery("""select e from TimeOffEntity e where e.barbershopId = :barbershopId and e.barberId = :barberId
            and e.startsAt < :endsAt and e.endsAt > :startsAt""", TimeOffEntity::class.java)
        .setParameter("barbershopId", barbershopId).setParameter("barberId", barberId)
        .setParameter("startsAt", startsAt).setParameter("endsAt", endsAt).resultList
}
