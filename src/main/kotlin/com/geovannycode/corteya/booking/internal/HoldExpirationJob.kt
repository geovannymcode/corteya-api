package com.geovannycode.corteya.booking.internal

import com.geovannycode.corteya.shared.TenantTransaction
import com.geovannycode.corteya.barbershop.BarbershopService
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class HoldExpirationJob(private val bookings: AppointmentRepository, private val clock: Clock, private val shops: BarbershopService, private val tenants: TenantTransaction, private val deposits: DepositRepository) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // TODO: use ShedLock if the deployment ever runs more than one instance.
    @Scheduled(cron = "0 * * * * *", zone = "UTC")
    fun expire() {
        val now = clock.instant()
        shops.activeIds().forEach { id ->
            try {
                tenants.execute(id) {
                    bookings.expireForShop(id, now)
                    deposits.expirePending(id, now)
                }
            } catch (failure: Exception) {
                logger.warn("No se pudieron expirar los holds de la barbería {}; tipo={}", id, failure.javaClass.name)
            }
        }
    }
}
