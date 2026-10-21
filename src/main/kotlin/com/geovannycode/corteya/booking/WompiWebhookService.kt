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
        val payment = tenants.execute(shop) {
            deposits.byReference(shop, verified.path("reference").asString(""))
                ?: deposits.byReference(shop, notified.path("reference").asString(""))
        }
        if (payment == null) {
            if (verified.path("id").asString("") != transactionId) invalidTransaction()
            logger.warn("Transacción Wompi ajena a CorteYa; barbería={}; transacción={}", shop, transactionId)
            return
        }
        discrepancy(payment, transactionId, notified, verified)?.let { reject(payment, it) }
        val status = verified.path("status").asString("")
        if (status == "APPROVED") {
            confirmation.confirm(shop, payment.id, DepositEventSource.WEBHOOK, transactionId = transactionId)
        } else if (status in listOf("DECLINED", "VOIDED", "ERROR")) {
            val mismatch = tenants.execute(shop) {
                val a = bookings.lock(shop, payment.appointmentId) ?: BookingFailure.notFound()
                val current = deposits.find(shop, payment.id) ?: BookingFailure.notFound()
                if (current.providerTransactionId != null && current.providerTransactionId != transactionId) {
                    return@execute DepositDiscrepancyReason.TRANSACTION_MISMATCH
                }
                if (current.status in listOf(DepositStatus.PENDING, DepositStatus.EXPIRED) && a.status == AppointmentStatus.PENDING_PAYMENT) {
                    deposits.transition(current, DepositStatus.valueOf(status), DepositEventSource.WEBHOOK, clock.instant(),
                        transactionId = transactionId, providerStatus = status)
                }
                null
            }
            mismatch?.let { reject(payment, it) }
        }
    }

    private fun discrepancy(payment: DepositPayment, transactionId: String, notified: JsonNode, verified: JsonNode): DepositDiscrepancyReason? {
        val cents = Math.multiplyExact(payment.amountCop, 100L)
        return when {
            payment.method != DepositMode.WOMPI -> DepositDiscrepancyReason.METHOD_MISMATCH
            verified.path("id").asString("") != transactionId ||
                (payment.providerTransactionId != null && payment.providerTransactionId != transactionId) -> DepositDiscrepancyReason.TRANSACTION_MISMATCH
            verified.path("reference").asString("") != notified.path("reference").asString("") -> DepositDiscrepancyReason.REFERENCE_MISMATCH
            verified.path("currency").asString("") != "COP" || notified.path("currency").asString("") != "COP" -> DepositDiscrepancyReason.CURRENCY_MISMATCH
            !verified.path("amount_in_cents").isIntegralNumber || verified.path("amount_in_cents").asLong() != cents ||
                !notified.path("amount_in_cents").isIntegralNumber || notified.path("amount_in_cents").asLong() != cents -> DepositDiscrepancyReason.AMOUNT_MISMATCH
            verified.path("status").asString("") != notified.path("status").asString("") -> DepositDiscrepancyReason.STATUS_MISMATCH
            else -> null
        }
    }

    private fun reject(payment: DepositPayment, reason: DepositDiscrepancyReason): Nothing {
        // Commit the audit before throwing the HTTP error, so the rejection cannot roll it back.
        tenants.execute(payment.barbershopId) {
            bookings.lock(payment.barbershopId, payment.appointmentId) ?: BookingFailure.notFound()
            val current = deposits.find(payment.barbershopId, payment.id) ?: BookingFailure.notFound()
            deposits.discrepancy(current, reason, clock.instant())
        }
        invalidTransaction()
    }

    private fun invalidTransaction(): Nothing = throw ApiFailure(400, "La transacción no coincide con el pago esperado")
}
