package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.dto.*
import com.geovannycode.corteya.booking.internal.AppointmentRepository
import com.geovannycode.corteya.booking.internal.DepositRepository
import com.geovannycode.corteya.booking.internal.DepositHistory
import com.geovannycode.corteya.catalog.BarberDirectoryService
import com.geovannycode.corteya.catalog.ServiceCatalogService
import com.geovannycode.corteya.customer.CustomerService
import com.geovannycode.corteya.identity.AuthorizationPolicy
import com.geovannycode.corteya.shared.TenantTransaction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class StaffAgendaService(private val guard: AuthorizationPolicy, private val availability: AvailabilityService,
    private val tenants: TenantTransaction, private val bookings: AppointmentRepository, private val deposits: DepositRepository,
    private val services: ServiceCatalogService, private val barbers: BarberDirectoryService, private val customers: CustomerService,
    private val cancellations: CancellationService) {
    fun agenda(user: UUID, slug: String, date: LocalDate, barberId: UUID?): List<AgendaAppointmentResponse> {
        val scope = guard.agenda(user, slug, barberId)
        val zone = ZoneId.of(availability.shop(slug).timezone)
        return tenants.execute(scope.barbershopId) {
            val rows = bookings.agenda(scope.barbershopId, scope.barberId, date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
            if (rows.isEmpty()) return@execute emptyList()
            val policies = cancellations.policies(rows)
            val servicesById = services.findAll(scope.barbershopId).associateBy { it.id }
            val barbersById = barbers.findAll(scope.barbershopId).associateBy { it.id }
            val customersById = customers.details(scope.barbershopId, rows.map { it.customerId }.distinct())
            val payments = deposits.readForAppointments(scope.barbershopId, rows.map { it.id }).groupBy { it.appointmentId }
            rows.map { a ->
                val cancellation = policies.getValue(a.id)
                val payment = DepositHistory.effective(payments[a.id].orEmpty())
                val customer = customersById.getValue(a.customerId)
                AgendaAppointmentResponse(a.barberId, a.serviceId,
                    AppointmentStatusResponse(a.publicToken, a.status, a.startsAt.bogota(), a.endsAt.bogota(), a.depositCop,
                        a.paymentReference, a.holdExpiresAt?.bogota(), cancellation.cancellableWithRefundUntil.bogota(), cancellation.cancelPolicyText),
                    a.id, servicesById.getValue(a.serviceId).name, barbersById.getValue(a.barberId).name, a.startsAt.bogota(), a.endsAt.bogota(),
                    a.status, a.source, payment?.status, payment?.refundStatus ?: RefundStatus.NONE, customer.name, customer.phone)
            }
        }
    }
    private fun Instant.bogota() = atZone(ZoneId.of("America/Bogota")).toOffsetDateTime()
}
