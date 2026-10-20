package com.geovannycode.corteya.identity

import com.geovannycode.corteya.identity.AppUser
import com.geovannycode.corteya.identity.PhoneNumbers
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val store: IdentityRepository,
    private val digests: SecretDigests,
    private val sender: OtpSender,
    private val tokens: AccessTokens,
    private val clock: Clock,
    private val reservations: OtpReservationService,
) {
    fun request(rawPhone: String, ip: String) {
        // The separate Spring service commits before invoking the external sender.
        val reservation = reservations.reserveOtp(rawPhone, ip)
        try {
            sender.send(reservation.phone, reservation.code)
        } catch (_: Exception) {
            reservations.invalidate(reservation.id)
            throw AuthFailure(503, "No pudimos enviar el código, intenta de nuevo en un momento")
        }
    }

    // Failed verification attempts and refresh replay revocations must survive error responses.
    @Transactional(noRollbackFor = [AuthFailure::class])
    fun verify(rawPhone: String, code: String): TokenPair {
        val phone = normalize(rawPhone)
        store.lockOtpPhone(phone)
        val now = clock.instant()
        val challenge = store.latestOtp(phone) ?: AuthFailure.invalidOtp()
        if (challenge.consumedAt != null || challenge.attempts >= 5 || challenge.expiresAt <= now) AuthFailure.invalidOtp()
        if (!digests.matchesOtp(challenge.id, phone, code, challenge.codeHash)) {
            store.failOtp(challenge.id, challenge.attempts + 1, now)
            AuthFailure.invalidOtp()
        }
        store.consumeOtp(challenge.id, now)
        val user = store.findOrCreateUser(phone, now)
        store.linkVerifiedCustomers(user.id, phone)
        return issue(user, UUID.randomUUID()).second
    }

    @Transactional(noRollbackFor = [AuthFailure::class])
    fun refresh(rawToken: String): TokenPair {
        val hash = digests.refreshHash(rawToken)
        val initial = store.findRefresh(hash) ?: AuthFailure.unauthenticated()
        // User-level lock also serializes rotation with logout and account deletion.
        val user = store.lockUser(initial.userId) ?: AuthFailure.unauthenticated()
        val token = store.findRefresh(hash) ?: AuthFailure.unauthenticated()
        val now = clock.instant()
        if (token.revokedAt != null || token.replacedBy != null || token.expiresAt <= now) {
            store.revokeFamily(token.familyId, now)
            AuthFailure.unauthenticated()
        }
        val (newId, pair) = issue(user, token.familyId)
        store.replaceRefresh(token.id, newId, now)
        return pair
    }

    @Transactional
    fun logout(rawToken: String) {
        val token = store.findRefresh(digests.refreshHash(rawToken)) ?: return
        store.lockUser(token.userId) ?: return
        store.revokeFamily(token.familyId, clock.instant())
    }

    private fun issue(user: AppUser, familyId: UUID): Pair<UUID, TokenPair> {
        val now = clock.instant()
        val opaque = digests.refreshToken()
        val id = UUID.randomUUID()
        store.insertRefresh(id, user.id, digests.refreshHash(opaque), familyId, now.plusSeconds(30L * 24 * 3600), now)
        return id to TokenPair(tokens.issue(user, store.memberships(user.id)), opaque)
    }

    private fun normalize(phone: String): String = PhoneNumbers.normalize(phone)
        ?: throw AuthFailure(400, "Ingresa un celular colombiano de 10 dígitos que empiece por 3, o su formato +57")
}
