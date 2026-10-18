package com.geovannycode.corteya.catalog.application

import com.geovannycode.corteya.catalog.domain.BarberService
import java.util.UUID

/** Tenant-scoped read port. Mutation use cases belong to subsequent phases. */
interface BarberServiceRepository {
    fun findById(barbershopId: UUID, id: UUID): BarberService?
    fun findAll(barbershopId: UUID): List<BarberService>
}
