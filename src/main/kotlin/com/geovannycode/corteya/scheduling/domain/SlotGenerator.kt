package com.geovannycode.corteya.scheduling.domain

import com.geovannycode.corteya.booking.domain.AppointmentStatus
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class TimeInterval(val startsAt: Instant, val endsAt: Instant) {
    fun overlaps(other: TimeInterval): Boolean = startsAt < other.endsAt && other.startsAt < endsAt
}

data class OccupyingAppointment(
    val interval: TimeInterval,
    val status: AppointmentStatus,
    val holdExpiresAt: Instant?,
) {
    fun occupies(now: Instant): Boolean = status == AppointmentStatus.CONFIRMED ||
        (status == AppointmentStatus.PENDING_PAYMENT && holdExpiresAt?.let { it >= now } == true)
}

data class BookingRules(val slotStepMinutes: Int, val minLeadMinutes: Int, val bookingWindowDays: Int)
data class Slots(val morning: List<Instant>, val afternoon: List<Instant>) {
    fun all(): List<Instant> = morning + afternoon
}

/** Pure calculation. The booking window includes today through today + window - 1. */
object SlotGenerator {
    fun generate(
        workingHours: List<WorkingHours>,
        timeOff: List<TimeInterval>,
        appointments: List<OccupyingAppointment>,
        durationMinutes: Int,
        date: LocalDate,
        zone: ZoneId,
        rules: BookingRules,
        now: Instant,
    ): Slots {
        require(durationMinutes > 0 && rules.slotStepMinutes > 0 && rules.minLeadMinutes >= 0 && rules.bookingWindowDays > 0)
        val today = now.atZone(zone).toLocalDate()
        if (date < today || date >= today.plusDays(rules.bookingWindowDays.toLong())) return Slots(emptyList(), emptyList())
        val earliest = now.plusSeconds(rules.minLeadMinutes.toLong() * 60)
        val busy = timeOff + appointments.filter { it.occupies(now) }.map { it.interval }
        val candidates = workingHours.filter { it.dayOfWeek == date.dayOfWeek.value }.flatMap { hours ->
            generateSequence(date.atTime(hours.startTime)) { it.plusMinutes(rules.slotStepMinutes.toLong()) }
                .takeWhile { it.toLocalDate() == date && it.toLocalTime() < hours.endTime }
                .flatMap { local ->
                    // Skip nonexistent local times during DST; preserve both offsets when clocks repeat.
                    zone.rules.getValidOffsets(local).map { offset -> local.toInstant(offset) }
                }
                .filter { start ->
                    val end = start.plusSeconds(durationMinutes.toLong() * 60)
                    val close = date.atTime(hours.endTime).atZone(zone).toInstant()
                    val interval = TimeInterval(start, end)
                    start >= earliest && end <= close && busy.none { it.overlaps(interval) }
                }.toList()
        }.distinct().sorted()
        val (morning, afternoon) = candidates.partition { it.atZone(zone).toLocalTime() < LocalTime.NOON }
        return Slots(morning, afternoon)
    }
}
