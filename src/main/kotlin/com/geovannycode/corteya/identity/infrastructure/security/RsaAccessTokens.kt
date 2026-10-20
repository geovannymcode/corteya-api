package com.geovannycode.corteya.identity.infrastructure.security

import com.geovannycode.corteya.identity.application.AccessTokens
import com.geovannycode.corteya.identity.domain.AppUser
import com.geovannycode.corteya.identity.domain.StaffMembership
import java.time.Clock
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Component

@Component
class RsaAccessTokens(private val encoder: JwtEncoder, private val key: SigningKey, private val clock: Clock) : AccessTokens {
    override fun issue(user: AppUser, memberships: List<StaffMembership>): String {
        val now = clock.instant()
        val roles = (listOf("CUSTOMER") + memberships.map { it.role.name } + if (user.platformAdmin) listOf("PLATFORM_ADMIN") else emptyList()).distinct()
        val claims = JwtClaimsSet.builder().issuer(JwtConfiguration.ISSUER).subject(user.id.toString())
            .issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(900))
            .claim("roles", roles).claim("memberships", memberships.map {
                mapOf("barbershopId" to it.barbershopId.toString(), "role" to it.role.name, "barberId" to it.barberId?.toString())
            }).build()
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.kid).build(), claims)).tokenValue
    }
}

