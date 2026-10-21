package com.geovannycode.corteya

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean(initMethod = "start", destroyMethod = "stop")
    fun postgresContainer(): PostgreSQLContainer =
        testPostgres()

    @Bean
    fun jdbcConnectionDetails(postgres: PostgreSQLContainer): JdbcConnectionDetails = object : JdbcConnectionDetails {
        override fun getJdbcUrl(): String = postgres.jdbcUrl
        override fun getUsername(): String = "corteya_app"
        override fun getPassword(): String = "app-test-only"
    }

    @Bean
    fun flywayConnectionDetails(postgres: PostgreSQLContainer): FlywayConnectionDetails = object : FlywayConnectionDetails {
        override fun getJdbcUrl(): String = postgres.jdbcUrl
        override fun getUsername(): String = "corteya_owner"
        override fun getPassword(): String = "owner-test-only"
    }
}
