package com.filmiqoo.app

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object FilmiqooCrashStore {
    private val installed=AtomicBoolean(false)

    fun install(context:Context) {
        if(!installed.compareAndSet(false,true)) return
        val appContext=context.applicationContext
        val previous=Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread,throwable ->
            runCatching {
                val payload=JSONObject()
                    .put("eventType","app_crash")
                    .put("severity","fatal")
                    .put("message",throwable.message.orEmpty().take(2000))
                    .put("stackTrace",throwable.stackTraceToString().take(12000))
                    .put("appVersion",BuildConfig.VERSION_NAME)
                    .put("platform","android")
                    .put("sessionId",TelemetryIdentity.sessionId(appContext))
                    .put("deviceId",TelemetryIdentity.deviceId(appContext))
                    .put(
                        "metadata",
                        JSONObject()
                            .put("thread",thread.name.take(120))
                            .put("sdk",Build.VERSION.SDK_INT)
                            .put("manufacturer",Build.MANUFACTURER.take(80))
                            .put("model",Build.MODEL.take(120))
                    )

                pendingFile(appContext).writeText(payload.toString(),Charsets.UTF_8)
            }
            previous?.uncaughtException(thread,throwable)
        }
    }

    fun read(context:Context):String? =
        runCatching {
            pendingFile(context.applicationContext)
                .takeIf(File::isFile)
                ?.readText(Charsets.UTF_8)
                ?.takeIf(String::isNotBlank)
        }.getOrNull()

    fun clear(context:Context) {
        runCatching { pendingFile(context.applicationContext).delete() }
    }

    private fun pendingFile(context:Context)=
        File(context.filesDir,"filmiqoo-pending-crash.json")
}

object TelemetryIdentity {
    private const val PREFS="filmiqoo_telemetry"
    private const val SESSION_ID="session_id"

    fun deviceId(context:Context):String =
        LocalDeviceIdentity.id(context)

    fun sessionId(context:Context):String {
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val existing=prefs.getString(SESSION_ID,null)
        if(!existing.isNullOrBlank()) return existing
        val next=UUID.randomUUID().toString()
        prefs.edit().putString(SESSION_ID,next).apply()
        return next
    }
}

class TelemetryRepository(
    context:Context,
    private val backend:BackendRepository
) {
    private val appContext=context.applicationContext

    suspend fun flushPendingCrash() {
        val raw=FilmiqooCrashStore.read(appContext) ?: return
        val payload=runCatching { JSONObject(raw) }.getOrNull()
        if(payload==null) {
            FilmiqooCrashStore.clear(appContext)
            return
        }
        runCatching {
            backend.postJson(
                "/v1/telemetry/events",
                payload,
                authorized=false
            )
        }.onSuccess {
            FilmiqooCrashStore.clear(appContext)
        }
    }

    suspend fun event(
        type:String,
        severity:String="info",
        message:String="",
        metadata:JSONObject=JSONObject()
    ) {
        runCatching {
            backend.postJson(
                "/v1/telemetry/events",
                JSONObject()
                    .put("deviceId",TelemetryIdentity.deviceId(appContext))
                    .put("sessionId",TelemetryIdentity.sessionId(appContext))
                    .put("eventType",type.take(80))
                    .put("severity",severity.take(16))
                    .put("message",message.take(2000))
                    .put("stackTrace","")
                    .put("metadata",metadata)
                    .put("appVersion",BuildConfig.VERSION_NAME)
                    .put("platform","android"),
                authorized=false
            )
        }
    }
}
