package com.geovannycode.corteya.scheduling

import com.geovannycode.corteya.scheduling.TimeOff
import java.time.Instant
import java.util.UUID

/** Tenant-scoped read port. Mutation use cases belong to subsequent phases. */
interface TimeOffRepository {
    fun findOverlapping(barbershopId: UUID, barberId: UUID, startsAt: Instant, endsAt: Instant): List<TimeOff>

    fun findById(barbershopId: UUID, id: UUID): TimeOff?
    fun findAll(barbershopId: UUID): List<TimeOff>
}
