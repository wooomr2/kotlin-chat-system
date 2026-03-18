package com.chat.persistence.mapper

import com.chat.domain.dto.ChatRoomDto
import com.chat.domain.model.ChatRoom
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.chat.persistence.repository.MessageRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Component

@Component
class ChatRoomMapper(
    private val userMapper: UserMapper,
    private val messageMapper: MessageMapper,
    private val messageRepository: MessageRepository,
    private val chatRoomMemberRepository: ChatRoomMemberRepository,
) {

    @Cacheable(value = ["chatRooms"], key = "#chatRoom.id")
    fun toDtoAndCacheable(chatRoom: ChatRoom): ChatRoomDto {
        val memberCount = chatRoomMemberRepository.countActiveMembersInRoom(chatRoom.id).toInt()
        val lastMessage = messageRepository.findLatestMessage(chatRoom.id)?.let { messageMapper.toDto(it) }

        return ChatRoomDto(
            id = chatRoom.id,
            name = chatRoom.name,
            description = chatRoom.description,
            type = chatRoom.type,
            imageUrl = chatRoom.imageUrl,
            isActive = chatRoom.isActive,
            maxMembers = chatRoom.maxMembers,
            memberCount = memberCount,
            createdBy = userMapper.toDtoAndCacheable(chatRoom.createdBy),
            createdAt = chatRoom.createdAt,
            lastMessage = lastMessage
        )
    }
}