package com.filmiqoo.app

import android.content.Context
import java.util.UUID

object LocalDeviceIdentity {
    private const val PREFS="filmiqoo_device_identity"
    private const val KEY_ID="installation_id"

    fun id(context:Context):String {
        val appContext=context.applicationContext
        val prefs=appContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val existing=prefs.getString(KEY_ID,null)
        if(!existing.isNullOrBlank()) return existing

        val next="android-"+UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ID,next).apply()
        return next
    }
}
