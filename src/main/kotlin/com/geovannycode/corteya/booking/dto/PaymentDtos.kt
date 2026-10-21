package com.geovannycode.corteya.booking.dto

import com.geovannycode.corteya.booking.*
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import java.time.OffsetDateTime
import java.util.UUID

data class PaymentSettingsRequest(
    val depositMode: DepositMode,
    val brebKeyType: BrebKeyType? = null,
    @field:Size(max = 254) val brebKey: String? = null,
    @field:Size(max = 150) val brebHolderName: String? = null,
    val wompiEnvironment: WompiEnvironment? = null,
    @field:Size(max = 200) val wompiPublicKey: String? = null,
    @field:Size(max = 200) val wompiPrivateKey: String? = null,
    @field:Size(max = 200) val wompiIntegritySecret: String? = null,
    @field:Size(max = 200) val wompiEventsSecret: String? = null,
    @field:Min(1) @field:Max(1440) val holdMinutes: Int? = null,
) {
    override fun toString(): String = "PaymentSettingsRequest(depositMode=$depositMode, credentials=[redacted])"
}

data class PaymentSettingsResponse(
    val depositMode: DepositMode, val holdMinutes: Int,
    val brebKeyType: BrebKeyType?, val brebKey: String?, val brebHolderName: String?,
    val wompiEnvironment: WompiEnvironment?, val wompiPublicKey: String?,
    val wompiPrivateKey: String?, val wompiIntegritySecret: String?, val wompiEventsSecret: String?,
    val eventsUrl: String?, val wompiCredentialsError: String?,
)

data class PaymentInstructions(
    val mode: DepositMode, val amountCop: Long, val reference: String, val expiresAt: OffsetDateTime,
    val brebKeyType: BrebKeyType? = null, val brebKey: String? = null, val brebHolderName: String? = null,
    val checkoutUrl: String? = null, val checkoutParameters: Map<String, String>? = null, val amountInCents: Long? = null,
)

data class DepositResponse(
    val id: UUID, val appointmentId: UUID, val method: DepositMode, val amountCop: Long,
    val reference: String, val status: DepositStatus, val confirmedAt: OffsetDateTime?,
)
