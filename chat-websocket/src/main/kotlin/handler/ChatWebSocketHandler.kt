package com.chat.websocket.handler

import com.chat.domain.dto.ErrorMessage
import com.chat.domain.dto.SendMessageRequest
import com.chat.domain.model.MessageType
import com.chat.domain.service.ChatService
import com.chat.persistence.service.WebSocketSessionManager
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Component
import org.springframework.web.socket.*

@Component
class ChatWebSocketHandler(
    private val sessionManager: WebSocketSessionManager,
    private val chatService: ChatService,
    private val objectMapper: ObjectMapper,
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(ChatWebSocketHandler::class.java)

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val userId = getUserIdFromSession(session)
        if (userId != null) {
            sessionManager.addSession(userId, session)
            logger.info("WebSocket connection established for userId: $userId")

            try {
                loadUserChatRooms(userId)
            } catch (e: Exception) {
                logger.error("WebSocket connection error", e)
            }
        }
    }

    override fun handleMessage(
        session: WebSocketSession,
        message: WebSocketMessage<*>
    ) {
        val userId = getUserIdFromSession(session) ?: return

        try {
            when (message) {
                is TextMessage -> {
                    handelTextMessage(session, userId, message.payload)
                }

                else -> {
                    logger.warn("Unsupported message type: ${message.javaClass.name}")
                }
            }

        } catch (e: Exception) {
            logger.warn("Exception while processing message", e)
            sendErrorMessage(session, "메시지 처리 에러")
        }
    }

    override fun handleTransportError(
        session: WebSocketSession,
        exception: Throwable?
    ) {
        val userId = getUserIdFromSession(session)

        // EOFException -> 클라이언트 연결 해제, 정상적인 상황 (로그레벨을 따로 두기 위해)
        if (exception is java.io.EOFException) {
            logger.debug("WebSocket connection closed by client for user: $userId")
        } else {
            logger.error("WebSocket transport error for user: $userId", exception)
        }

        if (userId != null) {
            sessionManager.removeSession(userId, session)
        }
    }

    override fun afterConnectionClosed(
        session: WebSocketSession,
        closeStatus: CloseStatus?
    ) {
        val userId = getUserIdFromSession(session)
        if (userId != null) {
            sessionManager.removeSession(userId, session)
            logger.info("WebSocket connection closed for userId: $userId")
        }
    }

    override fun supportsPartialMessages(): Boolean {
        return false
    }

    private fun getUserIdFromSession(session: WebSocketSession): Long? {
        return session.attributes["userId"] as? Long
    }

    private fun loadUserChatRooms(userId: Long) {
        try {
            val chatRooms = chatService.getChatRooms(userId, PageRequest.of(0, 100))

            chatRooms.content.forEach { room ->
                sessionManager.joinRoom(userId, room.id)
            }

            logger.info("Loaded ${chatRooms.content.size} chat rooms for userId: $userId")
        } catch (e: Exception) {
            logger.error("Error while loading user chat rooms for userId: $userId", e)
        }
    }

    private fun extractMessageType(payload: String): String? {
        try {
            return objectMapper.readTree(payload).get("type")?.asText()
        } catch (e: Exception) {
            logger.error("Error while extracting message type from payload: ${e.message}", e)
            return null
        }
    }

    private fun handelTextMessage(session: WebSocketSession, userId: Long, payload: String) {
        try {
            when (val messageType = extractMessageType(payload)) {
                "SEND_MESSAGE" -> {
                    val jsonNode = objectMapper.readTree(payload)

                    val chatRoomId =
                        jsonNode.get("chatRoomId")?.asLong() ?: throw IllegalArgumentException("chatRoomId is required")
                    val messageTypeText = jsonNode.get("messageType")?.asText()
                        ?: throw IllegalArgumentException("messageType is required")
                    val content = jsonNode.get("content")?.asText()

                    val sendMessageRequest = SendMessageRequest(
                        chatRoomId = chatRoomId,
                        type = MessageType.valueOf(messageTypeText),
                        content = content
                    )

                    chatService.sendMessage(sendMessageRequest, userId)
                }

                else -> {
                    logger.warn("Unsupported message type: $messageType")
                    sendErrorMessage(session, "지원하지 않는 메시지 타입입니다", "UNKNOWN_MESSAGE_TYPE")
                }
            }
        } catch (e: Exception) {
            logger.error("Error while handling text message from user $userId: ${e.message}", e)
            sendErrorMessage(session, "메시지 처리 에러")
        }
    }

    private fun sendErrorMessage(session: WebSocketSession, errorMessage: String, errorCode: String? = null) {
        try {
            val errorMsg = ErrorMessage(
                message = errorMessage,
                code = errorCode,
                chatRoomId = null,
            )

            val json = objectMapper.writeValueAsString(errorMsg)
            session.sendMessage(TextMessage(json))
        } catch (e: Exception) {
            logger.error("Error while sending error message", e)
        }
    }

}