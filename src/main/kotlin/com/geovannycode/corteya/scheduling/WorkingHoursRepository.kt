package com.geovannycode.corteya.scheduling

import com.geovannycode.corteya.scheduling.WorkingHours
import java.util.UUID

/** Tenant-scoped read port. Mutation use cases belong to subsequent phases. */
interface WorkingHoursRepository {
    fun findForBarber(barbershopId: UUID, barberId: UUID): List<WorkingHours>

    fun findById(barbershopId: UUID, id: UUID): WorkingHours?
    fun findAll(barbershopId: UUID): List<WorkingHours>
}
