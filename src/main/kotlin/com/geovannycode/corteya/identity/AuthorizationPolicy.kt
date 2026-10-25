package com.geovannycode.corteya.identity

import com.geovannycode.corteya.shared.TenantTransaction
import com.geovannycode.corteya.barbershop.BarbershopService
import com.geovannycode.corteya.catalog.BarberDirectoryService
import com.geovannycode.corteya.identity.internal.IdentityRepository
import java.util.UUID
import org.springframework.stereotype.Component

data class AgendaScope(val barbershopId: UUID, val barberId: UUID?)

/** All object ownership and live membership decisions are centralized here, not trusted from JWT claims. */
@Component
class AuthorizationPolicy(
    private val identities: IdentityRepository,
    private val shops: BarbershopService,
    private val barbers: BarberDirectoryService,
    private val tenants: TenantTransaction,
) {
    fun user(userId: UUID): AppUser = identities.findUser(userId) ?: AuthFailure.unauthenticated()

    fun barbershops(userId: UUID): List<UUID> {
        user(userId)
        return (identities.customerBarbershops(userId) + identities.memberships(userId).map { it.barbershopId })
            .distinct().sortedBy { it.toString() }
    }

    // Called within the booking transaction: serialize creation with account deletion.
    fun bookingCustomer(userId: UUID): AppUser = (identities.lockUser(userId) ?: AuthFailure.unauthenticated()).also {
        if (it.name.isNullOrBlank()) throw AuthFailure(422, "Completa tu nombre en el perfil antes de reservar")
    }

    fun appointment(userId: UUID, barbershopId: UUID, customerId: UUID) {
        user(userId)
        if (!identities.ownsCustomer(userId, barbershopId, customerId) &&
            identities.membership(userId, barbershopId) == null) notFound()
    }

    fun agenda(userId: UUID, slug: String, requestedBarberId: UUID? = null): AgendaScope {
        user(userId)
        val shop = shops.findBySlug(slug) ?: notFound()
        val membership = identities.membership(userId, shop.id) ?: notFound()
        if (membership.role == StaffRole.BARBER) {
            val assigned = membership.barberId ?: notFound()
            if (requestedBarberId != null && requestedBarberId != assigned) notFound()
            return AgendaScope(shop.id, assigned)
        }
        if (requestedBarberId != null) tenants.execute(membership.barbershopId) {
            if (barbers.findById(shop.id, requestedBarberId) == null) notFound()
        }
        return AgendaScope(shop.id, requestedBarberId)
    }
    fun requireBarber(scope: AgendaScope, barberId: UUID) {
        if (scope.barberId != null && scope.barberId != barberId) notFound()
    }

    fun owner(userId: UUID, slug: String): UUID {
        user(userId)
        val shop = shops.findBySlug(slug) ?: notFound()
        if (identities.membership(userId, shop.id)?.role != StaffRole.OWNER) notFound()
        return shop.id
    }

    fun appointmentCustomer(userId: UUID, barbershopId: UUID, customerId: UUID) {
        user(userId)
        if (!identities.ownsCustomer(userId, barbershopId, customerId)) notFound()
    }

    private fun notFound(): Nothing = throw AuthFailure(404, "No encontramos el recurso solicitado en esta barbería")
}
