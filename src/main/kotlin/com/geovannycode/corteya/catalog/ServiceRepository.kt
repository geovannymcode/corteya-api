package com.geovannycode.corteya.catalog

import com.geovannycode.corteya.catalog.Service
import java.util.UUID

/** Tenant-scoped read port. Mutation use cases belong to subsequent phases. */
interface ServiceRepository {
    fun findById(barbershopId: UUID, id: UUID): Service?
    fun findAll(barbershopId: UUID): List<Service>
}
