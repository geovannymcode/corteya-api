package com.geovannycode.corteya.booking

import com.geovannycode.corteya.barbershop.BarbershopRepository
import com.geovannycode.corteya.barbershop.Barbershop
import com.geovannycode.corteya.catalog.BarberRepository
import com.geovannycode.corteya.catalog.BarberServiceRepository
import com.geovannycode.corteya.catalog.ServiceRepository
import com.geovannycode.corteya.catalog.Barber
import com.geovannycode.corteya.catalog.BarberService
import com.geovannycode.corteya.catalog.Service
import com.geovannycode.corteya.booking.AvailabilityService
import com.geovannycode.corteya.booking.BookingSelection
import com.geovannycode.corteya.booking.BookingStore
import com.geovannycode.corteya.booking.AppointmentStatus
import com.geovannycode.corteya.scheduling.TimeOffRepository
import com.geovannycode.corteya.scheduling.WorkingHoursRepository
import com.geovannycode.corteya.scheduling.*
import java.time.*
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import kotlin.test.assertEquals

class AvailabilityServiceTests {
    private val shops = mock(BarbershopRepository::class.java)
    private val barbers = mock(BarberRepository::class.java)
    private val services = mock(ServiceRepository::class.java)
    private val assignments = mock(BarberServiceRepository::class.java)
    private val hours = mock(WorkingHoursRepository::class.java)
    private val timeOff = mock(TimeOffRepository::class.java)
    private val bookings = mock(BookingStore::class.java)
    private val now = Instant.parse("2026-10-19T13:00:00Z")
    private val zone = ZoneId.of("America/Bogota")
    private val today = now.atZone(zone).toLocalDate()
    private val shop = Barbershop(slug = "shop-a", name = "Piloto")
    private val service = Service(barbershopId = shop.id, name = "Corte", durationMinutes = 30, priceCop = 38000)
    private val subject = AvailabilityService(shops, barbers, services, assignments, hours, timeOff, bookings,
        Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `out of window dates do not query schedule inputs`() {
        val barber = Barber(barbershopId = shop.id, name = "Barbero")
        listOf(today.minusDays(1), today.plusDays(shop.bookingWindowDays.toLong()), LocalDate.MAX).forEach {
            assertEquals(emptyList(), subject.slots(BookingSelection(shop, barber, service), it, now).all())
        }
        verifyNoInteractions(hours, timeOff, bookings)
    }

    @Test
    fun `next availability matches daily generation with one query per input and barber`() {
        val staff = listOf("Ana", "Beto", "Carlos").map { Barber(barbershopId = shop.id, name = it) }
        `when`(shops.findBySlug(shop.slug)).thenReturn(shop)
        `when`(services.findById(shop.id, service.id)).thenReturn(service)
        `when`(barbers.findAll(shop.id)).thenReturn(staff)
        `when`(assignments.findAll(shop.id)).thenReturn(staff.map {
            BarberService(barbershopId = shop.id, barberId = it.id, serviceId = service.id)
        })
        val windowStart = today.atStartOfDay(zone).toInstant()
        val windowEnd = today.plusDays(14).atStartOfDay(zone).toInstant()
        val expected = staff.mapIndexed { index, barber ->
            val weekly = (1..6).map { WorkingHours(barbershopId = shop.id, barberId = barber.id,
                dayOfWeek = it, startTime = LocalTime.of(9, 0), endTime = LocalTime.of(20, 0)) }
            val blocks = listOf(TimeOff(barbershopId = shop.id, barberId = barber.id,
                startsAt = windowStart, endsAt = if (index == 2) windowEnd else today.plusDays(1).atStartOfDay(zone).toInstant()))
            val occupied = listOf(OccupyingAppointment(
                TimeInterval(today.plusDays(1).atTime(9, 0).atZone(zone).toInstant(),
                    today.plusDays(1).atTime(10, 0).atZone(zone).toInstant()), AppointmentStatus.CONFIRMED, null))
            `when`(hours.findForBarber(shop.id, barber.id)).thenReturn(weekly)
            `when`(timeOff.findOverlapping(shop.id, barber.id, windowStart, windowEnd)).thenReturn(blocks)
            `when`(bookings.occupying(shop.id, barber.id, windowStart, windowEnd)).thenReturn(occupied)
            // Reference: same daily inputs and pure generator as the original implementation.
            (0 until 14).asSequence().mapNotNull { offset ->
                val date = today.plusDays(offset.toLong())
                val day = TimeInterval(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
                SlotGenerator.generate(weekly, blocks.map { TimeInterval(it.startsAt, it.endsAt) }.filter { it.overlaps(day) },
                    occupied.filter { it.interval.overlaps(day) }, 30, date, zone, BookingRules(15, 30, 14), now).all().firstOrNull()
            }.firstOrNull()
        }
        assertEquals(listOf(Instant.parse("2026-10-20T15:00:00Z"), Instant.parse("2026-10-20T15:00:00Z"), null), expected)
        assertEquals(expected, subject.barbers(shop.slug, service.id).map { it.nextAvailableAt })
        staff.forEach { barber ->
            verify(hours, times(1)).findForBarber(shop.id, barber.id)
            verify(timeOff, times(1)).findOverlapping(shop.id, barber.id, windowStart, windowEnd)
            verify(bookings, times(1)).occupying(shop.id, barber.id, windowStart, windowEnd)
        }
        verifyNoMoreInteractions(hours, timeOff, bookings)
    }
}
