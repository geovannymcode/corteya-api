package com.geovannycode.corteya.booking.application

import com.geovannycode.corteya.booking.domain.Appointment
import com.geovannycode.corteya.scheduling.domain.OccupyingAppointment
import java.time.Instant
import java.util.UUID

interface BookingStore {
    fun occupying(barbershopId: UUID, barberId: UUID, startsAt: Instant, endsAt: Instant): List<OccupyingAppointment>
    fun upsertAndLockCustomer(barbershopId: UUID, phone: String, name: String, userId: UUID, now: Instant): UUID
    fun pendingCount(barbershopId: UUID, customerId: UUID): Int
    /** Returns false only for a payment reference collision. */
    fun insert(appointment: Appointment): Boolean
    fun expireForBarber(barbershopId: UUID, barberId: UUID, now: Instant): Int
    /** Deliberate cross-tenant maintenance operation; never called from a public controller. */
    fun expireAll(now: Instant): Int
    /** Resolve capability internally; AuthorizationPolicy must authorize before returning it. */
    fun findByUser(userId: UUID): List<Appointment>
    fun agenda(barbershopId: UUID, barberId: UUID?, startsAt: Instant, endsAt: Instant): List<Appointment>
    fun findByPublicToken(publicToken: String): Appointment?
}
