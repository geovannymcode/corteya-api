package com.geovannycode.corteya.identity

import com.geovannycode.corteya.identity.PhoneNumbers
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class ReservedOtp(val id: UUID, val phone: String, val code: String) {
    override fun toString(): String = "ReservedOtp(phone=${PhoneNumbers.mask(phone)}, code=[redacted])"
}

@Service
class OtpReservationService(private val store: IdentityRepository, private val digests: SecretDigests, private val clock: Clock) {
    @Transactional
    fun reserveOtp(rawPhone: String, ip: String): ReservedOtp {
        val phone = PhoneNumbers.normalize(rawPhone)
            ?: throw AuthFailure(400, "Ingresa un celular colombiano de 10 dígitos que empiece por 3, o su formato +57")
        store.lockOtpRequest(phone, ip)
        val now = clock.instant()
        if (hasReachedRequestLimit(phone, ip, now)) {
            throw AuthFailure(429, "Has solicitado demasiados códigos; espera antes de intentar de nuevo")
        }
        val id = UUID.randomUUID()
        val code = digests.otpCode()
        store.insertOtp(id, phone, digests.otpHash(id, phone, code), now.plusSeconds(300), now, ip)
        return ReservedOtp(id, phone, code)
    }

    @Transactional
    fun invalidate(id: UUID) { store.consumeOtp(id, clock.instant()) }

    private fun hasReachedRequestLimit(phone: String, ip: String, now: Instant): Boolean {
        val cooldown = store.lastOtpSentAt(phone)?.let { now < it.plusSeconds(60) } == true
        val phoneLimit = store.otpCountForPhone(phone, now.minusSeconds(3600)) >= 5
        val ipLimit = store.otpCountForIp(ip, now.minusSeconds(3600)) >= 10
        return cooldown || phoneLimit || ipLimit
    }
}
