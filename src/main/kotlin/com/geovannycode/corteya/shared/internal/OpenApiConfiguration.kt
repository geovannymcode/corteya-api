package com.geovannycode.corteya.shared.internal

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.security.SecurityScheme
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
@SecurityScheme(name = "bearerAuth", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@OpenAPIDefinition(info = Info(
    title = "CorteYa API",
    version = "v1",
    description = "Reservas por barbería. Instantes ISO-8601 con offset America/Bogota y montos COP enteros. Los tokens públicos de citas requieren sesión y verificación de propiedad o membresía.",
))
class OpenApiConfiguration
