package com.geovannycode.corteya

import com.geovannycode.corteya.barbershop.internal.BarbershopEntity
import com.geovannycode.corteya.barbershop.internal.BarbershopRepository
import com.geovannycode.corteya.booking.AppointmentStatus
import com.geovannycode.corteya.booking.internal.AppointmentEntity
import com.geovannycode.corteya.booking.internal.AppointmentRepository
import com.geovannycode.corteya.catalog.internal.BarberEntity
import com.geovannycode.corteya.catalog.internal.BarberRepository
import com.geovannycode.corteya.catalog.internal.BarberServiceEntity
import com.geovannycode.corteya.catalog.internal.BarberServiceRepository
import com.geovannycode.corteya.catalog.internal.ServiceEntity
import com.geovannycode.corteya.catalog.internal.ServiceRepository
import com.geovannycode.corteya.customer.internal.CustomerEntity
import com.geovannycode.corteya.customer.internal.CustomerRepository
import com.geovannycode.corteya.scheduling.internal.TimeOffEntity
import com.geovannycode.corteya.scheduling.internal.TimeOffRepository
import com.geovannycode.corteya.scheduling.internal.WorkingHoursEntity
import com.geovannycode.corteya.scheduling.internal.WorkingHoursRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.OptimisticLockException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

class BackendIntegrationTests : PostgresIntegrationTest(web = true) {
    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var flyway: Flyway

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var entityManager: EntityManager

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var shops: BarbershopRepository

    @Autowired
    private lateinit var barbers: BarberRepository

    @Autowired
    private lateinit var services: ServiceRepository

    @Autowired
    private lateinit var barberServices: BarberServiceRepository

    @Autowired
    private lateinit var hours: WorkingHoursRepository

    @Autowired
    private lateinit var timeOffs: TimeOffRepository

    @Autowired
    private lateinit var customers: CustomerRepository

    @Autowired
    private lateinit var appointments: AppointmentRepository

    private val port: Int get() = serverPort

    private val shopId = UUID.randomUUID()
    private val otherShopId = UUID.randomUUID()
    private val barberId = UUID.randomUUID()
    private val secondBarberId = UUID.randomUUID()
    private val serviceId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()
    private val start = Instant.parse("2026-10-19T14:00:00Z")

    @BeforeEach
    fun fixtures() {
        resetData(listOf(shopId, otherShopId))
        inShop(otherShopId) { entityManager.persist(BarbershopEntity(id = otherShopId, slug = "other-shop", name = "Other")) }
        inShop(shopId) {
            entityManager.persist(BarbershopEntity(id = shopId, slug = "test-shop", name = "Test"))
            entityManager.persist(BarberEntity(id = barberId, barbershopId = shopId, name = "Barber"))
            entityManager.persist(BarberEntity(id = secondBarberId, barbershopId = shopId, name = "Second"))
            entityManager.persist(ServiceEntity(id = serviceId, barbershopId = shopId, name = "Corte", durationMinutes = 30, priceCop = 25000))
            entityManager.persist(CustomerEntity(id = customerId, barbershopId = shopId, phone = "+573001234567", name = "Synthetic"))
        }
    }

    @Test
    fun `migrations run on real PostgreSQL with no dev data by default`() {
        flyway.validate()
        assertEquals("7", flyway.info().current()?.version?.version)
        assertEquals(0, flyway.info().pending().size)
        assertEquals(0, jdbc.queryForObject("select count(*) from flyway_schema_history where script = 'R__demo_data.sql'", Int::class.java))
        assertEquals("UTC", jdbc.queryForObject("show timezone", String::class.java))
        assertTrue(jdbc.queryForObject("select exists(select 1 from pg_extension where extname = 'btree_gist')", Boolean::class.java) ?: false)
    }

    @ParameterizedTest
    @EnumSource(AppointmentStatus::class, names = ["PENDING_PAYMENT", "CONFIRMED"])
    fun `overlapping active appointments for one barber fail`(status: AppointmentStatus) {
        insertAppointment(status = status)
        assertConstraint("23P01", "ex_appointment_barber_overlap") {
            insertAppointment(startsAt = start.plusSeconds(900))
        }
    }

    @Test
    fun `overlap for different barbers is allowed`() {
        insertAppointment()
        insertAppointment(barber = secondBarberId)
        assertEquals(2, inShop(shopId) { appointments.findAll(shopId) }.size)
    }

