package com.geovannycode.corteya.booking.internal

import com.geovannycode.corteya.booking.*
import java.util.UUID

class PaymentSettings(
    val barbershopId: UUID, val mode: DepositMode = DepositMode.NONE, val holdMinutes: Int = 15,
    val brebKeyType: BrebKeyType? = null, val brebKey: String? = null, val brebHolderName: String? = null,
    val environment: WompiEnvironment? = null, val publicKey: String? = null,
    val privateKeyEnc: String? = null, val integritySecretEnc: String? = null,
    val eventsSecretEnc: String? = null, val webhookId: String? = null, val credentialsInvalid: Boolean = false,
) {
    override fun toString(): String = "PaymentSettings(barbershopId=$barbershopId, mode=$mode, credentials=[redacted])"
}
