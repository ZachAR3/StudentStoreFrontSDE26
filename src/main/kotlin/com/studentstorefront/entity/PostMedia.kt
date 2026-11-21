package com.studentstorefront.entity
import jakarta.persistence.*
@Entity
@Table (name = "post_media")
    data class PostMedia(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
        @ManyToOne(fetch = FetchType.LAZY)
        @JoinColumn(name = "post_id", nullable = false)
        val post: Post,
        @Column(nullable = false)
        val mediaUrl: String,
        @Column(name = "image_hash")
        var imageHash: String? = null,
        @Column(name = "cloudinary_public_id", length = 255)
        val cloudinaryPublicId: String? = null,
        @Column(columnDefinition = "integer default 0", nullable = false)
        var displayOrder: Int = 0,
        @Column(columnDefinition = "boolean default false", nullable = false)
        var isCover: Boolean = false
    ) {
        override fun toString(): String =
            "PostMedia(id=$id, postId=${post.postId}, mediaUrl=$mediaUrl, displayOrder=$displayOrder, isCover=$isCover)"

        override fun equals(other: Any?): Boolean =
            this === other || (other is PostMedia && id != null && id == other.id)

        override fun hashCode(): Int = id?.hashCode() ?: System.identityHashCode(this)
    }
