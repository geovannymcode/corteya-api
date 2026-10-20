package com.geovannycode.corteya.identity

import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountService(private val store: IdentityRepository, private val clock: Clock) {
    @Transactional
    fun rename(userId: UUID, name: String) {
        store.lockUser(userId) ?: AuthFailure.unauthenticated()
        val trimmed = name.trim()
        if (trimmed.isBlank() || trimmed.length > 150) throw AuthFailure(400, "Ingresa un nombre entre 1 y 150 caracteres")
        store.updateName(userId, trimmed)
    }

    @Transactional
    fun delete(userId: UUID) {
        val user = store.findUser(userId) ?: AuthFailure.unauthenticated()
        // Verification locks phone -> user. Keep that order before removing OTP rows.
        user.phone?.let(store::lockOtpPhone)
        store.lockUser(userId) ?: AuthFailure.unauthenticated()
        store.anonymize(userId, clock.instant())
    }
}
