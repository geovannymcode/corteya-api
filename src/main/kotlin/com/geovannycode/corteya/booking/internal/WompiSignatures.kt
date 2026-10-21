package com.geovannycode.corteya.booking.internal

import java.security.MessageDigest
import tools.jackson.databind.JsonNode

/** Algorithms from docs.wompi.co/docs/colombia/{widget-checkout-web,eventos}/. */
object WompiSignatures {
    fun integrity(reference: String, cents: Long, expiration: String, secret: String): String =
        sha256("${reference}${cents}COP${expiration}${secret}")

    fun validEvent(event: JsonNode, secret: String): Boolean {
        val properties = event.path("signature").path("properties")
        val checksum = event.path("signature").path("checksum").asString("")
        if (!properties.isArray || properties.isEmpty || properties.size() > 30 || !checksum.matches(Regex("[A-Fa-f0-9]{64}")) ||
            !event.path("timestamp").isIntegralNumber) return false
        val values = StringBuilder()
        for (property in properties) {
            if (!property.isString) return false
            val path = property.asString()
            if (path.length > 200) return false
            var value = event.path("data")
            for (part in path.split('.')) value = value.path(part)
            if (value.isMissingNode || value.isNull || value.isContainer) return false
            values.append(value.asString())
        }
        values.append(event.path("timestamp").asString()).append(secret)
        return MessageDigest.isEqual(sha256(values.toString()).toByteArray(Charsets.US_ASCII), checksum.lowercase().toByteArray(Charsets.US_ASCII))
    }

    private fun sha256(value: String): String = java.util.HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
}
