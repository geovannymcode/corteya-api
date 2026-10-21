package com.geovannycode.corteya

import com.geovannycode.corteya.booking.FixedBookingTestConfiguration
import com.geovannycode.corteya.booking.MutableTestClock
import com.geovannycode.corteya.booking.internal.HoldExpirationJob
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate

class RowLevelSecurityIntegrationTests : PostgresIntegrationTest() {
    @Autowired private lateinit var flyway: org.flywaydb.core.Flyway
    @Autowired private lateinit var transactions: org.springframework.transaction.support.TransactionTemplate
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var clock: MutableTestClock
    @Autowired private lateinit var bookings: com.geovannycode.corteya.booking.internal.AppointmentRepository
    @Autowired private lateinit var job: HoldExpirationJob
    private val shopA = UUID.randomUUID()
    private val shopB = UUID.randomUUID()
    private val barberA = UUID.randomUUID()
    private val barberB = UUID.randomUUID()
    private val serviceA = UUID.randomUUID()
    private val serviceB = UUID.randomUUID()
    private val customerA = UUID.randomUUID()
    private val customerB = UUID.randomUUID()
    private val now = Instant.parse("2026-10-19T13:00:00Z")

    override fun customize(builder: SpringApplicationBuilder): SpringApplicationBuilder =
        builder.sources(FixedBookingTestConfiguration::class.java)
            .properties(mapOf("spring.datasource.hikari.maximum-pool-size" to 1, "spring.datasource.hikari.minimum-idle" to 0))

    @BeforeEach
    fun fixtures() {
        resetData(listOf(shopA, shopB))
        clock.set(now)
        listOf(listOf(shopA, barberA, serviceA, customerA), listOf(shopB, barberB, serviceB, customerB)).forEach { (shop, barber, service, customer) ->
            inShop(shop) {
                jdbc.update("insert into barbershop (id, slug, name, primary_color, secondary_color) values (?, ?, 'RLS', '#112233', '#445566')", shop, shop.toString())
                jdbc.update("insert into barber (id, barbershop_id, name) values (?, ?, 'Barbero')", barber, shop)
                jdbc.update("insert into service (id, barbershop_id, name, duration_minutes, price_cop) values (?, ?, 'Corte', 30, 38000)", service, shop)
                jdbc.update("insert into customer (id, barbershop_id, name, phone) values (?, ?, 'Cliente', '+573001234567')", customer, shop)
                insertAppointment(shop, barber, service, customer)
            }
        }
    }

    @Test
    fun `native query without tenant context returns zero rows and writes fail closed`() {
        assertEquals("corteya_app", jdbc.queryForObject("select current_user", String::class.java))
        assertTrue(jdbc.queryForList("SELECT * FROM appointment").isEmpty())
        assertEquals(0, jdbc.update("update appointment set status = 'CANCELLED'"))
        assertEquals(0, jdbc.update("delete from appointment"))
        assertRlsViolation { insertAppointment(shopA, barberA, serviceA, customerA) }
    }

    @Test
    fun `native queries in A cannot read update delete or insert B`() {
        inShop(shopA) {
            val rows = jdbc.queryForList("SELECT * FROM appointment")
            assertEquals(1, rows.size)
            assertEquals(shopA, rows.single()["barbershop_id"])
            assertEquals(0, jdbc.update("update appointment set status = 'CANCELLED' where barbershop_id = ?", shopB))
            assertEquals(0, jdbc.update("delete from appointment where barbershop_id = ?", shopB))
        }
        assertRlsViolation { inShop(shopA) { insertAppointment(shopB, barberB, serviceB, customerB) } }
        inShop(shopB) { assertEquals(1, jdbc.queryForList("SELECT * FROM appointment").size) }
    }

