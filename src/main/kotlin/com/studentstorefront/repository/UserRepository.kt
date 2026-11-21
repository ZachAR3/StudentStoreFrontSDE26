package com.studentstorefront.repository

import com.studentstorefront.entity.User
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType

@Repository
interface UserRepository: JpaRepository<User, Long> {
    fun findByEmail(email: String): User?
    fun existsByEmail(email: String): Boolean
    fun findByPhoneNumber(phoneNumber: String): User?
    fun findByPendingEmail(pendingEmail: String): User?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.userId = :userId")
    fun findByIdForUpdate(@Param("userId") userId: Long): User?

    @Query(
        """
        SELECT s FROM User s
        WHERE s.isEnabled = true
        AND (:excludeSellerId IS NULL OR s.userId <> :excludeSellerId)
        AND (
            LOWER(s.name) LIKE LOWER(CONCAT('%', :query, '%')) ESCAPE '!'
        )
        """
    )
    fun searchEnabledUsersByName(
        @Param("query") query: String,
        @Param("excludeSellerId") excludeSellerId: Long?,
        pageable: Pageable
    ): Page<User>
}
