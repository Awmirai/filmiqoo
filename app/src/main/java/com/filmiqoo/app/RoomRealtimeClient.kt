package com.filmiqoo.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class RoomRealtimeClient(
    private val session: SessionStore
) {
    private val client=OkHttpClient.Builder()
        .pingInterval(20,TimeUnit.SECONDS)
        .readTimeout(0,TimeUnit.MILLISECONDS)
        .build()

    fun connect(
        roomId: String,
        onConnected: () -> Unit,
        onEvent: (String) -> Unit,
        onDisconnected: (String?) -> Unit
    ): WebSocket? {
        val token=session.accessToken ?: return null
        val base=session.baseUrl
            .replaceFirst("https://","wss://")
            .replaceFirst("http://","ws://")
            .trimEnd('/')
        val request=Request.Builder()
            .url(base+"/v1/realtime/rooms/"+roomId)
            .header("Authorization","Bearer "+token)
            .build()

        return client.newWebSocket(request,object:WebSocketListener() {
            override fun onOpen(webSocket: WebSocket,response: Response) {
                onConnected()
            }

            override fun onMessage(webSocket: WebSocket,text: String) {
                onEvent(text)
            }

            override fun onClosed(webSocket: WebSocket,code: Int,reason: String) {
                onDisconnected(reason)
            }

            override fun onFailure(webSocket: WebSocket,t: Throwable,response: Response?) {
                onDisconnected(t.message)
            }
        })
    }
}
