package com.geovannycode.corteya.booking

import com.geovannycode.corteya.PostgresIntegrationTest
import com.geovannycode.corteya.booking.dto.PaymentSettingsRequest
import com.geovannycode.corteya.identity.AppUser
import com.geovannycode.corteya.identity.internal.AccessTokens
import com.geovannycode.corteya.identity.internal.IdentityRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class StaffModeIntegrationTests : PostgresIntegrationTest(web = true) {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mapper: JsonMapper
    @Autowired private lateinit var identities: IdentityRepository
    @Autowired private lateinit var tokens: AccessTokens
    @Autowired private lateinit var settings: PaymentSettingsService
    @Autowired private lateinit var clock: MutableTestClock
    @Autowired private lateinit var events: CancellationEventRecorder
    private val client = HttpClient.newHttpClient()
    private val shop = UUID.randomUUID()
    private val otherShop = UUID.randomUUID()
    private val barber = UUID.randomUUID()
    private val secondBarber = UUID.randomUUID()
    private val otherBarber = UUID.randomUUID()
    private val service = UUID.randomUUID()
    private val otherService = UUID.randomUUID()
    private lateinit var customer: AppUser
    private lateinit var owner: AppUser
    private lateinit var staff: AppUser
    private lateinit var secondStaff: AppUser
    private val now = Instant.parse("2026-10-19T13:00:00Z")
    override fun customize(builder: SpringApplicationBuilder) = builder.sources(FixedBookingTestConfiguration::class.java, CancellationEventTestConfiguration::class.java)
    @BeforeEach fun fixtures() {
        resetData(listOf(shop, otherShop))
        clock.set(now)
        events.events.clear(); events.completed.clear(); events.noShows.clear()
        customer = user("3001000000"); owner = user("3001000001"); staff = user("3001000002"); secondStaff = user("3001000003")
        listOf(shop to "staff-a", otherShop to "staff-b").forEach { (id, slug) -> inShop(id) {
            jdbc.update("insert into barbershop (id, slug, name, primary_color, secondary_color) values (?, ?, 'Demo staff', '#112233', '#445566')", id, slug)
            settings.configure(id, PaymentSettingsRequest(DepositMode.BREB_MANUAL, BrebKeyType.ALIAS, "@STAFFTEST", "Titular ficticio"))
        } }
        listOf(barber to shop, secondBarber to shop, otherBarber to otherShop).forEach { (id, tenant) -> inShop(tenant) {
            jdbc.update("insert into barber (id, barbershop_id, name) values (?, ?, ?)", id, tenant, "Barbero $id")
            jdbc.update("insert into working_hours (id, barbershop_id, barber_id, day_of_week, start_time, end_time) values (?, ?, ?, 1, '09:00', '20:00')", UUID.randomUUID(), tenant, id)
        } }
        listOf(service to shop, otherService to otherShop).forEach { (id, tenant) -> inShop(tenant) {
            jdbc.update("insert into service (id, barbershop_id, name, duration_minutes, price_cop) values (?, ?, 'Corte staff', 30, 38000)", id, tenant)
        } }
        listOf(Triple(shop, barber, service), Triple(shop, secondBarber, service), Triple(otherShop, otherBarber, otherService)).forEach { (tenant, b, s) -> inShop(tenant) {
            jdbc.update("insert into barber_service (id, barbershop_id, barber_id, service_id) values (?, ?, ?, ?)", UUID.randomUUID(), tenant, b, s)
        } }
        listOf(Triple(owner, "OWNER", null), Triple(staff, "BARBER", barber), Triple(secondStaff, "BARBER", secondBarber)).forEach { (u, role, b) ->
            jdbc.update("insert into staff_membership (id, user_id, barbershop_id, role, barber_id) values (?, ?, ?, ?, ?)", UUID.randomUUID(), u.id, shop, role, b)
        }
    }
    @AfterAll fun closeClient() { client.close() }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `blocks reject pending and confirmed appointments and return only authorized conflicts`(confirmed: Boolean) {
        val a = publicBooking()
        if (confirmed) approve(a)
        val response = block("10:00", "11:00")
        assertEquals(409, response.statusCode(), response.body())
        val conflicts = body(response).path("conflicts")
        assertEquals(1, conflicts.size())
        assertEquals(a.path("publicToken"), conflicts.first().path("publicToken"))
        assertEquals(0, inShop(shop) { jdbc.queryForObject("select count(*) from time_off where barbershop_id = ?", Int::class.java, shop) })
        assertEquals(404, block("10:00", "11:00", actor = secondStaff).statusCode())
    }

    @Test fun `blocking and deleting updates public availability immediately and isolates staff`() {
        val created = block("10:00", "11:00")
        assertEquals(201, created.statusCode(), created.body())
        val id = body(created).path("id").asString()
        val slots = body(http("GET", "/api/v1/barbershops/staff-a/availability?barberId=$barber&serviceId=$service&date=2026-10-19", null)).path("morning")
        assertFalse(slots.any { it.asString() in listOf("2026-10-19T09:45:00-05:00", "2026-10-19T10:00:00-05:00", "2026-10-19T10:45:00-05:00") })
        assertTrue(slots.any { it.asString() == "2026-10-19T11:00:00-05:00" })
        val path = "/api/v1/barbershops/staff-a/staff/time-off?from=2026-10-19T09:00:00-05:00&to=2026-10-19T12:00:00-05:00"
        assertEquals(1, body(http("GET", path, owner)).size())
        assertEquals(0, body(http("GET", path, secondStaff)).size())
        assertEquals(404, http("DELETE", "/api/v1/barbershops/staff-a/staff/time-off/$id", secondStaff).statusCode())
        assertEquals(404, http("DELETE", "/api/v1/barbershops/staff-b/staff/time-off/$id", owner).statusCode())
        assertEquals(204, http("DELETE", "/api/v1/barbershops/staff-a/staff/time-off/$id", staff).statusCode())
        val freed = body(http("GET", "/api/v1/barbershops/staff-a/availability?barberId=$barber&serviceId=$service&date=2026-10-19", null)).path("morning")
        assertTrue(freed.any { it.asString() == "2026-10-19T10:00:00-05:00" })
    }

    @Test fun `owner can block another barber but invalid interval and another tenant are rejected`() {
        assertEquals(201, block("11:00", "12:00", secondBarber, owner).statusCode())
        assertEquals(400, block("12:00", "11:00", actor = owner).statusCode())
        assertEquals(400, block("12:00", "12:00", actor = owner).statusCode())
        assertEquals(404, block("11:00", "12:00", otherBarber, owner).statusCode())
        assertEquals(404, block("11:00", "12:00", actor = customer).statusCode())
    }

    @Test fun `manual appointment works now outside hours and grid without deposit but exclusion rejects overlap`() {
        clock.set(Instant.parse("2026-10-19T13:07:00Z"))
        val response = manual("2026-10-19T08:07:00-05:00")
        assertEquals(201, response.statusCode(), response.body())
        val a = body(response)
        assertEquals("CONFIRMED", a.path("status").asString())
        assertEquals("MANUAL", a.path("source").asString())
        assertEquals(409, manual("2026-10-19T08:08:00-05:00").statusCode())
        inShop(shop) {
            assertEquals(0L, jdbc.queryForObject("select deposit_cop from appointment where barbershop_id = ?", Long::class.java, shop))
            assertEquals(0, jdbc.queryForObject("select count(*) from deposit_payment where barbershop_id = ?", Int::class.java, shop))
            assertNull(jdbc.queryForMap("select hold_expires_at from appointment where barbershop_id = ?", shop)["hold_expires_at"])
            val c = jdbc.queryForMap("select * from customer where barbershop_id = ?", shop)
            assertNull(c["phone"]); assertNull(c["user_id"])
        }
    }

    @Test fun `manual booking normalizes phone reuses local customer and rejects cross tenant ids`() {
        assertEquals(201, manual("2026-10-19T08:00:00-05:00", phone = "3002223333").statusCode())
        assertEquals(201, manual("2026-10-19T09:00:00-05:00", phone = "+573002223333").statusCode())
        inShop(shop) {
            assertEquals(1, jdbc.queryForObject("select count(*) from customer where barbershop_id = ?", Int::class.java, shop))
            assertEquals("+573002223333", jdbc.queryForObject("select phone from customer where barbershop_id = ?", String::class.java, shop))
        }
        assertEquals(400, manual("2026-10-19T12:00:00-05:00", phone = "123").statusCode())
        assertEquals(404, manual("2026-10-19T12:00:00-05:00", b = secondBarber, actor = staff).statusCode())
        assertEquals(404, manual("2026-10-19T12:00:00-05:00", b = otherBarber).statusCode())
        assertEquals(404, manual("2026-10-19T12:00:00-05:00", s = otherService).statusCode())
        assertEquals(409, block("08:00", "09:00", actor = owner).statusCode())
        assertEquals(201, block("12:00", "13:00", actor = owner).statusCode())
        assertEquals(409, manual("2026-10-19T12:10:00-05:00").statusCode())
    }

    @Test fun `complete respects fifteen minute boundary and publishes once after commit`() {
        val a = body(manual("2026-10-19T10:00:00-05:00"))
        clock.set(Instant.parse("2026-10-19T14:44:59Z"))
        assertEquals(409, action(a, "complete").statusCode())
        clock.set(Instant.parse("2026-10-19T14:45:00Z"))
        assertEquals(204, action(a, "complete").statusCode())
        assertEquals(204, action(a, "complete").statusCode())
        assertEquals(1, events.completed.size)
        assertEquals(UUID.fromString(a.path("id").asString()), events.completed.single().appointmentId)
        assertEquals(409, action(a, "no-show").statusCode())
    }

    @Test fun `no show starts strictly after appointment and forfeits approved deposit with audit exactly once`() {
        val created = publicBooking(); approve(created)
        val a = appointment(created)
        clock.set(Instant.parse("2026-10-19T15:00:00Z"))
        assertEquals(409, action(a, "no-show").statusCode())
        clock.set(Instant.parse("2026-10-19T15:00:01Z"))
        assertEquals(204, action(a, "no-show").statusCode())
        assertEquals(204, action(a, "no-show").statusCode())
        assertEquals(1, events.noShows.size)
        inShop(shop) {
            assertEquals("FORFEITED", jdbc.queryForObject("select refund_status from deposit_payment where barbershop_id = ?", String::class.java, shop))
            assertEquals(staff.id, jdbc.queryForObject("select confirmed_by_user_id from deposit_payment where barbershop_id = ?", UUID::class.java, shop))
            assertEquals(1, jdbc.queryForObject("select count(*) from deposit_event where barbershop_id = ? and to_refund_status = 'FORFEITED'", Int::class.java, shop))
        }
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun `staff cancellation voids pending payment or refunds approved regardless of customer deadline`(approved: Boolean) {
        val created = publicBooking()
        if (approved) approve(created)
        val a = appointment(created)
        assertEquals(204, action(a, "cancel", data = mapOf("reason" to "Imprevisto ficticio")).statusCode())
        assertEquals(204, action(a, "cancel", data = mapOf("reason" to "Reintento")).statusCode())
        assertEquals(1, events.events.size)
        assertEquals(CancellationOrigin.STAFF, events.events.single().origin)
        assertEquals("cancelada por la barbería", events.events.single().reason)
        inShop(shop) {
            assertEquals(if (approved) "PENDING" else "NONE", jdbc.queryForObject("select refund_status from deposit_payment where barbershop_id = ?", String::class.java, shop))
            assertEquals(if (approved) "APPROVED" else "VOIDED", jdbc.queryForObject("select status from deposit_payment where barbershop_id = ?", String::class.java, shop))
            assertEquals("Imprevisto ficticio", jdbc.queryForObject("select cancellation_reason from appointment where barbershop_id = ?", String::class.java, shop))
        }
    }

    @Test fun `agenda includes contacts and source but BARBER cannot read or mutate another barber`() {
        val one = body(manual("2026-10-19T09:00:00-05:00", phone = "3002223333"))
        assertEquals(201, manual("2026-10-19T10:00:00-05:00", b = secondBarber).statusCode())
        val path = "/api/v1/barbershops/staff-a/staff/agenda?date=2026-10-19"
        assertEquals(2, body(http("GET", path, owner)).size())
        val agenda = body(http("GET", path, staff))
        assertEquals(1, agenda.size())
        assertEquals("Cliente manual", agenda.first().path("customerName").asString())
        assertEquals("+573002223333", agenda.first().path("customerPhone").asString())
        assertEquals("Corte staff", agenda.first().path("serviceName").asString())
        assertEquals("MANUAL", agenda.first().path("source").asString())
        assertEquals(1, body(http("GET", "$path&barberId=$secondBarber", owner)).size())
        assertEquals(404, http("GET", "$path&barberId=$secondBarber", staff).statusCode())
        listOf("complete", "no-show", "cancel").forEach { action ->
            assertEquals(404, action(one, action, secondStaff, if (action == "cancel") mapOf("reason" to "Prueba") else null).statusCode())
            assertEquals(404, http("POST", "/api/v1/barbershops/staff-b/staff/appointments/${one.path("id").asString()}/$action", owner,
                if (action == "cancel") mapOf("reason" to "Prueba") else null).statusCode())
        }
        assertEquals(401, http("GET", path, null).statusCode())
        assertEquals(404, http("GET", path, customer).statusCode())
    }

    @Test fun `action items include pending manual late payments and refunds within assigned barber only`() {
        val a = publicBooking()
        val late = body(manual("2026-10-19T11:00:00-05:00"))
        val refund = body(manual("2026-10-19T12:00:00-05:00", b = secondBarber))
        payment(late, "LATE_PAYMENT", "NONE", now)
        payment(refund, "APPROVED", "PENDING", now)
        val path = "/api/v1/barbershops/staff-a/staff/action-items"
        val all = body(http("GET", path, owner))
        assertEquals(1, all.path("pendingBreB").size()); assertEquals(1, all.path("latePayments").size()); assertEquals(1, all.path("pendingRefunds").size())
        assertEquals(a.path("publicToken"), all.path("pendingBreB").first().path("publicToken"))
        assertEquals(12000L, all.path("pendingBreB").first().path("amountCop").asLong())
        val own = body(http("GET", path, staff))
        assertEquals(1, own.path("pendingBreB").size()); assertEquals(1, own.path("latePayments").size()); assertEquals(0, own.path("pendingRefunds").size())
        assertEquals(404, http("GET", path, customer).statusCode())
        assertEquals(404, http("GET", path.replace("staff-a", "staff-b"), staff).statusCode())
        clock.set(now.plusSeconds(1800))
        assertEquals(0, body(http("GET", path, owner)).path("pendingBreB").size())
    }

    @Test fun `summary uses local appointment and payment dates and current debt with owner breakdown`() {
        val before = body(manual("2026-10-18T23:59:00-05:00"))
        val first = body(manual("2026-10-19T00:30:00-05:00"))
        val second = body(manual("2026-10-19T10:00:00-05:00", b = secondBarber))
        val future = body(manual("2026-10-20T00:00:00-05:00"))
        val old = body(manual("2026-09-01T10:00:00-05:00"))
        state(before, "COMPLETED"); state(first, "COMPLETED"); state(second, "NO_SHOW"); state(old, "CANCELLED")
        payment(future, "APPROVED", "NONE", Instant.parse("2026-10-19T04:59:59Z")) // Sunday in Bogotá
        payment(before, "APPROVED", "DONE", Instant.parse("2026-10-19T05:00:00Z"), Instant.parse("2026-10-19T05:00:00Z"))
        payment(old, "APPROVED", "PENDING", Instant.parse("2026-09-01T15:00:00Z"))
        val path = "/api/v1/barbershops/staff-a/staff/summary?period=DAY&date=2026-10-19"
        val summary = body(http("GET", path, owner))
        assertEquals("2026-10-19T00:00:00-05:00", summary.path("from").asString())
        val m = summary.path("metrics")
        assertEquals(2L, m.path("totalAppointments").asLong()); assertEquals(1L, m.path("completed").asLong()); assertEquals(1L, m.path("noShows").asLong())
        assertEquals(50.0, m.path("noShowRate").asDouble()); assertEquals(38000L, m.path("estimatedRevenueCop").asLong())
        assertEquals(12000L, m.path("depositsReceivedCop").asLong()); assertEquals(1L, m.path("completedRefunds").asLong())
        assertEquals(12000L, m.path("completedRefundsCop").asLong()); assertEquals(1L, m.path("pendingRefunds").asLong())
        assertEquals(12000L, m.path("pendingRefundsCop").asLong()); assertEquals(2, summary.path("barbers").size())
        val own = body(http("GET", path, staff))
        assertEquals(1L, own.path("metrics").path("totalAppointments").asLong()); assertEquals(0, own.path("barbers").size())
        val week = body(http("GET", path.replace("period=DAY", "period=WEEK"), owner))
        assertEquals(3L, week.path("metrics").path("totalAppointments").asLong())
        val month = body(http("GET", path.replace("period=DAY", "period=MONTH"), owner))
        assertEquals(4L, month.path("metrics").path("totalAppointments").asLong())
        assertEquals(404, http("GET", path, customer).statusCode())
        assertEquals(404, http("GET", path.replace("staff-a", "staff-b"), owner).statusCode())
        val otherPeriod = body(http("GET", path.replace("2026-10-19", "2027-01-01"), owner)).path("metrics")
        assertEquals(0L, otherPeriod.path("totalAppointments").asLong()); assertEquals(1L, otherPeriod.path("pendingRefunds").asLong())
        assertEquals(0L, otherPeriod.path("completedRefunds").asLong())
        val empty = body(http("GET", path.replace("period=DAY", "period=MONTH").replace("2026-10-19", "2027-02-01"), owner))
        assertEquals(2, empty.path("barbers").size())
        empty.path("barbers").forEach { assertEquals(0L, it.path("metrics").path("completed").asLong()) }
    }

    @Test fun `a block and a manual appointment racing for the same interval cannot both be created`() {
        val ready = java.util.concurrent.CountDownLatch(2)
        val start = java.util.concurrent.CountDownLatch(1)
        java.util.concurrent.Executors.newFixedThreadPool(2).use { executor ->
            val tasks = listOf<() -> HttpResponse<String>>({ block("10:00", "11:00", actor = owner) }, { manual("2026-10-19T10:00:00-05:00") })
            val futures = tasks.map { task -> executor.submit<HttpResponse<String>> { ready.countDown(); start.await(); task() } }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)); start.countDown()
            val codes = futures.map { it.get(20, java.util.concurrent.TimeUnit.SECONDS).statusCode() }
            assertEquals(listOf(201, 409), codes.sorted())
        }
    }

    @Test fun `late confirmation cannot revive an expired appointment over a staff block`() {
        val created = publicBooking()
        clock.set(now.plusSeconds(1801))
        inShop(shop) { jdbc.update("update appointment set status = 'EXPIRED' where barbershop_id = ?", shop) }
        assertEquals(201, block("10:00", "11:00").statusCode())
        approve(created)
        inShop(shop) {
            assertEquals("EXPIRED", jdbc.queryForObject("select status from appointment where barbershop_id = ?", String::class.java, shop))
            assertEquals("LATE_PAYMENT", jdbc.queryForObject("select status from deposit_payment where barbershop_id = ?", String::class.java, shop))
        }
    }

    @Test fun `pending appointments reject attendance and manual cancellation publishes without a refund`() {
        val pending = appointment(publicBooking())
        assertEquals(409, action(pending, "complete").statusCode())
        clock.set(Instant.parse("2026-10-19T15:01:00Z"))
        assertEquals(409, action(pending, "no-show").statusCode())
        val a = body(manual("2026-10-19T12:00:00-05:00"))
        assertEquals(204, action(a, "cancel", data = mapOf("reason" to "Imprevisto ficticio")).statusCode())
        assertEquals(RefundStatus.NONE, events.events.single().refundStatus)
        assertEquals(0L, events.events.single().refundAmountCop)
    }

    @Test fun `late payment can be cancelled refunded and removed from action items with its DONE date`() {
        val a = body(manual("2026-10-19T10:00:00-05:00"))
        state(a, "EXPIRED")
        payment(a, "LATE_PAYMENT", "NONE", now)
        assertEquals(204, action(a, "cancel", data = mapOf("reason" to "Pago recibido fuera de plazo")).statusCode())
        val path = "/api/v1/barbershops/staff-a/staff/action-items"
        val pending = body(http("GET", path, staff)).path("pendingRefunds")
        assertEquals(1, pending.size())
        assertEquals("Cliente manual", pending.first().path("customerName").asString())
        assertEquals("BREB_MANUAL", pending.first().path("method").asString())
        assertEquals(204, action(a, "deposit/refunded").statusCode())
        val resolved = body(http("GET", path, owner))
        assertEquals(0, resolved.path("latePayments").size()); assertEquals(0, resolved.path("pendingRefunds").size())
        val m = body(http("GET", "/api/v1/barbershops/staff-a/staff/summary?period=DAY&date=2026-10-19", owner)).path("metrics")
        assertEquals(12000L, m.path("completedRefundsCop").asLong())
        assertEquals(0L, m.path("pendingRefundsCop").asLong())
        assertEquals(0.0, m.path("noShowRate").asDouble())
    }

    @Test fun `OpenAPI includes protected staff operations and safe request schemas`() {
        val spec = body(http("GET", "/v3/api-docs", null))
        val paths = spec.path("paths")
        listOf("/time-off" to "post", "/time-off" to "get", "/time-off/{id}" to "delete",
            "/appointments" to "post", "/appointments/{id}/complete" to "post", "/appointments/{id}/no-show" to "post",
            "/appointments/{id}/cancel" to "post", "/summary" to "get", "/action-items" to "get").forEach { (path, verb) ->
            val operation = paths.path("/api/v1/barbershops/{slug}/staff$path").path(verb)
            assertFalse(operation.isMissingNode, path)
            assertTrue(operation.path("security").first().path("bearerAuth").isArray, path)
        }
        val schemas = spec.path("components").path("schemas")
        assertFalse(schemas.path("AgendaAppointmentResponse").path("properties").path("customerPhone").isMissingNode)
        assertFalse(schemas.path("StaffMetrics").path("properties").path("completedRefundsCop").isMissingNode)
        assertFalse(schemas.path("ActionItem").path("properties").path("providerTransactionId").isMissingNode)
    }

    @Autowired private lateinit var flyway: org.flywaydb.core.Flyway

    @Test fun `summary uses bounded index scans despite thirty thousand historical appointments payments and DONE events`() {
        val a = body(manual("2026-10-19T10:00:00-05:00"))
        payment(a, "APPROVED", "DONE", now, now)
        val path = "/api/v1/barbershops/staff-a/staff/summary?period=DAY&date=2026-10-19"
        val before = body(http("GET", path, owner))
        inShop(shop) {
            val customerId = jdbc.queryForObject("select customer_id from appointment where barbershop_id = ? and id = ?",
                UUID::class.java, shop, UUID.fromString(a.path("id").asString()))
            jdbc.update("""insert into appointment (id, barbershop_id, barber_id, service_id, customer_id, starts_at, ends_at,
                status, price_cop, deposit_cop, payment_reference, source)
                select gen_random_uuid(), ?, ?, ?, ?, timestamptz '2010-01-01 05:00:00+00' + n * interval '1 minute',
                    timestamptz '2010-01-01 05:30:00+00' + n * interval '1 minute', 'COMPLETED', 38000, 12000, 'H-' || n, 'MANUAL'
                from generate_series(1,30000) n""", shop, barber, service, customerId)
            jdbc.update("""insert into deposit_payment (id, barbershop_id, appointment_id, method, amount_cop, reference, attempt, status, refund_status, confirmed_at)
                select gen_random_uuid(), barbershop_id, id, 'BREB_MANUAL', 12000, payment_reference, 1, 'APPROVED', 'DONE', starts_at
                from appointment where barbershop_id = ? and starts_at < timestamptz '2011-01-01 00:00:00+00'""", shop)
            jdbc.update("""insert into deposit_event (id, barbershop_id, deposit_payment_id, to_status, source, to_refund_status, created_at)
                select gen_random_uuid(), barbershop_id, id, 'APPROVED', 'STAFF', 'DONE', confirmed_at
                from deposit_payment where barbershop_id = ? and confirmed_at < timestamptz '2011-01-01 00:00:00+00'""", shop)
        }
        // Maintenance only: owner refreshes planner statistics. All fixtures, summaries and EXPLAIN reads use corteya_app/RLS.
        flyway.configuration.dataSource.connection.use { connection ->
            assertEquals("corteya_owner", connection.metaData.userName)
            connection.createStatement().use { statement ->
                listOf("appointment", "deposit_payment", "deposit_event").forEach { statement.execute("ANALYZE $it") }
            }
        }
        assertEquals(before, body(http("GET", path, owner)))
        val expectedIndexes = setOf("idx_appointment_shop_starts", "idx_deposit_payment_shop_confirmed",
            "idx_deposit_payment_shop_refund", "idx_deposit_event_shop_refund_created")
        val output = java.nio.file.Path.of("build/reports/staff-summary")
        java.nio.file.Files.createDirectories(output)
        listOf("owner" to null, "barber" to barber).forEach { (role, filter) -> inShop(shop) {
            assertEquals("corteya_app", jdbc.queryForObject("select current_user", String::class.java))
            val parameters = mapOf("shop" to shop, "barber" to filter, "from" to java.sql.Timestamp.from(Instant.parse("2026-10-19T05:00:00Z")),
                "to" to java.sql.Timestamp.from(Instant.parse("2026-10-20T05:00:00Z")))
            val json = checkNotNull(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc).queryForObject(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + com.geovannycode.corteya.booking.internal.StaffReportRepository.SUMMARY_SQL,
                parameters, String::class.java))
            java.nio.file.Files.writeString(output.resolve("explain-$role.json"), json)
            val nodes = planNodes(mapper.readTree(json).first().path("Plan"))
            val indexes = nodes.mapNotNull { it.path("Index Name").takeIf { value -> value.isString }?.asString() }.toSet()
            assertTrue(indexes.containsAll(expectedIndexes), "Missing indexes for $role: ${expectedIndexes - indexes}; plan: $json")
            val tenantTables = setOf("appointment", "deposit_payment", "deposit_event")
            assertFalse(nodes.any { it.path("Relation Name").asString("") in tenantTables && it.path("Node Type").asString() == "Seq Scan" && it.path("Actual Loops").asLong() > 0 }, json)
            val visited = nodes.sumOf { (it.path("Actual Rows").asLong() + it.path("Rows Removed by Filter").asLong()) * it.path("Actual Loops").asLong() }
            assertTrue(visited < 500, "Plan visited $visited rows instead of the bounded period/debt")
        } }
    }

    @Test fun `summary only adds active barbers as zero rows while preserving inactive barber movements`() {
        inShop(shop) { jdbc.update("update barber set active = false where barbershop_id = ? and id = ?", shop, secondBarber) }
        val path = "/api/v1/barbershops/staff-a/staff/summary?period=DAY&date=2026-10-19"
        assertEquals(1, body(http("GET", path, owner)).path("barbers").size())
        inShop(shop) { jdbc.update("update barber set active = true where barbershop_id = ? and id = ?", shop, secondBarber) }
        assertEquals(201, manual("2026-10-19T11:00:00-05:00", b = secondBarber).statusCode())
        inShop(shop) { jdbc.update("update barber set active = false where barbershop_id = ? and id = ?", shop, secondBarber) }
        val report = body(http("GET", path, owner))
        assertEquals(2, report.path("barbers").size())
        assertEquals(1L, report.path("metrics").path("totalAppointments").asLong())
    }

    private fun planNodes(node: JsonNode): List<JsonNode> = listOf(node) + node.path("Plans").flatMap { planNodes(it) }

    private fun user(phone: String) = identities.findOrCreateUser("+57$phone", now).let { identities.updateName(it.id, "Cliente"); it.copy(name = "Cliente") }
    private fun manual(time: String, b: UUID = barber, s: UUID = service, actor: AppUser = owner, phone: String? = null) =
        http("POST", "/api/v1/barbershops/staff-a/staff/appointments", actor, mapOf("barberId" to b, "serviceId" to s, "startsAt" to time, "customerName" to "Cliente manual", "customerPhone" to phone))
    private fun block(from: String, to: String, b: UUID = barber, actor: AppUser = staff) = http("POST", "/api/v1/barbershops/staff-a/staff/time-off", actor,
        mapOf("barberId" to b, "startsAt" to "2026-10-19T$from:00-05:00", "endsAt" to "2026-10-19T$to:00-05:00", "reason" to "Almuerzo ficticio"))
    private fun publicBooking(): JsonNode {
        val response = http("POST", "/api/v1/barbershops/staff-a/appointments", customer, mapOf("barberId" to barber, "serviceId" to service, "startsAt" to "2026-10-19T10:00:00-05:00"))
        assertEquals(201, response.statusCode(), response.body()); return body(response)
    }
    private fun appointment(a: JsonNode): JsonNode = inShop(shop) {
        val id = jdbc.queryForObject("select id from appointment where barbershop_id = ? and public_token = ?", UUID::class.java, shop, a.path("publicToken").asString())
        mapper.valueToTree(mapOf("id" to id))
    }
    private fun approve(a: JsonNode) { assertEquals(204, action(appointment(a), "deposit/confirm").statusCode()) }
    private fun action(a: JsonNode, action: String, actor: AppUser = staff, data: Any? = null) = http("POST", "/api/v1/barbershops/staff-a/staff/appointments/${a.path("id").asString()}/$action", actor, data)
    private fun state(a: JsonNode, status: String) = inShop(shop) { jdbc.update("update appointment set status = ? where barbershop_id = ? and id = ?", status, shop, UUID.fromString(a.path("id").asString())) }
    private fun payment(a: JsonNode, status: String, refund: String, confirmed: Instant, done: Instant? = null) = inShop(shop) {
        val id = UUID.randomUUID()
        jdbc.update("insert into deposit_payment (id, barbershop_id, appointment_id, method, amount_cop, reference, attempt, status, refund_status, confirmed_at) values (?, ?, ?, 'BREB_MANUAL', 12000, ?, 1, ?, ?, ?)",
            id, shop, UUID.fromString(a.path("id").asString()), "R-${UUID.randomUUID()}", status, refund, java.sql.Timestamp.from(confirmed))
        if (done != null) jdbc.update("insert into deposit_event (id, barbershop_id, deposit_payment_id, to_status, source, to_refund_status, created_at) values (?, ?, ?, ?, 'STAFF', 'DONE', ?)",
            UUID.randomUUID(), shop, id, status, java.sql.Timestamp.from(done))
    }
    private fun body(response: HttpResponse<String>) = mapper.readTree(response.body())
    private fun http(method: String, path: String, actor: AppUser?, data: Any? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$serverPort$path"))
        if (actor != null) request.header("Authorization", "Bearer " + tokens.issue(actor, emptyList()))
        if (data != null) request.header("Content-Type", "application/json")
        return client.send(request.method(method, if (data == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(data))).build(), HttpResponse.BodyHandlers.ofString())
    }
}
