package com.chat.websocket.interceptor

import org.slf4j.LoggerFactory
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor

@Component
class WebSocketHandShakeInterceptor : HandshakeInterceptor {

    private val logger = LoggerFactory.getLogger(WebSocketHandler::class.java)

    override fun beforeHandshake(
        request: ServerHttpRequest?,
        response: ServerHttpResponse?,
        wsHandler: WebSocketHandler?,
        attributes: MutableMap<String?, Any?>
    ): Boolean {
        try {
            // ex) ws://localhost:8080/chat?userId=123
            val uri = request?.uri
            val query = uri?.query ?: return false

            val params = parseQuery(query)
            val userId = params["userId"]?.toLongOrNull() ?: return false

            attributes["userId"] = userId
            return true
        } catch (e: Exception) {
            logger.error(e.message, e)
            return false
        }
    }

    override fun afterHandshake(
        request: ServerHttpRequest?,
        response: ServerHttpResponse?,
        wsHandler: WebSocketHandler?,
        exception: Exception?
    ) {
        if (exception != null) {
            logger.error("WWebSocketHandShakeInterceptor failed: ${exception.message}", exception)
        } else {
            logger.info("WWebSocketHandShakeInterceptor successful")
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        return query.split("&")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    parts[0] to parts[1]
                } else null
            }
            .toMap()
    }
}