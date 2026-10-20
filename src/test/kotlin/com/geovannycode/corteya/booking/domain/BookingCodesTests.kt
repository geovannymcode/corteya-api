package com.geovannycode.corteya.booking.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BookingCodesTests {
    @Test
    fun `deposits round upward using integer arithmetic`() {
        assertEquals(12000L, BookingCodes.depositCop(38000, 30))
        assertEquals(12000L, BookingCodes.depositCop(40000, 30))
        assertEquals(0L, BookingCodes.depositCop(38000, 0))
        assertEquals(0L, BookingCodes.depositCop(0, 100))
        assertEquals(1000L, BookingCodes.depositCop(1, 30))
        assertEquals(38000L, BookingCodes.depositCop(38000, 100))
        assertTrue(BookingCodes.depositCop(Long.MAX_VALUE, 30) > 0)
    }

    @Test
    fun `references exclude ambiguous characters and public tokens contain 128 random bits`() {
        repeat(100) {
            assertTrue(BookingCodes.paymentReference().matches(Regex("R-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}")))
            assertTrue(BookingCodes.publicToken().matches(Regex("[0-9a-f]{32}")))
        }
    }
}
