package com.chat.persistence.redis

import com.chat.domain.dto.ChatMessage
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class RedisMessageBroker(
    private val redisTemplate: RedisTemplate<String, String>,
    private val messageListenerContainer: RedisMessageListenerContainer,
    private val objectMapper: ObjectMapper
) {

    private val logger = LoggerFactory.getLogger(RedisMessageBroker::class.java)
    private val serverId = System.getenv("HOSTNAME") ?: "server-${System.currentTimeMillis()}"
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

    @PreDestroy
    fun cleanup() {
        subscribeRooms.forEach { roomId ->
            // TODO:: 구독취소
        }
        logger.info("redis-broker cleanup")
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

    fun setLocalMessageHandler(handler: (Long, ChatMessage) -> Unit) {
        this.localMessageHandler = handler
    }
}