package com.geovannycode.corteya.booking

import com.geovannycode.corteya.testPostgres
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test

class RlsMigrationUpgradeTests {
    @Test
    fun `V5 protects existing V4 data and backfills identity links for inactive shops`() {
        testPostgres().use { postgres ->
            postgres.start()
            fun migrations(target: String) = Flyway.configure()
                .dataSource(postgres.jdbcUrl, "corteya_owner", "owner-test-only")
                .locations("classpath:db/migration", "classpath:db/rls-upgrade").target(target).load()
            migrations("4.1").migrate()
            val shop = UUID.randomUUID()
            val user = UUID.randomUUID()
            val customer = UUID.randomUUID()
            DriverManager.getConnection(postgres.jdbcUrl, "corteya_app", "app-test-only").use { connection ->
                connection.prepareStatement("insert into barbershop (id, slug, name, primary_color, secondary_color, active) values (?, 'legacy-rls', 'Legacy', '#112233', '#445566', false)").use {
                    it.setObject(1, shop); it.executeUpdate()
                }
                connection.prepareStatement("insert into legacy_tenant_note (id, barbershop_id) values (?, ?)").use {
                    it.setObject(1, UUID.randomUUID()); it.setObject(2, shop); it.executeUpdate()
                }
                connection.prepareStatement("insert into app_user (id, phone) values (?, '+573001234567')").use {
                    it.setObject(1, user); it.executeUpdate()
                }
                connection.prepareStatement("insert into customer (id, barbershop_id, user_id, phone, name) values (?, ?, ?, '+573001234567', 'Legacy')").use {
                    it.setObject(1, customer); it.setObject(2, shop); it.setObject(3, user); it.executeUpdate()
                }
            }
            assertEquals(1, migrations("5").migrate().migrationsExecuted)
            DriverManager.getConnection(postgres.jdbcUrl, "corteya_app", "app-test-only").use { connection ->
                connection.createStatement().use { sql ->
                    sql.executeQuery("select current_user").use { assertTrue(it.next()); assertEquals("corteya_app", it.getString(1)) }
                    sql.executeQuery("select * from customer").use { assertFalse(it.next()) }
                    sql.executeQuery("select * from legacy_tenant_note").use { assertFalse(it.next()) }
                    sql.executeQuery("select relrowsecurity and relforcerowsecurity from pg_class where relname = 'legacy_tenant_note'").use {
                        assertTrue(it.next()); assertTrue(it.getBoolean(1))
                    }
                    sql.executeQuery("select * from customer_identity").use {
                        assertTrue(it.next()); assertEquals(shop, it.getObject("barbershop_id", UUID::class.java))
                        assertEquals(user, it.getObject("user_id", UUID::class.java)); assertEquals(customer, it.getObject("id", UUID::class.java))
                        assertFalse(it.next())
                    }
                }
            }
        }
    }
}
