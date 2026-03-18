package com.chat.persistence.mapper

import com.chat.domain.dto.ChatRoomMemberDto
import com.chat.domain.model.ChatRoomMember
import org.springframework.stereotype.Component

@Component
class ChatRoomMemberMapper(
    private val userMapper: UserMapper
) {

    fun toDto(member: ChatRoomMember): ChatRoomMemberDto {
        return ChatRoomMemberDto(
            id = member.id,
            user = userMapper.toDtoAndCacheable(member.user),
            role = member.role,
            isActive = member.isActive,
            lastReadMessageId = member.lastReadMessageId,
            joinedAt = member.joinedAt,
            leftAt = member.leftAt
        )
    }
}