package com.geovannycode.corteya.identity.application

import com.geovannycode.corteya.identity.domain.AppUser
import com.geovannycode.corteya.identity.domain.PhoneNumbers
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val store: IdentityStore,
    private val digests: AuthSecrets,
    private val sender: OtpSender,
    private val tokens: AccessTokens,
    private val clock: Clock,
) {
    // AuthFailure must commit: failed attempts, replay revocations and attempted sends are durable.
    @Transactional(noRollbackFor = [AuthFailure::class])
    fun request(rawPhone: String, ip: String) {
        val phone = normalize(rawPhone)
        store.lockOtpRequest(phone, ip)
        val now = clock.instant()
        val last = store.lastOtpSentAt(phone)
        if (last != null && now < last.plusSeconds(60) || store.otpCountForPhone(phone, now.minusSeconds(3600)) >= 5 ||
            store.otpCountForIp(ip, now.minusSeconds(3600)) >= 10) {
            throw AuthFailure(429, "Has solicitado demasiados códigos; espera antes de intentar de nuevo")
        }
        val id = UUID.randomUUID()
        val code = digests.otpCode()
        store.insertOtp(id, phone, digests.otpHash(id, phone, code), now.plusSeconds(300), now, ip)
        sender.send(phone, code)
    }

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
