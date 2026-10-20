package com.geovannycode.corteya.shared

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.OutputStreamAppender
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MaskedLoggingTests {
    @Test
    fun `configured encoder masks phones in messages and complete exception chains`() {
        val context = LoggerContext().apply { mdcAdapter = ch.qos.logback.classic.util.LogbackMDCAdapter() }
        try {
            JoranConfigurator().apply { setContext(context) }.doConfigure(
                checkNotNull(javaClass.getResource("/logback-spring.xml")),
            )
            val bytes = ByteArrayOutputStream()
            val encoder = PatternLayoutEncoder().apply {
                setContext(context)
                pattern = context.getProperty("MASKED_LOG_PATTERN")
                start()
            }
            val appender = OutputStreamAppender<ILoggingEvent>().apply {
                setContext(context)
                setEncoder(encoder)
                outputStream = bytes
                start()
            }
            context.getLogger("mask-test").apply {
                isAdditive = false
                addAppender(appender)
                error("Phone +573001234567", IllegalStateException("Key (phone)=(+573001234567)",
                    IllegalArgumentException("Nested +573001234567")))
            }
            val output = bytes.toString(Charsets.UTF_8)
            assertFalse(output.contains("+573001234567"), output)
            assertEquals(3, Regex(Regex.escape("+57300****567")).findAll(output).count(), output)
            assertTrue(output.contains("IllegalStateException"), output)
            assertTrue(output.contains("Caused by:"), output)
            assertTrue(output.contains("MaskedLoggingTests"), output)
        } finally {
            context.stop()
        }
    }
}
