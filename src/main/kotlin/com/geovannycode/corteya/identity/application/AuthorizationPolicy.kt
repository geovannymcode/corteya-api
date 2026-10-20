package com.geovannycode.corteya.identity.application

import com.geovannycode.corteya.barbershop.application.BarbershopRepository
import com.geovannycode.corteya.booking.application.BookingFailure
import com.geovannycode.corteya.booking.domain.Appointment
import com.geovannycode.corteya.catalog.application.BarberRepository
import com.geovannycode.corteya.identity.domain.AppUser
import com.geovannycode.corteya.identity.domain.StaffRole
import java.util.UUID
import org.springframework.stereotype.Component

data class AgendaScope(val barbershopId: UUID, val barberId: UUID?)

/** All object ownership and live membership decisions are centralized here, not trusted from JWT claims. */
@Component
class AuthorizationPolicy(
    private val identities: IdentityStore,
    private val shops: BarbershopRepository,
    private val barbers: BarberRepository,
) {
    fun user(userId: UUID): AppUser = identities.findUser(userId) ?: AuthFailure.unauthenticated()

    // Called within the booking transaction: serialize creation with account deletion.
    fun bookingCustomer(userId: UUID): AppUser = (identities.lockUser(userId) ?: AuthFailure.unauthenticated()).also {
        if (it.name.isNullOrBlank()) throw AuthFailure(422, "Completa tu nombre en el perfil antes de reservar")
    }

    fun appointment(userId: UUID, appointment: Appointment) {
        user(userId)
        if (!identities.ownsCustomer(userId, appointment.barbershopId, appointment.customerId) &&
            identities.membership(userId, appointment.barbershopId) == null) BookingFailure.notFound()
    }

    fun agenda(userId: UUID, slug: String, requestedBarberId: UUID? = null): AgendaScope {
        user(userId)
        val shop = shops.findBySlug(slug) ?: BookingFailure.notFound()
        val membership = identities.membership(userId, shop.id) ?: BookingFailure.notFound()
        if (membership.role == StaffRole.BARBER) {
            val assigned = membership.barberId ?: BookingFailure.notFound()
            if (requestedBarberId != null && requestedBarberId != assigned) BookingFailure.notFound()
            return AgendaScope(shop.id, assigned)
        }
        if (requestedBarberId != null && barbers.findById(shop.id, requestedBarberId) == null) BookingFailure.notFound()
        return AgendaScope(shop.id, requestedBarberId)
    }
}
