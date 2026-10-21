package com.geovannycode.corteya.booking.internal

import java.net.http.HttpClient
import java.time.Duration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient

@Configuration(proxyBeanMethods = false)
class WompiHttpConfiguration {
    @Bean("wompiRestClientBuilder")
    fun wompiRestClientBuilder(): RestClient.Builder {
        val factory = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build())
        factory.setReadTimeout(Duration.ofSeconds(3))
        return RestClient.builder().requestFactory(factory)
    }
}
