package com.geovannycode.corteya

import com.geovannycode.corteya.identity.OtpSender
import java.util.concurrent.ConcurrentHashMap
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

class RecordingOtpSender : OtpSender {
    val codes = ConcurrentHashMap<String, String>()
    @Volatile var beforeSend: ((String, String) -> Unit)? = null
    override fun send(phone: String, code: String) {
        beforeSend?.invoke(phone, code)
        codes[phone] = code
    }
}

@TestConfiguration(proxyBeanMethods = false)
class AuthTestConfiguration {
    @Bean fun recordingOtpSender(): RecordingOtpSender = RecordingOtpSender()
}
