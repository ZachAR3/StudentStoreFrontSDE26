package com.studentstorefront
import org.springframework.data.jpa.repository.JpaRepository

//same thing as the Seller Repo but with the Posts table

interface PostRepository: JpaRepository<Post, Long> {
}