    @ParameterizedTest
    @EnumSource(AppointmentStatus::class, names = ["CANCELLED", "EXPIRED", "COMPLETED", "NO_SHOW"])
    fun `inactive appointments do not block the slot`(status: AppointmentStatus) {
        insertAppointment(status = status)
        insertAppointment()
        assertEquals(2, inShop(shopId) { appointments.findAll(shopId) }.size)
    }

    @Test
    fun `cancelling or expiring an existing hold releases the slot`() {
        for (status in listOf("CANCELLED", "EXPIRED")) {
            val id = insertAppointment()
            inShop(shopId) { jdbc.update("update appointment set status = ? where barbershop_id = ? and id = ?", status, shopId, id) }
            val replacement = insertAppointment()
            inShop(shopId) { jdbc.update("delete from appointment where barbershop_id = ? and id = ?", shopId, replacement) }
        }
    }

    @Test
    fun `adjacent half open intervals are allowed`() {
        insertAppointment()
        insertAppointment(startsAt = start.plusSeconds(1800))
        assertEquals(2, inShop(shopId) { appointments.findAll(shopId) }.size)
    }

    @Test
    fun `invalid appointment intervals fail`() {
        assertConstraint("23514", "ck_appointment_interval") { insertAppointment(endsAt = start) }
    }

    @Test
    fun `Colombia nine am survives UTC persistence and JPA round trip`() {
        val bogota = ZoneId.of("America/Bogota")
        val local = LocalDateTime.of(2026, 10, 19, 9, 0)
        val instant = local.atZone(bogota).toInstant()
        val id = UUID.randomUUID()
        inShop(shopId) {
            entityManager.persist(AppointmentEntity(
                id = id, barbershopId = shopId, barberId = barberId, serviceId = serviceId,
                customerId = customerId, startsAt = instant, endsAt = instant.plusSeconds(1800),
                priceCop = 25000, depositCop = 7500, paymentReference = "R-4821",
                holdExpiresAt = instant.minusSeconds(600),
            ))
        }
        val stored = assertNotNull(inShop(shopId) { appointments.findById(shopId, id) })
        assertEquals(Instant.parse("2026-10-19T14:00:00Z"), stored.startsAt)
        assertEquals(local, stored.startsAt.atZone(bogota).toLocalDateTime())
        assertEquals("2026-10-19 14:00:00", inShop(shopId) { jdbc.queryForObject(
            "select to_char(starts_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS') from appointment where barbershop_id = ? and id = ?",
            String::class.java, shopId, id,
        ) })
        assertEquals(0L, stored.version)
        assertEquals(7500L, stored.depositCop)
    }

    @Test
    fun `all repositories scope reads and all entity mappings round trip`() {
        val linkId = UUID.randomUUID()
        val hoursId = UUID.randomUUID()
        val offId = UUID.randomUUID()
        inShop(shopId) {
            entityManager.persist(BarberServiceEntity(id = linkId, barbershopId = shopId, barberId = barberId, serviceId = serviceId))
            entityManager.persist(WorkingHoursEntity(id = hoursId, barbershopId = shopId, barberId = barberId, dayOfWeek = 1,
                startTime = LocalTime.of(9, 0), endTime = LocalTime.of(18, 0)))
            entityManager.persist(TimeOffEntity(id = offId, barbershopId = shopId, barberId = barberId,
                startsAt = start, endsAt = start.plusSeconds(1800), reason = "Almuerzo"))
        }
        val appointmentId = insertAppointment()
        assertNotNull(inShop(shopId) { shops.findById(shopId) })
        assertNull(inShop(shopId) { shops.findById(UUID.randomUUID()) })
        assertNotNull(inShop(shopId) { barbers.findById(shopId, barberId) })
        assertNotNull(inShop(shopId) { services.findById(shopId, serviceId) })
        assertNotNull(inShop(shopId) { customers.findById(shopId, customerId) })
        assertNotNull(inShop(shopId) { barberServices.findById(shopId, linkId) })
        assertEquals(LocalTime.of(9, 0), inShop(shopId) { hours.findById(shopId, hoursId) }?.startTime)
        assertEquals("09:00:00", inShop(shopId) { jdbc.queryForObject(
            "select start_time::text from working_hours where barbershop_id = ? and id = ?", String::class.java, shopId, hoursId,
        ) })
        assertEquals("Almuerzo", inShop(shopId) { timeOffs.findById(shopId, offId) }?.reason)
        assertNotNull(inShop(shopId) { appointments.findById(shopId, appointmentId) })
        assertNull(inShop(otherShopId) { barbers.findById(otherShopId, barberId) })
        assertNull(inShop(otherShopId) { services.findById(otherShopId, serviceId) })
        assertNull(inShop(otherShopId) { customers.findById(otherShopId, customerId) })
        assertNull(inShop(otherShopId) { barberServices.findById(otherShopId, linkId) })
        assertNull(inShop(otherShopId) { hours.findById(otherShopId, hoursId) })
        assertNull(inShop(otherShopId) { timeOffs.findById(otherShopId, offId) })
        assertNull(inShop(otherShopId) { appointments.findById(otherShopId, appointmentId) })
        listOf(inShop(otherShopId) { barbers.findAll(otherShopId) }, inShop(otherShopId) { services.findAll(otherShopId) }, inShop(otherShopId) { customers.findAll(otherShopId) },
            inShop(otherShopId) { barberServices.findAll(otherShopId) }, inShop(otherShopId) { hours.findAll(otherShopId) }, inShop(otherShopId) { timeOffs.findAll(otherShopId) },
            inShop(otherShopId) { appointments.findAll(otherShopId) }).forEach { assertTrue(it.isEmpty()) }
    }

