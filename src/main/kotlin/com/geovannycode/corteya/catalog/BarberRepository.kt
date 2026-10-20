package com.geovannycode.corteya.catalog

import com.geovannycode.corteya.catalog.Barber
import java.util.UUID

/** Tenant-scoped read port. Mutation use cases belong to subsequent phases. */
interface BarberRepository {
    fun findById(barbershopId: UUID, id: UUID): Barber?
    fun findAll(barbershopId: UUID): List<Barber>
}
