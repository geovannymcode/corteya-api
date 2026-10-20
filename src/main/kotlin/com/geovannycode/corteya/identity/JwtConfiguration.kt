package com.geovannycode.corteya.identity

import com.geovannycode.corteya.identity.IdentityRepository
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.UUID
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder

data class SigningKey(val rsa: RSAKey, val kid: String) {
    override fun toString(): String = "SigningKey(kid=$kid, rsa=[redacted])"
}

@Configuration(proxyBeanMethods = false)
class JwtConfiguration {
    @Bean
    fun signingKey(environment: Environment): SigningKey {
        val ephemeral = environment.getProperty("auth.jwt.ephemeral", Boolean::class.java, false)
        val dev = environment.acceptsProfiles(Profiles.of("dev", "test"))
        val rsa = if (ephemeral && dev) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            RSAKey.Builder(pair.public as RSAPublicKey).privateKey(pair.private).build()
        } else {
            require(!ephemeral) { "La llave efímera solo se permite en dev o test" }
            val pem = environment.getProperty("auth.jwt.private-key", "").replace("\\n", "\n")
            require(pem.contains("-----BEGIN PRIVATE KEY-----")) { "Configura AUTH_JWT_PRIVATE_KEY como PEM RSA PKCS#8" }
            val bytes = Base64.getDecoder().decode(pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace(Regex("\\s"), ""))
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes)) as RSAPrivateCrtKey
            require(privateKey.modulus.bitLength() >= 2048) { "La llave RSA debe tener al menos 2048 bits" }
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)) as RSAPublicKey
            RSAKey.Builder(publicKey).privateKey(privateKey).build()
        }
        val kid = environment.getProperty("auth.jwt.kid", "").ifBlank {
            require(dev) { "Configura AUTH_JWT_KID" }
            UUID.randomUUID().toString()
        }
        return SigningKey(RSAKey.Builder(rsa).keyID(kid).build(), kid)
    }

    @Bean
    fun jwtEncoder(key: SigningKey): JwtEncoder = NimbusJwtEncoder(ImmutableJWKSet(JWKSet(key.rsa)))

    @Bean
    fun jwtDecoder(key: SigningKey, store: IdentityRepository, clock: Clock): JwtDecoder =
        NimbusJwtDecoder.withPublicKey(key.rsa.toRSAPublicKey()).signatureAlgorithm(SignatureAlgorithm.RS256).build().apply {
            val time = JwtTimestampValidator(Duration.ZERO).apply { setClock(clock) }
            val account = OAuth2TokenValidator<Jwt> { jwt ->
                val id = runCatching { UUID.fromString(jwt.subject) }.getOrNull()
                if (id != null && jwt.expiresAt?.let { it > clock.instant() } == true && jwt.issuedAt?.let { it <= clock.instant() } == true &&
                    jwt.headers["kid"] == key.kid && store.findUser(id) != null) OAuth2TokenValidatorResult.success()
                else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Sesión no válida", null))
            }
            setJwtValidator(DelegatingOAuth2TokenValidator(time, JwtIssuerValidator(ISSUER), account))
        }

    companion object { const val ISSUER = "corteya-api" }
}
