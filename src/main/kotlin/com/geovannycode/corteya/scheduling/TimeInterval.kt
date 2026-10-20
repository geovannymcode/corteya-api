package com.geovannycode.corteya.scheduling

import java.time.Instant

data class TimeInterval(val startsAt: Instant, val endsAt: Instant) {
    fun overlaps(other: TimeInterval): Boolean = startsAt < other.endsAt && other.startsAt < endsAt
}