    @Test
    fun `database rejects cross shop appointment references`() {
        val foreignBarber = UUID.randomUUID()
        val foreignService = UUID.randomUUID()
        val foreignCustomer = UUID.randomUUID()
        inShop(otherShopId) { jdbc.update("insert into barber (id, barbershop_id, name) values (?, ?, 'Other')", foreignBarber, otherShopId) }
        inShop(otherShopId) { jdbc.update("insert into service (id, barbershop_id, name, duration_minutes, price_cop) values (?, ?, 'Other', 30, 25000)", foreignService, otherShopId) }
        inShop(otherShopId) { jdbc.update("insert into customer (id, barbershop_id, phone, name) values (?, ?, '+573001234567', 'Other')", foreignCustomer, otherShopId) }
        assertConstraint("23503", "fk_appointment_barber") { insertAppointment(barber = foreignBarber) }
        assertConstraint("23503", "fk_appointment_service") { insertAppointment(service = foreignService) }
        assertConstraint("23503", "fk_appointment_customer") { insertAppointment(customer = foreignCustomer) }
    }

    @Test
    fun `phone and payment references enforce uniqueness and E164`() {
        assertConstraint("23505", "uq_customer_phone") {
            inShop(shopId) { jdbc.update("insert into customer (id, barbershop_id, phone, name) values (?, ?, '+573001234567', 'Duplicate')", UUID.randomUUID(), shopId) }
        }
        assertConstraint("23514", "ck_customer_phone") {
            inShop(shopId) { jdbc.update("insert into customer (id, barbershop_id, phone, name) values (?, ?, '3001234567', 'Invalid')", UUID.randomUUID(), shopId) }
        }
        insertAppointment(reference = "R-4821")
        assertConstraint("23505", "uq_appointment_shop_reference") {
            insertAppointment(barber = secondBarberId, reference = "R-4821")
        }
    }

    @Test
    fun `tenant ownership is immutable and audit timestamps update`() {
        val before = assertNotNull(inShop(shopId) { barbers.findById(shopId, barberId) })
        inShop(shopId) { jdbc.update("update barber set name = 'Updated' where barbershop_id = ? and id = ?", shopId, barberId) }
        val after = assertNotNull(inShop(shopId) { barbers.findById(shopId, barberId) })
        assertEquals(before.createdAt, after.createdAt)
        assertTrue(after.updatedAt > before.updatedAt)
        assertConstraint("23514", "barbershop_id is immutable") {
            inShop(shopId) { jdbc.update("update barber set barbershop_id = ? where barbershop_id = ? and id = ?", otherShopId, shopId, barberId) }
        }
    }

