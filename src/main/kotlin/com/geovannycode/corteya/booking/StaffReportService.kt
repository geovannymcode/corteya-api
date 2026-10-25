package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.dto.*
import com.geovannycode.corteya.booking.internal.StaffReportRepository
import com.geovannycode.corteya.catalog.BarberDirectoryService
import com.geovannycode.corteya.identity.AuthorizationPolicy
import com.geovannycode.corteya.shared.TenantTransaction
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class StaffReportService(private val guard: AuthorizationPolicy, private val tenants: TenantTransaction,
    private val reports: StaffReportRepository, private val barbers: BarberDirectoryService, private val clock: Clock,
    private val bookings: com.geovannycode.corteya.booking.internal.AppointmentRepository,
    private val customers: com.geovannycode.corteya.customer.CustomerService,
    private val services: com.geovannycode.corteya.catalog.ServiceCatalogService) {
    fun summary(user: UUID, slug: String, period: SummaryPeriod, date: LocalDate): StaffSummaryResponse {
        val scope = guard.agenda(user, slug)
        val start = when (period) {
            SummaryPeriod.DAY -> date
            SummaryPeriod.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            SummaryPeriod.MONTH -> date.withDayOfMonth(1)
        }
        val end = when (period) {
            SummaryPeriod.DAY -> start.plusDays(1)
            SummaryPeriod.WEEK -> start.plusWeeks(1)
            SummaryPeriod.MONTH -> start.plusMonths(1)
        }
        val zone = ZoneId.of("America/Bogota")
        val from = start.atStartOfDay(zone)
        val to = end.atStartOfDay(zone)
        return tenants.execute(scope.barbershopId) {
            val rows = reports.summary(scope.barbershopId, scope.barberId, from.toInstant(), to.toInstant())
            val names = barbers.findAll(scope.barbershopId).associateBy { it.id }
            val metricsByBarber = rows.toMap()
            val empty = StaffReportRepository.metrics(0, 0, 0, 0, 0, 0, 0, 0, 0)
            val details = names.values.sortedWith(compareBy({ it.name }, { it.id.toString() }))
                .map { BarberSummary(it.id, it.name, metricsByBarber[it.id] ?: empty) }
            val metrics = rows.map { it.second }
            val total = StaffReportRepository.metrics(metrics.sumOf { it.totalAppointments }, metrics.sumOf { it.completed },
                metrics.sumOf { it.noShows }, metrics.sumOf { it.depositsReceivedCop }, metrics.sumOf { it.estimatedRevenueCop },
                metrics.sumOf { it.pendingRefunds }, metrics.sumOf { it.pendingRefundsCop },
                metrics.sumOf { it.completedRefunds }, metrics.sumOf { it.completedRefundsCop })
            StaffSummaryResponse(period, from.toOffsetDateTime(), to.toOffsetDateTime(), total, if (scope.barberId == null) details else emptyList())
        }
    }
    fun actions(user: UUID, slug: String): ActionItemsResponse {
        val scope = guard.agenda(user, slug)
        return tenants.execute(scope.barbershopId) {
            val pending = reports.actionItems(scope.barbershopId, scope.barberId, clock.instant())
            if (pending.isEmpty()) return@execute ActionItemsResponse(emptyList(), emptyList(), emptyList())
            val appointments = bookings.findMany(scope.barbershopId, pending.map { it.appointmentId }.distinct()).associateBy { it.id }
            val contacts = customers.details(scope.barbershopId, appointments.values.map { it.customerId }.distinct())
            val barberNames = barbers.findAll(scope.barbershopId).associateBy { it.id }
            val serviceNames = services.findAll(scope.barbershopId).associateBy { it.id }
            val rows = pending.map { item ->
                val appointment = appointments.getValue(item.appointmentId)
                val customer = contacts.getValue(appointment.customerId)
                item.copy(barberName = barberNames.getValue(item.barberId).name, serviceName = serviceNames.getValue(appointment.serviceId).name,
                    customerName = customer.name, customerPhone = customer.phone)
            }
            ActionItemsResponse(rows.filter { it.status == DepositStatus.PENDING }, rows.filter { it.status == DepositStatus.LATE_PAYMENT },
                rows.filter { it.refundStatus == RefundStatus.PENDING })
        }
    }
}
