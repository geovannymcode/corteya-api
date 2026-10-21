package com.geovannycode.corteya.booking

import com.geovannycode.corteya.PostgresIntegrationTest
import com.geovannycode.corteya.booking.dto.PaymentSettingsRequest
import com.geovannycode.corteya.booking.internal.HoldExpirationJob
import com.geovannycode.corteya.identity.AppUser
import com.geovannycode.corteya.identity.internal.AccessTokens
import com.geovannycode.corteya.identity.internal.IdentityRepository
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.AfterAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.env.MapPropertySource
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.ResponseCreator
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

class PaymentsIntegrationTests : PostgresIntegrationTest(web = true) {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var clock: MutableTestClock
    @Autowired private lateinit var mapper: JsonMapper
    @Autowired private lateinit var identities: IdentityRepository
    @Autowired private lateinit var tokens: AccessTokens
    @Autowired private lateinit var settings: PaymentSettingsService
    @Autowired private lateinit var job: HoldExpirationJob
    @Autowired @Qualifier("wompiRestClientBuilder") private lateinit var wompi: RestClient.Builder
    private lateinit var provider: MockRestServiceServer
    private val client = HttpClient.newHttpClient()
    private val shop = UUID.randomUUID()
    private val otherShop = UUID.randomUUID()
    private val barber = UUID.randomUUID()
    private val otherBarber = UUID.randomUUID()
    private val secondBarber = UUID.randomUUID()
    private val service = UUID.randomUUID()
    private val otherService = UUID.randomUUID()
    private lateinit var customer: AppUser
    private lateinit var owner: AppUser
    private lateinit var staff: AppUser
    private lateinit var wrongStaff: AppUser
    private val now = Instant.parse("2026-10-19T13:00:00Z")

