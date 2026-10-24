package com.geovannycode.corteya.booking

import com.geovannycode.corteya.identity.AuthorizationPolicy
import com.geovannycode.corteya.shared.TenantTransaction
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service

@Service
class BookingQuoteService(
    private val availability: AvailabilityService, private val settings: PaymentSettingsService,
    private val policy: AuthorizationPolicy, private val tenants: TenantTransaction, private val clock: Clock,
) {
    fun quote(userId: UUID, slug: String, barberId: UUID, serviceId: UUID, startsAt: Instant): BookingTerms {
        policy.user(userId)
        val selection = availability.select(slug, barberId, serviceId)
        return tenants.execute(selection.shop.id) {
            val configuration = settings.requireConfigured(selection.shop.id)
            availability.requireSlot(selection, startsAt, clock.instant())
            BookingTerms.calculate(selection.service.priceCop, configuration.mode, selection.shop.depositPercent,
                configuration.holdMinutes, startsAt, selection.shop.cancelHours)
        }
    }
}
