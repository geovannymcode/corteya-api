package com.geovannycode.corteya.barbershop

import com.geovannycode.corteya.catalog.BarberDirectoryService
import com.geovannycode.corteya.scheduling.TimeInterval
import com.geovannycode.corteya.scheduling.WorkingHoursService
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.springframework.stereotype.Component

data class ShopStatus(val open: Boolean, val message: String, val nextChangeAt: Instant?)

@Component
class ShopStatusService(
    private val barbers: BarberDirectoryService,
    private val hours: WorkingHoursService,
    private val clock: Clock,
) {
    fun status(shop: ShopDetails): ShopStatus {
        val now = clock.instant()
        val zone = ZoneId.of(shop.timezone)
        val today = now.atZone(zone).toLocalDate()
        val activeBarbers = barbers.findAll(shop.id).filter { it.active }.map { it.id }.toSet()
        val schedules = hours.findAll(shop.id).filter { it.barberId in activeBarbers }
        val intervals = (0..7).flatMap { offset ->
            val date = today.plusDays(offset.toLong())
            schedules.filter { it.dayOfWeek == date.dayOfWeek.value }.map {
                TimeInterval(date.atTime(it.startTime).atZone(zone).toInstant(), date.atTime(it.endTime).atZone(zone).toInstant())
            }
        }.sortedBy { it.startsAt }.fold(emptyList<TimeInterval>()) { merged, interval ->
            val last = merged.lastOrNull()
            if (last != null && interval.startsAt <= last.endsAt) {
                merged.dropLast(1) + TimeInterval(last.startsAt, maxOf(last.endsAt, interval.endsAt))
            } else merged + interval
        }
        val current = intervals.firstOrNull { now >= it.startsAt && now < it.endsAt }
        if (current != null) return ShopStatus(true, "abierto, cierra a las ${formatTime(current.endsAt.atZone(zone).toLocalTime())}", current.endsAt)
        val next = intervals.firstOrNull { it.startsAt > now }
            ?: return ShopStatus(false, "cerrado, sin horario de apertura disponible", null)
        val nextLocal = next.startsAt.atZone(zone)
        val day = when (nextLocal.toLocalDate()) {
            today -> "hoy"
            today.plusDays(1) -> "mañana"
            else -> "el " + listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")[nextLocal.dayOfWeek.value - 1]
        }
        return ShopStatus(false, "cerrado, abre $day a las ${formatTime(nextLocal.toLocalTime())}", next.startsAt)
    }

    private fun formatTime(time: LocalTime): String =
        "${if (time.hour % 12 == 0) 12 else time.hour % 12}:${time.minute.toString().padStart(2, '0')} ${if (time.hour < 12) "a. m." else "p. m."}"
}
