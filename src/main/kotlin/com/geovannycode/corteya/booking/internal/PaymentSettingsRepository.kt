package com.geovannycode.corteya.booking.internal

import com.geovannycode.corteya.booking.*
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class PaymentSettingsRepository(private val jdbc: JdbcTemplate) {
    fun find(shop: UUID, lock: Boolean = false): PaymentSettings? = jdbc.query(
        "select * from barbershop_payment_settings where barbershop_id = ?" + if (lock) " for update" else "",
        { r, _ -> PaymentSettings(shop, DepositMode.valueOf(r.getString("deposit_mode")), r.getInt("hold_minutes"),
            r.getString("breb_key_type")?.let(BrebKeyType::valueOf), r.getString("breb_key"), r.getString("breb_holder_name"),
            r.getString("wompi_environment")?.let(WompiEnvironment::valueOf), r.getString("wompi_public_key"),
            r.getString("wompi_private_key_enc"), r.getString("wompi_integrity_secret_enc"), r.getString("wompi_events_secret_enc"),
            r.getString("wompi_webhook_id"), r.getBoolean("wompi_credentials_invalid")) }, shop,
    ).singleOrNull()

    fun credentialsInvalid(shop: UUID, webhookId: String, invalid: Boolean) {
        jdbc.update("""update barbershop_payment_settings set wompi_credentials_invalid = ?
            where barbershop_id = ? and wompi_webhook_id = ? and wompi_credentials_invalid is distinct from ?""", invalid, shop, webhookId, invalid)
    }

    fun save(s: PaymentSettings) {
        jdbc.update("""insert into barbershop_payment_settings (id, barbershop_id, deposit_mode, hold_minutes,
            breb_key_type, breb_key, breb_holder_name, wompi_environment, wompi_public_key, wompi_private_key_enc,
            wompi_integrity_secret_enc, wompi_events_secret_enc, wompi_webhook_id, wompi_credentials_invalid)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (barbershop_id) do update set deposit_mode = excluded.deposit_mode, hold_minutes = excluded.hold_minutes,
            breb_key_type = excluded.breb_key_type, breb_key = excluded.breb_key, breb_holder_name = excluded.breb_holder_name,
            wompi_environment = excluded.wompi_environment, wompi_public_key = excluded.wompi_public_key,
            wompi_private_key_enc = excluded.wompi_private_key_enc, wompi_integrity_secret_enc = excluded.wompi_integrity_secret_enc,
            wompi_events_secret_enc = excluded.wompi_events_secret_enc, wompi_webhook_id = excluded.wompi_webhook_id,
            wompi_credentials_invalid = excluded.wompi_credentials_invalid
            where barbershop_payment_settings.barbershop_id = ?""",
            UUID.randomUUID(), s.barbershopId, s.mode.name, s.holdMinutes, s.brebKeyType?.name, s.brebKey, s.brebHolderName,
            s.environment?.name, s.publicKey, s.privateKeyEnc, s.integritySecretEnc, s.eventsSecretEnc, s.webhookId, s.credentialsInvalid, s.barbershopId)
    }
}
