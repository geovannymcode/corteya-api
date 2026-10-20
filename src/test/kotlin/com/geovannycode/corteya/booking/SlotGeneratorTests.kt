package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.AppointmentStatus
import com.geovannycode.corteya.scheduling.WorkingSchedule as WorkingHours
import com.geovannycode.corteya.scheduling.TimeInterval
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.time.*
import java.util.UUID
import kotlin.test.*

class SlotGeneratorTests {
    private val date = LocalDate.of(2026, 10, 19) // Monday
    private val zone = ZoneId.of("America/Bogota")
    private val rules = BookingRules(15, 30, 14)
    private val now = at("07:00")
    private val hours = listOf(schedule("09:00", "12:00"), schedule("13:00", "18:00"))

    @Test
    fun `starts on the opening boundary and fits exactly on closing boundary`() {
        val slots = generate()
        assertEquals(at("09:00"), slots.morning.first())
        assertEquals(at("11:30"), slots.morning.last())
        assertEquals(at("17:30"), slots.afternoon.last())
        assertEquals(30, slots.all().size)
    }

    @Test
    fun `service that cannot fit before closing is excluded`() {
        val result = generate(duration = 50)
        assertEquals(at("11:00"), result.morning.last())
        assertEquals(at("17:00"), result.afternoon.last())
        assertTrue(generate(duration = 400).all().isEmpty())
    }

    @Test
    fun `time off in the middle subtracts all overlapping starts with half open boundaries`() {
        val result = generate(off = listOf(TimeInterval(at("10:00"), at("11:00"))))
        assertTrue(at("09:30") in result.all())
        assertTrue(at("11:00") in result.all())
        assertTrue(listOf("09:45", "10:00", "10:15", "10:30", "10:45").none { at(it) in result.all() })
    }

    @Test
    fun `confirmed appointment blocks even with a past hold timestamp`() {
        val result = generate(appointments = listOf(appointment(AppointmentStatus.CONFIRMED, now.minusSeconds(1))))
        assertTrue(at("10:00") !in result.all())
        assertTrue(at("09:30") in result.all())
        assertTrue(at("10:30") in result.all())
    }

    @Test
    fun `pending hold is valid through its exact expiry instant`() {
        listOf(now, now.plusSeconds(1)).forEach {
            assertTrue(at("10:00") !in generate(appointments = listOf(appointment(AppointmentStatus.PENDING_PAYMENT, it))).all())
        }
        listOf(null, now.minusNanos(1)).forEach {
            assertTrue(at("10:00") in generate(appointments = listOf(appointment(AppointmentStatus.PENDING_PAYMENT, it))).all())
        }
    }

    @ParameterizedTest
    @EnumSource(AppointmentStatus::class, names = ["CANCELLED", "EXPIRED", "COMPLETED", "NO_SHOW"])
    fun `non occupying statuses are ignored`(status: AppointmentStatus) {
        assertEquals(generate(), generate(appointments = listOf(appointment(status, now.plusSeconds(60)))))
    }

    @Test
    fun `non working day and missing schedules return empty`() {
        assertTrue(generate(day = date.plusDays(1)).all().isEmpty())
        assertTrue(generate(schedules = emptyList()).all().isEmpty())
    }

    @Test
    fun `today respects now and exact minimum lead boundary`() {
        val current = at("09:30")
        val result = generate(current = current)
        assertEquals(at("10:00"), result.all().first())
        assertEquals(at("10:15"), generate(current = current.plusNanos(1)).all().first())
        assertEquals(at("18:00"), generate(current = at("18:00"), customRules = rules.copy(minLeadMinutes = 0),
            schedules = listOf(schedule("18:00", "19:00"))).all().first())
        assertTrue(generate(current = at("19:00")).all().isEmpty())
    }

