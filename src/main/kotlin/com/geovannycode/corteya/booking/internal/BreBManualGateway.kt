package com.geovannycode.corteya.booking.internal

import com.geovannycode.corteya.booking.DepositMode
import com.geovannycode.corteya.booking.PaymentGateway
import com.geovannycode.corteya.booking.dto.PaymentInstructions
import java.time.ZoneId
import org.springframework.stereotype.Component

@Component
class BreBManualGateway : PaymentGateway {
    override val mode = DepositMode.BREB_MANUAL
    override fun instructions(settings: PaymentSettings, payment: DepositPayment, appointment: AppointmentEntity) =
        PaymentInstructions(mode, payment.amountCop, payment.reference,
            checkNotNull(appointment.holdExpiresAt).atZone(ZoneId.of("America/Bogota")).toOffsetDateTime(),
            settings.brebKeyType, settings.brebKey, settings.brebHolderName)
}
