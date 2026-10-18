package com.geovannycode.corteya.catalog.application

import com.geovannycode.corteya.catalog.domain.Service
import java.util.UUID

/** Tenant-scoped read port. Mutation use cases belong to subsequent phases. */
interface ServiceRepository {
    fun findById(barbershopId: UUID, id: UUID): Service?
    fun findAll(barbershopId: UUID): List<Service>
}
