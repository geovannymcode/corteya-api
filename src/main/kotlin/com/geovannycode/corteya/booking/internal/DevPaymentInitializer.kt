package com.geovannycode.corteya.booking.internal

import com.geovannycode.corteya.booking.*
import com.geovannycode.corteya.booking.dto.PaymentSettingsRequest
import com.geovannycode.corteya.shared.TenantTransaction
import java.util.UUID
import java.util.Base64
import org.slf4j.LoggerFactory
import com.geovannycode.corteya.shared.ApiFailure
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/** No sandbox environment variables are read by any production component. */
@Component
@Profile("dev")
class DevPaymentInitializer(private val settings: PaymentSettingsService, private val tenants: TenantTransaction,
    private val environment: Environment) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)
    override fun run(args: ApplicationArguments) {
        val manual = PaymentSettingsRequest(DepositMode.BREB_MANUAL, BrebKeyType.ALIAS, "@DEMO", "Titular ficticio de prueba")
        val shop = UUID.fromString("10000000-0000-0000-0000-000000000001")
        val request = sandboxRequest() ?: manual
        try {
            tenants.execute(shop) { settings.configure(shop, request) }
        } catch (failure: ApiFailure) {
            if (request.depositMode != DepositMode.WOMPI) throw failure
            logger.warn("Configuración dev inválida: PAYMENT_SECRETS_KEY")
            tenants.execute(shop) { settings.configure(shop, manual) }
        }
        tenants.execute(UUID.fromString("10000000-0000-0000-0000-000000000002")) {
            settings.configure(UUID.fromString("10000000-0000-0000-0000-000000000002"),
                PaymentSettingsRequest(DepositMode.BREB_MANUAL, BrebKeyType.ALIAS, "@DEMOBREB", "Titular ficticio Bre-B"))
        }
        tenants.execute(UUID.fromString("10000000-0000-0000-0000-000000000003")) {
            settings.configure(UUID.fromString("10000000-0000-0000-0000-000000000003"), PaymentSettingsRequest(DepositMode.NONE))
        }
    }

    private fun sandboxRequest(): PaymentSettingsRequest? {
        val patterns = linkedMapOf("WOMPI_SANDBOX_PUBLIC_KEY" to "pub_test_[A-Za-z0-9]+",
            "WOMPI_SANDBOX_PRIVATE_KEY" to "prv_test_[A-Za-z0-9]+",
            "WOMPI_SANDBOX_INTEGRITY_SECRET" to "test_integrity_[A-Za-z0-9]+",
            "WOMPI_SANDBOX_EVENTS_SECRET" to "test_events_[A-Za-z0-9]+")
        val values = patterns.keys.associateWith { environment.getProperty(it).orEmpty() }
        if (values.values.all(String::isEmpty)) return null
        val invalid = patterns.keys.filter { values.getValue(it).length > 200 || !values.getValue(it).matches(Regex(patterns.getValue(it))) }
        invalid.forEach { logger.warn("Configuración dev inválida: {}", it) }
        if (invalid.isNotEmpty()) return null
        val master = try { Base64.getDecoder().decode(environment.getProperty("payments.secrets.key").orEmpty()) }
            catch (_: IllegalArgumentException) { byteArrayOf() }
        if (master.size != 32) {
            logger.warn("Configuración dev inválida: PAYMENT_SECRETS_KEY")
            return null
        }
        return PaymentSettingsRequest(DepositMode.WOMPI, wompiEnvironment = WompiEnvironment.SANDBOX,
            wompiPublicKey = values["WOMPI_SANDBOX_PUBLIC_KEY"], wompiPrivateKey = values["WOMPI_SANDBOX_PRIVATE_KEY"],
            wompiIntegritySecret = values["WOMPI_SANDBOX_INTEGRITY_SECRET"], wompiEventsSecret = values["WOMPI_SANDBOX_EVENTS_SECRET"])
    }
}
