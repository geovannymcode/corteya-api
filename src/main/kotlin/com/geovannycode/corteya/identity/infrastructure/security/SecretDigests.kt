package com.geovannycode.corteya.identity.infrastructure.security

import com.geovannycode.corteya.identity.application.AuthSecrets
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class SecretDigests(@Value("\${auth.otp.pepper}") pepper: String) : AuthSecrets {
    private val key = SecretKeySpec(pepper.toByteArray(StandardCharsets.UTF_8).also {
        require(it.size >= 32) { "Configura AUTH_OTP_PEPPER con al menos 32 bytes" }
    }, "HmacSHA256")
    private val random = SecureRandom()

    override fun otpCode(): String = random.nextInt(1_000_000).toString().padStart(6, '0')
    override fun otpHash(id: UUID, phone: String, code: String): String = HexFormat.of().formatHex(
        Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal("$id:$phone:$code".toByteArray(StandardCharsets.UTF_8)),
    )
    override fun matchesOtp(id: UUID, phone: String, code: String, expected: String): Boolean = MessageDigest.isEqual(
        otpHash(id, phone, code).toByteArray(StandardCharsets.US_ASCII), expected.toByteArray(StandardCharsets.US_ASCII),
    )
    override fun refreshToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    override fun refreshHash(token: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray(StandardCharsets.UTF_8)))
}
