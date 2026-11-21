package com.studentstorefront.controller

import com.studentstorefront.service.SaleService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sales")
class SaleController(private val saleService: SaleService) {
    @GetMapping("/pending-confirmations")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    fun getPendingConfirmations() = ResponseEntity.ok(saleService.getPendingConfirmations())

    @DeleteMapping("/posts/{postId}")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    fun cancelSale(@PathVariable postId: Long): ResponseEntity<Void> {
        saleService.cancelSale(postId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/posts/{postId}/decline")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    fun declinePurchase(@PathVariable postId: Long): ResponseEntity<Void> {
        saleService.declinePurchase(postId)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/posts/{postId}/confirm")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    fun confirmPurchase(@PathVariable postId: Long): ResponseEntity<Void> {
        saleService.confirmPurchase(postId)
        return ResponseEntity.noContent().build()
    }
}