    @Test
    fun `booking queries require both the explicit shop filter and RLS context`() {
        val user = UUID.randomUUID()
        jdbc.update("insert into app_user (id, phone) values (?, '+573001234567')", user)
        inShop(shopA) {
            jdbc.update("update customer set user_id = ?", user)
            val token = checkNotNull(jdbc.queryForObject("select public_token from appointment", String::class.java))
            assertEquals(1, bookings.findByUser(shopA, user).size)
            assertNotNull(bookings.findByPublicToken(shopA, token))
            assertTrue(bookings.findByUser(shopB, user).isEmpty())
            assertNull(bookings.findByPublicToken(shopB, token))
        }
    }

    @Test
    fun `app is neither owner superuser nor bypass role and cannot become owner`() {
        val roles = jdbc.queryForMap("select rolsuper, rolbypassrls, rolcreaterole, rolcreatedb from pg_roles where rolname = current_user")
        assertTrue(roles.values.all { it == false }, roles.toString())
        assertEquals(0, jdbc.queryForObject("select count(*) from pg_tables where schemaname = 'public' and tableowner <> 'corteya_owner'", Int::class.java))
        assertFalse(jdbc.queryForObject("select has_schema_privilege(current_user, 'public', 'CREATE')", Boolean::class.java) ?: true)
        assertFalse(jdbc.queryForObject("select has_table_privilege(current_user, 'appointment', 'TRUNCATE')", Boolean::class.java) ?: true)
        assertFailsWith<DataAccessException> { jdbc.execute("SET ROLE corteya_owner") }
        // Negative startup check uses only role metadata through Flyway's owner connection.
        assertFailsWith<IllegalStateException> {
            com.geovannycode.corteya.shared.internal.RuntimeDatabaseRoleValidator(
                JdbcTemplate(flyway.configuration.dataSource)).afterSingletonsInstantiated()
        }
        val flags = jdbc.queryForList("""select relname, relrowsecurity, relforcerowsecurity from pg_class
            where relnamespace = 'public'::regnamespace and relkind in ('r','p')
              and (relname = 'barbershop' or exists(select 1 from pg_attribute a
                  where a.attrelid = pg_class.oid and a.attname = 'barbershop_id' and not a.attisdropped))
              and relname not in ('staff_membership','customer_identity')""")
        assertTrue(flags.size >= 8)
        assertTrue(flags.all { it["relrowsecurity"] == true && it["relforcerowsecurity"] == true })
    }

    @Test
    fun `identity tables remain global and future table grants are configured for owner`() {
        assertEquals(0, jdbc.queryForObject("""select count(*) from pg_class where relnamespace = 'public'::regnamespace
            and relname in ('app_user','otp_challenge','refresh_token','staff_membership','customer_identity') and relrowsecurity""", Int::class.java))
        val privileges = jdbc.queryForList("""select x.privilege_type from pg_default_acl d
            cross join lateral aclexplode(d.defaclacl) x
            where d.defaclrole = 'corteya_owner'::regrole and d.defaclnamespace = 'public'::regnamespace
              and d.defaclobjtype = 'r' and x.grantee = 'corteya_app'::regrole""", String::class.java).toSet()
        assertEquals(setOf("SELECT", "INSERT", "UPDATE", "DELETE"), privileges)
        assertEquals(listOf("USAGE"), jdbc.queryForList("""select x.privilege_type from pg_default_acl d
            cross join lateral aclexplode(d.defaclacl) x where d.defaclrole = 'corteya_owner'::regrole
            and d.defaclnamespace = 'public'::regnamespace and d.defaclobjtype = 'S'
            and x.grantee = 'corteya_app'::regrole""", String::class.java))
        assertFalse(jdbc.queryForObject("select prosecdef from pg_proc where proname = 'sync_customer_identity'", Boolean::class.java) ?: true)
    }

