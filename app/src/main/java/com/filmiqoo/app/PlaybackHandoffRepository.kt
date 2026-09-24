package com.filmiqoo.app

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.net.URLEncoder

data class PlaybackDevice(
    val deviceId:String,
    val deviceName:String,
    val platform:String,
    val lastSeenAt:String,
    val mediaVersionId:String?,
    val positionMs:Long,
    val current:Boolean,
    val online:Boolean
)

data class PendingPlaybackHandoff(
    val id:String,
    val mediaVersionId:String,
    val positionMs:Long,
    val sourceDeviceId:String,
    val sourceDeviceName:String,
    val title:String,
    val subtitle:String,
    val posterUrl:String?,
    val createdAt:String,
    val expiresAt:String
) {
    fun asTarget():PlaybackTarget = PlaybackTarget(
        mediaVersionId=mediaVersionId,
        title=title,
        subtitle=subtitle,
        posterUrl=posterUrl,
        startPositionMs=positionMs
    )
}

class PlaybackHandoffRepository(
    context:Context,
    private val backend:BackendRepository
) {
    private val appContext=context.applicationContext

    fun deviceId():String = LocalDeviceIdentity.id(appContext)

    fun deviceName():String {
        val manufacturer=Build.MANUFACTURER
            ?.trim()
            .orEmpty()
            .replaceFirstChar { if(it.isLowerCase()) it.titlecase() else it.toString() }
        val model=Build.MODEL?.trim().orEmpty()
        return listOf(manufacturer,model)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android device" }
    }

    suspend fun heartbeat(
        mediaVersionId:String?=null,
        positionMs:Long=0L
    ) {
        val body=JSONObject()
            .put("deviceId",deviceId())
            .put("deviceName",deviceName())
            .put("platform","android")
            .put("positionMs",positionMs.coerceAtLeast(0L))
        if(!mediaVersionId.isNullOrBlank()) {
            body.put("mediaVersionId",mediaVersionId)
        }
        backend.postJson(
            "/v1/playback/devices/heartbeat",
            body,
            authorized=true
        )
    }

    suspend fun devices():List<PlaybackDevice> {
        val root=backend.getJson(
            "/v1/playback/devices?currentDeviceId="+
                URLEncoder.encode(deviceId(),"UTF-8"),
            authorized=true
        )
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    PlaybackDevice(
                        deviceId=x.optString("deviceId"),
                        deviceName=x.optString("deviceName").ifBlank{"Android device"},
                        platform=x.optString("platform"),
                        lastSeenAt=x.optString("lastSeenAt"),
                        mediaVersionId=x.optString("mediaVersionId").takeIf(String::isNotBlank),
                        positionMs=x.optLong("positionMs"),
                        current=x.optBoolean("current"),
                        online=x.optBoolean("online")
                    )
                )
            }
        }
    }

    suspend fun send(
        targetDeviceId:String,
        mediaVersionId:String,
        positionMs:Long
    ):String =
        backend.postJson(
            "/v1/playback/handoffs",
            JSONObject()
                .put("sourceDeviceId",deviceId())
                .put("targetDeviceId",targetDeviceId)
                .put("mediaVersionId",mediaVersionId)
                .put("positionMs",positionMs.coerceAtLeast(0L)),
            authorized=true
        ).optString("id")

    suspend fun pending():PendingPlaybackHandoff? {
        val root=backend.getJson(
            "/v1/playback/handoffs/pending?deviceId="+
                URLEncoder.encode(deviceId(),"UTF-8"),
            authorized=true
        )
        if(!root.optBoolean("pending")) return null
        val h=root.optJSONObject("handoff") ?: return null
        return PendingPlaybackHandoff(
            id=h.optString("id"),
            mediaVersionId=h.optString("mediaVersionId"),
            positionMs=h.optLong("positionMs"),
            sourceDeviceId=h.optString("sourceDeviceId"),
            sourceDeviceName=h.optString("sourceDeviceName"),
            title=h.optString("title"),
            subtitle=h.optString("subtitle"),
            posterUrl=h.optString("posterUrl").takeIf(String::isNotBlank),
            createdAt=h.optString("createdAt"),
            expiresAt=h.optString("expiresAt")
        )
    }

    suspend fun accept(id:String) {
        backend.postJson(
            "/v1/playback/handoffs/"+id+"/accept",
            JSONObject().put("deviceId",deviceId()),
            authorized=true
        )
    }

    suspend fun cancel(id:String) {
        backend.postJson(
            "/v1/playback/handoffs/"+id+"/cancel",
            JSONObject(),
            authorized=true
        )
    }
}
