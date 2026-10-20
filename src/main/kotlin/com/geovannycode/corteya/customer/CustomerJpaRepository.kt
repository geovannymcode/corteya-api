package com.geovannycode.corteya.customer

import com.geovannycode.corteya.customer.CustomerRepository
import java.util.UUID
import org.springframework.data.repository.Repository
import org.springframework.stereotype.Component

// Deliberately does not extend CrudRepository/JpaRepository: no unscoped operations.
internal interface CustomerJpaRepository : Repository<CustomerEntity, UUID> {
    fun findByBarbershopIdAndId(barbershopId: UUID, id: UUID): CustomerEntity?
    fun findAllByBarbershopId(barbershopId: UUID): List<CustomerEntity>
}

@Component
internal class CustomerPersistenceAdapter(
    private val repository: CustomerJpaRepository,
) : CustomerRepository {
    override fun findById(barbershopId: UUID, id: UUID) =
        repository.findByBarbershopIdAndId(barbershopId, id)?.toDomain()

    override fun findAll(barbershopId: UUID) =
        repository.findAllByBarbershopId(barbershopId).map { it.toDomain() }
}
