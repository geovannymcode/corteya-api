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
