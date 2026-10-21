package com.geovannycode.corteya.booking.internal

import com.geovannycode.corteya.booking.*
import com.geovannycode.corteya.booking.dto.PaymentSettingsRequest
import com.geovannycode.corteya.shared.TenantTransaction
import java.util.UUID
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
    override fun run(args: ApplicationArguments) {
        val names = listOf("WOMPI_SANDBOX_PUBLIC_KEY", "WOMPI_SANDBOX_PRIVATE_KEY", "WOMPI_SANDBOX_INTEGRITY_SECRET", "WOMPI_SANDBOX_EVENTS_SECRET")
        val keys = names.map { environment.getProperty(it)?.takeIf(String::isNotBlank) }
        val request = if (keys.all { it != null }) PaymentSettingsRequest(DepositMode.WOMPI,
            wompiEnvironment = WompiEnvironment.SANDBOX, wompiPublicKey = keys[0], wompiPrivateKey = keys[1],
            wompiIntegritySecret = keys[2], wompiEventsSecret = keys[3])
        else PaymentSettingsRequest(DepositMode.BREB_MANUAL, BrebKeyType.ALIAS, "@DEMO", "Demo: reemplazar antes de recibir pagos")
        val shop = UUID.fromString("10000000-0000-0000-0000-000000000001")
        tenants.execute(shop) { settings.configure(shop, request) }
    }
}
