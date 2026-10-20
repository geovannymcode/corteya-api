package com.geovannycode.corteya.booking

import com.geovannycode.corteya.barbershop.application.BarbershopRepository
import com.geovannycode.corteya.barbershop.domain.Barbershop
import com.geovannycode.corteya.catalog.application.BarberRepository
import com.geovannycode.corteya.catalog.application.BarberServiceRepository
import com.geovannycode.corteya.catalog.application.ServiceRepository
import com.geovannycode.corteya.catalog.domain.Barber
import com.geovannycode.corteya.catalog.domain.BarberService
import com.geovannycode.corteya.catalog.domain.Service
import com.geovannycode.corteya.booking.application.AvailabilityService
import com.geovannycode.corteya.booking.application.BookingSelection
import com.geovannycode.corteya.booking.application.BookingStore
import com.geovannycode.corteya.booking.domain.AppointmentStatus
import com.geovannycode.corteya.scheduling.application.TimeOffRepository
import com.geovannycode.corteya.scheduling.application.WorkingHoursRepository
import com.geovannycode.corteya.scheduling.domain.*
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

}
