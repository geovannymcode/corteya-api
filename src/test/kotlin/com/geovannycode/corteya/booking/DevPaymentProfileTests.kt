package com.geovannycode.corteya.booking

import com.geovannycode.corteya.PostgresIntegrationTest
import com.geovannycode.corteya.booking.internal.PaymentSecrets
import java.util.Base64
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.env.MapPropertySource
import org.springframework.jdbc.core.JdbcTemplate

abstract class DevPaymentProfileTest(private val sandbox: Map<String, String>) : PostgresIntegrationTest(profile = "dev") {
    @Autowired protected lateinit var jdbc: JdbcTemplate
    protected val demo = UUID.fromString("10000000-0000-0000-0000-000000000001")
    override fun customize(builder: SpringApplicationBuilder) = builder.initializers({ context ->
        val defaults = mapOf("WOMPI_SANDBOX_PUBLIC_KEY" to "", "WOMPI_SANDBOX_PRIVATE_KEY" to "",
            "WOMPI_SANDBOX_INTEGRITY_SECRET" to "", "WOMPI_SANDBOX_EVENTS_SECRET" to "",
            "payments.secrets.key" to Base64.getEncoder().encodeToString(ByteArray(32) { 7 }))
        context.environment.propertySources.addFirst(MapPropertySource("synthetic-dev-payments", defaults + sandbox))
    })
}

