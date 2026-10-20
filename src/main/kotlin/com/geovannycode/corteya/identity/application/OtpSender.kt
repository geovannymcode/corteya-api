package com.geovannycode.corteya.identity.application

interface OtpSender {
    fun send(phone: String, code: String)
}
