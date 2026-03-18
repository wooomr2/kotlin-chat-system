package com.chat.persistence.service

import com.chat.domain.dto.*
import com.chat.domain.model.ChatRoom
import com.chat.domain.model.ChatRoomMember
import com.chat.domain.model.MemberRole
import com.chat.domain.model.Message
import com.chat.domain.service.ChatService
import com.chat.persistence.mapper.ChatRoomMapper
import com.chat.persistence.mapper.ChatRoomMemberMapper
import com.chat.persistence.mapper.MessageMapper
import com.chat.persistence.redis.RedisMessageBroker
import com.chat.persistence.repository.ChatRoomMemberRepository
import com.chat.persistence.repository.ChatRoomRepository
import com.chat.persistence.repository.MessageRepository
import com.chat.persistence.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class ChatServiceImpl(
    private val chatRoomRepository: ChatRoomRepository,
    private val messageRepository: MessageRepository,
    private val chatRoomMemberRepository: ChatRoomMemberRepository,
    private val userRepository: UserRepository,
    private val redisMessageBroker: RedisMessageBroker,
    private val messageSequenceService: MessageSequenceService,
    private val webSocketSessionManager: WebSocketSessionManager,
    private val chatRoomMapper: ChatRoomMapper,
    private val chatRoomMemberMapper: ChatRoomMemberMapper,
    private val messageMapper: MessageMapper
) : ChatService {

    private val logger = LoggerFactory.getLogger(ChatServiceImpl::class.java)

    @CacheEvict(value = ["chatRooms"], allEntries = true)
    override fun createChatRoom(request: CreateChatRoomRequest, createdBy: Long): ChatRoomDto {
        val creator = userRepository.findById(createdBy)
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다: $createdBy") }

        val chatRoom = ChatRoom(
            name = request.name,
            description = request.description,
            type = request.type,
            imageUrl = request.imageUrl,
            maxMembers = request.maxMembers,
            createdBy = creator,
        )

        val savedRoom = chatRoomRepository.save(chatRoom)

        val roomOwner = ChatRoomMember(
            chatRoom = savedRoom,
            user = creator,
            role = MemberRole.OWNER
        )
        chatRoomMemberRepository.save(roomOwner)

        // session 갱신
        if (webSocketSessionManager.isUserOnlineLocally(creator.id)) {
            webSocketSessionManager.joinRoom(creator.id, savedRoom.id)
        }

        return chatRoomMapper.toDtoAndCacheable(savedRoom)
    }

    @Cacheable(value = ["chatRooms"], key = "#roomId")
    override fun getChatRoom(roomId: Long): ChatRoomDto {
        val chatRoom = chatRoomRepository.findById(roomId)
            .orElseThrow { IllegalArgumentException("채팅방을 찾을 수 없습니다: $roomId") }

        return chatRoomMapper.toDtoAndCacheable(chatRoom)
    }

    override fun getChatRooms(userId: Long, pageable: Pageable): Page<ChatRoomDto> {
        return chatRoomRepository.findUserChatRooms(userId, pageable).map { chatRoomMapper.toDtoAndCacheable(it) }
    }

    override fun searchChatRooms(query: String, userId: Long): List<ChatRoomDto> {
        val chatRooms = if (query.isBlank()) {
            chatRoomRepository.findByIsActiveTrueOrderByCreatedAtDesc()
        } else {
            chatRoomRepository.findByNameContainingIgnoreCaseAndIsActiveTrueOrderByCreatedAtDesc(query)
        }

        return chatRooms.map { chatRoomMapper.toDtoAndCacheable(it) }
    }

    @Caching(
        evict = [
            CacheEvict(value = ["chatRoomMembers"], key = "#roomId"),
            CacheEvict(value = ["chatRooms"], key = "#roomId")
        ]
    )
    override fun joinChatRoom(roomId: Long, userId: Long) {
        val chatRoom =
            chatRoomRepository.findById(roomId).orElseThrow { IllegalArgumentException("채팅방을 찾을 수 없습니다 $roomId") }

        val user = userRepository.findById(userId).orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다: $userId") }

        if (chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)) {
            throw IllegalStateException("이미 채팅방에 참여중입니다: roomId=$roomId, userId=$userId")
        }

        val currentMemberCount = chatRoomMemberRepository.countActiveMembersInRoom(roomId)
        if (currentMemberCount >= chatRoom.maxMembers) {
            throw IllegalStateException("채팅방이 가득 찼습니다: roomId=$roomId")
        }

        val member = ChatRoomMember(
            chatRoom = chatRoom,
            user = user,
            role = MemberRole.MEMBER
        )
        chatRoomMemberRepository.save(member)

        // session 갱신
        if (webSocketSessionManager.isUserOnlineLocally(userId)) {
            webSocketSessionManager.joinRoom(userId, roomId)
        }
    }

    @Caching(
        evict = [
            CacheEvict(value = ["chatRoomMembers"], key = "#roomId"),
            CacheEvict(value = ["chatRooms"], key = "#roomId")
        ]
    )
    override fun leaveChatRoom(roomId: Long, userId: Long) {
        chatRoomMemberRepository.leaveChatRoom(roomId, userId)
        // 세션을 정리하지 않은 이유는? - interceptor 별도 구현
    }

    @Cacheable(value = ["chatRoomMembers"], key = "#roomId")
    override fun getChatRoomMembers(roomId: Long): List<ChatRoomMemberDto> {
        return chatRoomMemberRepository.findByChatRoomIdAndIsActiveTrue(roomId)
            .map { chatRoomMemberMapper.toDto(it) }
    }

    override fun sendMessage(request: SendMessageRequest, senderId: Long): MessageDto {
        val chatRoom = chatRoomRepository.findById(request.chatRoomId)
            .orElseThrow { IllegalArgumentException("채팅방을 찾을 수 없습니다: ${request.chatRoomId}") }

        val sender = userRepository.findById(senderId)
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다: $senderId") }

        chatRoomMemberRepository.findByChatRoomIdAndUserIdAndIsActiveTrue(request.chatRoomId, senderId)
            .orElseThrow { IllegalStateException("채팅방에 참여중이지 않습니다: roomId=${request.chatRoomId}, userId=$senderId") }

        // 시퀀스
        val sequenceNumber = messageSequenceService.getNextSequence(request.chatRoomId)

        // 메세지 저장
        val message = Message(
            content = request.content,
            type = request.type,
            chatRoom = chatRoom,
            sender = sender,
            sequenceNumber = sequenceNumber
        )
        val savedMessage = messageRepository.save(message)

        val chatMessage = ChatMessage(
            id = savedMessage.id,
            content = savedMessage.content ?: "",
            type = savedMessage.type,
            senderId = savedMessage.sender.id,
            senderName = savedMessage.sender.displayName,
            sequenceNumber = savedMessage.sequenceNumber,
            chatRoomId = savedMessage.chatRoom.id,
            timestamp = savedMessage.createdAt
        )

        // 1. 로컬 세션에 즉시 전송 (실시간 응답성 보장)
        webSocketSessionManager.sendMessageToLocalRoom(request.chatRoomId, chatMessage)

        // 2. 다른 서버 인스턴스에 브로드캐스트 (자신을 제외)
        try {
            redisMessageBroker.broadcastToRoom(
                roomId = request.chatRoomId,
                message = chatMessage,
                excludeServerId = redisMessageBroker.getServerId()
            )
        } catch (e: Exception) {
            logger.error("Error broadcasting message to other servers for roomId=${request.chatRoomId}", e)
        }

        return messageMapper.toDto(savedMessage)
    }

    override fun getMessages(roomId: Long, userId: Long, pageable: Pageable): Page<MessageDto> {
        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(roomId, userId)) {
            throw IllegalStateException("채팅방에 참여중이지 않습니다: roomId=$roomId, userId=$userId")
        }

        return messageRepository.findByChatRoomId(roomId, pageable)
            .map { messageMapper.toDto(it) }
    }

    override fun getMessagesByCursor(request: MessagePageRequest, userId: Long): MessagePageResponse {
        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserIdAndIsActiveTrue(request.chatRoomId, userId)) {
            throw IllegalArgumentException("채팅방 멤버가 아닙니다")
        }

        val pageable = PageRequest.of(0, request.limit)
        val chatRoomId = request.chatRoomId
        val cursor = request.cursor // 로컬 변수로 복사하여 스마트 캐스트 가능하게 함

        val messages = when {
            cursor == null -> {
                // 커서가 없으면 최신 메세지 부터
                messageRepository.findLatestMessages(chatRoomId, pageable)
            }

            request.direction == MessageDirection.BEFORE -> {
                // 커서 이전 메세지들(과거 방향)
                messageRepository.findMessagesBefore(chatRoomId, cursor, pageable)
            }

            else -> {
                // 커서 이후 메세지들(최신 방향)
                messageRepository.findMessagesAfter(chatRoomId, cursor, pageable)
            }
        }

        val messageDtoList = messages.map { messageMapper.toDto(it) }

        // 다음/이전 커서 계산
        val nextCursor = if (messageDtoList.isNotEmpty()) messageDtoList.last().id else null
        val prevCursor = if (messageDtoList.isNotEmpty()) messageDtoList.first().id else null

        // 추가 데이터 존재 여부 확인
        val hasNext = messages.size == request.limit
        val hasPrev = cursor != null

        return MessagePageResponse(
            messages = messageDtoList,
            nextCursor = nextCursor,
            prevCursor = prevCursor,
            hasNext = hasNext,
            hasPrev = hasPrev,
        )
    }
}