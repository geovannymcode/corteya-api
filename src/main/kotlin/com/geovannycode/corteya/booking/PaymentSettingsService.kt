package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.dto.PaymentSettingsRequest
import com.geovannycode.corteya.booking.dto.PaymentSettingsResponse
import com.geovannycode.corteya.booking.internal.PaymentSecrets
import com.geovannycode.corteya.booking.internal.PaymentSettings
import com.geovannycode.corteya.booking.internal.PaymentSettingsRepository
import com.geovannycode.corteya.identity.AuthorizationPolicy
import com.geovannycode.corteya.identity.PhoneNumbers
import com.geovannycode.corteya.shared.ApiFailure
import com.geovannycode.corteya.shared.TenantTransaction
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class PaymentSettingsService(
    private val repository: PaymentSettingsRepository, private val secrets: PaymentSecrets,
    private val policy: AuthorizationPolicy, private val tenants: TenantTransaction,
    private val deposits: com.geovannycode.corteya.booking.internal.DepositRepository,
    @Value("\${payments.public-base-url:http://localhost:8080}") private val publicBaseUrl: String,
) {
    fun get(user: UUID, slug: String): PaymentSettingsResponse {
        val shop = policy.owner(user, slug)
        return tenants.execute(shop) { response(current(shop)) }
    }

    fun update(user: UUID, slug: String, request: PaymentSettingsRequest): PaymentSettingsResponse {
        val shop = policy.owner(user, slug)
        return tenants.execute(shop) { configure(shop, request) }
    }

    fun current(shop: UUID): PaymentSettings = repository.find(shop) ?: PaymentSettings(shop)

    fun credentialsInvalid(configuration: PaymentSettings, invalid: Boolean) = tenants.execute(configuration.barbershopId) {
        // An old attempt must not flag or clear credentials that the OWNER has since replaced.
        configuration.webhookId?.let { repository.credentialsInvalid(configuration.barbershopId, it, invalid) }
    }

    fun bookingEnabled(shop: UUID): Boolean = tenants.execute(shop) { repository.find(shop) != null }

    fun requireConfigured(shop: UUID): PaymentSettings = repository.find(shop)
        ?: throw ApiFailure(409, "Esta barbería todavía no recibe reservas en línea")

    /** Caller must already have a trusted tenant transaction (OWNER policy or dev-only initializer). */
    fun configure(shop: UUID, request: PaymentSettingsRequest): PaymentSettingsResponse {
        val old = repository.find(shop, lock = true)
        val hold = request.holdMinutes ?: if (request.depositMode == DepositMode.BREB_MANUAL) 30 else 15
        if (hold !in 1..1440) invalid()
        val settings = when (request.depositMode) {
            DepositMode.NONE -> PaymentSettings(shop, DepositMode.NONE, hold)
            DepositMode.BREB_MANUAL -> {
                val key = validateBreb(request.brebKeyType, request.brebKey)
                val holder = request.brebHolderName?.trim()?.takeIf { it.isNotBlank() && it.length <= 150 } ?: invalid()
                PaymentSettings(shop, DepositMode.BREB_MANUAL, hold, request.brebKeyType, key, holder)
            }
            DepositMode.WOMPI -> {
                val environment = request.wompiEnvironment ?: invalid()
                val prefix = if (environment == WompiEnvironment.SANDBOX) "test" else "prod"
                val publicKey = request.wompiPublicKey ?: old?.publicKey ?: invalid()
                if (!publicKey.matches(Regex("pub_${prefix}_[A-Za-z0-9]+"))) invalid()
                fun encrypted(value: String?, previous: String?, pattern: String, field: String): String {
                    val plain = value ?: previous?.let { secrets.decrypt(it, "$shop:$field") } ?: invalid()
                    if (!plain.matches(Regex(pattern))) invalid()
                    return secrets.encrypt(plain, "$shop:$field")
                }
                fun unchanged(value: String?, previous: String?, field: String): Boolean = value == null ||
                    previous?.let { secrets.decrypt(it, "$shop:$field") } == value
                val sameCredentials = old?.environment == environment && old.publicKey == publicKey &&
                    unchanged(request.wompiPrivateKey, old.privateKeyEnc, "private") &&
                    unchanged(request.wompiIntegritySecret, old.integritySecretEnc, "integrity") &&
                    unchanged(request.wompiEventsSecret, old.eventsSecretEnc, "events")
                PaymentSettings(shop, DepositMode.WOMPI, hold, environment = environment, publicKey = publicKey,
                    privateKeyEnc = encrypted(request.wompiPrivateKey, old?.privateKeyEnc, "prv_${prefix}_[A-Za-z0-9]+", "private"),
                    integritySecretEnc = encrypted(request.wompiIntegritySecret, old?.integritySecretEnc, "${prefix}_integrity_[A-Za-z0-9]+", "integrity"),
                    eventsSecretEnc = encrypted(request.wompiEventsSecret, old?.eventsSecretEnc, "${prefix}_events_[A-Za-z0-9]+", "events"),
                    webhookId = (if (sameCredentials) old.webhookId else null)
                        ?: secrets.encrypt(shop.toString(), "wompi-webhook"),
                    credentialsInvalid = sameCredentials && old.credentialsInvalid &&
                        request.wompiPublicKey == null && request.wompiPrivateKey == null &&
                        request.wompiIntegritySecret == null && request.wompiEventsSecret == null)
            }
        }
        repository.save(settings)
        return response(settings)
    }

    fun webhookSettings(webhookId: String): PaymentSettings {
        if (webhookId.length !in 40..200) throw ApiFailure(404, "No encontramos el recurso solicitado")
        val shop = try { UUID.fromString(secrets.decrypt(webhookId, "wompi-webhook")) }
            catch (_: Exception) { throw ApiFailure(404, "No encontramos el recurso solicitado") }
        return tenants.execute(shop) {
            current(shop).takeIf { it.mode == DepositMode.WOMPI && it.webhookId == webhookId }
                ?: deposits.configurationByWebhook(shop, webhookId)
                ?: throw ApiFailure(404, "No encontramos el recurso solicitado")
        }
    }

    private fun response(s: PaymentSettings) = PaymentSettingsResponse(s.mode, s.holdMinutes,
        s.brebKeyType, s.brebKey, s.brebHolderName, s.environment, s.publicKey,
        s.privateKeyEnc?.let { "configurado ✓" }, s.integritySecretEnc?.let { "configurado ✓" }, s.eventsSecretEnc?.let { "configurado ✓" },
        s.webhookId?.let { "${publicBaseUrl.trimEnd('/')}/api/v1/webhooks/wompi/$it" },
        if (s.credentialsInvalid) "Credenciales de Wompi inválidas" else null)

    private fun validateBreb(type: BrebKeyType?, raw: String?): String {
        val key = raw?.trim()?.takeIf { it.length <= 254 } ?: invalid()
        return when (type) {
            BrebKeyType.CELULAR -> PhoneNumbers.normalize(key) ?: invalid()
            BrebKeyType.ALIAS -> key.takeIf { it.matches(Regex("@[A-Za-z0-9]{1,100}")) } ?: invalid()
            BrebKeyType.CORREO -> key.takeIf { it.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) } ?: invalid()
            BrebKeyType.DOCUMENTO -> key.takeIf { it.matches(Regex("[0-9]{1,30}")) } ?: invalid()
            null -> invalid()
        }
    }

    private fun invalid(): Nothing = throw ApiFailure(400, "Revisa la configuración de pagos y que todas las llaves pertenezcan al mismo ambiente")
}
