package com.studentstorefront.service

import com.studentstorefront.entity.Post
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.format.DateTimeFormatter

@Service
class BotNotificationService(
    @Value("\${whatsapp.bot.api-url}") private val botApiUrl: String,
    @Value("\${whatsapp.bot.api-key}") private val botApiKey: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val client = WebClient.builder().baseUrl(botApiUrl).build()

    fun sendRenewalReminder(post: Post) {
        val phone = post.seller?.phoneNumber ?: run {
            log.warn("Post ${post.postId} has no seller phone — skipping reminder")
            return
        }
        val expiresAt = post.expiresAt?.format(DateTimeFormatter.ofPattern("HH:mm, dd MMM")) ?: "soon"
        val message = "Your listing \"${post.title}\" expires at $expiresAt. " +
                "Reply RENEW ${post.postId} to extend it for 2 more days."

        client.post()
            .uri("/send-message")
            .header("X-Bot-Api-Key", botApiKey)
            .bodyValue(mapOf("phone" to phone, "message" to message))
            .retrieve()
            .bodyToMono(Void::class.java)
            .onErrorResume { ex ->
                log.error("Failed to send renewal reminder for post ${post.postId}: ${ex.message}")
                Mono.empty()
            }
            .subscribe()
    }
}