    override fun customize(builder: SpringApplicationBuilder): SpringApplicationBuilder =
        builder.sources(FixedBookingTestConfiguration::class.java).initializers({ context ->
            context.environment.propertySources.addFirst(MapPropertySource("payment-test", mapOf(
                "payments.secrets.key" to java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 }),
                // These must be ignored in this non-dev profile.
                "WOMPI_SANDBOX_PUBLIC_KEY" to "pub_test_Ignored", "WOMPI_SANDBOX_PRIVATE_KEY" to "prv_test_Ignored",
                "WOMPI_SANDBOX_INTEGRITY_SECRET" to "test_integrity_Ignored", "WOMPI_SANDBOX_EVENTS_SECRET" to "test_events_Ignored")))
        })

    @BeforeEach
    fun fixtures() {
        resetData(listOf(shop, otherShop))
        clock.set(now)
        provider = MockRestServiceServer.bindTo(wompi).build()
        customer = user("3000000100")
        owner = user("3000000101")
        staff = user("3000000102")
        wrongStaff = user("3000000103")
        listOf(shop to "pay-a", otherShop to "pay-b").forEach { (id, slug) -> inShop(id) {
            jdbc.update("insert into barbershop (id, slug, name, primary_color, secondary_color) values (?, ?, 'Pagos', '#112233', '#445566')", id, slug)
        } }
        listOf(barber to shop, secondBarber to shop, otherBarber to otherShop).forEach { (id, tenant) -> inShop(tenant) {
            jdbc.update("insert into barber (id, barbershop_id, name) values (?, ?, 'Barbero')", id, tenant)
            jdbc.update("insert into working_hours (id, barbershop_id, barber_id, day_of_week, start_time, end_time) values (?, ?, ?, 1, '09:00', '20:00')", UUID.randomUUID(), tenant, id)
        } }
        listOf(service to shop, otherService to otherShop).forEach { (id, tenant) -> inShop(tenant) {
            jdbc.update("insert into service (id, barbershop_id, name, duration_minutes, price_cop) values (?, ?, 'Corte', 30, 38000)", id, tenant)
        } }
        listOf(Triple(shop, barber, service), Triple(shop, secondBarber, service), Triple(otherShop, otherBarber, otherService)).forEach { (tenant, b, s) -> inShop(tenant) {
            jdbc.update("insert into barber_service (id, barbershop_id, barber_id, service_id) values (?, ?, ?, ?)", UUID.randomUUID(), tenant, b, s)
        } }
        membership(owner, shop, "OWNER")
        membership(staff, shop, "BARBER", barber)
        membership(wrongStaff, shop, "BARBER", secondBarber)
    }

    @AfterEach fun verifyProvider() { provider.verify() }
    @AfterAll fun closeClient() { client.close() }

    @Test
    fun `NONE confirms immediately without a deposit or hold and sandbox variables are ignored outside dev`() {
        assertEquals(0, jdbc.queryForObject("select count(*) from barbershop_payment_settings", Int::class.java))
        inShop(shop) { settings.configure(shop, PaymentSettingsRequest(DepositMode.NONE)) }
        val created = create()
        assertEquals("CONFIRMED", created.path("status").asString())
        assertEquals(0L, created.path("depositCop").asLong())
        assertTrue(created.path("holdExpiresAt").isNull)
        assertTrue(created.path("paymentInstructions").isNull)
        inShop(shop) { assertEquals(0, jdbc.queryForObject("select count(*) from deposit_payment where barbershop_id = ?", Int::class.java, shop)) }
    }

    @Test
    fun `BreB defaults to 30 minutes supplies direct instructions and assigned staff can confirm idempotently`() {
        breb()
        val created = create()
        assertEquals("2026-10-19T08:30:00-05:00", created.path("holdExpiresAt").asString())
        assertEquals("@TEST", created.path("paymentInstructions").path("brebKey").asString())
        assertEquals(12000L, created.path("paymentInstructions").path("amountCop").asLong())
        val id = appointmentId(created)
        assertEquals(200, http("GET", "/api/v1/barbershops/pay-a/staff/deposits", staff).statusCode())
        assertEquals(204, http("POST", "/api/v1/barbershops/pay-a/staff/appointments/$id/deposit/confirm", staff).statusCode())
        assertEquals(204, http("POST", "/api/v1/barbershops/pay-a/staff/appointments/$id/deposit/confirm", staff).statusCode())
        assertEquals("CONFIRMED", appointmentStatus(id))
        assertEquals("APPROVED", depositStatus(id))
        inShop(shop) {
            assertEquals(staff.id, jdbc.queryForObject("select confirmed_by_user_id from deposit_payment where barbershop_id = ?", UUID::class.java, shop))
            assertEquals(2, jdbc.queryForObject("select count(*) from deposit_event where barbershop_id = ?", Int::class.java, shop))
        }
    }

    @Test
    fun `manual confirmation and settings are hidden from wrong barber customer and other shop`() {
        breb()
        val id = appointmentId(create())
        assertEquals(404, http("POST", "/api/v1/barbershops/pay-a/staff/appointments/$id/deposit/confirm", wrongStaff).statusCode())
        assertEquals(404, http("POST", "/api/v1/barbershops/pay-b/staff/appointments/$id/deposit/confirm", staff).statusCode())
        assertEquals(404, http("GET", "/api/v1/barbershops/pay-a/staff/payment-settings", staff).statusCode())
        assertEquals(404, http("GET", "/api/v1/barbershops/pay-a/staff/deposits", customer).statusCode())
        assertEquals("PENDING_PAYMENT", appointmentStatus(id))
        assertEquals(0, mapper.readTree(http("GET", "/api/v1/barbershops/pay-a/staff/deposits", wrongStaff).body()).size())
    }

    @Test
    fun `manual rejection cancels the appointment and releases the slot`() {
        breb()
        val id = appointmentId(create())
        assertEquals(204, http("POST", "/api/v1/barbershops/pay-a/staff/appointments/$id/deposit/reject", staff).statusCode())
        assertEquals("CANCELLED", appointmentStatus(id))
        assertEquals("DECLINED", depositStatus(id))
        assertEquals("PENDING_PAYMENT", create().path("status").asString())
    }

    @Test
    fun `late approval recovers an expired appointment when its slot is free`() {
        breb()
        val id = appointmentId(create())
        clock.set(now.plusSeconds(31 * 60))
        job.expire()
        assertEquals("EXPIRED", appointmentStatus(id))
        assertEquals("EXPIRED", depositStatus(id))
        assertEquals(204, http("POST", "/api/v1/barbershops/pay-a/staff/appointments/$id/deposit/confirm", staff).statusCode())
        assertEquals("CONFIRMED", appointmentStatus(id))
        assertEquals("APPROVED", depositStatus(id))
    }

    @Test
    fun `late approval preserves payment as LATE_PAYMENT when a replacement occupies the slot`() {
        breb()
        val first = appointmentId(create())
        clock.set(now.plusSeconds(31 * 60))
        val replacement = appointmentId(create()) // expires the old hold even without the job
        assertEquals("EXPIRED", depositStatus(first))
        assertEquals(204, http("POST", "/api/v1/barbershops/pay-a/staff/appointments/$first/deposit/confirm", staff).statusCode())
        assertEquals("EXPIRED", appointmentStatus(first))
        assertEquals("LATE_PAYMENT", depositStatus(first))
        assertEquals("PENDING_PAYMENT", appointmentStatus(replacement))
        assertEquals(204, http("POST", "/api/v1/barbershops/pay-a/staff/appointments/$first/deposit/confirm", staff).statusCode())
        inShop(shop) { assertEquals(1, jdbc.queryForObject("select count(*) from deposit_event where barbershop_id = ? and to_status = 'LATE_PAYMENT'", Int::class.java, shop)) }
    }

    @Test
    fun `Wompi stores encrypted secrets returns only configured flags and produces checkout parameters`() {
        configureWompi()
        val response = http("GET", "/api/v1/barbershops/pay-a/staff/payment-settings", owner)
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().contains("configurado"))
        listOf("prv_test_Private", "test_integrity_Integrity", "test_events_Events").forEach { assertFalse(response.body().contains(it)) }
        val created = create()
        val instruction = created.path("paymentInstructions")
        assertEquals(1200000L, instruction.path("amountInCents").asLong())
        assertTrue(instruction.path("reference").asString().endsWith("-1"))
        val params = instruction.path("checkoutParameters")
        assertEquals("pub_test_Public", params.path("public-key").asString())
        assertEquals("2026-10-19T13:15:00.000Z", params.path("expiration-time").asString())
        assertEquals("corteya://appointments/" + created.path("publicToken").asString(), params.path("redirect-url").asString())
        assertFalse(mapper.writeValueAsString(created).contains("test_integrity_Integrity"))
        inShop(shop) {
            val row = jdbc.queryForMap("select wompi_private_key_enc, wompi_integrity_secret_enc, wompi_events_secret_enc from barbershop_payment_settings where barbershop_id = ?", shop)
            assertTrue(row.values.all { it.toString().startsWith("1.") })
            assertTrue(row.values.none { it.toString().contains("Private") || it.toString().contains("Integrity") || it.toString().contains("Events") })
        }
    }

    @Test
    fun `valid signed webhook verifies the remote transaction outside a transaction and is idempotent`() {
        configureWompi()
        val created = create()
        val id = appointmentId(created)
        assertEquals(200, webhook(created).statusCode())
        assertEquals(200, webhook(created).statusCode())
        assertEquals("CONFIRMED", appointmentStatus(id))
        assertEquals("APPROVED", depositStatus(id))
        inShop(shop) { assertEquals(2, jdbc.queryForObject("select count(*) from deposit_event where barbershop_id = ?", Int::class.java, shop)) }
    }

    @Test
    fun `existing Wompi attempts retain their encrypted credentials after environment or mode changes`() {
        configureWompi()
        val created = create()
        val oldWebhook = inShop(shop) { checkNotNull(settings.current(shop).webhookId) }
        inShop(shop) { settings.configure(shop, PaymentSettingsRequest(DepositMode.WOMPI,
            wompiEnvironment = WompiEnvironment.PRODUCTION, wompiPublicKey = "pub_prod_New", wompiPrivateKey = "prv_prod_New",
            wompiIntegritySecret = "prod_integrity_New", wompiEventsSecret = "prod_events_New")) }
        assertNotEquals(oldWebhook, inShop(shop) { settings.current(shop).webhookId })
        assertEquals(200, webhook(created, webhookIdOverride = oldWebhook).statusCode())
        assertEquals("APPROVED", depositStatus(appointmentId(created)))
        inShop(shop) { settings.configure(shop, PaymentSettingsRequest(DepositMode.NONE)) }
        assertEquals(200, webhook(created, webhookIdOverride = oldWebhook).statusCode())
    }

    @ParameterizedTest
    @ValueSource(ints = [404, 408, 429, 500, 502, 503, 504])
    fun `transient Wompi HTTP errors remain retryable without exposing provider data`(status: Int) {
        configureWompi()
        val created = create()
        val failure = withStatus(org.springframework.http.HttpStatus.valueOf(status))
            .body("prv_test_Private https://provider.example/private +573001234567")
        val response = webhook(created, apiResponse = failure)
        assertEquals(503, response.statusCode(), response.body())
        assertEquals("PENDING", depositStatus(appointmentId(created)))
        assertTrue(credentialsError().isNull)
        listOf("prv_test_Private", "provider.example", "+573001234567").forEach { assertFalse(response.body().contains(it)) }
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403])
    fun `invalid credentials return 502 persist an OWNER warning and log only tenant and HTTP code`(status: Int) {
        configureWompi()
        val created = create()
        val logger = org.slf4j.LoggerFactory.getLogger(com.geovannycode.corteya.booking.internal.WompiGateway::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        try {
            val response = webhook(created, apiResponse = withStatus(org.springframework.http.HttpStatus.valueOf(status))
                .body("prv_test_Private https://provider.example/private +573001234567"))
            assertEquals(502, response.statusCode())
            assertEquals("Credenciales de Wompi inválidas", credentialsError().asString())
            assertEquals("PENDING", depositStatus(appointmentId(created)))
            val log = appender.list.single { it.level == ch.qos.logback.classic.Level.ERROR }
            assertEquals("Credenciales Wompi inválidas; barbería=$shop; HTTP=$status", log.formattedMessage)
            assertNull(log.throwableProxy)
            listOf("prv_test_Private", "provider.example", "+573001234567").forEach { assertFalse(response.body().contains(it)) }
        } finally { logger.detachAppender(appender); appender.stop() }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `timeouts and IO failures remain retryable and do not expose exception messages`(timeout: Boolean) {
        configureWompi()
        val created = create()
        val response = webhook(created, apiResponse = ResponseCreator {
            if (timeout) throw java.net.SocketTimeoutException("prv_test_Private https://provider.example/private")
            else throw java.io.IOException("prv_test_Private https://provider.example/private")
        })
        assertEquals(503, response.statusCode())
        assertEquals("PENDING", depositStatus(appointmentId(created)))
        assertTrue(credentialsError().isNull)
        assertFalse(response.body().contains("prv_test_Private"))
        assertFalse(response.body().contains("provider.example"))
    }

    @Test
    fun `successful later query clears the credential warning`() {
        configureWompi()
        val created = create()
        assertEquals(502, webhook(created, apiResponse = withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)).statusCode())
        assertFalse(credentialsError().isNull)
        assertEquals(200, webhook(created).statusCode())
        assertTrue(credentialsError().isNull)
    }

    @Test
    fun `OWNER key update clears the warning but a hold-only update preserves it`() {
        configureWompi()
        val created = create()
        assertEquals(502, webhook(created, apiResponse = withStatus(org.springframework.http.HttpStatus.FORBIDDEN)).statusCode())
        val path = "/api/v1/barbershops/pay-a/staff/payment-settings"
        assertEquals(200, http("PUT", path, owner, PaymentSettingsRequest(DepositMode.WOMPI,
            wompiEnvironment = WompiEnvironment.SANDBOX, holdMinutes = 20)).statusCode())
        assertFalse(credentialsError().isNull)
        assertEquals(200, http("PUT", path, owner, wompiRequest()).statusCode())
        assertTrue(credentialsError().isNull)
    }

    @Test
    fun `old attempt errors cannot mark replacement Wompi credentials invalid`() {
        configureWompi()
        val created = create()
        val oldWebhook = inShop(shop) { checkNotNull(settings.current(shop).webhookId) }
        inShop(shop) { settings.configure(shop, wompiRequest().copy(wompiPublicKey = "pub_test_Replacement", wompiPrivateKey = "prv_test_Replacement")) }
        assertEquals(502, webhook(created, webhookIdOverride = oldWebhook,
            apiResponse = withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)).statusCode())
        assertTrue(credentialsError().isNull)
    }

    @Test
    fun `correcting the private key recovers earlier attempts from the same merchant`() {
        configureWompi()
        val created = create()
        val oldWebhook = inShop(shop) { checkNotNull(settings.current(shop).webhookId) }
        assertEquals(502, webhook(created, apiResponse = withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)).statusCode())
        inShop(shop) { settings.configure(shop, wompiRequest().copy(wompiPrivateKey = "prv_test_Corrected")) }
        assertEquals(200, webhook(created, webhookIdOverride = oldWebhook, apiPrivateKey = "prv_test_Corrected").statusCode())
        assertEquals("APPROVED", depositStatus(appointmentId(created)))
        assertTrue(credentialsError().isNull)
    }

    private fun credentialsError(): JsonNode = mapper.readTree(
        http("GET", "/api/v1/barbershops/pay-a/staff/payment-settings", owner).body()).path("wompiCredentialsError")

    @Test
    fun `invalid webhook checksum returns 401 without contacting Wompi`() {
        configureWompi()
        val created = create()
        assertEquals(401, webhook(created, valid = false).statusCode())
        assertEquals("PENDING_PAYMENT", appointmentStatus(appointmentId(created)))
    }

    @Test
    fun `API amount reference currency or status mismatches never confirm`() {
        configureWompi()
        val created = create()
        listOf(mapOf("amount_in_cents" to 1), mapOf("reference" to "wrong"), mapOf("currency" to "USD"), mapOf("status" to "DECLINED")).forEach { override ->
            assertEquals(400, webhook(created, overrides = override).statusCode())
            assertEquals("PENDING", depositStatus(appointmentId(created)))
        }
    }

    @Test
    fun `authentic foreign sale returns 200 logs only tenant and transaction and changes no rows`() {
        configureWompi()
        val created = create()
        val before = inShop(shop) {
            listOf("appointment", "deposit_payment", "deposit_event", "barbershop_payment_settings").map { table ->
                jdbc.queryForList("select * from $table where barbershop_id = ? order by id", shop)
            }
        }
        val logger = org.slf4j.LoggerFactory.getLogger(WompiWebhookService::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start(); logger.addAppender(it) }
        try {
            assertEquals(200, webhook(created, foreignReference = "venta-persona@example.com").statusCode())
            val log = appender.list.single { it.level == ch.qos.logback.classic.Level.WARN }
            assertEquals("Transacción Wompi ajena a CorteYa; barbería=$shop; transacción=tx-1", log.formattedMessage)
            assertNull(log.throwableProxy)
        } finally { logger.detachAppender(appender); appender.stop() }
        val after = inShop(shop) {
            listOf("appointment", "deposit_payment", "deposit_event", "barbershop_payment_settings").map { table ->
                jdbc.queryForList("select * from $table where barbershop_id = ? order by id", shop)
            }
        }
        assertEquals(before, after)
    }

    @ParameterizedTest
    @ValueSource(strings = ["amount_in_cents", "currency", "status", "reference"])
    fun `own reference discrepancies persist their safe audit reason despite the HTTP error`(field: String) {
        configureWompi()
        val created = create()
        val value: Any = when (field) {
            "amount_in_cents" -> 1L
            "currency" -> "USD"
            "status" -> "DECLINED"
            else -> "unknown-api-reference"
        }
        assertEquals(400, webhook(created, overrides = mapOf(field to value)).statusCode())
        assertEquals("PENDING_PAYMENT", appointmentStatus(appointmentId(created)))
        assertEquals("PENDING", depositStatus(appointmentId(created)))
        val reason = when (field) {
            "amount_in_cents" -> "AMOUNT_MISMATCH"
            "currency" -> "CURRENCY_MISMATCH"
            "status" -> "STATUS_MISMATCH"
            else -> "REFERENCE_MISMATCH"
        }
        inShop(shop) {
            val event = jdbc.queryForMap("select from_status, to_status, source, reason, provider_status from deposit_event where barbershop_id = ? and reason is not null", shop)
            assertEquals("PENDING", event["from_status"])
            assertEquals("PENDING", event["to_status"])
            assertEquals("WEBHOOK", event["source"])
            assertEquals(reason, event["reason"])
            assertNull(event["provider_status"])
        }
    }

    @Test
    fun `declined Wompi attempt can be retried only by the customer while its hold lives`() {
        configureWompi()
        val created = create()
        assertEquals(200, webhook(created, status = "DECLINED").statusCode())
        val path = "/api/v1/appointments/${created.path("publicToken").asString()}/payment-attempts"
        assertEquals(404, http("POST", path, owner).statusCode())
        val response = http("POST", path, customer)
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(mapper.readTree(response.body()).path("reference").asString().endsWith("-2"))
        assertEquals(409, http("POST", path, customer).statusCode())
        assertEquals(200, webhook(created, status = "ERROR", transaction = "tx-2").statusCode())
        clock.set(now.plusSeconds(16 * 60))
        assertEquals(409, http("POST", path, customer).statusCode())
    }

    @Test
    fun `payment tables fail closed without context and reject mismatched writes`() {
        breb()
        create()
        listOf("barbershop_payment_settings", "deposit_payment", "deposit_event").forEach { table ->
            assertEquals(0, jdbc.queryForObject("select count(*) from $table", Int::class.java))
            assertEquals(0, inShop(otherShop) { jdbc.queryForObject("select count(*) from $table", Int::class.java) })
        }
        assertFailsWith<org.springframework.dao.DataAccessException> { inShop(otherShop) {
            jdbc.update("insert into barbershop_payment_settings (id, barbershop_id, deposit_mode, hold_minutes) values (?, ?, 'NONE', 15)", UUID.randomUUID(), shop)
        } }
    }

    @Test
    fun `settings validate environment and BreB key formats and secrets remain write only`() {
        val path = "/api/v1/barbershops/pay-a/staff/payment-settings"
        val valid = wompiRequest()
        val saved = http("PUT", path, owner, valid)
        assertEquals(200, saved.statusCode(), saved.body())
        assertEquals(400, http("PUT", path, owner, valid.copy(wompiPrivateKey = "prv_prod_Wrong")).statusCode())
        assertEquals(200, http("PUT", path, owner, valid.copy(wompiPrivateKey = null, wompiIntegritySecret = null, wompiEventsSecret = null)).statusCode())
        listOf(BrebKeyType.CELULAR to "3101234567", BrebKeyType.ALIAS to "@MiBarberia", BrebKeyType.CORREO to "barbero@example.com", BrebKeyType.DOCUMENTO to "12345678").forEach { (type, key) ->
            assertEquals(200, http("PUT", path, owner, PaymentSettingsRequest(DepositMode.BREB_MANUAL, type, key, "Titular")).statusCode())
            assertEquals(400, http("PUT", path, owner, PaymentSettingsRequest(DepositMode.BREB_MANUAL, type, "!invalid", "Titular")).statusCode())
        }
    }

    @Test
    fun `missing payment configuration rejects booking without leaving any appointment`() {
        val response = http("POST", "/api/v1/barbershops/pay-a/appointments", customer,
            mapOf("barberId" to barber, "serviceId" to service, "startsAt" to "2026-10-19T10:00:00-05:00"))
        assertEquals(409, response.statusCode())
        assertEquals("Esta barbería todavía no recibe reservas en línea", mapper.readTree(response.body()).path("detail").asString())
        val disabled = http("GET", "/api/v1/barbershops/pay-a", null)
        assertFalse(mapper.readTree(disabled.body()).path("bookingEnabled").asBoolean())
        inShop(shop) { settings.configure(shop, PaymentSettingsRequest(DepositMode.NONE)) }
        val enabled = http("GET", "/api/v1/barbershops/pay-a", null)
        assertTrue(mapper.readTree(enabled.body()).path("bookingEnabled").asBoolean())
        inShop(shop) { assertEquals(0, jdbc.queryForObject("select count(*) from appointment where barbershop_id = ?", Int::class.java, shop)) }
    }

    @Test
    fun `late Wompi approval confirms a free slot even before the expiration job`() {
        configureWompi()
        val created = create()
        clock.set(now.plusSeconds(16 * 60))
        assertEquals(200, webhook(created).statusCode())
        assertEquals("CONFIRMED", appointmentStatus(appointmentId(created)))
        assertEquals("APPROVED", depositStatus(appointmentId(created)))
    }

    @Test
    fun `late Wompi approval records funds requiring attention when another customer holds the slot`() {
        configureWompi()
        val created = create()
        clock.set(now.plusSeconds(16 * 60))
        create()
        assertEquals(200, webhook(created).statusCode())
        assertEquals("EXPIRED", appointmentStatus(appointmentId(created)))
        assertEquals("LATE_PAYMENT", depositStatus(appointmentId(created)))
    }

    @Test
    fun `cancelled and completed appointments ignore manual approval`() {
        breb()
        val id = appointmentId(create())
        listOf("CANCELLED", "COMPLETED").forEach { status ->
            inShop(shop) { jdbc.update("update appointment set status = ? where barbershop_id = ? and id = ?", status, shop, id) }
            assertEquals(204, http("POST", "/api/v1/barbershops/pay-a/staff/appointments/$id/deposit/confirm", staff).statusCode())
            assertEquals(status, appointmentStatus(id))
            assertEquals("PENDING", depositStatus(id))
        }
    }

    @Test
    fun `webhook body is bounded even when sent without authentication`() {
        assertEquals(413, http("POST", "/api/v1/webhooks/wompi/opaque", null, " ".repeat(65537)).statusCode())
    }

    private fun breb() { inShop(shop) { settings.configure(shop, PaymentSettingsRequest(DepositMode.BREB_MANUAL, BrebKeyType.ALIAS, "@TEST", "Titular")) } }
    private fun wompiRequest() = PaymentSettingsRequest(DepositMode.WOMPI, wompiEnvironment = WompiEnvironment.SANDBOX,
        wompiPublicKey = "pub_test_Public", wompiPrivateKey = "prv_test_Private", wompiIntegritySecret = "test_integrity_Integrity", wompiEventsSecret = "test_events_Events")
    private fun configureWompi() { inShop(shop) { settings.configure(shop, wompiRequest()) } }
    private fun user(phone: String): AppUser = identities.findOrCreateUser("+57$phone", now).let { identities.updateName(it.id, "Cliente"); it.copy(name = "Cliente") }
    private fun membership(user: AppUser, tenant: UUID, role: String, barberId: UUID? = null) {
        jdbc.update("insert into staff_membership (id, user_id, barbershop_id, role, barber_id) values (?, ?, ?, ?, ?)", UUID.randomUUID(), user.id, tenant, role, barberId)
    }
    private fun create(): JsonNode {
        val response = http("POST", "/api/v1/barbershops/pay-a/appointments", customer,
            mapOf("barberId" to barber, "serviceId" to service, "startsAt" to "2026-10-19T10:00:00-05:00"))
        assertEquals(201, response.statusCode(), response.body())
        return mapper.readTree(response.body())
    }
    private fun appointmentId(created: JsonNode): UUID = inShop(shop) {
        checkNotNull(jdbc.queryForObject("select id from appointment where barbershop_id = ? and public_token = ?", UUID::class.java, shop, created.path("publicToken").asString()))
    }
    private fun appointmentStatus(id: UUID): String = inShop(shop) { checkNotNull(jdbc.queryForObject("select status from appointment where barbershop_id = ? and id = ?", String::class.java, shop, id)) }
    private fun depositStatus(id: UUID): String = inShop(shop) { checkNotNull(jdbc.queryForObject("select status from deposit_payment where barbershop_id = ? and appointment_id = ? order by attempt desc limit 1", String::class.java, shop, id)) }

    private fun webhook(created: JsonNode, valid: Boolean = true, status: String = "APPROVED", transaction: String = "tx-1", overrides: Map<String, Any> = emptyMap(), webhookIdOverride: String? = null, apiResponse: ResponseCreator? = null, foreignReference: String? = null, apiPrivateKey: String = "prv_test_Private"): HttpResponse<String> {
        val reference = inShop(shop) { checkNotNull(jdbc.queryForObject("select reference from deposit_payment where barbershop_id = ? and appointment_id = ? order by attempt desc limit 1", String::class.java, shop, appointmentId(created))) }
        val tx = mapOf("id" to transaction, "status" to status, "amount_in_cents" to 1200000L, "currency" to "COP", "reference" to (foreignReference ?: reference))
        val text = "${transaction}${status}1200000${now.epochSecond}test_events_Events"
        val checksum = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
        val event = mapOf("event" to "transaction.updated", "environment" to "test", "timestamp" to now.epochSecond,
            "data" to mapOf("transaction" to tx), "signature" to mapOf("properties" to listOf("transaction.id", "transaction.status", "transaction.amount_in_cents"), "checksum" to if (valid) checksum else "0".repeat(64)))
        provider.verify()
        provider.reset()
        if (valid) provider.expect(requestTo("https://sandbox.wompi.co/v1/transactions/$transaction"))
            .andExpect(header("Authorization", "Bearer $apiPrivateKey"))
            .andExpect { assertFalse(TransactionSynchronizationManager.isActualTransactionActive()) }
            .andRespond(apiResponse ?: withSuccess(mapper.writeValueAsString(mapOf("data" to (tx + overrides))), MediaType.APPLICATION_JSON))
        val webhookId = webhookIdOverride ?: inShop(shop) { checkNotNull(settings.current(shop).webhookId) }
        return http("POST", "/api/v1/webhooks/wompi/$webhookId", null, event)
    }

    private fun http(method: String, path: String, user: AppUser?, body: Any? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$serverPort$path"))
        if (user != null) request.header("Authorization", "Bearer " + tokens.issue(user, emptyList()))
        if (body != null) request.header("Content-Type", "application/json")
        val content = if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(if (body is String) body else mapper.writeValueAsString(body))
        return client.send(request.method(method, content).build(), HttpResponse.BodyHandlers.ofString())
    }
}
