package com.geovannycode.corteya.identity

import com.geovannycode.corteya.identity.IdentityStore
import java.time.Clock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class OtpRetentionJob(private val store: IdentityStore, private val clock: Clock) {
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    @Transactional
    fun purge() {
        val now = clock.instant()
        store.purgeExpiredOtps(now.minusSeconds(24L * 3600))
        store.purgeExpiredRefreshTokens(now.minusSeconds(30L * 24 * 3600))
    }
}
