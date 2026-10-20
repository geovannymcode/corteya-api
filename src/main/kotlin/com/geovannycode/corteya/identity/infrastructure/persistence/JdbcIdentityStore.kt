package com.geovannycode.corteya.identity.infrastructure.persistence

import com.geovannycode.corteya.identity.application.IdentityStore
import com.geovannycode.corteya.identity.domain.AppUser
import com.geovannycode.corteya.identity.domain.OtpChallenge
import com.geovannycode.corteya.identity.domain.StaffMembership
import com.geovannycode.corteya.identity.domain.StaffRole
import com.geovannycode.corteya.identity.domain.StoredRefreshToken
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class JdbcIdentityStore(private val jdbc: JdbcTemplate) : IdentityStore {
    override fun findUser(userId: UUID): AppUser? = jdbc.query("select * from app_user where id = ? and deleted_at is null", { r, _ -> r.user() }, userId).singleOrNull()
    override fun lockUser(userId: UUID): AppUser? = jdbc.query("select * from app_user where id = ? and deleted_at is null for update", { r, _ -> r.user() }, userId).singleOrNull()
    override fun findOrCreateUser(phone: String, now: Instant): AppUser = checkNotNull(jdbc.query(
        """insert into app_user (id, phone, created_at, updated_at) values (?, ?, ?, ?)
            on conflict (phone) do update set phone = excluded.phone returning *""",
        { r, _ -> r.user() }, UUID.randomUUID(), phone, Timestamp.from(now), Timestamp.from(now),
    ).singleOrNull())

    override fun memberships(userId: UUID): List<StaffMembership> = jdbc.query(
        "select barbershop_id, role, barber_id from staff_membership where user_id = ? order by barbershop_id",
        { r, _ -> r.membership() }, userId,
    )
    override fun membership(userId: UUID, barbershopId: UUID): StaffMembership? = jdbc.query(
        "select barbershop_id, role, barber_id from staff_membership where user_id = ? and barbershop_id = ?",
        { r, _ -> r.membership() }, userId, barbershopId,
    ).singleOrNull()
    override fun linkVerifiedCustomers(userId: UUID, phone: String) {
        jdbc.update("update customer set user_id = ? where phone = ? and user_id is null", userId, phone)
    }

    override fun updateName(userId: UUID, name: String) { jdbc.update("update app_user set name = ? where id = ? and deleted_at is null", name, userId) }
    override fun anonymize(userId: UUID, now: Instant) {
        jdbc.update("update customer set name = 'Cliente eliminado', phone = null where user_id = ?", userId)
        jdbc.update("delete from staff_membership where user_id = ?", userId)
        jdbc.update("update refresh_token set revoked_at = coalesce(revoked_at, ?) where user_id = ?", Timestamp.from(now), userId)
        jdbc.update("update app_user set name = 'Cliente eliminado', phone = null, platform_admin = false, deleted_at = ? where id = ? and deleted_at is null", Timestamp.from(now), userId)
    }
    override fun ownsCustomer(userId: UUID, barbershopId: UUID, customerId: UUID): Boolean = jdbc.queryForObject(
        "select exists(select 1 from customer where user_id = ? and barbershop_id = ? and id = ?)", Boolean::class.java, userId, barbershopId, customerId,
    ) == true

    override fun lockOtpRequest(phone: String, ip: String) {
        // Fixed ordering serializes both rate limits across instances without lock-order cycles.
        lock("otp-ip:$ip")
        lockOtpPhone(phone)
    }
    override fun lockOtpPhone(phone: String) { lock("otp-phone:$phone") }
    private fun lock(value: String) { jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", { _, _ -> Unit }, value) }
    override fun lastOtpSentAt(phone: String): Instant? = jdbc.query("select created_at from otp_challenge where phone = ? order by created_at desc limit 1", { r, _ -> r.getTimestamp(1).toInstant() }, phone).singleOrNull()
    override fun otpCountForPhone(phone: String, since: Instant): Int = count("phone", phone, since)
    override fun otpCountForIp(ip: String, since: Instant): Int = count("request_ip", ip, since)
    private fun count(column: String, value: String, since: Instant) = checkNotNull(jdbc.queryForObject("select count(*) from otp_challenge where $column = ? and created_at > ?", Int::class.java, value, Timestamp.from(since)))
    override fun insertOtp(id: UUID, phone: String, hash: String, expiresAt: Instant, now: Instant, ip: String) {
        jdbc.update("insert into otp_challenge (id, phone, code_hash, expires_at, request_ip, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?)", id, phone, hash, Timestamp.from(expiresAt), ip, Timestamp.from(now), Timestamp.from(now))
    }
    override fun latestOtp(phone: String): OtpChallenge? = jdbc.query(
        "select id, code_hash, expires_at, attempts, consumed_at from otp_challenge where phone = ? order by created_at desc, id desc limit 1 for update",
        { r, _ -> OtpChallenge(r.getObject("id", UUID::class.java), r.getString("code_hash"), r.getTimestamp("expires_at").toInstant(), r.getInt("attempts"), r.getTimestamp("consumed_at")?.toInstant()) }, phone,
    ).singleOrNull()
    override fun failOtp(id: UUID, attempts: Int, now: Instant) {
        jdbc.update("update otp_challenge set attempts = ?, consumed_at = case when ? >= 5 then ? else consumed_at end where id = ?", attempts, attempts, Timestamp.from(now), id)
    }
    override fun consumeOtp(id: UUID, now: Instant) { jdbc.update("update otp_challenge set consumed_at = ? where id = ?", Timestamp.from(now), id) }
    override fun insertRefresh(id: UUID, userId: UUID, hash: String, familyId: UUID, expiresAt: Instant, now: Instant) {
        jdbc.update("insert into refresh_token (id, user_id, token_hash, family_id, expires_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?)", id, userId, hash, familyId, Timestamp.from(expiresAt), Timestamp.from(now), Timestamp.from(now))
    }
    override fun findRefresh(hash: String): StoredRefreshToken? = jdbc.query(
        "select id, user_id, family_id, expires_at, revoked_at, replaced_by from refresh_token where token_hash = ?",
        { r, _ -> StoredRefreshToken(r.getObject("id", UUID::class.java), r.getObject("user_id", UUID::class.java), r.getObject("family_id", UUID::class.java), r.getTimestamp("expires_at").toInstant(), r.getTimestamp("revoked_at")?.toInstant(), r.getObject("replaced_by", UUID::class.java)) }, hash,
    ).singleOrNull()
    override fun replaceRefresh(id: UUID, replacement: UUID, now: Instant) { jdbc.update("update refresh_token set revoked_at = ?, replaced_by = ? where id = ?", Timestamp.from(now), replacement, id) }
    override fun revokeFamily(familyId: UUID, now: Instant) { jdbc.update("update refresh_token set revoked_at = coalesce(revoked_at, ?) where family_id = ?", Timestamp.from(now), familyId) }

    private fun ResultSet.user() = AppUser(getObject("id", UUID::class.java), getString("phone"), getString("name"), getBoolean("platform_admin"), getTimestamp("deleted_at")?.toInstant())
    private fun ResultSet.membership() = StaffMembership(getObject("barbershop_id", UUID::class.java), StaffRole.valueOf(getString("role")), getObject("barber_id", UUID::class.java))
}
