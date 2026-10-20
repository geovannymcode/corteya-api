package com.geovannycode.corteya.booking

import com.geovannycode.corteya.barbershop.BarbershopRepository
import com.geovannycode.corteya.barbershop.Barbershop
import com.geovannycode.corteya.catalog.BarberRepository
import com.geovannycode.corteya.catalog.BarberServiceRepository
import com.geovannycode.corteya.catalog.ServiceRepository
import com.geovannycode.corteya.catalog.Barber
import com.geovannycode.corteya.catalog.Service
import com.geovannycode.corteya.scheduling.TimeOffRepository
import com.geovannycode.corteya.scheduling.WorkingHoursRepository
import com.geovannycode.corteya.scheduling.BookingRules
import com.geovannycode.corteya.scheduling.SlotGenerator
import com.geovannycode.corteya.scheduling.Slots
import com.geovannycode.corteya.scheduling.TimeInterval
import com.geovannycode.corteya.scheduling.OccupyingAppointment
import com.geovannycode.corteya.scheduling.WorkingHours
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
        val shop = selection.shop
        val zone = ZoneId.of(shop.timezone)
        val today = now.atZone(zone).toLocalDate()
        if (date < today || date >= today.plusDays(shop.bookingWindowDays.toLong())) return Slots(emptyList(), emptyList())
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        return generate(selection, date, zone, now, loadInputs(selection, start, end))
    }

    fun barbers(slug: String, serviceId: UUID): List<BarberAvailability> {
        val shop = shop(slug)
        val service = services.findById(shop.id, serviceId)?.takeIf { it.active } ?: BookingFailure.notFound()
        val eligible = barberServices.findAll(shop.id).filter { it.serviceId == serviceId }.map { it.barberId }.toSet()
        val now = clock.instant()
        val zone = ZoneId.of(shop.timezone)
        val today = now.atZone(zone).toLocalDate()
        val start = today.atStartOfDay(zone).toInstant()
        val end = today.plusDays(shop.bookingWindowDays.toLong()).atStartOfDay(zone).toInstant()
        return barbers.findAll(shop.id).filter { it.active && it.id in eligible }.sortedBy { it.name }.map { barber ->
            val selection = BookingSelection(shop, barber, service)
            val inputs = loadInputs(selection, start, end)
            val next = (0 until shop.bookingWindowDays).asSequence()
                .map { today.plusDays(it.toLong()) }
                .mapNotNull { generate(selection, it, zone, now, inputs).all().firstOrNull() }.firstOrNull()
            BarberAvailability(barber, next)
        }
    }

    private fun loadInputs(selection: BookingSelection, start: Instant, end: Instant): AvailabilityInputs {
        val shopId = selection.shop.id
        val barberId = selection.barber.id
        return AvailabilityInputs(
            hours.findForBarber(shopId, barberId),
            timeOff.findOverlapping(shopId, barberId, start, end).map { TimeInterval(it.startsAt, it.endsAt) },
            bookings.occupying(shopId, barberId, start, end),
        )
    }

    private fun generate(selection: BookingSelection, date: LocalDate, zone: ZoneId, now: Instant, inputs: AvailabilityInputs): Slots {
        val shop = selection.shop
        val day = TimeInterval(date.atStartOfDay(zone).toInstant(), date.plusDays(1).atStartOfDay(zone).toInstant())
        return SlotGenerator.generate(
            inputs.hours.filter { it.dayOfWeek == date.dayOfWeek.value }, inputs.timeOff.filter { it.overlaps(day) },
            inputs.appointments.filter { it.interval.overlaps(day) }, selection.service.durationMinutes, date, zone,
            BookingRules(shop.slotStepMinutes, shop.minLeadMinutes, shop.bookingWindowDays), now,
        )
    }

    private data class AvailabilityInputs(
        val hours: List<WorkingHours>, val timeOff: List<TimeInterval>, val appointments: List<OccupyingAppointment>,
    )
}
