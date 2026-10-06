package com.filmiqoo.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class WatchPartyRealtimeClient(
    private val session:SessionStore
) {
    private val client=OkHttpClient.Builder()
        .pingInterval(15,TimeUnit.SECONDS)
        .readTimeout(0,TimeUnit.MILLISECONDS)
        .build()

    /** Callbacks arrive on OkHttp's worker; consumers must enqueue them for UI ownership. */
    fun connect(
        partyId:String,
        onConnected:()->Unit,
        onEvent:(String)->Unit,
        onDisconnected:(String?)->Unit
    ):WebSocket? {
        val token=session.accessToken ?: return null
        val base=session.baseUrl
            .replaceFirst("https://","wss://")
            .replaceFirst("http://","ws://")
            .trimEnd('/')
        val request=Request.Builder()
            .url(base+"/v1/realtime/watch-parties/"+partyId)
            .header("Authorization","Bearer "+token)
            .build()

        return client.newWebSocket(request,object:WebSocketListener() {
            override fun onOpen(webSocket:WebSocket,response:Response) {
                onConnected()
            }

            override fun onMessage(webSocket:WebSocket,text:String) {
                onEvent(text)
            }

            override fun onClosing(webSocket:WebSocket,code:Int,reason:String) {
                webSocket.close(code,reason)
            }

            override fun onClosed(webSocket:WebSocket,code:Int,reason:String) {
                onDisconnected(reason)
            }

            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) {
                onDisconnected(t.message)
            }
        })
    }
}
