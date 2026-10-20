package com.geovannycode.corteya.scheduling

import com.geovannycode.corteya.scheduling.internal.TimeOffRepository
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

/** Occupied interval only; reasons and persistence metadata stay in scheduling. */
data class BlockedTime(val barbershopId: UUID, val barberId: UUID, val startsAt: Instant, val endsAt: Instant)

@Service
class TimeOffService(private val repository: TimeOffRepository) {
    fun findOverlapping(barbershopId: UUID, barberId: UUID, startsAt: Instant, endsAt: Instant): List<BlockedTime> =
        repository.findOverlapping(barbershopId, barberId, startsAt, endsAt).map {
            BlockedTime(it.barbershopId, it.barberId, it.startsAt, it.endsAt)
        }
}
