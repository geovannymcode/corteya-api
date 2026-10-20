package com.geovannycode.corteya.barbershop

import java.util.UUID
import org.springframework.stereotype.Service

/** Shop configuration and branding exposed to other features, without persistence metadata. */
data class ShopDetails(
    val id: UUID = UUID.randomUUID(), val slug: String, val name: String, val logoUrl: String? = null,
    val primaryColor: String = "#1F2937", val secondaryColor: String = "#F59E0B",
    val timezone: String = "America/Bogota", val depositPercent: Int = 30, val cancelHours: Int = 24,
    val active: Boolean = true, val slotStepMinutes: Int = 15, val minLeadMinutes: Int = 30,
    val bookingWindowDays: Int = 14, val holdMinutes: Int = 15,
)

@Service
class BarbershopService(private val repository: BarbershopRepository) {
    fun findBySlug(slug: String): ShopDetails? = repository.findBySlug(slug)?.details()
    fun findById(barbershopId: UUID): ShopDetails? = repository.findById(barbershopId)?.details()

    private fun BarbershopEntity.details() = ShopDetails(id, slug, name, logoUrl, primaryColor, secondaryColor,
        timezone, depositPercent, cancelHours, active, slotStepMinutes, minLeadMinutes, bookingWindowDays, holdMinutes)
}
