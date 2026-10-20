package com.geovannycode.corteya.shared

import jakarta.persistence.EntityManager
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** Context belongs to the database transaction, never to a pooled connection/session. */
@Component
class TenantTransaction(manager: PlatformTransactionManager, private val jdbc: JdbcTemplate,
    private val entityManager: EntityManager) {
    private val transactions = TransactionTemplate(manager)
    private val active = ThreadLocal<UUID>()

    fun <T> execute(barbershopId: UUID, operation: () -> T): T {
        val previous = active.get()
        check(previous == null || previous == barbershopId) { "No se permite cambiar de barbería dentro de una operación" }
        return checkNotNull(transactions.execute {
            if (previous == null) {
                jdbc.queryForObject("select set_config('app.barbershop_id', ?, true)", String::class.java, barbershopId.toString())
            }
            active.set(barbershopId)
            try {
                val result = operation()
                // Flush tenant writes before a global identity transaction visits another shop.
                entityManager.flush()
                if (previous == null) entityManager.clear()
                Result(result)
            } finally {
                if (previous == null) active.remove() else active.set(previous)
            }
        }).value
    }

    private data class Result<T>(val value: T)
}
