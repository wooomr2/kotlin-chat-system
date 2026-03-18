package com.chat.persistence.mapper

import com.chat.domain.dto.UserDto
import com.chat.domain.model.User
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component

@Component
class UserMapper {

    @Cacheable(value = ["users"], key = "#user.id")
    fun toDtoAndCacheable(user: User): UserDto {
        return UserDto(
            id = user.id,
            username = user.username,
            displayName = user.displayName,
            profileImageUrl = user.profileImageUrl,
            status = user.status,
            isActive = user.isActive,
            lastSeenAt = user.lastSeenAt,
            createdAt = user.createdAt
        )
    }

    fun toDto(user: User): UserDto {
        return UserDto(
            id = user.id,
            username = user.username,
            displayName = user.displayName,
            profileImageUrl = user.profileImageUrl,
            status = user.status,
            isActive = user.isActive,
            lastSeenAt = user.lastSeenAt,
            createdAt = user.createdAt
        )
    }
}