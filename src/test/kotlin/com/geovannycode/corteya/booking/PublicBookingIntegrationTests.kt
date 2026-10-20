package com.geovannycode.corteya.booking

import com.geovannycode.corteya.PostgresIntegrationTest
import com.geovannycode.corteya.identity.application.AccessTokens
import com.geovannycode.corteya.identity.application.IdentityStore
import com.geovannycode.corteya.booking.application.PaymentReferences
import com.geovannycode.corteya.booking.domain.BookingCodes
import com.geovannycode.corteya.booking.infrastructure.scheduling.HoldExpirationJob
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.sql.Timestamp
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class PublicBookingIntegrationTests : PostgresIntegrationTest(web = true) {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mapper: JsonMapper
    @Autowired private lateinit var clock: MutableTestClock
    @Autowired private lateinit var references: ControlledReferences
    @Autowired private lateinit var identities: IdentityStore
    @Autowired private lateinit var accessTokens: AccessTokens
    @Autowired private lateinit var job: HoldExpirationJob

    private val owners = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val client = HttpClient.newHttpClient()
    private val shop = UUID.randomUUID()
    private val otherShop = UUID.randomUUID()
    private val barber = UUID.randomUUID()
    private val secondBarber = UUID.randomUUID()
    private val otherBarber = UUID.randomUUID()
    private val service = UUID.randomUUID()
    private val otherService = UUID.randomUUID()
    private val baseTime = Instant.parse("2026-10-19T13:00:00Z") // Monday 08:00 Colombia

    override fun customize(builder: SpringApplicationBuilder): SpringApplicationBuilder = builder.sources(FixedBookingTestConfiguration::class.java)

    @BeforeEach
    fun fixtures() {
        clock.set(baseTime)
        references.queue.clear()
        owners.clear()
        jdbc.execute("TRUNCATE app_user CASCADE")
        jdbc.execute("TRUNCATE barbershop CASCADE")
        listOf(shop to "shop-a", otherShop to "shop-b").forEach { (id, slug) ->
            jdbc.update("insert into barbershop (id, slug, name, primary_color, secondary_color) values (?, ?, 'Piloto', '#112233', '#445566')", id, slug)
        }
        listOf(barber to shop, secondBarber to shop, otherBarber to otherShop).forEach { (id, tenant) ->
            jdbc.update("insert into barber (id, barbershop_id, name) values (?, ?, 'Barbero')", id, tenant)
            for (day in 1..6) jdbc.update(
                "insert into working_hours (id, barbershop_id, barber_id, day_of_week, start_time, end_time) values (?, ?, ?, ?, '09:00', '20:00')",
                UUID.randomUUID(), tenant, id, day,
            )
        }
        listOf(service to shop, otherService to otherShop).forEach { (id, tenant) ->
            jdbc.update("insert into service (id, barbershop_id, name, duration_minutes, price_cop) values (?, ?, 'Corte', 30, 38000)", id, tenant)
        }
        listOf(Triple(shop, barber, service), Triple(shop, secondBarber, service), Triple(otherShop, otherBarber, otherService)).forEach { (tenant, b, s) ->
            jdbc.update("insert into barber_service (id, barbershop_id, barber_id, service_id) values (?, ?, ?, ?)", UUID.randomUUID(), tenant, b, s)
        }
    }

    @AfterAll fun closeClient() { client.close() }

    @Test
    fun `ten concurrent requests for one slot create exactly one appointment and nine conflicts`() {
        val responses = parallel((0..9).map { i -> { create(phone = "30000000${i.toString().padStart(2, '0')}") } })
        assertEquals(1, responses.count { it.statusCode() == 201 })
        assertEquals(9, responses.count { it.statusCode() == 409 })
        responses.filter { it.statusCode() == 409 }.forEach {
            assertTrue(it.body().contains("Ese horario acaba de ser tomado, elige otro"), it.body())
            assertTrue(it.headers().firstValue("Content-Type").orElse("").contains("application/problem+json"))
        }
        assertEquals(1, count("appointment"))
        assertEquals(1, count("customer")) // losing transactions also roll back customer upserts
    }

    @Test
    fun `expired hold can be retaken without the scheduled job and only this barber is cleaned`() {
        val expired = created(create())
        val untouched = created(create(b = secondBarber, phone = "3000000002"))
        clock.set(baseTime.plusSeconds(16 * 60))
        val replacement = create(phone = "3000000003")
        assertEquals(201, replacement.statusCode(), replacement.body())
        assertEquals("EXPIRED", status(expired))
        assertEquals("PENDING_PAYMENT", status(untouched))
        assertEquals(3, count("appointment"))
    }

    @Test
    fun `job expires exactly past pending holds and is idempotent without touching confirmed`() {
        val expired = created(create())
        val confirmed = created(create(time = "10:00", phone = "3000000002"))
        jdbc.update("update appointment set status = 'CONFIRMED' where barbershop_id = ? and public_token = ?", shop, confirmed)
        val boundary = created(create(time = "11:00", phone = "3000000003"))
        val future = created(create(time = "12:00", phone = "3000000004"))
        val otherTenant = created(create(slug = "shop-b", b = otherBarber, s = otherService, phone = "3000000005"))
        jdbc.update("update appointment set hold_expires_at = ? where barbershop_id = ? and public_token = ?", Timestamp.from(baseTime.plusSeconds(1800)), shop, future)
        clock.set(baseTime.plusSeconds(900))
        job.expire()
        assertEquals("PENDING_PAYMENT", status(boundary)) // exact boundary remains valid
        jdbc.update("update appointment set hold_expires_at = ? where barbershop_id = ? and public_token = ?", Timestamp.from(baseTime.plusSeconds(899)), shop, expired)
        job.expire()
        assertEquals("EXPIRED", status(expired))
        assertEquals("CONFIRMED", status(confirmed))
        assertEquals("PENDING_PAYMENT", status(future))
        assertEquals("PENDING_PAYMENT", status(otherTenant))
        clock.set(baseTime.plusSeconds(901))
        job.expire()
        assertEquals("EXPIRED", status(boundary))
        assertEquals("EXPIRED", status(otherTenant)) // the job covers all tenants intentionally
        val versions = jdbc.queryForList("select version from appointment order by id", Long::class.java)
        job.expire()
        assertEquals(versions, jdbc.queryForList("select version from appointment order by id", Long::class.java))
        assertEquals("CONFIRMED", status(confirmed))
    }

    @Test
    fun `barber or service from another shop always returns 404 on reads and creates`() {
        listOf(barber to otherService, otherBarber to service).forEach { (b, s) ->
            assertEquals(404, create(b = b, s = s).statusCode())
            assertEquals(404, get("/api/v1/barbershops/shop-a/availability?barberId=$b&serviceId=$s&date=2026-10-19").statusCode())
        }
        assertEquals(404, create(slug = "shop-b").statusCode())
        assertEquals(404, get("/api/v1/barbershops/shop-b/barbers?serviceId=$service").statusCode())
        assertEquals(0, count("customer"))
        assertEquals(0, count("appointment"))
    }

    @Test
    fun `inactive shops barbers services and unassigned services are not exposed`() {
        jdbc.update("delete from barber_service where barbershop_id = ? and barber_id = ?", shop, barber)
        assertEquals(404, create().statusCode())
        jdbc.update("update barber set active = false where barbershop_id = ? and id = ?", shop, secondBarber)
        assertEquals(404, create(b = secondBarber).statusCode())
        assertEquals("[]", get("/api/v1/barbershops/shop-a/barbers?serviceId=$service").body())
        jdbc.update("update service set active = false where barbershop_id = ? and id = ?", shop, service)
        assertEquals("[]", get("/api/v1/barbershops/shop-a/services").body())
        assertEquals(404, get("/api/v1/barbershops/shop-a/barbers?serviceId=$service").statusCode())
        jdbc.update("update barbershop set active = false where id = ?", shop)
        assertEquals(404, get("/api/v1/barbershops/shop-a").statusCode())
    }

    @Test
    fun `two pending appointments per phone are allowed and third conflicts but another shop is independent`() {
        assertEquals(201, create().statusCode())
        assertEquals(201, create(time = "10:00").statusCode())
        val denied = create(time = "11:00")
        assertEquals(409, denied.statusCode())
        assertTrue(denied.body().contains("2 reservas pendientes"))
        assertEquals(1, count("customer"))
        assertEquals("+573001234567", jdbc.queryForObject("select phone from customer where barbershop_id = ?", String::class.java, shop))
        assertEquals(201, create(slug = "shop-b", b = otherBarber, s = otherService).statusCode())
    }

    @Test
    fun `customer row locking enforces pending limit under concurrent different-slot requests`() {
        val responses = parallel((9..18).map { hour -> { create(time = "$hour:00".padStart(5, '0')) } })
        assertEquals(2, responses.count { it.statusCode() == 201 }, responses.joinToString { it.body() })
        assertEquals(8, responses.count { it.statusCode() == 409 })
        assertEquals(2, count("appointment"))
        assertEquals(1, count("customer"))
    }

    @Test
    fun `deposit rounding and minimal public polling use Bogota offsets and a secret token`() {
        val response = create()
        assertEquals(201, response.statusCode(), response.body())
        val body = json(response)
        assertEquals(12000L, body.path("depositCop").asLong())
        assertEquals("PENDING_PAYMENT", body.path("status").asString())
        assertEquals("2026-10-19T08:15:00-05:00", body.path("holdExpiresAt").asString())
        assertTrue(body.path("paymentReference").asString().matches(Regex("R-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}")))
        val token = body.path("publicToken").asString()
        assertTrue(token.matches(Regex("[0-9a-f]{32}")))
        val polled = get("/api/v1/appointments/$token")
        assertEquals(200, polled.statusCode())
        assertEquals("no-store", polled.headers().firstValue("Cache-Control").orElse(""))
        assertEquals("2026-10-19T09:00:00-05:00", json(polled).path("startsAt").asString())
        assertTrue(listOf("customerId", "customerPhone", "customerName", "barbershopId", "barberId", "serviceId").none { polled.body().contains(it) })
        jdbc.update("update appointment set status = 'CONFIRMED' where barbershop_id = ? and public_token = ?", shop, token)
        assertEquals("CONFIRMED", json(get("/api/v1/appointments/$token")).path("status").asString())
        val internalId = jdbc.queryForObject("select id from appointment where barbershop_id = ? and public_token = ?", UUID::class.java, shop, token)
        assertEquals(404, get("/api/v1/appointments/$internalId").statusCode())
        assertEquals(404, get("/api/v1/appointments/${"a".repeat(32)}").statusCode())
    }

    @Test
    fun `UTC request represents the same Colombia slot and amounts come exclusively from the catalog`() {
        val response = create(time = "2026-10-19T14:00:00Z")
        val token = created(response)
        assertEquals("2026-10-19T09:00:00-05:00", json(get("/api/v1/appointments/$token")).path("startsAt").asString())
        assertEquals(38000L, jdbc.queryForObject("select price_cop from appointment where barbershop_id = ? and public_token = ?", Long::class.java, shop, token))
    }

    @Test
    fun `expired rows still pending on another barber count until target cleanup or the job runs`() {
        created(create(b = secondBarber))
        created(create(b = secondBarber, time = "10:00"))
        clock.set(baseTime.plusSeconds(16 * 60))
        assertEquals(409, create().statusCode())
        job.expire()
        assertEquals(201, create().statusCode())
    }

    @Test
    fun `reference collision retries within transaction and same code is allowed in another shop`() {
        references.queue.addAll(listOf("R-ABCD", "R-ABCD", "R-EFGH", "R-ABCD"))
        assertEquals("R-ABCD", json(create()).path("paymentReference").asString())
        val retried = create(time = "10:00", phone = "3000000002")
        assertEquals(201, retried.statusCode(), retried.body())
        assertEquals("R-EFGH", json(retried).path("paymentReference").asString())
        assertEquals("R-ABCD", json(create(slug = "shop-b", b = otherBarber, s = otherService)).path("paymentReference").asString())
        assertEquals(3, count("appointment"))
    }

    @Test
    fun `reference retry exhaustion rolls back all writes`() {
        references.queue.add("R-ABCD")
        assertEquals(201, create().statusCode())
        references.queue.addAll(List(32) { "R-ABCD" })
        assertEquals(409, create(time = "10:00", phone = "3000000002").statusCode())
        assertEquals(1, count("customer"))
        assertEquals(1, count("appointment"))
    }

    @Test
    fun `creation revalidates working schedule slot grid blocks existing appointments and booking window`() {
        listOf("08:00", "09:05", "19:45").forEach {
            assertEquals(409, create(time = it).statusCode(), it)
        }
        assertEquals(409, create(time = "2026-10-18T09:00:00-05:00").statusCode())
        assertEquals(409, create(time = "2026-11-02T09:00:00-05:00").statusCode())
        jdbc.update("insert into time_off (id, barbershop_id, barber_id, starts_at, ends_at, reason) values (?, ?, ?, '2026-10-19 14:00:00+00', '2026-10-19 15:00:00+00', 'Almuerzo')", UUID.randomUUID(), shop, barber)
        assertEquals(409, create().statusCode())
        assertEquals(201, create(time = "10:00").statusCode())
        assertEquals(409, create(time = "10:15", phone = "3000000002").statusCode())
        assertEquals(201, create(time = "10:30", phone = "3000000003").statusCode())
    }

    @Test
    fun `dates before today outside the window and at LocalDate MAX return empty availability`() {
        listOf("2026-10-18", "2026-11-02", "%2B999999999-12-31").forEach { date ->
            val response = get("/api/v1/barbershops/shop-a/availability?barberId=$barber&serviceId=$service&date=$date")
            assertEquals(200, response.statusCode(), response.body())
            assertEquals(0, json(response).path("morning").size())
            assertEquals(0, json(response).path("afternoon").size())
        }
    }

    @Test
    fun `validation errors are Spanish ProblemDetails and never contain submitted PII or stack traces`() {
        val invalid = listOf(
            create(time = "2026-10-19T09:00:00"),
            post("/api/v1/barbershops/shop-a/appointments", "{\"invalid\":true}"),
            get("/api/v1/barbershops/shop-a/availability?barberId=bad&serviceId=$service&date=2026-10-19"),
            get("/api/v1/barbershops/shop-a/availability?barberId=$barber&serviceId=$service&date=bad"),
            get("/api/v1/barbershops/shop-a/barbers"),
        )
        invalid.forEach {
            assertEquals(400, it.statusCode(), it.body())
            assertEquals("Datos inválidos", json(it).path("title").asString())
            assertTrue(it.headers().firstValue("Content-Type").orElse("").contains("application/problem+json"))
            assertTrue(listOf("stackTrace", "SQLException", "java.lang", "Synthetic Customer").none { secret -> it.body().contains(secret) })
        }
        assertEquals(0, count("customer"))
        assertEquals(0, count("appointment"))
    }

    @Test
    fun `shop open closed and next availability follow the fixed clock and next working day`() {
        var shopBody = json(get("/api/v1/barbershops/shop-a"))
        assertFalse(shopBody.path("open").asBoolean())
        assertEquals("cerrado, abre hoy a las 9:00 a. m.", shopBody.path("statusMessage").asString())
        assertEquals("2026-10-19T09:00:00-05:00", nextAvailable())
        clock.set(Instant.parse("2026-10-19T14:00:00Z"))
        shopBody = json(get("/api/v1/barbershops/shop-a"))
        assertTrue(shopBody.path("open").asBoolean())
        assertEquals("abierto, cierra a las 8:00 p. m.", shopBody.path("statusMessage").asString())
        assertEquals("2026-10-19T09:30:00-05:00", nextAvailable())
        clock.set(Instant.parse("2026-10-20T01:00:00Z")) // exact Monday close
        shopBody = json(get("/api/v1/barbershops/shop-a"))
        assertFalse(shopBody.path("open").asBoolean())
        assertEquals("cerrado, abre mañana a las 9:00 a. m.", shopBody.path("statusMessage").asString())
        assertEquals("2026-10-20T09:00:00-05:00", nextAvailable())
        clock.set(Instant.parse("2026-10-25T01:00:00Z")) // Saturday 20:00 -> Monday
        assertEquals("2026-10-26T09:00:00-05:00", nextAvailable())
        assertEquals("cerrado, abre el lunes a las 9:00 a. m.", json(get("/api/v1/barbershops/shop-a")).path("statusMessage").asString())
    }

    @Test
    fun `availability is grouped at noon and nextAvailableAt respects appointments and time off`() {
        val body = json(get("/api/v1/barbershops/shop-a/availability?barberId=$barber&serviceId=$service&date=2026-10-19"))
        assertEquals("2026-10-19T09:00:00-05:00", body.path("morning").first().asString())
        assertEquals("2026-10-19T12:00:00-05:00", body.path("afternoon").first().asString())
        created(create())
        assertEquals("2026-10-19T09:30:00-05:00", nextAvailable())
        jdbc.update("insert into time_off (id, barbershop_id, barber_id, starts_at, ends_at) values (?, ?, ?, '2026-10-19 14:30:00+00', '2026-10-19 15:00:00+00')", UUID.randomUUID(), shop, barber)
        assertEquals("2026-10-19T10:00:00-05:00", nextAvailable())
        clock.set(baseTime.plusSeconds(16 * 60))
        assertEquals("2026-10-19T09:00:00-05:00", nextAvailable()) // expired hold ignored without a write
    }

    @Test
    fun `Swagger UI and OpenAPI document all six public routes and their schemas`() {
        val spec = get("/v3/api-docs")
        assertEquals(200, spec.statusCode(), spec.body())
        assertEquals("CorteYa API", json(spec).path("info").path("title").asString())
        val paths = json(spec).path("paths")
        assertFalse(paths.path("/api/v1/barbershops/{slug}/appointments").path("post").path("responses").path("201").isMissingNode)
        assertEquals("date-time", json(spec).path("components").path("schemas").path("CreatedAppointmentResponse")
            .path("properties").path("holdExpiresAt").path("format").asString())
        listOf("/api/v1/barbershops/{slug}", "/api/v1/barbershops/{slug}/services", "/api/v1/barbershops/{slug}/barbers",
            "/api/v1/barbershops/{slug}/availability", "/api/v1/barbershops/{slug}/appointments", "/api/v1/appointments/{publicToken}").forEach {
            assertFalse(paths.path(it).isMissingNode, it)
        }
        assertTrue(spec.body().contains("CreatedAppointmentResponse"))
        assertTrue(spec.body().contains("application/problem+json"))
        assertTrue(get("/swagger-ui/index.html").body().contains("Swagger UI"))
        val redirect = get("/swagger-ui.html")
        assertTrue(redirect.statusCode() in listOf(200, 302), redirect.body())
        assertEquals(404, get("/actuator/env").statusCode())
    }

    private fun nextAvailable(): String = json(get("/api/v1/barbershops/shop-a/barbers?serviceId=$service"))
        .first { it.path("id").asString() == barber.toString() }.path("nextAvailableAt").asString()

    private fun created(response: HttpResponse<String>): String {
        assertEquals(201, response.statusCode(), response.body())
        return json(response).path("publicToken").asString()
    }
    private fun status(token: String): String = json(get("/api/v1/appointments/$token")).path("status").asString()
    private fun count(table: String): Int = checkNotNull(jdbc.queryForObject("select count(*) from $table", Int::class.java))
    private fun json(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())
    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri(path))
        if (path.startsWith("/api/v1/appointments/")) request.header("Authorization", "Bearer " + tokenFor(owners[path.substringAfterLast('/')] ?: "3001234567"))
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun post(path: String, body: String, access: String = tokenFor("3001234567")): HttpResponse<String> = client.send(HttpRequest.newBuilder(uri(path))
        .header("Authorization", "Bearer $access").header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    private fun tokenFor(phone: String, name: String = "Synthetic Customer"): String {
        val user = identities.findOrCreateUser(if (phone.startsWith("+")) phone else "+57$phone", clock.instant())
        identities.updateName(user.id, name)
        return accessTokens.issue(user.copy(name = name), emptyList())
    }
    private fun uri(path: String): URI = URI("http://localhost:$serverPort$path")

    private fun create(slug: String = "shop-a", b: UUID = barber, s: UUID = service, time: String = "09:00",
        phone: String = "3001234567", name: String = "Synthetic Customer"): HttpResponse<String> {
        val start = if (time.contains('T')) time else "2026-10-19T$time:00-05:00"
        val response = post("/api/v1/barbershops/$slug/appointments", mapper.writeValueAsString(mapOf(
            "barberId" to b, "serviceId" to s, "startsAt" to start,
        )), tokenFor(phone, name))
        if (response.statusCode() == 201) owners[json(response).path("publicToken").asString()] = phone
        return response
    }

    private fun parallel(attempts: List<() -> HttpResponse<String>>): List<HttpResponse<String>> {
        val gate = CountDownLatch(1)
        return Executors.newFixedThreadPool(attempts.size).use { executor ->
            val futures = attempts.map { attempt -> executor.submit(Callable { gate.await(10, TimeUnit.SECONDS); attempt() }) }
            gate.countDown()
            futures.map { it.get(30, TimeUnit.SECONDS) }
        }
    }
}

class MutableTestClock : Clock() {
    private val current = AtomicReference(Instant.EPOCH)
    fun set(instant: Instant) { current.set(instant) }
    override fun instant(): Instant = current.get()
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
}

class ControlledReferences : PaymentReferences {
    val queue = ConcurrentLinkedQueue<String>()
    override fun next(): String = queue.poll() ?: BookingCodes.paymentReference()
}

@TestConfiguration(proxyBeanMethods = false)
class FixedBookingTestConfiguration {
    @Bean @Primary fun fixedClock(): MutableTestClock = MutableTestClock()
    @Bean @Primary fun controlledReferences(): ControlledReferences = ControlledReferences()
}
