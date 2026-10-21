package com.geovannycode.corteya.booking

import com.geovannycode.corteya.booking.internal.PaymentSecrets
import com.geovannycode.corteya.booking.internal.WompiSignatures
import com.geovannycode.corteya.shared.ApiFailure
import kotlin.test.*
import org.junit.jupiter.api.Test

class PaymentSecurityTests {
    private val key = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 7 })

    @Test
    fun `integrity signature matches the official checkout example including expiration`() {
        assertEquals("e972f58e7de36b283fbb93d9318d39a54ffdff4e8e917b534f30d63c9d7bb89c",
            WompiSignatures.integrity("sk8-438k4-xmxm392-sn2m", 2490000,
                "2023-06-09T20:28:50.000Z", "prod_integrity_Z5mMke9x0k8gpErbDqwrJXMqsI6SFli6"))
    }

    @Test
    fun `AES GCM uses independent nonces authenticates tenant and field and rejects tampering`() {
        val cipher = PaymentSecrets(key, 1, "")
        val first = cipher.encrypt("prv_test_SyntheticSecret", "shop-a:private")
        val second = cipher.encrypt("prv_test_SyntheticSecret", "shop-a:private")
        assertNotEquals(first, second)
        assertFalse(first.contains("SyntheticSecret"))
        assertEquals("prv_test_SyntheticSecret", cipher.decrypt(first, "shop-a:private"))
        assertFailsWith<ApiFailure> { cipher.decrypt(first, "shop-b:private") }
        assertFailsWith<ApiFailure> { cipher.decrypt(first, "shop-a:events") }
        assertFailsWith<ApiFailure> { cipher.decrypt(first.dropLast(4) + "AAAA", "shop-a:private") }
    }

    @Test
    fun `key rotation reads old envelopes and writes the new version`() {
        val old = PaymentSecrets(key, 1, "").encrypt("secret", "field")
        val newKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 8 })
        val rotated = PaymentSecrets(newKey, 2, "1:$key")
        assertEquals("secret", rotated.decrypt(old, "field"))
        assertTrue(rotated.encrypt("secret", "field").startsWith("2."))
        assertFailsWith<ApiFailure> { PaymentSecrets(newKey, 2, "").decrypt(old, "field") }
        assertFailsWith<ApiFailure> { PaymentSecrets("", 1, "").encrypt("secret", "field") }
    }
}
