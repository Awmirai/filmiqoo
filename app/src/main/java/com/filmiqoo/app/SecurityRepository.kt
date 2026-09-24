package com.filmiqoo.app

import org.json.JSONObject

data class AccountSession(
    val id:String,
    val deviceName:String,
    val userAgent:String,
    val ipAddress:String,
    val createdAt:String,
    val lastUsedAt:String,
    val expiresAt:String,
    val current:Boolean
)

class SecurityRepository(
    private val backend:BackendRepository
) {
    suspend fun sessions():List<AccountSession> {
        val token=backend.session.refreshToken.orEmpty()
        val root=backend.postJson(
            "/v1/security/sessions",
            JSONObject().put("refreshToken",token),
            authorized=true
        )
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(
                    AccountSession(
                        id=x.optString("id"),
                        deviceName=x.optString("deviceName").ifBlank{"دستگاه ناشناس"},
                        userAgent=x.optString("userAgent"),
                        ipAddress=x.optString("ipAddress"),
                        createdAt=x.optString("createdAt"),
                        lastUsedAt=x.optString("lastUsedAt"),
                        expiresAt=x.optString("expiresAt"),
                        current=x.optBoolean("current")
                    )
                )
            }
        }
    }

    suspend fun revoke(id:String):Boolean =
        backend.postJson(
            "/v1/security/sessions/"+id+"/revoke",
            JSONObject(),
            authorized=true
        ).optBoolean("revoked")

    suspend fun revokeOthers():Long =
        backend.postJson(
            "/v1/security/sessions/revoke-others",
            JSONObject().put("refreshToken",backend.session.refreshToken.orEmpty()),
            authorized=true
        ).optLong("revoked")

    suspend fun accountExportJson():String =
        backend.getJson(
            "/v1/privacy/export",
            authorized=true
        ).toString(2)

    suspend fun deleteAccount(
        password:String,
        confirmation:String
    ):Boolean =
        backend.postJson(
            "/v1/privacy/delete-account",
            JSONObject()
                .put("password",password)
                .put("confirmation",confirmation),
            authorized=true
        ).optBoolean("deleted")
}
