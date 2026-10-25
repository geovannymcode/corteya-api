package com.geovannycode.corteya.scheduling.internal

import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Repository

/** Concrete, tenant-scoped persistence. No unscoped CRUD methods. */
@Repository
class TimeOffRepository(private val entityManager: EntityManager, private val jdbc: org.springframework.jdbc.core.JdbcTemplate) {
    fun blocks(shop: UUID, barber: UUID?, from: Instant, to: Instant) = jdbc.query(
        """select * from time_off where barbershop_id = ? and (?::uuid is null or barber_id = ?)
            and starts_at < ? and ends_at > ? order by starts_at, id""",
        { r, _ -> r.details() }, shop, barber, barber, java.sql.Timestamp.from(to), java.sql.Timestamp.from(from))
    fun get(shop: UUID, id: UUID) = jdbc.query("select * from time_off where barbershop_id = ? and id = ?",
        { r, _ -> r.details() }, shop, id).singleOrNull()
    fun create(shop: UUID, barber: UUID, from: Instant, to: Instant, reason: String): UUID {
        val id = UUID.randomUUID()
        jdbc.update("insert into time_off (id, barbershop_id, barber_id, starts_at, ends_at, reason) values (?, ?, ?, ?, ?, ?)",
            id, shop, barber, java.sql.Timestamp.from(from), java.sql.Timestamp.from(to), reason)
        return id
    }
    fun delete(shop: UUID, id: UUID) { jdbc.update("delete from time_off where barbershop_id = ? and id = ?", shop, id) }
    private fun java.sql.ResultSet.details() = com.geovannycode.corteya.scheduling.TimeOffDetails(
        getObject("id", UUID::class.java), getObject("barber_id", UUID::class.java), getTimestamp("starts_at").toInstant(),
        getTimestamp("ends_at").toInstant(), getString("reason"))

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
