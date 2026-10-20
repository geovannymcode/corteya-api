package com.geovannycode.corteya.identity.internal

import java.time.Instant
import java.util.UUID

data class OtpChallenge(val id: UUID, val codeHash: String, val expiresAt: Instant, val attempts: Int, val consumedAt: Instant?)
data class StoredRefreshToken(val id: UUID, val userId: UUID, val familyId: UUID, val expiresAt: Instant, val revokedAt: Instant?, val replacedBy: UUID?)
