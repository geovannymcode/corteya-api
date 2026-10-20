package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.internal.AppointmentEntity
import com.geovannycode.corteya.booking.internal.AppointmentRepository
import com.geovannycode.corteya.identity.AgendaScope
import com.geovannycode.corteya.identity.AuthorizationPolicy
import com.geovannycode.corteya.shared.TenantTransaction
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class BookingQueries(private val bookings: AppointmentRepository, private val policy: AuthorizationPolicy,
    private val tenants: TenantTransaction) {
    fun mine(userId: UUID): List<AppointmentEntity> = policy.barbershops(userId).flatMap { id ->
        tenants.execute(id) { bookings.findByUser(userId) }
    }.sortedWith(compareBy<AppointmentEntity> { it.startsAt }.thenBy { it.id.toString() })

    fun appointment(userId: UUID, token: String): AppointmentEntity {
        for (id in policy.barbershops(userId)) {
            val result = tenants.execute(id) { bookings.findByPublicToken(token) } ?: continue
            policy.appointment(userId, result.barbershopId, result.customerId)
            return result
        }
        BookingFailure.notFound()
    }

    fun agenda(scope: AgendaScope, startsAt: Instant, endsAt: Instant): List<AppointmentEntity> =
        tenants.execute(scope.barbershopId) { bookings.agenda(scope.barbershopId, scope.barberId, startsAt, endsAt) }
}