    @Test
    fun `same pooled connection loses context after commit and rollback`() {
        val pid = inShop(shopA) {
            assertEquals(1, jdbc.queryForList("SELECT * FROM appointment").size)
            jdbc.queryForObject("select pg_backend_pid()", Int::class.java)
        }
        assertEquals(pid, jdbc.queryForObject("select pg_backend_pid()", Int::class.java))
        assertTrue(jdbc.queryForList("SELECT * FROM appointment").isEmpty())
        assertFailsWith<IllegalStateException> { inShop(shopB) {
            assertEquals(shopB, jdbc.queryForList("SELECT * FROM appointment").single()["barbershop_id"])
            error("Simulated rollback")
        } }
        assertEquals(pid, jdbc.queryForObject("select pg_backend_pid()", Int::class.java))
        assertTrue(jdbc.queryForList("SELECT * FROM appointment").isEmpty())
        assertFailsWith<IllegalStateException> { inShop(shopA) { inShop(shopB) { error("must not reach here") } } }
        assertTrue(jdbc.queryForList("SELECT * FROM appointment").isEmpty())
    }

    @Test
    fun `public shop discovery sees active shops and mutations require its own context`() {
        inShop(shopB) { jdbc.update("update barbershop set active = false where id = ?", shopB) }
        assertEquals(listOf(shopA), jdbc.queryForList("select id from barbershop", UUID::class.java))
        assertEquals(0, jdbc.update("update barbershop set name = 'No context'"))
        assertEquals(0, inShop(shopA) { jdbc.update("update barbershop set name = 'Wrong tenant' where id = ?", shopB) })
        inShop(shopB) { assertEquals(1, jdbc.update("update barbershop set active = true where id = ?", shopB)) }
    }

    @Test
    fun `expiration visits every active shop and never expires inactive shops or confirmed appointments`() {
        job.expire()
        listOf(shopA, shopB).forEach { id -> inShop(id) {
            assertEquals("EXPIRED", jdbc.queryForObject("select status from appointment", String::class.java))
            jdbc.update("update appointment set status = 'PENDING_PAYMENT'")
        } }
        inShop(shopA) { jdbc.update("update appointment set status = 'CONFIRMED'") }
        inShop(shopB) { jdbc.update("update barbershop set active = false where id = ?", shopB) }
        job.expire()
        assertEquals("CONFIRMED", inShop(shopA) { jdbc.queryForObject("select status from appointment", String::class.java) })
        assertEquals("PENDING_PAYMENT", inShop(shopB) { jdbc.queryForObject("select status from appointment", String::class.java) })
    }

    @Test
    fun `tenant visits participate atomically in an outer identity transaction`() {
        assertFailsWith<IllegalStateException> { transactions.executeWithoutResult {
            inShop(shopA) { jdbc.update("update customer set name = 'Changed'") }
            inShop(shopB) { jdbc.update("update customer set name = 'Changed'") }
            error("Rollback all visited shops")
        } }
        listOf(shopA, shopB).forEach { id -> inShop(id) {
            assertEquals("Cliente", jdbc.queryForObject("select name from customer", String::class.java))
        } }
        assertTrue(jdbc.queryForList("SELECT * FROM appointment").isEmpty())
    }

    private fun insertAppointment(shop: UUID, barber: UUID, service: UUID, customer: UUID) {
        jdbc.update("""insert into appointment (id, barbershop_id, barber_id, service_id, customer_id, starts_at, ends_at,
            price_cop, deposit_cop, payment_reference, hold_expires_at, source, status)
            values (?, ?, ?, ?, ?, '2026-10-19 14:00:00+00', '2026-10-19 14:30:00+00',
            38000, 12000, ?, '2026-10-19 12:59:00+00', 'APP', 'PENDING_PAYMENT')""", UUID.randomUUID(), shop, barber, service, customer,
            "R-" + UUID.randomUUID().toString().take(8))
    }

    private fun assertRlsViolation(operation: () -> Unit) {
        val failure = assertFailsWith<DataAccessException>(block = operation)
        val sql = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().last()
        assertEquals("42501", sql.sqlState)
        assertTrue(sql.message.orEmpty().contains("row-level security"), sql.message)
    }
}
