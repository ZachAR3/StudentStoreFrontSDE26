package com.studentstorefront.service

import com.studentstorefront.entity.Post
import com.studentstorefront.entity.PostMedia
import com.studentstorefront.repository.PostMediaRepository
import org.springframework.stereotype.Service

@Service
class MediaCleanupService(
    private val postMediaRepository: PostMediaRepository,
    private val cloudinaryService: CloudinaryService
) {
    fun scheduleForPosts(posts: Collection<Post>) {
        val postIds = posts.mapNotNull { it.postId }
        if (postIds.isEmpty()) return
        schedule(postMediaRepository.findByPostPostIdIn(postIds))
    }

    fun schedule(media: Collection<PostMedia>) {
        val publicIds = media.mapNotNull { it.cloudinaryPublicId }.distinct()
        if (publicIds.isEmpty()) return
        AfterCommit.run {
            publicIds
                .filterNot(postMediaRepository::existsByCloudinaryPublicId)
                .forEach(cloudinaryService::deleteImage)
        }
    }
}
