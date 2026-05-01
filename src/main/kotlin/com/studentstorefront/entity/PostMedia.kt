package com.studentstorefront.entity
import jakarta.persistence.*
@Entity
@Table (name = "post_media")
    data class PostMedia(
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
        @ManyToOne
        @JoinColumn(name = "post_id")
        val post: Post,
        @Column(nullable = false)
        val mediaUrl: String,
        @Column(name = "image_hash")
        val imageHash: String? = null,
        @Column(columnDefinition = "integer default 0", nullable = false)
        val displayOrder: Int = 0,
        @Column(columnDefinition = "boolean default false", nullable = false)
        val isCover: Boolean = false
    )
