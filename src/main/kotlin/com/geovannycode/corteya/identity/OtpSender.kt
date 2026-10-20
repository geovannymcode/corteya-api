package com.geovannycode.corteya.identity

interface OtpSender {
    fun send(phone: String, code: String)
}
