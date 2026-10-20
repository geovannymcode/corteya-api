package com.geovannycode.corteya.catalog

import com.geovannycode.corteya.catalog.BarberService
import java.util.UUID

/** Tenant-scoped read port. Mutation use cases belong to subsequent phases. */
interface BarberServiceRepository {
    fun findById(barbershopId: UUID, id: UUID): BarberService?
    fun findAll(barbershopId: UUID): List<BarberService>
}
