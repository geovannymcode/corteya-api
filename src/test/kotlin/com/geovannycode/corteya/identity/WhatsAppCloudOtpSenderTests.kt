package com.geovannycode.corteya.identity

import com.geovannycode.corteya.identity.application.AuthFailure
import com.geovannycode.corteya.identity.infrastructure.otp.WhatsAppCloudOtpSender
import com.geovannycode.corteya.identity.infrastructure.otp.WhatsAppTemplate
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.client.MockClientHttpRequest
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.*
import org.springframework.test.web.client.response.MockRestResponseCreators.*
import org.springframework.web.client.RestClient
import tools.jackson.databind.json.JsonMapper
import kotlin.test.*

class WhatsAppCloudOtpSenderTests {
    @Test
    fun `authentication template contains OTP in body and copy-code button with configured Meta credentials`() {
        val builder = RestClient.builder().baseUrl("https://graph.facebook.com")
            .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-meta-token")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("https://graph.facebook.com/vTEST/test-number-id/messages"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-meta-token"))
            .andExpect { request ->
                val json = JsonMapper.builder().build().readTree((request as MockClientHttpRequest).bodyAsString)
                assertEquals("whatsapp", json.path("messaging_product").asString())
                assertEquals("573001234567", json.path("to").asString())
                assertEquals("template", json.path("type").asString())
                val template = json.path("template")
                assertEquals("corteya_login", template.path("name").asString())
                assertEquals("es_CO", template.path("language").path("code").asString())
                val parts = template.path("components")
                assertEquals("body", parts[0].path("type").asString())
                assertEquals("123456", parts[0].path("parameters")[0].path("text").asString())
                assertEquals("button", parts[1].path("type").asString())
                assertEquals("url", parts[1].path("sub_type").asString())
                assertEquals("0", parts[1].path("index").asString())
                assertEquals("123456", parts[1].path("parameters")[0].path("text").asString())
            }
            .andRespond(withSuccess("{\"messages\":[{\"id\":\"test-message-id\"}]}", MediaType.APPLICATION_JSON))
        WhatsAppCloudOtpSender(builder.build(), WhatsAppTemplate("vTEST", "test-number-id", "corteya_login", "es_CO"))
            .send("+573001234567", "123456")
        server.verify()
    }

    @Test
    fun `provider failures never expose response bodies or credentials`() {
        val builder = RestClient.builder().baseUrl("https://graph.facebook.com")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(anything()).andRespond(withStatus(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"private +573001234567 test-meta-token 123456\"}"))
        val failure = assertFailsWith<AuthFailure> {
            WhatsAppCloudOtpSender(builder.build(), WhatsAppTemplate("vTEST", "test-number-id", "corteya_login", "es_CO"))
                .send("+573001234567", "123456")
        }
        assertEquals(503, failure.status)
        assertTrue(listOf("573001234567", "test-meta-token", "123456", "private").none { failure.message.contains(it) })
        assertNull(failure.cause)
        server.verify()
    }
}
