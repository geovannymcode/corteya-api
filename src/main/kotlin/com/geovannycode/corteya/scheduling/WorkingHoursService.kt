package com.geovannycode.corteya.scheduling

import java.time.LocalTime
import java.util.UUID
import org.springframework.stereotype.Service

/** Weekly working interval used by the pure slot calculation, without a persistence identity. */
data class WorkingSchedule(val barbershopId: UUID, val barberId: UUID, val dayOfWeek: Int,
    val startTime: LocalTime, val endTime: LocalTime)

@Service
class WorkingHoursService(private val repository: WorkingHoursRepository) {
    fun findForBarber(barbershopId: UUID, barberId: UUID): List<WorkingSchedule> =
        repository.findForBarber(barbershopId, barberId).map { it.schedule() }
    fun findAll(barbershopId: UUID): List<WorkingSchedule> = repository.findAll(barbershopId).map { it.schedule() }

    private fun WorkingHoursEntity.schedule() = WorkingSchedule(barbershopId, barberId, dayOfWeek, startTime, endTime)
}
