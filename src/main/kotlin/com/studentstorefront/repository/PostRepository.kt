package com.studentstorefront.repository

import com.studentstorefront.entity.Post
import org.springframework.data.jpa.repository.JpaRepository

interface PostRepository: JpaRepository<Post, Long> {
}