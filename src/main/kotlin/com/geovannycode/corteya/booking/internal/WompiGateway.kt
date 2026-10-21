package com.geovannycode.corteya.booking.internal

import com.geovannycode.corteya.booking.*
import com.geovannycode.corteya.booking.dto.PaymentInstructions
import com.geovannycode.corteya.shared.ApiFailure
import java.time.ZoneId
import java.time.format.DateTimeFormatterBuilder
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode

@Component
class WompiGateway(
    private val secrets: PaymentSecrets,
    private val settingsService: PaymentSettingsService,
    @Qualifier("wompiRestClientBuilder") private val builder: RestClient.Builder,
    @Value("\${payments.redirect-url:corteya://appointments/}") private val redirectUrl: String,
) : PaymentGateway {
    private val logger = LoggerFactory.getLogger(javaClass)
    override val mode = DepositMode.WOMPI

    override fun instructions(settings: PaymentSettings, payment: DepositPayment, appointment: AppointmentEntity): PaymentInstructions {
        val expiresAt = checkNotNull(appointment.holdExpiresAt)
        val expiration = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(expiresAt)
        val cents = Math.multiplyExact(payment.amountCop, 100L)
        val signature = WompiSignatures.integrity(payment.reference, cents, expiration,
            secrets.decrypt(checkNotNull(settings.integritySecretEnc), "${settings.barbershopId}:integrity"))
        val parameters = linkedMapOf("public-key" to checkNotNull(settings.publicKey), "currency" to "COP",
            "amount-in-cents" to cents.toString(), "reference" to payment.reference,
            "expiration-time" to expiration, "redirect-url" to redirectUrl.trimEnd('/') + "/" + appointment.publicToken,
            "signature:integrity" to signature)
        return PaymentInstructions(mode, payment.amountCop, payment.reference,
            expiresAt.atZone(ZoneId.of("America/Bogota")).toOffsetDateTime(),
            checkoutUrl = "https://checkout.wompi.co/p/", checkoutParameters = parameters, amountInCents = cents)
    }

    fun transaction(settings: PaymentSettings, id: String): JsonNode {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "HTTP de Wompi fuera de transacciones" }
        if (!id.matches(Regex("[A-Za-z0-9-]{1,100}"))) throw ApiFailure(400, "Evento de pago inválido")
        val base = if (settings.environment == WompiEnvironment.SANDBOX) "https://sandbox.wompi.co/v1" else "https://production.wompi.co/v1"
        val privateKey = secrets.decrypt(checkNotNull(settings.privateKeyEnc), "${settings.barbershopId}:private")
        return try {
            val transaction = checkNotNull(builder.build().get().uri("$base/transactions/$id").headers { it.setBearerAuth(privateKey) }
                .retrieve()
                .onStatus({ it.value() == 401 || it.value() == 403 }) { _, response ->
                    logger.error("Credenciales Wompi inválidas; barbería={}; HTTP={}", settings.barbershopId, response.statusCode.value())
                    settingsService.credentialsInvalid(settings, true)
                    throw ApiFailure(502, "Las credenciales de Wompi requieren revisión por la barbería")
                }
                .onStatus({ it.isError }) { _, _ -> throw ApiFailure(503, "No pudimos verificar el pago en este momento") }
                .body(JsonNode::class.java)).path("data")
            settingsService.credentialsInvalid(settings, false)
            transaction
        } catch (failure: ApiFailure) {
            throw failure
        } catch (_: Exception) {
            // Provider exceptions can contain URLs, credentials or personal response data; never attach them.
            throw ApiFailure(503, "No pudimos verificar el pago en este momento")
        }
    }
}
