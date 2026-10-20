package com.geovannycode.corteya.identity

import com.geovannycode.corteya.PostgresIntegrationTest
import com.geovannycode.corteya.booking.FixedBookingTestConfiguration
import com.geovannycode.corteya.booking.MutableTestClock
import com.geovannycode.corteya.identity.internal.OtpRetentionJob
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.jdbc.core.JdbcTemplate

class CredentialRetentionIntegrationTests : PostgresIntegrationTest() {
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var clock: MutableTestClock
    @Autowired private lateinit var job: OtpRetentionJob
    private val now = Instant.parse("2026-10-19T13:00:00Z")
    private val user = UUID.randomUUID()

    override fun customize(builder: SpringApplicationBuilder): SpringApplicationBuilder =
        builder.sources(FixedBookingTestConfiguration::class.java)

    @BeforeEach
    fun fixtures() {
        clock.set(now)
        resetData()
        jdbc.update("insert into app_user (id, phone) values (?, '+573001234567')", user)
    }

    @Test
    fun `daily retention removes only credentials older than their cutoff and is idempotent`() {
        val day = 24L * 3600
        val otpCutoff = now.minusSeconds(day)
        val refreshCutoff = now.minusSeconds(30 * day)
        val oldOtp = otp(otpCutoff.minusSeconds(1))
        val retainedOtps = listOf(otp(otpCutoff), otp(now.minusSeconds(60)), otp(now.plusSeconds(300)))
        val expired = refresh(refreshCutoff.minusSeconds(1))
        val revoked = refresh(now.plusSeconds(day), refreshCutoff.minusSeconds(1))
        val retainedTokens = listOf(refresh(refreshCutoff), refresh(now.plusSeconds(day), refreshCutoff),
            refresh(now.plusSeconds(day), now.minusSeconds(60)), refresh(now.plusSeconds(day)))
        // A retained predecessor must not prevent deletion of an old replacement (self FK).
        jdbc.update("update refresh_token set replaced_by = ? where id = ?", expired, retainedTokens.first())
        job.purge()
        assertEquals(0, exists("otp_challenge", oldOtp))
        retainedOtps.forEach { assertEquals(1, exists("otp_challenge", it)) }
        listOf(expired, revoked).forEach { assertEquals(0, exists("refresh_token", it)) }
        retainedTokens.forEach { assertEquals(1, exists("refresh_token", it)) }
        assertEquals(0, jdbc.queryForObject("select count(*) from refresh_token where replaced_by is not null", Int::class.java))
        job.purge()
        assertEquals(3, jdbc.queryForObject("select count(*) from otp_challenge", Int::class.java))
        assertEquals(4, jdbc.queryForObject("select count(*) from refresh_token", Int::class.java))
    }

    private fun otp(expires: Instant): UUID = UUID.randomUUID().also {
        jdbc.update("""insert into otp_challenge (id, phone, code_hash, expires_at, request_ip)
            values (?, '+573001234567', ?, ?, '192.0.2.1')""", it, "a".repeat(64), Timestamp.from(expires))
    }

    private fun refresh(expires: Instant, revoked: Instant? = null): UUID = UUID.randomUUID().also {
        jdbc.update("""insert into refresh_token (id, user_id, token_hash, family_id, expires_at, revoked_at)
            values (?, ?, ?, ?, ?, ?)""", it, user, it.toString(), UUID.randomUUID(), Timestamp.from(expires), revoked?.let(Timestamp::from))
    }

    private fun exists(table: String, id: UUID): Int = checkNotNull(
        jdbc.queryForObject("select count(*) from $table where id = ?", Int::class.java, id),
    )
}
