package com.zouhu.speedlimit.data.map.baidu

import com.zouhu.speedlimit.domain.model.GpsSample
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class HorizonAuthException(val status: Int, message: String?) :
    IllegalStateException("Horizon auth failed: $status ${message.orEmpty()}")

/**
 * One WebSocket connection. Reconnects are owned by the provider so each attempt gets a fresh client.
 */
class BaiduHorizonClient(
    private val ak: String,
    private val entityId: String,
    private val listener: Listener,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        fun onAuthenticated()
        fun onEvent(event: HorizonEvent)
        fun onClosed(code: Int, reason: String)
        fun onFailure(error: Throwable)
    }

    private val nextId = AtomicLong(0)
    private var webSocket: WebSocket? = null
    @Volatile
    private var authenticated = false
    @Volatile
    private var closed = false

    fun open() {
        if (webSocket != null || closed) return
        val request = Request.Builder().url(URL).build()
        webSocket = client.newWebSocket(request, socketListener)
    }

    fun close() {
        closed = true
        webSocket?.close(1000, "client closed")
        webSocket = null
        authenticated = false
    }

    fun sendLoc(sample: GpsSample): Boolean {
        if (!authenticated || !sample.hasFix) return false
        val message = buildHorizonLocMessage(sample, clock(), nextId.incrementAndGet())
            ?: return false
        return webSocket?.send(message) == true
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            sendAuth(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = HorizonProtocol.parse(text) ?: return
            if (event is HorizonEvent.Auth) {
                if (event.status == 0) {
                    authenticated = true
                    listener.onAuthenticated()
                    sendRoute(webSocket)
                } else {
                    listener.onFailure(HorizonAuthException(event.status, event.message))
                    webSocket.close(1000, "auth failed")
                }
                return
            }
            listener.onEvent(event)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closed) listener.onClosed(code, reason)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!closed) listener.onFailure(t)
        }
    }

    private fun sendAuth(webSocket: WebSocket) {
        val message = buildJsonObject {
            put("id", nextId.incrementAndGet())
            put("qt", "auth")
            put("ver", "1.0")
            put("prt", 1)
            put("enc", 1)
            put("ts", clock())
            put("data", buildJsonObject {
                put("ak", ak)
                put("entity_id", entityId)
            })
        }
        webSocket.send(message.toString())
    }

    private fun sendRoute(webSocket: WebSocket) {
        val message = buildJsonObject {
            put("id", nextId.incrementAndGet())
            put("qt", "horizon")
            put("ver", "1.0")
            put("prt", 1)
            put("enc", 1)
            put("ts", clock())
            put("data", buildJsonObject {
                put("route", buildJsonObject { put("type", 1) })
            })
        }
        webSocket.send(message.toString())
    }

    companion object {
        const val URL = "wss://api.map.baidu.com/websocket"
    }
}


/**
 * Separated from the WebSocket so the timestamp contract is unit-testable without
 * opening a connection. Envelope `ts` is send time; `loc.ts` is the real fix time.
 */
internal fun buildHorizonLocMessage(sample: GpsSample, nowMs: Long, id: Long): String? {
    val locTs = HorizonLocStamp.resolve(nowMs, sample.timestampMs) ?: return null
    return buildJsonObject {
        put("id", id)
        put("qt", "horizon")
        put("ver", "1.0")
        put("prt", 1)
        put("enc", 1)
        put("ts", nowMs)
        put("data", buildJsonObject {
            put("loc", buildJsonObject {
                put("x", sample.longitude)
                put("y", sample.latitude)
                put("ts", locTs)
                put("coordtype", 1)
                put("speed", sample.speedMps)
                put("dir", sample.bearingDeg)
            })
        })
    }.toString()
}