class DevWompiPaymentTests : DevPaymentProfileTest(mapOf(
    "WOMPI_SANDBOX_PUBLIC_KEY" to "pub_test_Demo", "WOMPI_SANDBOX_PRIVATE_KEY" to "prv_test_Demo",
    "WOMPI_SANDBOX_INTEGRITY_SECRET" to "test_integrity_Demo", "WOMPI_SANDBOX_EVENTS_SECRET" to "test_events_Demo")) {
    @Autowired private lateinit var secrets: PaymentSecrets

    @Test
    fun `dev initializes demo Wompi sandbox credentials using the normal encrypted storage`() {
        inShop(demo) {
            val row = jdbc.queryForMap("select * from barbershop_payment_settings where barbershop_id = ?", demo)
            assertEquals("WOMPI", row["deposit_mode"])
            assertEquals("SANDBOX", row["wompi_environment"])
            assertEquals(15, row["hold_minutes"])
            assertEquals("pub_test_Demo", row["wompi_public_key"])
            listOf("private" to "wompi_private_key_enc", "integrity" to "wompi_integrity_secret_enc", "events" to "wompi_events_secret_enc").forEach { (purpose, field) ->
                val value = row[field].toString()
                assertTrue(value.startsWith("1."))
                assertFalse(value.contains("Demo"))
                assertTrue(secrets.decrypt(value, "$demo:$purpose").endsWith("_Demo"))
            }
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from barbershop_payment_settings", Int::class.java))
    }
}

class DevPartialPaymentTests : DevPaymentProfileTest(mapOf("WOMPI_SANDBOX_PUBLIC_KEY" to "pub_test_Partial")) {
    @Test
    fun `partial dev sandbox credentials keep demo in manual BreB without storing any Wompi secret`() {
        inShop(demo) {
            val row = jdbc.queryForMap("select * from barbershop_payment_settings where barbershop_id = ?", demo)
            assertEquals("BREB_MANUAL", row["deposit_mode"])
            assertEquals(30, row["hold_minutes"])
            assertNull(row["wompi_public_key"])
            assertNull(row["wompi_private_key_enc"])
            assertNull(row["wompi_integrity_secret_enc"])
            assertNull(row["wompi_events_secret_enc"])
        }
    }
}

class DevInvalidPaymentTests : DevPaymentProfileTest(mapOf(
    "WOMPI_SANDBOX_PUBLIC_KEY" to "invalid-public-SENSITIVE", "WOMPI_SANDBOX_PRIVATE_KEY" to "invalid-private-SENSITIVE",
    "WOMPI_SANDBOX_INTEGRITY_SECRET" to "invalid-integrity-SENSITIVE", "WOMPI_SANDBOX_EVENTS_SECRET" to "invalid-events-SENSITIVE")) {
    private val logger = org.slf4j.LoggerFactory.getLogger(com.geovannycode.corteya.booking.internal.DevPaymentInitializer::class.java) as ch.qos.logback.classic.Logger
    private val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
    override fun customize(builder: SpringApplicationBuilder) = super.customize(builder).initializers({ logger.addAppender(appender) })
    @org.junit.jupiter.api.AfterAll fun detachLogger() { logger.detachAppender(appender); appender.stop() }
    @Autowired private lateinit var environment: org.springframework.core.env.ConfigurableEnvironment
    @Autowired private lateinit var initializer: com.geovannycode.corteya.booking.internal.DevPaymentInitializer
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = ["WOMPI_SANDBOX_PUBLIC_KEY", "WOMPI_SANDBOX_PRIVATE_KEY", "WOMPI_SANDBOX_INTEGRITY_SECRET", "WOMPI_SANDBOX_EVENTS_SECRET"])
    fun `one invalid sandbox variable warns exactly its name and falls back safely`(invalid: String) {
        val before = appender.list.size
        val values = mapOf("WOMPI_SANDBOX_PUBLIC_KEY" to "pub_test_Demo", "WOMPI_SANDBOX_PRIVATE_KEY" to "prv_test_Demo",
            "WOMPI_SANDBOX_INTEGRITY_SECRET" to "test_integrity_Demo", "WOMPI_SANDBOX_EVENTS_SECRET" to "test_events_Demo") + (invalid to "invalid-SENSITIVE")
        environment.propertySources.addFirst(MapPropertySource("one-invalid-key", values))
        try {
            initializer.run(org.springframework.boot.DefaultApplicationArguments())
            val logs = appender.list.drop(before)
            assertEquals(listOf("Configuración dev inválida: $invalid"), logs.map { it.formattedMessage })
            inShop(demo) { assertEquals("BREB_MANUAL", jdbc.queryForObject("select deposit_mode from barbershop_payment_settings where barbershop_id = ?", String::class.java, demo)) }
        } finally { environment.propertySources.remove("one-invalid-key") }
    }
    @Test fun `invalid sandbox variables warn only their names and all three demos start in intended modes`() {
        val names = listOf("WOMPI_SANDBOX_PUBLIC_KEY", "WOMPI_SANDBOX_PRIVATE_KEY", "WOMPI_SANDBOX_INTEGRITY_SECRET", "WOMPI_SANDBOX_EVENTS_SECRET")
        names.forEach { name -> assertTrue(appender.list.any { it.level == ch.qos.logback.classic.Level.WARN && it.formattedMessage.contains(name) }) }
        assertFalse(appender.list.any { it.formattedMessage.contains("SENSITIVE") || it.throwableProxy != null })
        listOf(1 to "BREB_MANUAL", 2 to "BREB_MANUAL", 3 to "NONE").forEach { (number, mode) ->
            val id = UUID.fromString("10000000-0000-0000-0000-${number.toString().padStart(12, '0')}")
            inShop(id) {
                assertEquals(mode, jdbc.queryForObject("select deposit_mode from barbershop_payment_settings where barbershop_id = ?", String::class.java, id))
                assertTrue(checkNotNull(jdbc.queryForObject("select count(*) from service where barbershop_id = ?", Int::class.java, id)) > 0)
                assertTrue(checkNotNull(jdbc.queryForObject("select count(*) from working_hours where barbershop_id = ?", Int::class.java, id)) > 0)
                assertEquals(2, jdbc.queryForObject("select count(*) from staff_membership where barbershop_id = ?", Int::class.java, id))
            }
        }
        assertEquals(6, jdbc.queryForObject("select count(distinct phone) from app_user", Int::class.java))
    }
}

class DevInvalidMasterKeyTests : DevPaymentProfileTest(mapOf(
    "WOMPI_SANDBOX_PUBLIC_KEY" to "pub_test_Demo", "WOMPI_SANDBOX_PRIVATE_KEY" to "prv_test_Demo",
    "WOMPI_SANDBOX_INTEGRITY_SECRET" to "test_integrity_Demo", "WOMPI_SANDBOX_EVENTS_SECRET" to "test_events_Demo",
    "payments.secrets.key" to "invalid-master")) {
    @Test fun `invalid encryption master also leaves demo in manual mode without stopping startup`() {
        inShop(demo) {
            assertEquals("BREB_MANUAL", jdbc.queryForObject("select deposit_mode from barbershop_payment_settings where barbershop_id = ?", String::class.java, demo))
        }
    }
}
