package com.chat.persistence.redis

import com.chat.domain.dto.ChatMessage
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap

@Service
class RedisMessageBroker(
    private val redisTemplate: RedisTemplate<String, String>,
    private val messageListenerContainer: RedisMessageListenerContainer,
    private val objectMapper: ObjectMapper
) : MessageListener {

    private val logger = LoggerFactory.getLogger(RedisMessageBroker::class.java)
    private val serverId = System.getenv("HOSTNAME") ?: "server-${System.currentTimeMillis()}"

    /* id, timestamp */
    private val processedMessages = ConcurrentHashMap<String, Long>()
    private val subscribeRooms = ConcurrentHashMap.newKeySet<Long>()
    private var localMessageHandler: ((Long, ChatMessage) -> Unit)? = null

    fun getServerId() = serverId

    @PostConstruct
    fun initialize() {
        logger.info("initialize RedisMessageBroker with serverId: $serverId")
        Thread {
            try {
                Thread.sleep(30000) // 30초
                cleanUpProcessedMessage()
            } catch (e: Exception) {
                logger.error(e.message, e)
            }
        }.apply {
            isDaemon = true
            name = "redis-broker-cleanup"
            start()
        }
    }

    private fun cleanUpProcessedMessage() {
        val now = System.currentTimeMillis()
        val expiredKeys = processedMessages.filter { (_, time) ->
            now - time > 60000 // 1분
        }.keys

        expiredKeys.forEach { processedMessages.remove(it) }

        if (expiredKeys.isNotEmpty()) {
            logger.info("Cleaned up ${expiredKeys.size} expired processed messages from Redis")
        }
    }

    @PreDestroy
    fun cleanup() {
        subscribeRooms.forEach { roomId ->
            unsubscribeFromRoom(roomId)
        }
        logger.info("redis-broker cleanup")
    }

    fun setLocalMessageHandler(handler: (Long, ChatMessage) -> Unit) {
        this.localMessageHandler = handler
    }

    fun subscribeToRoom(roomId: Long) {
        if (subscribeRooms.add(roomId)) {
            val topic = ChannelTopic("chat.room.$roomId")
            messageListenerContainer.addMessageListener(this, topic)
            logger.info("Subscribed to room $roomId")
        } else {
            logger.error("subscribeToRoom $roomId does not exist")
        }
    }

    fun unsubscribeFromRoom(roomId: Long) {
        if (subscribeRooms.remove(roomId)) {
            val topic = ChannelTopic("chat.room.$roomId")
            messageListenerContainer.removeMessageListener(this, topic)
            logger.info("Unsubscribed from room $roomId")
        } else {
            logger.error("unsubscribeFromRoom $roomId does not exist")
        }
    }

    fun broadcastToRoom(roomId: Long, message: ChatMessage, excludeServerId: String? = null) {
        try {
            val distrubutedMessage = DistributedMessage(
                id = "$serverId-${System.currentTimeMillis()}-${System.nanoTime()}",
                serverId = serverId,
                roomId = roomId,
                excludeServerId = excludeServerId,
                timestamp = LocalDateTime.now(),
                payload = message
            )

            val json = objectMapper.writeValueAsString(distrubutedMessage)
            redisTemplate.convertAndSend("chat.room.$roomId", json)

            logger.info("Broadcasted message to room $roomId with id ${distrubutedMessage.id}")
        } catch (e: Exception) {
            logger.error("Error broadcasting message: ${e.message}", e)
        }
    }

    override fun onMessage(message: Message, pattern: ByteArray?) {
        try {
            val json = String(message.body)
            val distrubutedMessage = objectMapper.readValue(json, DistributedMessage::class.java)

            if (distrubutedMessage.excludeServerId == serverId) {
                logger.error("Ignore message from excludeded Server: ${distrubutedMessage.id}")
                return
            }

            if (processedMessages.containsKey(distrubutedMessage.id)) {
                logger.error("Ignore already processed message: ${distrubutedMessage.id}")
                return
            }

            // 메세지 전송
            localMessageHandler?.invoke(distrubutedMessage.roomId, distrubutedMessage.payload)

            // 메세지 전송 시각 기록
            processedMessages[distrubutedMessage.id] = System.currentTimeMillis()

            // 일정량 이상 쌓이면 클린업
            val cleanUpSize = 10000
            if (processedMessages.size > cleanUpSize) {
                val oldestEntries = processedMessages.entries.sortedBy { it.value }
                    .take(processedMessages.size - cleanUpSize)

                oldestEntries.forEach { processedMessages.remove(it.key) }
            }

            logger.info("processedMessage for room ${distrubutedMessage.roomId} with id ${distrubutedMessage.id}")

        } catch (e: Exception) {
            logger.error("Error processing message: ${e.message}", e)
        }
    }

    data class DistributedMessage(
        val id: String,
        val serverId: String,
        val roomId: Long,
        val excludeServerId: String?,
        val timestamp: LocalDateTime,
        val payload: ChatMessage
    )
}