package com.geovannycode.corteya.booking.internal

import com.geovannycode.corteya.shared.ApiFailure
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Versioned AES-GCM envelopes bind credentials to both tenant and field via authenticated data. */
@Component
class PaymentSecrets(
    @Value("\${payments.secrets.key:}") private val key: String,
    @Value("\${payments.secrets.version:1}") private val version: Int,
    @Value("\${payments.secrets.previous-keys:}") private val previousKeys: String,
) {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encrypt(value: String, purpose: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret(version), GCMParameterSpec(128, nonce))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        return "$version.${encoder.encodeToString(nonce)}.${encoder.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))}"
    }

    fun decrypt(value: String, purpose: String): String = try {
        val parts = value.split('.')
        require(parts.size == 3)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(parts[0].toInt()), GCMParameterSpec(128, decoder.decode(parts[1])))
        cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
        cipher.doFinal(decoder.decode(parts[2])).toString(Charsets.UTF_8)
    } catch (_: Exception) {
        throw ApiFailure(503, "No pudimos acceder a la configuración de pagos")
    }

    private fun secret(requestedVersion: Int): SecretKeySpec {
        val encoded = if (requestedVersion == version) key else previousKeys.split(',')
            .mapNotNull { it.split(':', limit = 2).takeIf { pair -> pair.size == 2 } }
            .firstOrNull { it[0] == requestedVersion.toString() }?.get(1).orEmpty()
        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { byteArrayOf() }
        if (bytes.size != 32 || version < 1) throw ApiFailure(503, "La configuración segura de pagos no está disponible")
        return SecretKeySpec(bytes, "AES")
    }
}
