package com.geovannycode.corteya.barbershop

import com.geovannycode.corteya.barbershop.Barbershop
import java.util.UUID

/** Tenant-scoped read port. Mutation use cases belong to subsequent phases. */
interface BarbershopRepository {
    fun findBySlug(slug: String): Barbershop?
    fun findById(barbershopId: UUID): Barbershop?
}
