package com.studentstorefront.repository

import com.studentstorefront.entity.Post
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PostRepository: JpaRepository<Post, Long> {

    fun findByCategory(category: String, pageable: Pageable): Page<Post>

    fun findBySellerSellerId(sellerId: Long, pageable: Pageable): Page<Post>

    fun findByIsSoldFalse(pageable: Pageable): Page<Post>

}