    @Test
    fun `appointment version rejects stale JPA writes`() {
        val id = insertAppointment()
        val stale = inShop(shopId) {
            assertNotNull(entityManager.createQuery(
                "select a from AppointmentEntity a where a.barbershopId = :shop and a.id = :id", AppointmentEntity::class.java,
            ).setParameter("shop", shopId).setParameter("id", id).singleResult)
        }
        inShop(shopId) {
            entityManager.merge(stale.copy(status = AppointmentStatus.CONFIRMED))
            entityManager.flush()
        }
        assertEquals(1L, inShop(shopId) { appointments.findById(shopId, id) }?.version)
        assertFailsWith<OptimisticLockException> {
            inShop(shopId) {
                entityManager.merge(stale.copy(status = AppointmentStatus.CANCELLED))
                entityManager.flush()
            }
        }
    }

    @Test
    fun `concurrent overlapping inserts allow exactly one winner`() {
        val gate = CountDownLatch(1)
        Executors.newFixedThreadPool(2).use { executor ->
            val attempts = (1..2).map {
                executor.submit(Callable {
                    gate.await(10, TimeUnit.SECONDS)
                    try {
                        inShop(shopId) {
                            val connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource)
                            connection.prepareStatement(INSERT_APPOINTMENT).use { statement ->
                                bindAppointment(statement, UUID.randomUUID(), barberId, serviceId, customerId, start,
                                    start.plusSeconds(1800), AppointmentStatus.PENDING_PAYMENT, newReference())
                                statement.executeUpdate()
                            }
                        }
                        "OK"
                    } catch (exception: Exception) {
                        generateSequence<Throwable>(exception) { it.cause }.filterIsInstance<SQLException>().last().sqlState
                    }
                })
            }
            gate.countDown()
            val outcomes = attempts.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, outcomes.count { it == "OK" })
            // PostgreSQL may abort a simultaneous GiST insert with a deadlock instead of 23P01.
            assertTrue(outcomes.single { it != "OK" } in listOf("23P01", "40P01"))
        }
    }

    @Test
    fun `health is the only exposed actuator endpoint`() {
        HttpClient.newHttpClient().use { client ->
            fun get(path: String) = client.send(HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString())
            val health = get("/actuator/health")
            assertEquals(200, health.statusCode())
            assertEquals("{\"status\":\"UP\"}", health.body())
            listOf("/actuator", "/actuator/info", "/actuator/env", "/actuator/metrics", "/appointments", "/api/appointments", "/").forEach {
                assertEquals(if (it.startsWith("/api/")) 401 else 404, get(it).statusCode(), it)
            }
        }
    }

    private fun insertAppointment(
        barber: UUID = barberId,
        service: UUID = serviceId,
        customer: UUID = customerId,
        startsAt: Instant = start,
        endsAt: Instant = startsAt.plusSeconds(1800),
        status: AppointmentStatus = AppointmentStatus.PENDING_PAYMENT,
        reference: String = newReference(),
    ): UUID {
        val id = UUID.randomUUID()
        inShop(shopId) {
        jdbc.update { connection ->
            connection.prepareStatement(INSERT_APPOINTMENT).also {
                bindAppointment(it, id, barber, service, customer, startsAt, endsAt, status, reference)
            }
        }
        }
        return id
    }

    private fun bindAppointment(statement: java.sql.PreparedStatement, id: UUID, barber: UUID, service: UUID,
        customer: UUID, startsAt: Instant, endsAt: Instant, status: AppointmentStatus, reference: String) {
        statement.setObject(1, id)
        statement.setObject(2, shopId)
        statement.setObject(3, barber)
        statement.setObject(4, service)
        statement.setObject(5, customer)
        statement.setTimestamp(6, Timestamp.from(startsAt))
        statement.setTimestamp(7, Timestamp.from(endsAt))
        statement.setString(8, status.name)
        statement.setString(9, reference)
    }

    private fun assertConstraint(state: String, constraint: String, action: () -> Unit) {
        val failure = assertFailsWith<DataIntegrityViolationException>(block = action)
        val sql = generateSequence<Throwable>(failure) { it.cause }.filterIsInstance<SQLException>().last()
        assertEquals(state, sql.sqlState)
        assertTrue(sql.message.orEmpty().contains(constraint), sql.message)
    }

    private fun newReference() = "R-" + UUID.randomUUID().toString().replace("-", "").take(12)

    companion object {
        private const val INSERT_APPOINTMENT = """
            insert into appointment (id, barbershop_id, barber_id, service_id, customer_id,
                starts_at, ends_at, status, price_cop, deposit_cop, payment_reference, source)
            values (?, ?, ?, ?, ?, ?, ?, ?, 25000, 7500, ?, 'APP')
        """
    }
}
