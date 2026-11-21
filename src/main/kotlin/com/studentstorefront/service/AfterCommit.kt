package com.studentstorefront.service

import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

internal object AfterCommit {
    fun run(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive() ||
            !TransactionSynchronizationManager.isActualTransactionActive()
        ) {
            action()
            return
        }

        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = action()
        })
    }

    fun onRollback(action: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive() ||
            !TransactionSynchronizationManager.isActualTransactionActive()
        ) {
            action()
            return
        }

        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) action()
            }
        })
    }
}
