package com.geovannycode.corteya.booking.application

import com.geovannycode.corteya.barbershop.application.BarbershopRepository
import com.geovannycode.corteya.barbershop.domain.Barbershop
import com.geovannycode.corteya.catalog.application.BarberRepository
import com.geovannycode.corteya.catalog.application.BarberServiceRepository
import com.geovannycode.corteya.catalog.application.ServiceRepository
import com.geovannycode.corteya.catalog.domain.Barber
import com.geovannycode.corteya.catalog.domain.Service
import com.geovannycode.corteya.scheduling.application.TimeOffRepository
import com.geovannycode.corteya.scheduling.application.WorkingHoursRepository
import com.geovannycode.corteya.scheduling.domain.BookingRules
import com.geovannycode.corteya.scheduling.domain.SlotGenerator
import com.geovannycode.corteya.scheduling.domain.Slots
import com.geovannycode.corteya.scheduling.domain.TimeInterval
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Component

data class BookingSelection(val shop: Barbershop, val barber: Barber, val service: Service)
data class BarberAvailability(val barber: Barber, val nextAvailableAt: Instant?)

@Component
class AvailabilityService(
    private val shops: BarbershopRepository,
    private val barbers: BarberRepository,
    private val services: ServiceRepository,
    private val barberServices: BarberServiceRepository,
    private val hours: WorkingHoursRepository,
    private val timeOff: TimeOffRepository,
    private val bookings: BookingStore,
    private val clock: Clock,
) {
    fun shop(slug: String): Barbershop = shops.findBySlug(slug) ?: BookingFailure.notFound()

    fun services(slug: String): List<Service> = services.findAll(shop(slug).id).filter { it.active }.sortedBy { it.name }

    fun select(slug: String, barberId: UUID, serviceId: UUID): BookingSelection {
        val shop = shop(slug)
        val barber = barbers.findById(shop.id, barberId)?.takeIf { it.active } ?: BookingFailure.notFound()
        val service = services.findById(shop.id, serviceId)?.takeIf { it.active } ?: BookingFailure.notFound()
        if (barberServices.findAll(shop.id).none { it.barberId == barber.id && it.serviceId == service.id }) BookingFailure.notFound()
        return BookingSelection(shop, barber, service)
    }

    fun availability(slug: String, barberId: UUID, serviceId: UUID, date: LocalDate): Slots =
        slots(select(slug, barberId, serviceId), date, clock.instant())

    fun slots(selection: BookingSelection, date: LocalDate, now: Instant): Slots {
        val (shop, barber, service) = selection
        val zone = ZoneId.of(shop.timezone)
        val today = now.atZone(zone).toLocalDate()
        if (date < today || date >= today.plusDays(shop.bookingWindowDays.toLong())) return Slots(emptyList(), emptyList())
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        return SlotGenerator.generate(
            hours.findForBarber(shop.id, barber.id),
            timeOff.findOverlapping(shop.id, barber.id, start, end).map { TimeInterval(it.startsAt, it.endsAt) },
            bookings.occupying(shop.id, barber.id, start, end), service.durationMinutes, date, zone,
            BookingRules(shop.slotStepMinutes, shop.minLeadMinutes, shop.bookingWindowDays), now,
        )
    }

    fun barbers(slug: String, serviceId: UUID): List<BarberAvailability> {
        val shop = shop(slug)
        val service = services.findById(shop.id, serviceId)?.takeIf { it.active } ?: BookingFailure.notFound()
        val eligible = barberServices.findAll(shop.id).filter { it.serviceId == serviceId }.map { it.barberId }.toSet()
        val now = clock.instant()
        val today = now.atZone(ZoneId.of(shop.timezone)).toLocalDate()
        return barbers.findAll(shop.id).filter { it.active && it.id in eligible }.sortedBy { it.name }.map { barber ->
            val selection = BookingSelection(shop, barber, service)
            val next = (0 until shop.bookingWindowDays).asSequence()
                .map { today.plusDays(it.toLong()) }.mapNotNull { slots(selection, it, now).all().firstOrNull() }.firstOrNull()
            BarberAvailability(barber, next)
        }
    }
}
