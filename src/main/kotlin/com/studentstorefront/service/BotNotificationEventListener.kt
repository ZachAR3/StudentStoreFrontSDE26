package com.studentstorefront.service

import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class BotNotificationEventListener(
    private val notificationOutboxService: NotificationOutboxService
) {
    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun enqueueSaleReviewRequest(event: SaleCompletedEvent) {
        notificationOutboxService.enqueueSaleReviewRequest(event)
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun enqueueVerifiedUserNotification(event: PhoneVerifiedEvent) {
        notificationOutboxService.enqueueRegisteredUser(
            userId = event.userId,
            phoneNumber = event.phoneNumber,
            trigger = "phone-verified-${event.verifiedAt}"
        )
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    fun enqueueRegisteredUserNotification(event: UserRegisteredEvent) {
        notificationOutboxService.enqueueRegisteredUser(
            userId = event.userId,
            phoneNumber = event.phoneNumber,
            trigger = "registration-${event.registrationVersion}"
        )
    }
}
