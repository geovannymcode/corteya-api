package com.geovannycode.corteya.identity

import com.geovannycode.corteya.identity.dto.RefreshBody
import com.geovannycode.corteya.identity.dto.RequestOtpBody
import com.geovannycode.corteya.identity.dto.TokenPair
import com.geovannycode.corteya.identity.dto.VerifyOtpBody
import com.geovannycode.corteya.identity.internal.JwtConfiguration
import com.geovannycode.corteya.identity.internal.LogOtpSender
import com.geovannycode.corteya.identity.internal.SecretDigests
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class IdentitySecurityTests {
    @Test
    fun `diagnostic DTO strings redact phones OTPs and refresh tokens`() {
        val phone = "+573001234567"
        val code = "123456"
        val token = "private-refresh-token"
        val values = listOf(
            com.geovannycode.corteya.identity.dto.RequestOtpBody(phone).toString(),
            com.geovannycode.corteya.identity.dto.VerifyOtpBody(phone, code).toString(),
            com.geovannycode.corteya.identity.dto.RefreshBody(token).toString(),
            com.geovannycode.corteya.identity.dto.TokenPair("private-access-token", token).toString(),
        )
        values.forEach { text -> assertTrue(listOf(phone, code, token, "private-access-token").none(text::contains)) }
    }

    @Test
    fun `phones normalize without external libraries and mask logs`() {
        assertEquals("+573001234567", PhoneNumbers.normalize("3001234567"))
        assertEquals("+573001234567", PhoneNumbers.normalize("+573001234567"))
        listOf("300123456", "2001234567", "+12025550123", "300 123 4567", "").forEach { assertNull(PhoneNumbers.normalize(it)) }
        assertEquals("+57300****567", PhoneNumbers.mask("+573001234567"))
    }

    @Test
    fun `HMAC binds the code to challenge phone and pepper`() {
        val digests = SecretDigests("test-only-pepper-at-least-32-bytes-long")
        val id = UUID.randomUUID()
        val hash = digests.otpHash(id, "+573001234567", "000123")
        assertEquals(64, hash.length)
        assertTrue(digests.matchesOtp(id, "+573001234567", "000123", hash))
        assertFalse(digests.matchesOtp(id, "+573001234567", "123000", hash))
        assertFalse(digests.matchesOtp(UUID.randomUUID(), "+573001234567", "000123", hash))
        assertFalse(digests.matchesOtp(id, "+573009999999", "000123", hash))
        assertFailsWith<IllegalArgumentException> { SecretDigests("short") }
    }

    @Test
    fun `production PEM PKCS8 restores the correct public key and kid`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----"
        val key = JwtConfiguration().signingKey(MockEnvironment().withProperty("auth.jwt.private-key", pem).withProperty("auth.jwt.kid", "production-key"))
        assertEquals("production-key", key.kid)
        assertEquals((pair.public as RSAPublicKey).modulus, key.rsa.toRSAPublicKey().modulus)
        val escaped = JwtConfiguration().signingKey(MockEnvironment().withProperty("auth.jwt.private-key", pem.replace("\n", "\\n")).withProperty("auth.jwt.kid", "escaped-key"))
        assertEquals(key.rsa.toRSAPublicKey(), escaped.rsa.toRSAPublicKey())
    }

    @Test
    fun `production cannot start with development OTP logging or ephemeral signing keys`() {
        assertFailsWith<IllegalArgumentException> { LogOtpSender(MockEnvironment()) }
        assertFailsWith<IllegalArgumentException> { JwtConfiguration().signingKey(MockEnvironment().withProperty("auth.jwt.ephemeral", "true")) }
        assertFailsWith<IllegalArgumentException> { JwtConfiguration().signingKey(MockEnvironment()) }
    }
}