    @Test
    fun `window includes today and last day but excludes tomorrow after its upper bound`() {
        val schedules = (1..7).map { schedule("09:00", "10:00", it) }
        assertTrue(generate(day = date.minusDays(1), schedules = schedules).all().isEmpty())
        assertTrue(generate(day = date.plusDays(13), schedules = schedules).all().isNotEmpty())
        assertTrue(generate(day = date.plusDays(14), schedules = schedules).all().isEmpty())
    }

    @Test
    fun `slot step anchors on each working period and duplicate intervals do not duplicate starts`() {
        val shifted = listOf(schedule("09:10", "10:10"))
        assertEquals(listOf(at("09:10"), at("09:30")), generate(schedules = shifted + shifted,
            customRules = rules.copy(slotStepMinutes = 20)).all())
    }

    @Test
    fun `noon belongs to the afternoon`() {
        val result = generate(schedules = listOf(schedule("11:45", "13:00")))
        assertEquals(listOf(at("11:45")), result.morning)
        assertEquals(at("12:00"), result.afternoon.first())
    }

    @Test
    fun `shop timezone determines date and UTC conversion`() {
        val result = generate()
        assertEquals(Instant.parse("2026-10-19T14:00:00Z"), result.all().first())
        // 02:00 UTC is still the preceding day in Colombia.
        val utcDate = Instant.parse("2026-10-20T02:00:00Z")
        assertTrue(generate(current = utcDate, day = date).all().isEmpty())
        assertTrue(generate(current = utcDate, day = date.plusDays(1),
            schedules = listOf(schedule("09:00", "10:00", 2))).all().isNotEmpty())
    }

    @Test
    fun `daylight saving gap is skipped and repeated offsets remain distinct instants`() {
        val newYork = ZoneId.of("America/New_York")
        val spring = LocalDate.of(2026, 3, 8)
        val springSlots = SlotGenerator.generate(listOf(schedule("01:00", "04:00", 7)), emptyList(), emptyList(),
            30, spring, newYork, BookingRules(30, 0, 1), spring.atStartOfDay(newYork).toInstant())
        assertEquals(4, springSlots.all().size)
        assertTrue(springSlots.all().none { it.atZone(newYork).hour == 2 })
        val fall = LocalDate.of(2026, 11, 1)
        val fallSlots = SlotGenerator.generate(listOf(schedule("01:00", "02:00", 7)), emptyList(), emptyList(),
            30, fall, newYork, BookingRules(30, 0, 1), fall.atStartOfDay(newYork).toInstant())
        assertEquals(4, fallSlots.all().size)
    }

    @Test
    fun `invalid rules fail fast instead of creating an infinite sequence`() {
        assertFailsWith<IllegalArgumentException> { generate(customRules = rules.copy(slotStepMinutes = 0)) }
        assertFailsWith<IllegalArgumentException> { generate(duration = 0) }
        assertFailsWith<IllegalArgumentException> { generate(customRules = rules.copy(minLeadMinutes = -1)) }
        assertFailsWith<IllegalArgumentException> { generate(customRules = rules.copy(bookingWindowDays = 0)) }
    }

    private fun generate(duration: Int = 30, off: List<TimeInterval> = emptyList(),
        appointments: List<OccupyingAppointment> = emptyList(), day: LocalDate = date,
        current: Instant = now, schedules: List<WorkingHours> = hours, customRules: BookingRules = rules) =
        SlotGenerator.generate(schedules, off, appointments, duration, day, zone, customRules, current)

    private fun at(time: String): Instant = date.atTime(LocalTime.parse(time)).atZone(zone).toInstant()
    private fun schedule(start: String, end: String, day: Int = 1) = WorkingHours(
        barbershopId = UUID.randomUUID(), barberId = UUID.randomUUID(), dayOfWeek = day,
        startTime = LocalTime.parse(start), endTime = LocalTime.parse(end),
    )
    private fun appointment(status: AppointmentStatus, expiry: Instant?) =
        OccupyingAppointment(TimeInterval(at("10:00"), at("10:30")), status, expiry)
}
