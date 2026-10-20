package com.geovannycode.corteya.identity

import com.geovannycode.corteya.PostgresIntegrationTest
import com.geovannycode.corteya.RecordingOtpSender
import com.geovannycode.corteya.booking.FixedBookingTestConfiguration
import com.geovannycode.corteya.booking.MutableTestClock
import com.geovannycode.corteya.identity.application.IdentityStore
import com.geovannycode.corteya.identity.infrastructure.security.JwtConfiguration
import com.geovannycode.corteya.identity.infrastructure.security.SigningKey
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.*
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class AuthenticationIntegrationTests : PostgresIntegrationTest(web = true) {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mapper: JsonMapper
    @Autowired private lateinit var clock: MutableTestClock
    @Autowired private lateinit var sender: RecordingOtpSender
    @Autowired private lateinit var encoder: JwtEncoder
    @Autowired private lateinit var signingKey: SigningKey
    @Autowired private lateinit var identities: IdentityStore

    private val client = HttpClient.newHttpClient()
    private val now = Instant.parse("2026-10-19T13:00:00Z")
    private val shop = UUID.randomUUID()
    private val otherShop = UUID.randomUUID()
    private val barber = UUID.randomUUID()
    private val secondBarber = UUID.randomUUID()
    private val otherBarber = UUID.randomUUID()
    private val service = UUID.randomUUID()
    private val otherService = UUID.randomUUID()

    override fun customize(builder: SpringApplicationBuilder): SpringApplicationBuilder = builder.sources(FixedBookingTestConfiguration::class.java)

    @BeforeEach
    fun fixtures() {
        clock.set(now)
        sender.codes.clear()
        jdbc.execute("TRUNCATE app_user CASCADE")
        jdbc.execute("TRUNCATE barbershop CASCADE")
        jdbc.execute("TRUNCATE otp_challenge")
        listOf(shop to "shop-a", otherShop to "shop-b").forEach { (id, slug) ->
            jdbc.update("insert into barbershop (id, slug, name, primary_color, secondary_color) values (?, ?, 'Piloto', '#112233', '#445566')", id, slug)
        }
        listOf(barber to shop, secondBarber to shop, otherBarber to otherShop).forEach { (id, tenant) ->
            jdbc.update("insert into barber (id, barbershop_id, name) values (?, ?, 'Barbero')", id, tenant)
            jdbc.update("insert into working_hours (id, barbershop_id, barber_id, day_of_week, start_time, end_time) values (?, ?, ?, 1, '09:00', '20:00')", UUID.randomUUID(), tenant, id)
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
    fun `complete OTP login access and refresh flow stores only secret hashes and signs RS256`() {
        val response = otpRequest("3001234567")
        assertEquals(200, response.statusCode(), response.body())
        val code = checkNotNull(sender.codes["+573001234567"])
        assertTrue(code.matches(Regex("[0-9]{6}")))
        val hash = jdbc.queryForObject("select code_hash from otp_challenge", String::class.java)
        assertEquals(64, hash?.length)
        assertNotEquals(code, hash)
        assertEquals(300L, jdbc.queryForObject("select extract(epoch from (expires_at-created_at))::bigint from otp_challenge", Long::class.java))
        val pair = verify("+573001234567", code)
        assertEquals(200, pair.statusCode(), pair.body())
        val access = body(pair).path("accessToken").asString()
        val refresh = body(pair).path("refreshToken").asString()
        val me = send("GET", "/api/v1/me", token = access)
        assertEquals(200, me.statusCode(), me.body())
        assertEquals("+573001234567", body(me).path("phone").asString())
        assertTrue(body(me).path("name").isNull)
        assertFalse(me.headers().allValues("Set-Cookie").any { it.contains("JSESSIONID") })
        val header = mapper.readTree(Base64.getUrlDecoder().decode(access.substringBefore('.')))
        val claims = mapper.readTree(Base64.getUrlDecoder().decode(access.split('.')[1]))
        assertEquals("RS256", header.path("alg").asString())
        assertTrue(header.path("kid").asString().isNotBlank())
        assertEquals(900, claims.path("exp").asLong() - claims.path("iat").asLong())
        assertTrue(claims.path("roles").any { it.asString() == "CUSTOMER" })
        assertTrue(claims.path("memberships").isArray)
        assertEquals(43, refresh.length)
        assertNotEquals(refresh, jdbc.queryForObject("select token_hash from refresh_token", String::class.java))
        assertEquals(30L * 24 * 3600, jdbc.queryForObject("select extract(epoch from (expires_at-created_at))::bigint from refresh_token", Long::class.java))
        val rotated = refresh(refresh)
        assertEquals(200, rotated.statusCode(), rotated.body())
        assertNotEquals(refresh, body(rotated).path("refreshToken").asString())
        assertEquals(1, count("refresh_token", "revoked_at is not null and replaced_by is not null"))
        assertEquals(1, count("refresh_token", "revoked_at is null"))
    }

    @Test
    fun `five wrong codes persist attempt counts and invalidate the challenge`() {
        otpRequest("3001234567")
        val correct = checkNotNull(sender.codes["+573001234567"])
        val wrong = if (correct == "000000") "999999" else "000000"
        repeat(5) { assertEquals(401, verify("3001234567", wrong).statusCode()) }
        assertEquals(5, jdbc.queryForObject("select attempts from otp_challenge", Int::class.java))
        assertEquals(1, count("otp_challenge", "consumed_at is not null"))
        assertEquals(401, verify("3001234567", correct).statusCode())
        assertEquals(0, count("app_user"))
        assertEquals(0, count("refresh_token"))
    }

    @Test
    fun `expired OTP fails at five-minute boundary and a consumed code cannot be reused`() {
        otpRequest("3001234567")
        val code = checkNotNull(sender.codes["+573001234567"])
        clock.set(now.plusSeconds(300))
        assertEquals(401, verify("3001234567", code).statusCode())
        assertEquals(200, otpRequest("3001234567").statusCode())
        val next = checkNotNull(sender.codes["+573001234567"])
        assertEquals(200, verify("3001234567", next).statusCode())
        assertEquals(401, verify("3001234567", next).statusCode())
        assertEquals(1, count("app_user"))
        assertEquals(1, count("refresh_token"))
    }

    @Test
    fun `OTP concurrent verification succeeds exactly once`() {
        otpRequest("3001234567")
        val code = checkNotNull(sender.codes["+573001234567"])
        val attempts = parallel(List(5) { { verify("3001234567", code) } })
        assertEquals(1, attempts.count { it.statusCode() == 200 })
        assertEquals(4, attempts.count { it.statusCode() == 401 })
        assertEquals(1, count("refresh_token"))
    }

    @Test
    fun `OTP cooldown and rolling hourly phone limit have exact boundaries`() {
        assertEquals(200, otpRequest("3001234567").statusCode())
        clock.set(now.plusSeconds(59))
        assertEquals(429, otpRequest("+573001234567").statusCode())
        for (i in 1..4) {
            clock.set(now.plusSeconds(i * 60L))
            assertEquals(200, otpRequest("3001234567").statusCode())
        }
        clock.set(now.plusSeconds(300))
        assertEquals(429, otpRequest("3001234567").statusCode())
        assertEquals(5, count("otp_challenge"))
        clock.set(now.plusSeconds(3600))
        assertEquals(200, otpRequest("3001234567").statusCode())
    }

    @Test
    fun `IP limit applies across phones and ignores caller-controlled forwarded headers`() {
        repeat(10) { i ->
            val response = send("POST", "/api/v1/auth/otp/request", mapOf("phone" to "30000000${i.toString().padStart(2, '0')}"), forwarded = "203.0.113.$i")
            assertEquals(200, response.statusCode(), response.body())
        }
        assertEquals(429, otpRequest("3009999999").statusCode())
        assertEquals(1, jdbc.queryForObject("select count(distinct request_ip) from otp_challenge", Int::class.java))
        assertFalse(checkNotNull(jdbc.queryForObject("select request_ip from otp_challenge limit 1", String::class.java)).startsWith("203.0.113."))
    }

    @Test
    fun `concurrent OTP requests cannot bypass table-backed rate limits`() {
        val responses = parallel(List(10) { { otpRequest("3001234567") } })
        assertEquals(1, responses.count { it.statusCode() == 200 })
        assertEquals(9, responses.count { it.statusCode() == 429 })
        assertEquals(1, count("otp_challenge"))
    }

    @Test
    fun `OTP request response is identical for new and registered users`() {
        identities.findOrCreateUser("+573001234567", now)
        val existing = otpRequest("3001234567")
        val new = otpRequest("3007654321")
        assertEquals(existing.statusCode(), new.statusCode())
        assertEquals(existing.body(), new.body())
        assertTrue(listOf("3001234567", "3007654321", "accessToken", "code").none { new.body().contains(it) })
    }

    @Test
    fun `refresh replay revokes its entire family but not a separate login family`() {
        val first = login("3001234567")
        val rotated = refresh(first.refresh)
        val child = body(rotated).path("refreshToken").asString()
        clock.set(now.plusSeconds(60))
        val second = login("3001234567")
        assertEquals(401, refresh(first.refresh).statusCode())
        assertEquals(401, refresh(child).statusCode())
        assertEquals(200, refresh(second.refresh).statusCode())
        assertEquals(2, jdbc.queryForObject("select count(distinct family_id) from refresh_token", Int::class.java))
    }

    @Test
    fun `concurrent reuse of a refresh token revokes the winning replacement too`() {
        val session = login("3001234567")
        val responses = parallel(List(2) { { refresh(session.refresh) } })
        assertEquals(1, responses.count { it.statusCode() == 200 })
        assertEquals(1, responses.count { it.statusCode() == 401 })
        val child = body(responses.single { it.statusCode() == 200 }).path("refreshToken").asString()
        assertEquals(401, refresh(child).statusCode())
        assertEquals(0, count("refresh_token", "revoked_at is null"))
    }

    @Test
    fun `logout is idempotent and makes the refresh family unusable`() {
        val session = login("3001234567")
        repeat(2) { assertEquals(204, send("POST", "/api/v1/auth/logout", mapOf("refreshToken" to session.refresh)).statusCode()) }
        assertEquals(401, refresh(session.refresh).statusCode())
        assertEquals(204, send("POST", "/api/v1/auth/logout", mapOf("refreshToken" to "unknown")).statusCode())
    }

    @Test
    fun `JWT expiry tampering missing customer role and absent session return safe errors`() {
        val session = login("3001234567")
        assertEquals(401, send("GET", "/api/v1/me").statusCode())
        assertEquals(401, send("POST", "/api/v1/barbershops/shop-a/appointments", bookingBody()).statusCode())
        val parts = session.access.split('.')
        val tampered = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"${UUID.randomUUID()}\"}".toByteArray()) + "." + parts[2]
        val invalid = send("GET", "/api/v1/me", token = tampered)
        assertEquals(401, invalid.statusCode())
        assertTrue(invalid.headers().firstValue("Content-Type").orElse("").contains("application/problem+json"))
        assertFalse(invalid.body().contains("Exception"))
        val claims = JwtClaimsSet.builder().issuer(JwtConfiguration.ISSUER).subject(session.id.toString())
            .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(900)).claim("roles", listOf("OWNER")).build()
        val ownerOnly = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(signingKey.kid).build(), claims)).tokenValue
        assertEquals(403, send("POST", "/api/v1/barbershops/shop-a/appointments", bookingBody(), ownerOnly).statusCode())
        clock.set(now.plusSeconds(900))
        assertEquals(401, send("GET", "/api/v1/me", token = session.access).statusCode())
        assertEquals(200, send("GET", "/api/v1/barbershops/shop-a/services").statusCode())
        clock.set(now.plusSeconds(30L * 24 * 3600))
        assertEquals(401, refresh(session.refresh).statusCode())
        assertEquals(403, send("POST", "/outside-api", mapOf("x" to true)).statusCode()) // CSRF remains enabled here
    }

    @Test
    fun `booking identity comes from token requires name and cannot be replaced by body fields`() {
        val session = login("3001234567", null)
        val incomplete = book(session)
        assertEquals(422, incomplete.statusCode(), incomplete.body())
        assertTrue(incomplete.body().contains("Completa tu nombre"))
        assertEquals(0, count("customer"))
        assertEquals(204, send("PATCH", "/api/v1/me", mapOf("name" to "  Nombre elegido  "), session.access).statusCode())
        val response = send("POST", "/api/v1/barbershops/shop-a/appointments", bookingBody() + mapOf("customerName" to "Impostor", "customerPhone" to "3009999999"), session.access)
        assertEquals(201, response.statusCode(), response.body())
        assertEquals("Nombre elegido", jdbc.queryForObject("select name from customer", String::class.java))
        assertEquals("+573001234567", jdbc.queryForObject("select phone from customer", String::class.java))
        assertEquals(session.id, jdbc.queryForObject("select user_id from customer", UUID::class.java))
    }

    @Test
    fun `another customer cannot read appointment and unrelated staff cannot use another shop agenda`() {
        val owner = login("3001234567")
        val stranger = login("3007654321")
        val created = book(owner)
        assertEquals(201, created.statusCode(), created.body())
        val token = body(created).path("publicToken").asString()
        assertEquals(200, send("GET", "/api/v1/appointments/$token", token = owner.access).statusCode())
        val denied = send("GET", "/api/v1/appointments/$token", token = stranger.access)
        assertEquals(404, denied.statusCode())
        val unknown = send("GET", "/api/v1/appointments/${"a".repeat(32)}", token = stranger.access)
        assertEquals(body(unknown).path("detail").asString(), body(denied).path("detail").asString())
        assertEquals(401, send("GET", "/api/v1/appointments/$token").statusCode())
        assertEquals(404, send("GET", "/api/v1/barbershops/shop-a/staff/agenda?date=2026-10-19", token = stranger.access).statusCode())
    }

    @Test
    fun `BARBER sees only assigned agenda OWNER sees all and live membership revocation takes effect`() {
        val customer = login("3001234567")
        val first = book(customer)
        val second = book(customer, b = secondBarber)
        assertEquals(201, first.statusCode(), first.body())
        assertEquals(201, second.statusCode(), second.body())
        val barberUser = staff("3001111111", "BARBER", barber)
        val owner = staff("3002222222", "OWNER", null)
        val route = "/api/v1/barbershops/shop-a/staff/agenda?date=2026-10-19"
        val ownAgenda = send("GET", route, token = barberUser.access)
        assertEquals(200, ownAgenda.statusCode(), ownAgenda.body())
        assertEquals(1, body(ownAgenda).size())
        assertEquals(barber.toString(), body(ownAgenda).first().path("barberId").asString())
        assertEquals(404, send("GET", "$route&barberId=$secondBarber", token = barberUser.access).statusCode())
        assertEquals(404, send("GET", "/api/v1/barbershops/shop-b/staff/agenda?date=2026-10-19", token = barberUser.access).statusCode())
        assertEquals(2, body(send("GET", route, token = owner.access)).size())
        assertEquals(1, body(send("GET", "$route&barberId=$secondBarber", token = owner.access)).size())
        // The public-token endpoint explicitly permits any staff of the owning shop.
        assertEquals(200, send("GET", "/api/v1/appointments/${body(second).path("publicToken").asString()}", token = barberUser.access).statusCode())
        val jwtClaims = mapper.readTree(Base64.getUrlDecoder().decode(barberUser.access.split('.')[1]))
        assertTrue(jwtClaims.path("roles").any { it.asString() == "BARBER" })
        assertEquals(shop.toString(), jwtClaims.path("memberships").first().path("barbershopId").asString())
        jdbc.update("delete from staff_membership where user_id = ? and barbershop_id = ?", owner.id, shop)
        assertEquals(404, send("GET", route, token = owner.access).statusCode())
    }

    @Test
    fun `my appointments contains only owned rows grouped into upcoming and past`() {
        val me = login("3001234567")
        val other = login("3007654321")
        val old = book(me)
        assertEquals(201, book(me, time = "10:00").statusCode())
        assertEquals(201, book(other, b = secondBarber).statusCode())
        jdbc.update("update appointment set starts_at = starts_at - interval '1 day', ends_at = ends_at - interval '1 day' where barbershop_id = ? and public_token = ?", shop, body(old).path("publicToken").asString())
        val mine = send("GET", "/api/v1/me/appointments", token = me.access)
        assertEquals(200, mine.statusCode(), mine.body())
        assertEquals(1, body(mine).path("upcoming").size())
        assertEquals(1, body(mine).path("past").size())
        assertEquals(0, body(send("GET", "/api/v1/me/appointments", token = other.access)).path("past").size())
    }

    @Test
    fun `delete anonymizes across shops revokes refresh and access while preserving appointments`() {
        val me = login("3001234567")
        val created = book(me)
        assertEquals(201, created.statusCode())
        assertEquals(201, book(me, slug = "shop-b", b = otherBarber, s = otherService).statusCode())
        assertEquals(204, send("DELETE", "/api/v1/me", token = me.access).statusCode())
        assertEquals(0, count("otp_challenge"))
        assertEquals(2, count("appointment"))
        assertEquals(2, count("customer", "name = 'Cliente eliminado' and phone is null"))
        assertEquals(1, count("app_user", "name = 'Cliente eliminado' and phone is null and deleted_at is not null"))
        assertEquals(0, count("refresh_token", "revoked_at is null"))
        assertEquals(401, refresh(me.refresh).statusCode())
        assertEquals(401, send("GET", "/api/v1/me", token = me.access).statusCode())
        clock.set(now.plusSeconds(60))
        val new = login("3001234567")
        assertNotEquals(me.id, new.id)
        assertEquals(404, send("GET", "/api/v1/appointments/${body(created).path("publicToken").asString()}", token = new.access).statusCode())
        assertEquals(0, body(send("GET", "/api/v1/me/appointments", token = new.access)).path("upcoming").size())
    }

    @Test
    fun `verified phone safely links existing manual customer history`() {
        val customerId = UUID.randomUUID()
        jdbc.update("insert into customer (id, barbershop_id, name, phone) values (?, ?, 'Manual', '+573001234567')", customerId, shop)
        jdbc.update("""insert into appointment (id, barbershop_id, barber_id, service_id, customer_id, starts_at, ends_at, status, price_cop, deposit_cop, payment_reference, source)
            values (?, ?, ?, ?, ?, '2026-10-19 14:00:00+00', '2026-10-19 14:30:00+00', 'CONFIRMED', 38000, 12000, 'R-ABCD', 'MANUAL')""", UUID.randomUUID(), shop, barber, service, customerId)
        val me = login("3001234567")
        assertEquals(me.id, jdbc.queryForObject("select user_id from customer where barbershop_id = ? and id = ?", UUID::class.java, shop, customerId))
        assertEquals(1, body(send("GET", "/api/v1/me/appointments", token = me.access)).path("upcoming").size())
    }

    @Test
    fun `concurrent account deletion never leaves recreated personal data from booking requests`() {
        val me = login("3001234567")
        val actions = (9..13).map { hour -> { book(me, time = "$hour:00".padStart(5, '0')) } } +
            listOf({ send("DELETE", "/api/v1/me", token = me.access) })
        val outcomes = parallel(actions)
        assertEquals(1, outcomes.count { it.statusCode() == 204 })
        assertTrue(outcomes.all { it.statusCode() in listOf(201, 204, 401, 409) }, outcomes.joinToString { it.body() })
        assertEquals(0, count("customer", "phone is not null or name <> 'Cliente eliminado'"))
        assertEquals(0, count("app_user", "deleted_at is null"))
        assertEquals(0, count("refresh_token", "revoked_at is null"))
    }

    @Test
    fun `OpenAPI documents bearer auth and the booking body no longer contains customer identity`() {
        val spec = send("GET", "/v3/api-docs")
        assertEquals(200, spec.statusCode(), spec.body())
        val document = body(spec)
        val scheme = document.path("components").path("securitySchemes").path("bearerAuth")
        assertEquals("http", scheme.path("type").asString())
        assertEquals("bearer", scheme.path("scheme").asString())
        val booking = document.path("components").path("schemas").path("CreateAppointmentRequest").path("properties")
        assertEquals(3, booking.size())
        assertTrue(booking.path("customerName").isMissingNode)
        assertTrue(booking.path("customerPhone").isMissingNode)
        val operation = document.path("paths").path("/api/v1/barbershops/{slug}/appointments").path("post")
        assertEquals(1, operation.path("parameters").size()) // slug only, not the injected JWT principal
        assertTrue(operation.path("security").first().path("bearerAuth").isArray)
        assertFalse(document.path("paths").path("/api/v1/auth/otp/verify").path("post").isMissingNode)
        assertFalse(document.path("paths").path("/api/v1/me").path("delete").isMissingNode)
    }

    private data class Session(val id: UUID, val access: String, val refresh: String)
    private fun login(phone: String, name: String? = "Cliente de prueba"): Session {
        val requested = otpRequest(phone)
        assertEquals(200, requested.statusCode(), requested.body())
        val normalized = if (phone.startsWith("+")) phone else "+57$phone"
        val verified = verify(phone, checkNotNull(sender.codes[normalized]))
        assertEquals(200, verified.statusCode(), verified.body())
        val access = body(verified).path("accessToken").asString()
        if (name != null) assertEquals(204, send("PATCH", "/api/v1/me", mapOf("name" to name), access).statusCode())
        val id = UUID.fromString(body(send("GET", "/api/v1/me", token = access)).path("id").asString())
        return Session(id, access, body(verified).path("refreshToken").asString())
    }
    private fun staff(phone: String, role: String, b: UUID?): Session {
        val me = login(phone)
        jdbc.update("insert into staff_membership (id, user_id, barbershop_id, role, barber_id) values (?, ?, ?, ?, ?)", UUID.randomUUID(), me.id, shop, role, b)
        val rotated = refresh(me.refresh)
        assertEquals(200, rotated.statusCode(), rotated.body())
        return me.copy(access = body(rotated).path("accessToken").asString(), refresh = body(rotated).path("refreshToken").asString())
    }
    private fun bookingBody(b: UUID = barber, s: UUID = service, time: String = "09:00") = mapOf("barberId" to b, "serviceId" to s, "startsAt" to "2026-10-19T$time:00-05:00")
    private fun book(session: Session, slug: String = "shop-a", b: UUID = barber, s: UUID = service, time: String = "09:00") = send("POST", "/api/v1/barbershops/$slug/appointments", bookingBody(b, s, time), session.access)
    private fun otpRequest(phone: String) = send("POST", "/api/v1/auth/otp/request", mapOf("phone" to phone))
    private fun verify(phone: String, code: String) = send("POST", "/api/v1/auth/otp/verify", mapOf("phone" to phone, "code" to code))
    private fun refresh(token: String) = send("POST", "/api/v1/auth/refresh", mapOf("refreshToken" to token))
    private fun body(response: HttpResponse<String>): JsonNode = mapper.readTree(response.body())
    private fun count(table: String, condition: String = "true"): Int = checkNotNull(jdbc.queryForObject("select count(*) from $table where $condition", Int::class.java))
    private fun send(method: String, path: String, data: Any? = null, token: String? = null, forwarded: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$serverPort$path"))
        token?.let { request.header("Authorization", "Bearer $it") }
        forwarded?.let { request.header("X-Forwarded-For", it) }
        val payload = data?.let { request.header("Content-Type", "application/json"); HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(it)) } ?: HttpRequest.BodyPublishers.noBody()
        return client.send(request.method(method, payload).build(), HttpResponse.BodyHandlers.ofString())
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
