package com.studentstorefront.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient

@Service
class BotNotificationService(
    @Value("\${whatsapp.bot.api-url}") private val botApiUrl: String,
    @Value("\${whatsapp.bot.api-key}") private val botApiKey: String
) {
    private val client = WebClient.builder().baseUrl(botApiUrl).build()

    fun notifySellerRegistered(phoneNumber: String) {
        client.post()
            .uri("/webhook/user-registered")
            .header("X-Bot-Api-Key", botApiKey)
            .bodyValue(mapOf("phoneNumber" to phoneNumber))
            .retrieve()
            .bodyToMono(Void::class.java)
            .block(java.time.Duration.ofSeconds(10))
    }

    fun sendDirectMessage(phone: String, message: String) {
        client.post()
            .uri("/send-message")
            .header("X-Bot-Api-Key", botApiKey)
            .bodyValue(mapOf("phone" to phone, "message" to message))
            .retrieve()
            .bodyToMono(Void::class.java)
            .block(java.time.Duration.ofSeconds(10))
    }

    fun notifyAccountDeleted(phoneNumber: String) {
        client.post()
            .uri("/webhook/account-deleted")
            .header("X-Bot-Api-Key", botApiKey)
            .bodyValue(mapOf("phoneNumber" to phoneNumber))
            .retrieve()
            .bodyToMono(Void::class.java)
            .block(java.time.Duration.ofSeconds(10))
    }
}
