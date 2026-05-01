package com.studentstorefront.repository

import com.studentstorefront.entity.Post
import com.studentstorefront.entity.PostMedia
import org.springframework.data.jpa.repository.JpaRepository

interface PostMediaRepository: JpaRepository<PostMedia, Long> {
    fun findByPost_postId(postId: Long): List<PostMedia>
    fun findByImageHashIn(imageHashes: Collection<String>): List<PostMedia>
}
