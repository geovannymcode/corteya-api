package com.geovannycode.corteya.identity

import com.geovannycode.corteya.barbershop.BarbershopService
import com.geovannycode.corteya.catalog.BarberDirectoryService
import com.geovannycode.corteya.identity.AppUser
import com.geovannycode.corteya.identity.StaffRole
import java.util.UUID
import org.springframework.stereotype.Component

data class AgendaScope(val barbershopId: UUID, val barberId: UUID?)

/** All object ownership and live membership decisions are centralized here, not trusted from JWT claims. */
@Component
class AuthorizationPolicy(
    private val identities: IdentityRepository,
    private val shops: BarbershopService,
    private val barbers: BarberDirectoryService,
) {
    fun user(userId: UUID): AppUser = identities.findUser(userId) ?: AuthFailure.unauthenticated()

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
        if (requestedBarberId != null && barbers.findById(shop.id, requestedBarberId) == null) notFound()
        return AgendaScope(shop.id, requestedBarberId)
    }
    private fun notFound(): Nothing = throw AuthFailure(404, "No encontramos el recurso solicitado en esta barbería")
}
