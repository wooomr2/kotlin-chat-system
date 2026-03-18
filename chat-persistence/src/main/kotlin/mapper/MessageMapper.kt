package com.chat.persistence.mapper

import com.chat.domain.dto.MessageDto
import com.chat.domain.model.Message
import org.springframework.stereotype.Component

@Component
class MessageMapper(
    private val userMapper: UserMapper
) {

    fun toDto(message: Message): MessageDto {
        return MessageDto(
            id = message.id,
            chatRoomId = message.chatRoom.id,
            sender = userMapper.toDtoAndCacheable(message.sender),
            type = message.type,
            content = message.content,
            isEdited = message.isEdited,
            isDeleted = message.isDeleted,
            createdAt = message.createdAt,
            editedAt = message.editedAt,
            sequenceNumber = message.sequenceNumber
        )
    }
}