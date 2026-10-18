package com.geovannycode.corteya.customer.application

import com.geovannycode.corteya.customer.domain.Customer
import java.util.UUID

/** Tenant-scoped read port. Mutation use cases belong to subsequent phases. */
interface CustomerRepository {
    fun findById(barbershopId: UUID, id: UUID): Customer?
    fun findAll(barbershopId: UUID): List<Customer>
}
