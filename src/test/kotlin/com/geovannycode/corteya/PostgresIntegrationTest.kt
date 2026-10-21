package com.geovannycode.corteya

import com.geovannycode.corteya.shared.TenantTransaction
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.server.context.WebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile

/**
 * Spring Framework 7's SpringExtension requires JUnit 6. Start the real application
 * explicitly to keep this project's requested JUnit 5 without downgrading Spring Boot.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class PostgresIntegrationTest(
    private val web: Boolean = false,
    private val profile: String? = null,
) {
    private val postgres = testPostgres()
    private lateinit var context: ConfigurableApplicationContext

    protected val serverPort: Int
        get() = checkNotNull((context as WebServerApplicationContext).webServer).port

    protected open fun customize(builder: SpringApplicationBuilder): SpringApplicationBuilder = builder

    @BeforeAll
    fun startApplication() {
        postgres.start()
        try {
            val builder = SpringApplicationBuilder(CorteyaApplication::class.java, AuthTestConfiguration::class.java)
                .web(if (web) WebApplicationType.SERVLET else WebApplicationType.NONE)
            builder.profiles(profile ?: "test")
            context = customize(builder).run(
                "--spring.datasource.url=${postgres.jdbcUrl}",
                "--spring.datasource.username=corteya_app",
                "--spring.datasource.password=app-test-only",
                "--spring.flyway.user=corteya_owner",
                "--spring.flyway.password=owner-test-only",
                "--server.port=0",
                "--booking.scheduling.enabled=false",
                "--auth.otp.sender=test",
                "--auth.otp.pepper=test-only-pepper-at-least-32-bytes-long",
                "--auth.jwt.ephemeral=true",
            )
            val runtimeJdbc = context.getBean(JdbcTemplate::class.java)
            check(runtimeJdbc.queryForObject("select current_user", String::class.java) == "corteya_app")
            check(runtimeJdbc.queryForObject("select not rolsuper and not rolbypassrls from pg_roles where rolname = current_user", Boolean::class.java) == true)
            context.autowireCapableBeanFactory.autowireBean(this)
        } catch (failure: Throwable) {
            if (::context.isInitialized) context.close()
            postgres.stop()
            throw failure
        }
    }

    protected fun <T> inShop(id: UUID, operation: () -> T): T =
        context.getBean(TenantTransaction::class.java).execute(id, operation)

    /** Fixtures use app DML and real tenant contexts; no owner/superuser cleanup or TRUNCATE. */
    protected fun resetData(shopIds: List<UUID> = emptyList()) {
        val jdbc = context.getBean(JdbcTemplate::class.java)
        jdbc.update("delete from staff_membership")
        shopIds.forEach { id -> inShop(id) {
            listOf("deposit_event", "deposit_payment", "barbershop_payment_settings", "appointment", "time_off", "working_hours", "barber_service", "customer", "service", "barber").forEach {
                jdbc.update("delete from $it where barbershop_id = ?", id)
            }
            jdbc.update("delete from barbershop where id = ?", id)
        } }
        jdbc.update("update refresh_token set replaced_by = null")
        jdbc.update("delete from refresh_token")
        jdbc.update("delete from app_user")
        jdbc.update("delete from otp_challenge")
    }

    /** Earlier booking scenarios explicitly keep their original 15 minute hold. */
    protected fun configureLegacyTestPayments(shop: UUID) = inShop(shop) {
        context.getBean(com.geovannycode.corteya.booking.PaymentSettingsService::class.java).configure(shop,
            com.geovannycode.corteya.booking.dto.PaymentSettingsRequest(com.geovannycode.corteya.booking.DepositMode.BREB_MANUAL,
                com.geovannycode.corteya.booking.BrebKeyType.ALIAS, "@TEST", "Titular de prueba", holdMinutes = 15))
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

/** Use the exact production bootstrap, not a privileged runtime or test-only grants. */
internal fun testPostgres(): PostgreSQLContainer = PostgreSQLContainer(DockerImageName.parse(TEST_POSTGRES_IMAGE))
    .withEnv("CORTEYA_OWNER_PASSWORD", "owner-test-only")
    .withEnv("CORTEYA_APP_PASSWORD", "app-test-only")
    .withCopyFileToContainer(MountableFile.forHostPath("docker/postgres/01-roles.sh", 493),
        "/docker-entrypoint-initdb.d/01-roles.sh")
