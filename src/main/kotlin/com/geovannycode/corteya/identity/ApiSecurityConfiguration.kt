package com.geovannycode.corteya.identity

import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter
import org.springframework.security.web.SecurityFilterChain
import tools.jackson.databind.json.JsonMapper

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ApiSecurityConfiguration {
    @Bean
    fun apiSecurity(http: HttpSecurity, mapper: JsonMapper): SecurityFilterChain {
        fun error(response: HttpServletResponse, status: Int, message: String) {
            response.status = status
            response.contentType = "application/problem+json"
            response.characterEncoding = "UTF-8"
            response.setHeader("Cache-Control", "no-store")
            val problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), message).apply {
                title = if (status == 401) "Autenticación requerida" else "Acceso no permitido"
            }
            response.writer.write(mapper.writeValueAsString(problem))
        }
        val authorities = JwtGrantedAuthoritiesConverter().apply { setAuthoritiesClaimName("roles"); setAuthorityPrefix("ROLE_") }
        val converter = JwtAuthenticationConverter().apply { setJwtGrantedAuthoritiesConverter(authorities) }
        http.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .requestCache { it.disable() }
            .csrf { it.ignoringRequestMatchers("/api/**") }
            .formLogin { it.disable() }.httpBasic { it.disable() }.logout { it.disable() }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/api/v1/auth/otp/request", "/api/v1/auth/otp/verify", "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/barbershops/*", "/api/v1/barbershops/*/services",
                        "/api/v1/barbershops/*/barbers", "/api/v1/barbershops/*/availability").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/barbershops/*/appointments").hasRole("CUSTOMER")
                    .requestMatchers("/api/**").authenticated()
                    .anyRequest().permitAll() // Only Swagger, health and error handling exist outside /api.
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ -> error(response, 401, "Inicia sesión para continuar") }
                    .accessDeniedHandler { _, response, _ -> error(response, 403, "No tienes permiso para realizar esta acción") }
            }
            .oauth2ResourceServer {
                it.jwt { jwt -> jwt.jwtAuthenticationConverter(converter) }
                    .authenticationEntryPoint { _, response, _ -> error(response, 401, "La sesión no es válida o ya venció; inicia sesión de nuevo") }
                    .accessDeniedHandler { _, response, _ -> error(response, 403, "No tienes permiso para realizar esta acción") }
            }
        return http.build()
    }
}
