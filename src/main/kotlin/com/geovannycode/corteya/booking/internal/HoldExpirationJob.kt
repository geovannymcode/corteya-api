package com.geovannycode.corteya.booking.internal

import java.time.Clock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class HoldExpirationJob(private val bookings: AppointmentRepository, private val clock: Clock) {
    // TODO: use ShedLock if the deployment ever runs more than one instance.
    @Scheduled(cron = "0 * * * * *", zone = "UTC")
    fun expire() { bookings.expireAll(clock.instant()) }
}
