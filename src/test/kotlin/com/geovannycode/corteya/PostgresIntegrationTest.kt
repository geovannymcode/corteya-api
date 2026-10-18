package com.geovannycode.corteya

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * Spring Framework 7's SpringExtension requires JUnit 6. Start the real application
 * explicitly to keep this project's requested JUnit 5 without downgrading Spring Boot.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PostgresIntegrationTest(
    private val web: Boolean = false,
    private val profile: String? = null,
) {
    private val postgres = PostgreSQLContainer(DockerImageName.parse(TEST_POSTGRES_IMAGE))
    private lateinit var context: ConfigurableApplicationContext

    protected val serverPort: Int
        get() = checkNotNull((context as WebServerApplicationContext).webServer).port

    @BeforeAll
    fun startApplication() {
        postgres.start()
        try {
            val builder = SpringApplicationBuilder(CorteyaApiApplication::class.java)
                .web(if (web) WebApplicationType.SERVLET else WebApplicationType.NONE)
            profile?.let { builder.profiles(it) }
            context = builder.run(
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=${postgres.username}",
                "--spring.datasource.password=${postgres.password}",
                "--server.port=0",
            )
            context.autowireCapableBeanFactory.autowireBean(this)
        } catch (failure: Throwable) {
            if (::context.isInitialized) context.close()
            postgres.stop()
            throw failure
        }
    }

    @AfterAll
    fun stopApplication() {
        try {
            if (::context.isInitialized) context.close()
        } finally {
            postgres.stop()
        }
    }
}

internal const val TEST_POSTGRES_IMAGE = "postgres:18.6"
