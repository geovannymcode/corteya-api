package com.geovannycode.corteya.booking.domain

import java.math.BigInteger
import java.security.SecureRandom

object BookingCodes {
    private val random = SecureRandom()
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"

    fun publicToken(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    fun paymentReference(): String = "R-" + (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    fun depositCop(priceCop: Long, percent: Int): Long {
        require(priceCop >= 0 && percent in 0..100)
        // ceil(price * percent / 100 / 1000) * 1000; no floating point or overflow.
        return BigInteger.valueOf(priceCop).multiply(BigInteger.valueOf(percent.toLong()))
            .add(BigInteger.valueOf(99999)).divide(BigInteger.valueOf(100000))
            .multiply(BigInteger.valueOf(1000)).longValueExact()
    }
}
