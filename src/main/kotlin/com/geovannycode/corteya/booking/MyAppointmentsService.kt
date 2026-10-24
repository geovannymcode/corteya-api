package com.geovannycode.corteya.booking

import com.geovannycode.corteya.barbershop.BarbershopService
import com.geovannycode.corteya.catalog.BarberDirectoryService
import com.geovannycode.corteya.catalog.ServiceCatalogService
import com.geovannycode.corteya.booking.dto.MyAppointmentResponse
import com.geovannycode.corteya.booking.dto.MyAppointmentsResponse
import com.geovannycode.corteya.booking.internal.AppointmentRepository
import com.geovannycode.corteya.booking.internal.DepositHistory
import com.geovannycode.corteya.booking.internal.DepositRepository
import com.geovannycode.corteya.identity.AuthorizationPolicy
import com.geovannycode.corteya.shared.TenantTransaction
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class MyAppointmentsService(
    private val policy: AuthorizationPolicy, private val bookings: AppointmentRepository,
    private val shops: BarbershopService, private val services: ServiceCatalogService,
    private val barbers: BarberDirectoryService, private val deposits: DepositRepository,
    private val tenants: TenantTransaction, private val clock: Clock,
) {
    fun mine(userId: UUID): MyAppointmentsResponse {
        policy.user(userId)
        val now = clock.instant()
        val rows = policy.barbershops(userId).flatMap { shopId ->
            tenants.execute(shopId) { forShop(userId, shopId, now) }
        }.sortedWith(compareBy<Pair<UUID, MyAppointmentResponse>> { it.second.startsAt.toInstant() }.thenBy { it.first.toString() }).map { it.second }
        val (upcoming, past) = rows.partition { it.endsAt.toInstant() > now }
        return MyAppointmentsResponse(upcoming, past.asReversed())
    }

    private fun forShop(userId: UUID, shopId: UUID, now: Instant): List<Pair<UUID, MyAppointmentResponse>> {
        val rows = bookings.findByUser(shopId, userId)
        if (rows.isEmpty()) return emptyList()
        val shop = shops.findById(shopId) ?: BookingFailure.notFound()
        val servicesById = services.findAll(shopId).associateBy { it.id }
        val barbersById = barbers.findAll(shopId).associateBy { it.id }
        val payments = deposits.readForAppointments(shopId, rows.map { it.id }).groupBy { it.appointmentId }
        return rows.map { a ->
            val payment = DepositHistory.effective(payments[a.id].orEmpty())
            val cancellation = CancellationPolicy.forAppointment(a.startsAt, a.cancelHoursSnapshot ?: shop.cancelHours, a.depositCop > 0)
            a.id to MyAppointmentResponse(a.publicToken, servicesById.getValue(a.serviceId).name, barbersById.getValue(a.barberId).name,
                shop.name, shop.slug, shop.logoUrl, a.startsAt.bogota(), a.endsAt.bogota(), a.priceCop, a.depositCop, a.status,
                payment?.status, payment?.refundStatus ?: RefundStatus.NONE,
                CancellationPolicy.canCancel(a.status, a.startsAt, now), cancellation.cancellableWithRefundUntil.bogota(),
                a.paymentReference, a.holdExpiresAt?.bogota(), cancellation.cancelPolicyText)
        }
    }

    private fun Instant.bogota() = atZone(ZoneId.of("America/Bogota")).toOffsetDateTime()
}
