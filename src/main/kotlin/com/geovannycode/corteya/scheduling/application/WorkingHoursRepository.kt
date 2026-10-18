package com.geovannycode.corteya.scheduling.application

import com.geovannycode.corteya.scheduling.domain.WorkingHours
import java.util.UUID

/** Tenant-scoped read port. Mutation use cases belong to subsequent phases. */
interface WorkingHoursRepository {
    fun findById(barbershopId: UUID, id: UUID): WorkingHours?
    fun findAll(barbershopId: UUID): List<WorkingHours>
}
