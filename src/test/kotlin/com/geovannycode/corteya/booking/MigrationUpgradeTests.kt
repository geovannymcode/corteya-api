package com.geovannycode.corteya.booking

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import com.geovannycode.corteya.TEST_POSTGRES_IMAGE
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationUpgradeTests {
    @Test
    fun `V2 and V3 upgrade an existing V1 database and backfills distinct public tokens without changing appointments`() {
        PostgreSQLContainer(DockerImageName.parse(TEST_POSTGRES_IMAGE)).use { postgres ->
            postgres.start()
            val config = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
            val v1 = config.target("1").load()
            v1.migrate()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { sql ->
                    sql.executeUpdate("""insert into barbershop (id, slug, name, primary_color, secondary_color)
                        values ('10000000-0000-0000-0000-000000000001', 'legacy', 'Legacy', '#112233', '#445566')""")
                    sql.executeUpdate("""insert into barber (id, barbershop_id, name)
                        values ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Legacy')""")
                    sql.executeUpdate("""insert into service (id, barbershop_id, name, duration_minutes, price_cop)
                        values ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Corte', 30, 38000)""")
                    sql.executeUpdate("""insert into customer (id, barbershop_id, name, phone)
                        values ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'Synthetic', '+573001234567')""")
                    for (i in 1..2) sql.executeUpdate("""insert into appointment
                        (id, barbershop_id, barber_id, service_id, customer_id, starts_at, ends_at, status, price_cop, deposit_cop, payment_reference, source)
                        values (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001',
                        '30000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001',
                        '2026-10-19 14:00:00+00', '2026-10-19 14:30:00+00', 'CANCELLED', 38000, 11400, 'R-482$i', 'APP')""")
                }
            }
            val latest = Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration").target("3").load()
            assertEquals(2, latest.migrate().migrationsExecuted)
            latest.validate()
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { sql ->
                    sql.executeQuery("select public_token, status, deposit_cop from appointment").use { rows ->
                        val tokens = mutableSetOf<String>()
                        while (rows.next()) {
                            val token = rows.getString("public_token")
                            assertTrue(token.matches(Regex("[0-9a-f]{32}")))
                            tokens.add(token)
                            assertEquals("CANCELLED", rows.getString("status"))
                            assertEquals(11400L, rows.getLong("deposit_cop"))
                        }
                        assertEquals(2, tokens.size)
                    }
                    sql.executeQuery("select slot_step_minutes, min_lead_minutes, booking_window_days, hold_minutes from barbershop").use { row ->
                        assertTrue(row.next())
                        assertEquals(listOf(15, 30, 14, 15), (1..4).map { row.getInt(it) })
                    }
                }
            }
        }
    }
}
