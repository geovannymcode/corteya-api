package com.geovannycode.corteya.identity.infrastructure.otp

import com.geovannycode.corteya.identity.application.OtpSender
import com.geovannycode.corteya.identity.domain.PhoneNumbers
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["auth.otp.sender"], havingValue = "log")
class LogOtpSender(environment: Environment) : OtpSender {
    init { require(environment.acceptsProfiles(Profiles.of("dev"))) { "LogOtpSender solo puede usarse en dev" } }
    private val logger = LoggerFactory.getLogger(javaClass)
    override fun send(phone: String, code: String) { logger.info("OTP de desarrollo para {}: {}", PhoneNumbers.mask(phone), code) }
}
