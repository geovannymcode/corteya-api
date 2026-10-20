package com.geovannycode.corteya.identity

import com.geovannycode.corteya.PostgresIntegrationTest
import com.geovannycode.corteya.RecordingOtpSender
import com.geovannycode.corteya.booking.FixedBookingTestConfiguration
import com.geovannycode.corteya.booking.MutableTestClock
import com.geovannycode.corteya.identity.AuthService
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import kotlin.test.*

class OtpDeliveryIntegrationTests : PostgresIntegrationTest(web = true) {
    @Autowired private lateinit var dataSource: com.zaxxer.hikari.HikariDataSource
    @Autowired private lateinit var auth: AuthService
    @Autowired private lateinit var sender: RecordingOtpSender
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var clock: MutableTestClock

    override fun customize(builder: SpringApplicationBuilder): SpringApplicationBuilder =
        builder.sources(FixedBookingTestConfiguration::class.java).properties(mapOf(
            // Flyway uses two connections during startup; reduce to one after migrations.
            "spring.datasource.hikari.maximum-pool-size" to 2,
            "spring.datasource.hikari.minimum-idle" to 0,
            "spring.datasource.hikari.connection-timeout" to 1000,
        ))

    @BeforeEach
    fun fixtures() {
        clock.set(Instant.parse("2026-10-19T13:00:00Z"))
        sender.beforeSend = null
        sender.codes.clear()
        dataSource.maximumPoolSize = 1
        dataSource.hikariPoolMXBean.softEvictConnections()
        jdbc.execute("TRUNCATE otp_challenge")
    }

    @AfterEach
    fun resetSender() { sender.beforeSend = null }

    @Test
    fun `slow sender holds neither transaction nor the sole pool connection for another IP`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        sender.beforeSend = { phone, _ ->
            assertEquals(1, dataSource.maximumPoolSize)
            assertTrue(dataSource.hikariPoolMXBean.totalConnections <= 1)
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            assertEquals(1, jdbc.queryForObject("select count(*) from otp_challenge where phone = ?", Int::class.java, phone))
            if (phone == "+573001234567") {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
            }
        }
        Executors.newFixedThreadPool(2).use { executor ->
            val first = executor.submit { auth.request("3001234567", "192.0.2.1") }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val second = executor.submit { auth.request("3007654321", "192.0.2.2") }
                second.get(3, TimeUnit.SECONDS)
                assertFalse(first.isDone)
                assertEquals(2, jdbc.queryForObject("select count(*) from otp_challenge", Int::class.java))
            } finally {
                release.countDown()
            }
            first.get(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `failed delivery returns 503 invalidates committed challenge and still counts for limits`() {
        var attemptedCode = ""
        sender.beforeSend = { _, code -> attemptedCode = code; throw IllegalStateException("Simulated delivery failure") }
        HttpClient.newHttpClient().use { client ->
            fun post(path: String, body: String): HttpResponse<String> = client.send(
                HttpRequest.newBuilder(URI("http://localhost:$serverPort/api/v1/auth/$path"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            val failed = post("otp/request", """{"phone":"3001234567"}""")
            assertEquals(503, failed.statusCode(), failed.body())
            assertTrue(failed.body().contains("No pudimos enviar el código, intenta de nuevo en un momento"))
            assertEquals(1, jdbc.queryForObject("select count(*) from otp_challenge where consumed_at is not null", Int::class.java))
            assertEquals(429, post("otp/request", """{"phone":"3001234567"}""").statusCode())
            assertEquals(401, post("otp/verify", """{"phone":"3001234567","code":"$attemptedCode"}""").statusCode())
        }
    }
}
