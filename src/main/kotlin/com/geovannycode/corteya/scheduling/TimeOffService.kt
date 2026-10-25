package com.geovannycode.corteya.scheduling

import com.geovannycode.corteya.scheduling.internal.TimeOffRepository
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

/** Occupied interval only; reasons and persistence metadata stay in scheduling. */
data class BlockedTime(val barbershopId: UUID, val barberId: UUID, val startsAt: Instant, val endsAt: Instant)

data class TimeOffDetails(val id: UUID, val barberId: UUID, val startsAt: Instant, val endsAt: Instant, val reason: String?)

@Service
class TimeOffService(private val repository: TimeOffRepository) {
    fun blocks(shop: UUID, barber: UUID?, from: Instant, to: Instant): List<TimeOffDetails> = repository.blocks(shop, barber, from, to)
    fun get(shop: UUID, id: UUID): TimeOffDetails? = repository.get(shop, id)
    fun create(shop: UUID, barber: UUID, from: Instant, to: Instant, reason: String): UUID = repository.create(shop, barber, from, to, reason)
    fun delete(shop: UUID, id: UUID) = repository.delete(shop, id)

    fun findOverlapping(barbershopId: UUID, barberId: UUID, startsAt: Instant, endsAt: Instant): List<BlockedTime> =
        repository.findOverlapping(barbershopId, barberId, startsAt, endsAt).map {
            BlockedTime(it.barbershopId, it.barberId, it.startsAt, it.endsAt)
        }
}
