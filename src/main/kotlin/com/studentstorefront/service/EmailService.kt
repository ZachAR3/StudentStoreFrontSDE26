package com.studentstorefront.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service

@Service
class EmailService(private val mailSender: JavaMailSender) {

    @Value("\${spring.mail.username}")
    private lateinit var fromEmail: String

    fun sendVerificationEmail(toEmail: String, code: String) {
        val message = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, true, "UTF-8")

        helper.setFrom(fromEmail)
        helper.setTo(toEmail)
        helper.setSubject("Verify your Student-Store Front email")
        helper.setText(buildVerificationEmailBody(code), true)

        mailSender.send(message)
    }

    fun sendPasswordResetEmail(toEmail: String, resetLink: String) {
        val message = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, true, "UTF-8")

        helper.setFrom(fromEmail)
        helper.setTo(toEmail)
        helper.setSubject("Reset your Student-Store Front password")
        helper.setText(buildEmailBody(resetLink), true)

        mailSender.send(message)
    }

    private fun buildVerificationEmailBody(code: String): String = """
        <html>
        <body style="font-family: Arial, sans-serif; background-color: #f4f4f4; padding: 32px;">
          <div style="max-width: 480px; margin: auto; background: white; border-radius: 8px; padding: 32px;">
            <h2 style="color: #1a1a1a;">Verify your email</h2>
            <p style="color: #555;">Enter this code to complete your Student-Store Front registration:</p>
            <div style="font-size: 36px; font-weight: bold; letter-spacing: 8px; text-align: center; margin: 24px 0; color: #2563eb; background: #f0f4ff; border-radius: 8px; padding: 16px;">$code</div>
            <p style="color: #555;">This code expires in <strong>15 minutes</strong>.</p>
            <p style="color: #999; font-size: 13px;">If you didn't create an account, you can safely ignore this email.</p>
          </div>
        </body>
        </html>
    """.trimIndent()

    private fun buildEmailBody(resetLink: String): String = """
        <html>
        <body style="font-family: Arial, sans-serif; background-color: #f4f4f4; padding: 32px;">
          <div style="max-width: 480px; margin: auto; background: white; border-radius: 8px; padding: 32px;">
            <h2 style="color: #1a1a1a;">Reset your password</h2>
            <p style="color: #555;">We received a request to reset the password for your Student-Store Front account.</p>
            <p style="color: #555;">Click the button below to choose a new password. This link expires in <strong>30 minutes</strong>.</p>
            <a href="$resetLink"
               style="display: inline-block; margin: 24px 0; padding: 12px 24px;
                      background-color: #2563eb; color: white; text-decoration: none;
                      border-radius: 6px; font-weight: bold;">
              Reset Password
            </a>
            <p style="color: #999; font-size: 13px;">If you didn't request this, you can safely ignore this email.</p>
          </div>
        </body>
        </html>
    """.trimIndent()
}
