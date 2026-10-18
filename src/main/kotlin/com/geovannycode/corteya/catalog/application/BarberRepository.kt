package com.geovannycode.corteya.catalog.application

import com.geovannycode.corteya.catalog.domain.Barber
import java.util.UUID

/** Tenant-scoped read port. Mutation use cases belong to subsequent phases. */
interface BarberRepository {
    fun findById(barbershopId: UUID, id: UUID): Barber?
    fun findAll(barbershopId: UUID): List<Barber>
}
