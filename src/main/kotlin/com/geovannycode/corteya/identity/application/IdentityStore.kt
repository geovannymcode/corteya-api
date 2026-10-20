package com.geovannycode.corteya.identity.application

import com.geovannycode.corteya.identity.domain.AppUser
import com.geovannycode.corteya.identity.domain.OtpChallenge
import com.geovannycode.corteya.identity.domain.StaffMembership
import com.geovannycode.corteya.identity.domain.StoredRefreshToken
import java.time.Instant
import java.util.UUID

interface IdentityStore {
    fun findUser(userId: UUID): AppUser?
    fun lockUser(userId: UUID): AppUser?
    fun findOrCreateUser(phone: String, now: Instant): AppUser
    fun memberships(userId: UUID): List<StaffMembership>
    fun membership(userId: UUID, barbershopId: UUID): StaffMembership?
    fun linkVerifiedCustomers(userId: UUID, phone: String)
    fun updateName(userId: UUID, name: String)
    fun anonymize(userId: UUID, now: Instant)
    fun ownsCustomer(userId: UUID, barbershopId: UUID, customerId: UUID): Boolean
    fun lockOtpRequest(phone: String, ip: String)
    fun lockOtpPhone(phone: String)
    fun lastOtpSentAt(phone: String): Instant?
    fun otpCountForPhone(phone: String, since: Instant): Int
    fun otpCountForIp(ip: String, since: Instant): Int
    fun insertOtp(id: UUID, phone: String, hash: String, expiresAt: Instant, now: Instant, ip: String)
    fun purgeExpiredOtps(cutoff: Instant): Int
    fun purgeExpiredRefreshTokens(cutoff: Instant): Int
    fun latestOtp(phone: String): OtpChallenge?
    fun failOtp(id: UUID, attempts: Int, now: Instant)
    fun consumeOtp(id: UUID, now: Instant)
    fun insertRefresh(id: UUID, userId: UUID, hash: String, familyId: UUID, expiresAt: Instant, now: Instant)
    fun findRefresh(hash: String): StoredRefreshToken?
    fun replaceRefresh(id: UUID, replacement: UUID, now: Instant)
    fun revokeFamily(familyId: UUID, now: Instant)
}
