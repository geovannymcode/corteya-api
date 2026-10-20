package com.geovannycode.corteya.identity

object PhoneNumbers {
    fun normalize(raw: String): String? = when {
        raw.matches(Regex("3[0-9]{9}")) -> "+57$raw"
        raw.matches(Regex("\\+573[0-9]{9}")) -> raw
        else -> null
    }

    fun mask(phone: String): String = normalize(phone)?.let { it.take(6) + "****" + it.takeLast(3) } ?: "***"
}
