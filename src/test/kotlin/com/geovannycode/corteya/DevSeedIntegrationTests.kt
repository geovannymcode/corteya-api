package com.geovannycode.corteya

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals

class DevSeedIntegrationTests : PostgresIntegrationTest(profile = "dev") {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var flyway: Flyway

    @Test
    fun `dev profile loads a repeatable demo with three barbers and four services`() {
        flyway.validate()
        assertEquals(0, flyway.migrate().migrationsExecuted)
        val expected = mapOf("barbershop" to 1, "barber" to 3, "service" to 4,
            "barber_service" to 12, "working_hours" to 18, "customer" to 0, "appointment" to 0)
        expected.forEach { (table, count) ->
            assertEquals(count, jdbc.queryForObject("select count(*) from $table", Int::class.java), table)
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from working_hours where day_of_week not between 1 and 6", Int::class.java))
        assertEquals(listOf(18000L, 25000L, 30000L, 40000L), jdbc.queryForList("select price_cop from service order by price_cop", Long::class.java))
    }
}
