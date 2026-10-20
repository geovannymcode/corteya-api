package com.geovannycode.corteya.identity.infrastructure.otp

import com.fasterxml.jackson.annotation.JsonProperty
import com.geovannycode.corteya.identity.application.AuthFailure
import com.geovannycode.corteya.identity.application.OtpSender
import java.net.http.HttpClient
import java.time.Duration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpHeaders
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

data class WhatsAppTemplate(val graphVersion: String, val phoneNumberId: String, val name: String, val language: String)

class WhatsAppCloudOtpSender(private val client: RestClient, private val template: WhatsAppTemplate) : OtpSender {
    override fun send(phone: String, code: String) {
        val parameter = listOf(mapOf("type" to "text", "text" to code))
        val body = WhatsAppMessage(
            to = phone.removePrefix("+"),
            template = mapOf("name" to template.name, "language" to mapOf("code" to template.language),
                "components" to listOf(mapOf("type" to "body", "parameters" to parameter),
                    mapOf("type" to "button", "sub_type" to "url", "index" to "0", "parameters" to parameter))),
        )
        try {
            client.post().uri("/{version}/{id}/messages", template.graphVersion, template.phoneNumberId)
                .body(body).retrieve().toBodilessEntity()
        } catch (_: RestClientException) {
            // Never propagate provider response bodies: they may contain phone numbers or credentials.
            throw AuthFailure(503, "No pudimos enviar el código; intenta de nuevo más tarde")
        }
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["auth.otp.sender"], havingValue = "whatsapp", matchIfMissing = true)
class WhatsAppOtpConfiguration {
    @Bean
    fun whatsAppOtpSender(environment: Environment): OtpSender {
        fun required(name: String): String = environment.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: error("Falta la configuración de WhatsApp: $name")
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(Duration.ofSeconds(10)) }
        val client = RestClient.builder().baseUrl("https://graph.facebook.com").requestFactory(factory)
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${required("auth.whatsapp.token")}").build()
        return WhatsAppCloudOtpSender(client, WhatsAppTemplate(required("auth.whatsapp.graph-version"), required("auth.whatsapp.phone-number-id"),
            required("auth.whatsapp.template"), environment.getProperty("auth.whatsapp.language", "es_CO")))
    }
}

/** HTTP diagnostic log formatting uses this safe representation rather than printing the OTP payload. */
private data class WhatsAppMessage(
    val to: String,
    val template: Map<String, Any>,
    @get:JsonProperty("messaging_product") val messagingProduct: String = "whatsapp",
    @get:JsonProperty("recipient_type") val recipientType: String = "individual",
    val type: String = "template",
) {
    override fun toString(): String = "WhatsAppMessage([redacted])"
}
