package com.filmiqoo.app

import android.content.Context
import android.provider.Settings
import org.json.JSONObject

data class PushDevice(
    val id:String,
    val deviceId:String,
    val provider:String,
    val platform:String,
    val locale:String,
    val enabled:Boolean
)

class PushRegistrationRepository(
    context:Context,
    private val backend:BackendRepository
) {
    private val appContext=context.applicationContext

    fun deviceId():String =
        Settings.Secure.getString(appContext.contentResolver,Settings.Secure.ANDROID_ID)
            ?.takeIf(String::isNotBlank)
            ?: "android-"+android.os.Build.MODEL.replace(" ","-")

    suspend fun registerFcmToken(token:String,locale:String="fa"):String {
        val root=backend.postJson(
            "/v1/push/devices",
            JSONObject()
                .put("deviceId",deviceId())
                .put("provider","fcm")
                .put("platform","android")
                .put("pushToken",token.trim())
                .put("locale",locale),
            authorized=true
        )
        return root.optString("id")
    }

    suspend fun disableCurrentDevice():Long =
        backend.postJson(
            "/v1/push/devices/"+java.net.URLEncoder.encode(deviceId(),"UTF-8")+"/disable",
            JSONObject(),
            authorized=true
        ).optLong("disabled")

    suspend fun devices():List<PushDevice> {
        val root=backend.getJson("/v1/push/devices",authorized=true)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    PushDevice(
                        id=x.optString("id"),
                        deviceId=x.optString("deviceId"),
                        provider=x.optString("provider"),
                        platform=x.optString("platform"),
                        locale=x.optString("locale"),
                        enabled=x.optBoolean("enabled")
                    )
                )
            }
        }
    }
}
