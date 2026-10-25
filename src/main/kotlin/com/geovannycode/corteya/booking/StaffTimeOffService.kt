package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.dto.*
import com.geovannycode.corteya.booking.internal.AppointmentRepository
import com.geovannycode.corteya.catalog.BarberDirectoryService
import com.geovannycode.corteya.identity.AuthorizationPolicy
import com.geovannycode.corteya.scheduling.TimeOffService
import com.geovannycode.corteya.scheduling.TimeOffDetails
import com.geovannycode.corteya.shared.ApiFailure
import com.geovannycode.corteya.shared.TenantTransaction
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class StaffTimeOffService(private val guard: AuthorizationPolicy, private val tenants: TenantTransaction,
    private val barbers: BarberDirectoryService, private val blocks: TimeOffService, private val bookings: AppointmentRepository) {
    fun create(user: UUID, slug: String, request: TimeOffRequest): TimeOffResponse {
        val scope = guard.agenda(user, slug, request.barberId)
        val from = request.startsAt.toInstant()
        val to = request.endsAt.toInstant()
        validRange(from, to)
        return tenants.execute(scope.barbershopId) {
            if (!barbers.lockSchedule(scope.barbershopId, request.barberId)) BookingFailure.notFound()
            val conflicts = bookings.agenda(scope.barbershopId, request.barberId, from, to)
                .filter { it.status in listOf(AppointmentStatus.PENDING_PAYMENT, AppointmentStatus.CONFIRMED) }
                .map { AppointmentConflict(it.id, it.publicToken, it.startsAt.bogota(), it.endsAt.bogota()) }
            if (conflicts.isNotEmpty()) throw ApiFailure(409, "El bloqueo se cruza con citas existentes", mapOf("conflicts" to conflicts))
            val id = blocks.create(scope.barbershopId, request.barberId, from, to, request.reason.trim())
            TimeOffResponse(id, request.barberId, from.bogota(), to.bogota(), request.reason.trim())
        }
    }
    fun list(user: UUID, slug: String, from: Instant, to: Instant): List<TimeOffResponse> {
        validRange(from, to)
        val scope = guard.agenda(user, slug)
        return tenants.execute(scope.barbershopId) { blocks.blocks(scope.barbershopId, scope.barberId, from, to).map { it.response() } }
    }
    fun delete(user: UUID, slug: String, id: UUID) {
        val scope = guard.agenda(user, slug)
        tenants.execute(scope.barbershopId) {
            val block = blocks.get(scope.barbershopId, id) ?: BookingFailure.notFound()
            guard.requireBarber(scope, block.barberId)
            barbers.lockSchedule(scope.barbershopId, block.barberId)
            blocks.delete(scope.barbershopId, id)
        }
    }
    private fun validRange(from: Instant, to: Instant) {
        if (to <= from) throw ApiFailure(400, "La hora final debe ser posterior a la inicial")
    }
    private fun TimeOffDetails.response() = TimeOffResponse(id, barberId, startsAt.bogota(), endsAt.bogota(), reason)
    private fun Instant.bogota() = atZone(ZoneId.of("America/Bogota")).toOffsetDateTime()
}
