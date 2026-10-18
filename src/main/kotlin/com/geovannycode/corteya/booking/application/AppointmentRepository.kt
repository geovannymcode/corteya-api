package com.geovannycode.corteya.booking.application

import com.geovannycode.corteya.booking.domain.Appointment
import java.util.UUID

/** Tenant-scoped read port. Mutation use cases belong to subsequent phases. */
interface AppointmentRepository {
    fun findById(barbershopId: UUID, id: UUID): Appointment?
    fun findAll(barbershopId: UUID): List<Appointment>
}
