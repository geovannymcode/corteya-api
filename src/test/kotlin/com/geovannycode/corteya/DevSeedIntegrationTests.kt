package com.geovannycode.corteya

import java.util.UUID
import kotlin.test.assertEquals
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

class DevSeedIntegrationTests : PostgresIntegrationTest(profile = "dev") {
    override fun customize(builder: org.springframework.boot.builder.SpringApplicationBuilder) = builder.initializers({ context ->
        context.environment.propertySources.addFirst(org.springframework.core.env.MapPropertySource("empty-dev-payment-keys", mapOf(
            "WOMPI_SANDBOX_PUBLIC_KEY" to "", "WOMPI_SANDBOX_PRIVATE_KEY" to "",
            "WOMPI_SANDBOX_INTEGRITY_SECRET" to "", "WOMPI_SANDBOX_EVENTS_SECRET" to "")))
    })

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var flyway: Flyway

    @Test
    fun `dev profile loads a repeatable demo with three barbers and four services`() {
        inShop(UUID.fromString("10000000-0000-0000-0000-000000000001")) {
        assertEquals("BREB_MANUAL", jdbc.queryForObject("select deposit_mode from barbershop_payment_settings", String::class.java))
        assertEquals(30, jdbc.queryForObject("select hold_minutes from barbershop_payment_settings", Int::class.java))
        assertEquals("Calle de ejemplo 123", jdbc.queryForObject("select address from barbershop", String::class.java))
        assertEquals("Bogotá", jdbc.queryForObject("select city from barbershop", String::class.java))
        assertEquals(14, jdbc.queryForObject("select booking_window_days from barbershop", Int::class.java))
        flyway.validate()
        assertEquals(0, flyway.migrate().migrationsExecuted)
        val expected = mapOf("barbershop" to 1, "barber" to 3, "service" to 4,
            "app_user" to 2, "staff_membership" to 2, "barber_service" to 12, "working_hours" to 18, "customer" to 0, "appointment" to 0)
        expected.forEach { (table, count) ->
            assertEquals(count, jdbc.queryForObject("select count(*) from $table", Int::class.java), table)
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from working_hours where day_of_week not between 1 and 6", Int::class.java))
        assertEquals(listOf(18000L, 25000L, 30000L, 40000L), jdbc.queryForList("select price_cop from service order by price_cop", Long::class.java))
        }
    }
}
