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
    @Autowired private lateinit var cancellations: CancellationService
    @Autowired private lateinit var tenants: com.geovannycode.corteya.shared.TenantTransaction
    @Autowired private lateinit var cancelledEvents: CancellationEventRecorder
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
        builder.sources(FixedBookingTestConfiguration::class.java, CancellationEventTestConfiguration::class.java).initializers({ context ->
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
        cancelledEvents.events.clear()
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

    @Test
    fun `customer cancellation voids pending payment releases the slot and publishes a committed event`() {
        breb()
        val created = create()
        val id = appointmentId(created)
        val response = cancel(created)
        assertEquals(200, response.statusCode(), response.body())
        assertEquals("CANCELLED", appointmentStatus(id))
        assertEquals("VOIDED", depositStatus(id))
        assertEquals("NONE", mapper.readTree(response.body()).path("refundStatus").asString())
        assertEquals(1, cancelledEvents.events.size)
        assertEquals(id, cancelledEvents.events.single().appointmentId)
        assertEquals(shop, cancelledEvents.events.single().barbershopId)
        assertEquals(now, cancelledEvents.events.single().cancelledAt)
        assertEquals("PENDING_PAYMENT", create().path("status").asString())
    }

    @Test
    fun `confirmed cancellation at the exact policy deadline leaves a pending refund with a clear COP message`() {
        breb()
        cancelHours(6)
        val created = create("2026-10-19T14:00:00-05:00")
        val id = appointmentId(created)
        approve(id)
        val response = cancel(created)
        assertEquals(200, response.statusCode(), response.body())
        val result = mapper.readTree(response.body())
        assertEquals("PENDING", result.path("refundStatus").asString())
        assertEquals(12000L, result.path("refundAmountCop").asLong())
        assertEquals("Cancelaste a tiempo: la barbería te devolverá $12.000", result.path("message").asString())
        assertEquals("PENDING", refundStatus(id))
        assertEquals("APPROVED", depositStatus(id))
        assertEquals(RefundStatus.PENDING, cancelledEvents.events.single().refundStatus)
        assertEquals(12000L, cancelledEvents.events.single().refundAmountCop)
    }

    @Test
    fun `confirmed cancellation after the deadline forfeits the deposit and still frees the slot`() {
        breb()
        cancelHours(6)
        val created = create("2026-10-19T14:00:00-05:00")
        val id = appointmentId(created)
        approve(id)
        clock.set(now.plusSeconds(1))
        val response = cancel(created)
        assertEquals(200, response.statusCode())
        assertEquals("FORFEITED", refundStatus(id))
        assertEquals("CANCELLED", appointmentStatus(id))
        assertEquals("Cancelaste con menos de 6 horas: la señal no es reembolsable", mapper.readTree(response.body()).path("message").asString())
        assertEquals(409, refunded(id, staff).statusCode())
        assertEquals("PENDING_PAYMENT", create("2026-10-19T14:00:00-05:00").path("status").asString())
    }

    @Test
    fun `NONE cancels without a deposit or refund`() {
        inShop(shop) { settings.configure(shop, PaymentSettingsRequest(DepositMode.NONE)) }
        val created = create()
        assertEquals(200, cancel(created).statusCode())
        assertEquals("CANCELLED", appointmentStatus(appointmentId(created)))
        inShop(shop) { assertEquals(0, jdbc.queryForObject("select count(*) from deposit_payment where barbershop_id = ?", Int::class.java, shop)) }
        assertEquals(RefundStatus.NONE, cancelledEvents.events.single().refundStatus)
        assertEquals(0L, cancelledEvents.events.single().refundAmountCop)
    }

    @Test
    fun `only the owning customer may cancel even if another user is staff`() {
        breb()
        val created = create()
        val stranger = user("3000000104")
        listOf(stranger, owner, staff).forEach { assertEquals(404, cancel(created, it).statusCode()) }
        assertEquals(401, http("POST", cancelPath(created), null).statusCode())
        assertEquals("PENDING_PAYMENT", appointmentStatus(appointmentId(created)))
        assertTrue(cancelledEvents.events.isEmpty())
    }

    @Test
    fun `an appointment cannot be cancelled twice`() {
        breb()
        val created = create()
        assertEquals(200, cancel(created).statusCode())
        assertEquals(409, cancel(created).statusCode())
        assertEquals(1, cancelledEvents.events.size)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `pending and confirmed appointments cannot be cancelled at their exact start`(confirmed: Boolean) {
        breb()
        val created = create()
        val id = appointmentId(created)
        if (confirmed) approve(id)
        clock.set(Instant.parse("2026-10-19T15:00:00Z"))
        assertEquals(409, cancel(created).statusCode())
        assertEquals(if (confirmed) "CONFIRMED" else "PENDING_PAYMENT", appointmentStatus(id))
        assertTrue(cancelledEvents.events.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["EXPIRED", "COMPLETED", "NO_SHOW"])
    fun `other appointment states reject cancellation`(status: String) {
        breb()
        val created = create()
        val id = appointmentId(created)
        inShop(shop) { jdbc.update("update appointment set status = ? where barbershop_id = ? and id = ?", status, shop, id) }
        assertEquals(409, cancel(created).statusCode())
        assertEquals(status, appointmentStatus(id))
        assertTrue(cancelledEvents.events.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `assigned BARBER or OWNER records a refund once with actor and audit history`(asOwner: Boolean) {
        breb()
        cancelHours(6)
        val created = create("2026-10-19T14:00:00-05:00")
        val id = appointmentId(created)
        approve(id)
        assertEquals(200, cancel(created).statusCode())
        val actor = if (asOwner) owner else staff
        assertEquals(204, refunded(id, actor).statusCode())
        assertEquals(204, refunded(id, actor).statusCode())
        assertEquals("DONE", refundStatus(id))
        inShop(shop) {
            assertEquals(actor.id, jdbc.queryForObject("select confirmed_by_user_id from deposit_payment where barbershop_id = ? and appointment_id = ?", UUID::class.java, shop, id))
            val event = jdbc.queryForMap("select from_refund_status, to_refund_status, confirmed_by_user_id from deposit_event where barbershop_id = ? and to_refund_status = 'DONE'", shop)
            assertEquals("PENDING", event["from_refund_status"])
            assertEquals("DONE", event["to_refund_status"])
            assertEquals(actor.id, event["confirmed_by_user_id"])
        }
    }

    @Test
    fun `refund confirmation rejects another barber and another tenant and remains protected by RLS`() {
        breb()
        cancelHours(6)
        val created = create("2026-10-19T14:00:00-05:00")
        val id = appointmentId(created)
        approve(id)
        assertEquals(200, cancel(created).statusCode())
        assertEquals(404, refunded(id, wrongStaff).statusCode())
        membership(wrongStaff, otherShop, "OWNER")
        assertEquals(404, http("POST", "/api/v1/barbershops/pay-b/staff/appointments/$id/deposit/refunded", wrongStaff).statusCode())
        assertEquals(404, refunded(id, customer).statusCode())
        assertEquals(0, jdbc.queryForObject("select count(*) from deposit_payment where refund_status = 'PENDING'", Int::class.java))
        assertEquals(0, inShop(otherShop) { jdbc.update("update deposit_payment set refund_status = 'DONE' where barbershop_id = ? and appointment_id = ?", shop, id) })
        assertEquals("PENDING", refundStatus(id))
    }

    @Test
    fun `creation and polling publish the same cancellation policy with a Bogota offset`() {
        breb()
        cancelHours(6)
        val created = create("2026-10-19T14:00:00-05:00")
        assertEquals("2026-10-19T08:00:00-05:00", created.path("cancellableWithRefundUntil").asString())
        assertTrue(created.path("cancelPolicyText").asString().contains("6 horas"))
        val response = http("GET", "/api/v1/appointments/${created.path("publicToken").asString()}", customer)
        assertEquals(200, response.statusCode())
        val polled = mapper.readTree(response.body())
        assertEquals(created.path("cancellableWithRefundUntil"), polled.path("cancellableWithRefundUntil"))
        assertEquals(created.path("cancelPolicyText"), polled.path("cancelPolicyText"))
    }

    @Test
    fun `cancellation applies the policy shown at creation after the shop changes its deadline`() {
        breb()
        cancelHours(6)
        val created = create("2026-10-19T14:00:00-05:00")
        val id = appointmentId(created)
        approve(id)
        cancelHours(24)
        val polled = mapper.readTree(http("GET", "/api/v1/appointments/${created.path("publicToken").asString()}", customer).body())
        assertEquals(created.path("cancellableWithRefundUntil"), polled.path("cancellableWithRefundUntil"))
        assertEquals(created.path("cancelPolicyText"), polled.path("cancelPolicyText"))
        assertEquals(200, cancel(created).statusCode())
        assertEquals("PENDING", refundStatus(id))
    }

    @Test
    fun `Wompi approved after pending cancellation keeps the appointment cancelled and records a pending refund idempotently`() {
        configureWompi()
        val created = create()
        val id = appointmentId(created)
        assertEquals(200, cancel(created).statusCode())
        assertEquals("VOIDED", depositStatus(id))
        assertEquals(200, webhook(created).statusCode())
        assertEquals(200, webhook(created).statusCode())
        assertEquals("CANCELLED", appointmentStatus(id))
        assertEquals("APPROVED", depositStatus(id))
        assertEquals("PENDING", refundStatus(id))
        inShop(shop) { assertEquals(1, jdbc.queryForObject("select count(*) from deposit_event where barbershop_id = ? and to_refund_status = 'PENDING'", Int::class.java, shop)) }
    }

    @Test
    fun `cancelled customer appointment in an inactive shop still follows RLS`() {
        breb()
        val created = create()
        inShop(shop) { jdbc.update("update barbershop set active = false where id = ?", shop) }
        assertEquals(200, cancel(created).statusCode())
        assertEquals("CANCELLED", appointmentStatus(appointmentId(created)))
    }

    @Test
    fun `rollback restores appointment and payment and does not deliver AppointmentCancelled after commit`() {
        breb()
        val created = create()
        val id = appointmentId(created)
        assertFailsWith<IllegalStateException> { tenants.execute(shop) {
            cancellations.cancel(customer.id, created.path("publicToken").asString())
            error("Synthetic rollback")
        } }
        assertEquals("PENDING_PAYMENT", appointmentStatus(id))
        assertEquals("PENDING", depositStatus(id))
        assertTrue(cancelledEvents.events.isEmpty())
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(DepositMode::class)
    fun `quote and creation share amounts and policy without writing or holding anything`(mode: DepositMode) {
        when (mode) {
            DepositMode.NONE -> inShop(shop) { settings.configure(shop, PaymentSettingsRequest(mode)) }
            DepositMode.BREB_MANUAL -> breb()
            DepositMode.WOMPI -> configureWompi()
        }
        cancelHours(6)
        val before = businessCounts()
        val response = quote()
        assertEquals(200, response.statusCode(), response.body())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""))
        assertEquals(before, businessCounts(), "Quote must not create customers, appointments, deposits or events")
        val quoted = mapper.readTree(response.body())
        assertEquals(38000L, quoted.path("priceCop").asLong())
        assertEquals(mode.name, quoted.path("depositMode").asString())
        val deposit = if (mode == DepositMode.NONE) 0L else 12000L
        assertEquals(deposit, quoted.path("depositCop").asLong())
        assertEquals(38000L - deposit, quoted.path("payAtShopCop").asLong())
        assertEquals(if (mode == DepositMode.NONE) 0 else if (mode == DepositMode.BREB_MANUAL) 30 else 15,
            quoted.path("holdMinutes").asInt())
        val created = create()
        listOf("depositCop", "cancellableWithRefundUntil", "cancelPolicyText").forEach { field ->
            assertEquals(quoted.path(field), created.path(field), field)
        }
        val storedPrice = inShop(shop) { jdbc.queryForObject("select price_cop from appointment where barbershop_id = ?", Long::class.java, shop) }
        assertEquals(quoted.path("priceCop").asLong(), storedPrice)
        assertEquals("2026-10-19T04:00:00-05:00", quoted.path("cancellableWithRefundUntil").asString())
    }

    @Test
    fun `quote rejects occupied misaligned past and out of window slots without changing data`() {
        breb()
        create()
        val before = businessCounts()
        listOf("2026-10-19T10:00:00-05:00", "2026-10-19T11:07:00-05:00", "2026-10-19T08:00:00-05:00",
            "2026-11-02T10:00:00-05:00").forEach { startsAt ->
            val response = quote(startsAt)
            assertEquals(409, response.statusCode(), response.body())
        }
        assertEquals(before, businessCounts())
    }

    @Test
    fun `quote requires a customer session and hides barber and service from another tenant`() {
        breb()
        assertEquals(401, http("GET", quotePath(), null).statusCode())
        assertEquals(404, http("GET", quotePath(b = otherBarber), customer).statusCode())
        assertEquals(404, http("GET", quotePath(s = otherService), customer).statusCode())
    }

    @Test
    fun `public shop includes booking window and location but never tenant identifiers`() {
        inShop(shop) { jdbc.update("update barbershop set address = 'Calle 10 # 20-30', city = 'Bogotá', booking_window_days = 21 where id = ?", shop) }
        val response = http("GET", "/api/v1/barbershops/pay-a", null)
        assertEquals(200, response.statusCode(), response.body())
        val body = mapper.readTree(response.body())
        assertEquals(21, body.path("bookingWindowDays").asInt())
        assertEquals("Calle 10 # 20-30", body.path("address").asString())
        assertEquals("Bogotá", body.path("city").asString())
        assertTrue(body.path("id").isMissingNode)
        assertTrue(body.path("barbershopId").isMissingNode)
    }

    @Test
    fun `NONE appointment detail has no deposit`() {
        inShop(shop) { settings.configure(shop, PaymentSettingsRequest(DepositMode.NONE)) }
        assertTrue(detail(create()).path("deposit").isNull)
        val history = mapper.readTree(http("GET", "/api/v1/me/appointments", customer).body()).path("upcoming").first()
        assertTrue(history.path("depositStatus").isNull)
        assertEquals("NONE", history.path("refundStatus").asString())
        assertTrue(history.path("cancellable").asBoolean())
    }

    @Test
    fun `pending BreB detail restores original instructions after changing mode and deactivating shop`() {
        breb()
        val created = create()
        configureWompi()
        inShop(shop) { jdbc.update("update barbershop set active = false where id = ?", shop) }
        val deposit = detail(created).path("deposit")
        assertEquals("BREB_MANUAL", deposit.path("method").asString())
        assertEquals("PENDING", deposit.path("status").asString())
        assertEquals("PENDING", deposit.path("latestAttemptStatus").asString())
        assertEquals(12000L, deposit.path("amountCop").asLong())
        assertEquals(created.path("paymentReference"), deposit.path("reference"))
        assertEquals(created.path("holdExpiresAt"), deposit.path("holdExpiresAt"))
        assertEquals(created.path("paymentInstructions"), deposit.path("paymentInstructions"))
        assertEquals("NONE", deposit.path("refundStatus").asString())
        assertFalse(deposit.path("canRetry").asBoolean())
        val forbidden = http("GET", detailPath(created), user("3000000199"))
        assertEquals(404, forbidden.statusCode())
        listOf("Private", "Integrity", "Events", "privateKey", "integritySecret", "eventsSecret").forEach {
            assertFalse(deposit.toString().contains(it))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["PENDING", "APPROVED", "VOIDED", "DECLINED", "EXPIRED", "ERROR", "LATE_PAYMENT"])
    fun `detail projects each deposit status without exposing instructions when payment cannot resume`(status: String) {
        breb()
        val created = create()
        inShop(shop) { jdbc.update("update deposit_payment set status = ? where barbershop_id = ?", status, shop) }
        val deposit = detail(created).path("deposit")
        assertEquals(status, deposit.path("status").asString())
        assertEquals(status, deposit.path("latestAttemptStatus").asString())
        assertFalse(deposit.path("canRetry").asBoolean())
        assertEquals(status != "PENDING", deposit.path("paymentInstructions").isNull)
    }

    @ParameterizedTest
    @ValueSource(strings = ["DECLINED", "ERROR"])
    fun `Wompi canRetry matches retry eligibility and expires exactly at the hold boundary`(status: String) {
        configureWompi()
        val created = create()
        assertFalse(detail(created).path("deposit").path("canRetry").asBoolean())
        inShop(shop) { jdbc.update("update deposit_payment set status = ? where barbershop_id = ?", status, shop) }
        assertTrue(detail(created).path("deposit").path("canRetry").asBoolean())
        val retry = http("POST", "/api/v1/appointments/${created.path("publicToken").asString()}/payment-attempts", customer)
        assertEquals(200, retry.statusCode(), retry.body())
        assertFalse(detail(created).path("deposit").path("canRetry").asBoolean())
        inShop(shop) { jdbc.update("update deposit_payment set status = ? where barbershop_id = ?", status, shop) }
        val expiry = java.time.OffsetDateTime.parse(created.path("holdExpiresAt").asString()).toInstant()
        clock.set(expiry.minusNanos(1))
        assertTrue(detail(created).path("deposit").path("canRetry").asBoolean())
        clock.set(expiry)
        assertFalse(detail(created).path("deposit").path("canRetry").asBoolean())
        assertEquals(409, http("POST", "/api/v1/appointments/${created.path("publicToken").asString()}/payment-attempts", customer).statusCode())
        clock.set(expiry.plusSeconds(1))
        assertFalse(detail(created).path("deposit").path("canRetry").asBoolean())
    }

    @Test
    fun `detail and history retain approved refund when a newer attempt failed`() {
        configureWompi()
        val created = create()
        assertEquals(200, webhook(created, status = "DECLINED").statusCode())
        assertEquals(200, http("POST", "/api/v1/appointments/${created.path("publicToken").asString()}/payment-attempts", customer).statusCode())
        val id = appointmentId(created)
        inShop(shop) {
            jdbc.update("update deposit_payment set status = 'APPROVED', refund_status = 'PENDING' where barbershop_id = ? and attempt = 1", shop)
            jdbc.update("update deposit_payment set status = 'ERROR' where barbershop_id = ? and attempt = 2", shop)
            jdbc.update("update appointment set status = 'CANCELLED' where barbershop_id = ? and id = ?", shop, id)
        }
        val deposit = detail(created).path("deposit")
        assertEquals("APPROVED", deposit.path("status").asString())
        assertEquals("ERROR", deposit.path("latestAttemptStatus").asString())
        assertEquals("PENDING", deposit.path("refundStatus").asString())
        assertFalse(deposit.path("canRetry").asBoolean())
        val history = mapper.readTree(http("GET", "/api/v1/me/appointments", customer).body()).path("upcoming").first()
        assertEquals("APPROVED", history.path("depositStatus").asString())
        assertEquals("PENDING", history.path("refundStatus").asString())
        assertFalse(history.path("cancellable").asBoolean())
        assertEquals(204, refunded(id, owner).statusCode())
        assertEquals("DONE", detail(created).path("deposit").path("refundStatus").asString())
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `detail exposes cancellation refund state`(onTime: Boolean) {
        breb()
        cancelHours(if (onTime) 1 else 6)
        val created = create()
        approve(appointmentId(created))
        assertEquals(200, cancel(created).statusCode())
        val deposit = detail(created).path("deposit")
        assertEquals("APPROVED", deposit.path("status").asString())
        assertEquals(if (onTime) "PENDING" else "FORFEITED", deposit.path("refundStatus").asString())
        assertTrue(deposit.path("paymentInstructions").isNull)
    }

    @Test
    fun `retry contract disables Wompi attempts when current mode or appointment state does not permit retry`() {
        configureWompi()
        val created = create()
        inShop(shop) { jdbc.update("update deposit_payment set status = 'ERROR' where barbershop_id = ?", shop) }
        assertTrue(detail(created).path("deposit").path("canRetry").asBoolean())
        inShop(shop) { settings.configure(shop, PaymentSettingsRequest(DepositMode.NONE)) }
        assertFalse(detail(created).path("deposit").path("canRetry").asBoolean())
        configureWompi()
        listOf("CONFIRMED", "CANCELLED", "EXPIRED", "COMPLETED", "NO_SHOW").forEach { state ->
            inShop(shop) { jdbc.update("update appointment set status = ? where barbershop_id = ?", state, shop) }
            assertFalse(detail(created).path("deposit").path("canRetry").asBoolean(), state)
            assertEquals(409, http("POST", "/api/v1/appointments/${created.path("publicToken").asString()}/payment-attempts", customer).statusCode())
        }
    }

    @Test
    fun `pending manual instructions disappear at hold expiry and history disables cancellation at start`() {
        breb()
        val created = create()
        clock.set(java.time.OffsetDateTime.parse(created.path("holdExpiresAt").asString()).toInstant())
        assertTrue(detail(created).path("deposit").path("paymentInstructions").isNull)
        clock.set(Instant.parse("2026-10-19T15:00:00Z"))
        val history = mapper.readTree(http("GET", "/api/v1/me/appointments", customer).body()).path("upcoming").first()
        assertFalse(history.path("cancellable").asBoolean())
    }

    private fun businessCounts(): List<Int> = inShop(shop) {
        listOf("customer", "appointment", "deposit_payment", "deposit_event").map {
            checkNotNull(jdbc.queryForObject("select count(*) from $it where barbershop_id = ?", Int::class.java, shop))
        }
    }
    private fun quotePath(startsAt: String = "2026-10-19T10:00:00-05:00", b: UUID = barber, s: UUID = service) =
        "/api/v1/barbershops/pay-a/booking-quote?serviceId=$s&barberId=$b&startsAt=$startsAt"
    private fun quote(startsAt: String = "2026-10-19T10:00:00-05:00") = http("GET", quotePath(startsAt), customer)
    private fun detailPath(created: JsonNode) = "/api/v1/appointments/${created.path("publicToken").asString()}"
    private fun detail(created: JsonNode): JsonNode {
        val response = http("GET", detailPath(created), customer)
        assertEquals(200, response.statusCode(), response.body())
        return mapper.readTree(response.body())
    }

    private fun cancelHours(hours: Int) = inShop(shop) { jdbc.update("update barbershop set cancel_hours = ? where id = ?", hours, shop) }
    private fun cancelPath(created: JsonNode) = "/api/v1/appointments/${created.path("publicToken").asString()}/cancel"
    private fun cancel(created: JsonNode, actor: AppUser = customer) = http("POST", cancelPath(created), actor)
    private fun approve(id: UUID) { assertEquals(204, http("POST", "/api/v1/barbershops/pay-a/staff/appointments/$id/deposit/confirm", staff).statusCode()) }
    private fun refunded(id: UUID, actor: AppUser) = http("POST", "/api/v1/barbershops/pay-a/staff/appointments/$id/deposit/refunded", actor)
    private fun refundStatus(id: UUID): String = inShop(shop) { checkNotNull(jdbc.queryForObject("select refund_status from deposit_payment where barbershop_id = ? and appointment_id = ? order by attempt desc limit 1", String::class.java, shop, id)) }

    private fun breb() { inShop(shop) { settings.configure(shop, PaymentSettingsRequest(DepositMode.BREB_MANUAL, BrebKeyType.ALIAS, "@TEST", "Titular")) } }
    private fun wompiRequest() = PaymentSettingsRequest(DepositMode.WOMPI, wompiEnvironment = WompiEnvironment.SANDBOX,
        wompiPublicKey = "pub_test_Public", wompiPrivateKey = "prv_test_Private", wompiIntegritySecret = "test_integrity_Integrity", wompiEventsSecret = "test_events_Events")
    private fun configureWompi() { inShop(shop) { settings.configure(shop, wompiRequest()) } }
    private fun user(phone: String): AppUser = identities.findOrCreateUser("+57$phone", now).let { identities.updateName(it.id, "Cliente"); it.copy(name = "Cliente") }
    private fun membership(user: AppUser, tenant: UUID, role: String, barberId: UUID? = null) {
        jdbc.update("insert into staff_membership (id, user_id, barbershop_id, role, barber_id) values (?, ?, ?, ?, ?)", UUID.randomUUID(), user.id, tenant, role, barberId)
    }
    private fun create(startsAt: String = "2026-10-19T10:00:00-05:00"): JsonNode {
        val response = http("POST", "/api/v1/barbershops/pay-a/appointments", customer,
            mapOf("barberId" to barber, "serviceId" to service, "startsAt" to startsAt))
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
