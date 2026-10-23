package com.geovannycode.corteya.identity

import com.geovannycode.corteya.identity.internal.IdentityRepository
import com.geovannycode.corteya.identity.dto.MembershipResponse
import com.geovannycode.corteya.barbershop.BarbershopService
import com.geovannycode.corteya.shared.TenantTransaction
import java.time.Clock
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountService(private val store: IdentityRepository, private val clock: Clock,
    private val shops: BarbershopService, private val tenants: TenantTransaction) {
    fun memberships(userId: UUID): List<MembershipResponse> = store.memberships(userId).map { membership ->
        tenants.execute(membership.barbershopId) {
            val shop = shops.findById(membership.barbershopId)
                ?: throw AuthFailure(404, "No encontramos la barbería de esta membresía")
            MembershipResponse(membership.barbershopId, membership.role, membership.barberId, shop.slug, shop.name)
        }
    }

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
