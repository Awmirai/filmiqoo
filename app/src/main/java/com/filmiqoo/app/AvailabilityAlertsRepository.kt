package com.filmiqoo.app

import org.json.JSONObject

data class AvailabilityFlags(
    val persianDub:Boolean,
    val persianSubtitle:Boolean,
    val uhd4k:Boolean,
    val hdr:Boolean
)

data class AvailabilityAlertStatus(
    val mediaTitleId:String,
    val title:String,
    val available:AvailabilityFlags,
    val subscribed:AvailabilityFlags
)

class AvailabilityAlertsRepository(
    private val backend:BackendRepository
) {
    suspend fun status(mediaId:String):AvailabilityAlertStatus {
        val root=backend.getJson(
            "/v1/catalog/"+mediaId+"/availability-alerts",
            authorized=true
        )
        val available=root.optJSONObject("available") ?: JSONObject()
        val subscribed=root.optJSONObject("subscribed") ?: JSONObject()

        return AvailabilityAlertStatus(
            mediaTitleId=root.optString("mediaTitleId"),
            title=root.optString("title"),
            available=parseFlags(available),
            subscribed=parseFlags(subscribed)
        )
    }

    suspend fun set(
        mediaId:String,
        alertType:String,
        enabled:Boolean
    ):Pair<Boolean,Boolean> {
        val root=backend.postJson(
            "/v1/catalog/"+mediaId+"/availability-alerts",
            JSONObject()
                .put("alertType",alertType)
                .put("enabled",enabled),
            authorized=true
        )
        return root.optBoolean("enabled") to root.optBoolean("available")
    }

    private fun parseFlags(o:JSONObject)=AvailabilityFlags(
        persianDub=o.optBoolean("persianDub"),
        persianSubtitle=o.optBoolean("persianSubtitle"),
        uhd4k=o.optBoolean("uhd4k"),
        hdr=o.optBoolean("hdr")
    )
}
