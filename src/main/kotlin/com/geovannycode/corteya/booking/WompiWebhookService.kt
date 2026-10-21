package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.internal.*
import com.geovannycode.corteya.shared.ApiFailure
import com.geovannycode.corteya.shared.TenantTransaction
import java.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.JsonNode

@Service
class WompiWebhookService(
    private val settings: PaymentSettingsService, private val secrets: PaymentSecrets, private val gateway: WompiGateway,
    private val deposits: DepositRepository, private val bookings: AppointmentRepository,
    private val confirmation: DepositConfirmationService, private val tenants: TenantTransaction, private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun process(webhookId: String, event: JsonNode) {
        val configuration = settings.webhookSettings(webhookId)
        val shop = configuration.barbershopId
        val secret = secrets.decrypt(checkNotNull(configuration.eventsSecretEnc), "$shop:events")
        if (!WompiSignatures.validEvent(event, secret)) {
            logger.warn("Firma de evento Wompi inválida; barbería={}", shop)
            throw ApiFailure(401, "La firma del evento no es válida")
        }
        val environment = if (configuration.environment == WompiEnvironment.SANDBOX) "test" else "prod"
        if (event.path("environment").asString("") != environment) throw ApiFailure(400, "El ambiente del evento no coincide")
        if (event.path("event").asString("") != "transaction.updated") return
        val notified = event.path("data").path("transaction")
        val transactionId = notified.path("id").asString("")
        // All network I/O finishes before obtaining any appointment/payment locks.
        val verified = gateway.transaction(configuration, transactionId)
        val payment = tenants.execute(shop) { deposits.byReference(shop, verified.path("reference").asString("")) }
            ?: throw ApiFailure(400, "No encontramos la referencia del pago")
        val cents = Math.multiplyExact(payment.amountCop, 100L)
        if (payment.method != DepositMode.WOMPI || verified.path("id").asString("") != transactionId ||
            verified.path("reference").asString("") != notified.path("reference").asString("") ||
            verified.path("currency").asString("") != "COP" || notified.path("currency").asString("") != "COP" ||
            !verified.path("amount_in_cents").isIntegralNumber || verified.path("amount_in_cents").asLong() != cents ||
            !notified.path("amount_in_cents").isIntegralNumber || notified.path("amount_in_cents").asLong() != cents ||
            verified.path("status").asString("") != notified.path("status").asString("")) {
            throw ApiFailure(400, "La transacción no coincide con el pago esperado")
        }
        val status = verified.path("status").asString("")
        if (status == "APPROVED") {
            confirmation.confirm(shop, payment.id, DepositEventSource.WEBHOOK, transactionId = transactionId)
        } else if (status in listOf("DECLINED", "VOIDED", "ERROR")) tenants.execute(shop) {
            val a = bookings.lock(shop, payment.appointmentId) ?: BookingFailure.notFound()
            val current = deposits.find(shop, payment.id) ?: BookingFailure.notFound()
            if (current.providerTransactionId != null && current.providerTransactionId != transactionId) {
                throw ApiFailure(400, "La transacción no coincide con el pago esperado")
            }
            if (current.status in listOf(DepositStatus.PENDING, DepositStatus.EXPIRED) && a.status == AppointmentStatus.PENDING_PAYMENT) {
                deposits.transition(current, DepositStatus.valueOf(status), DepositEventSource.WEBHOOK, clock.instant(),
                    transactionId = transactionId, providerStatus = status)
            }
        }
    }
}
