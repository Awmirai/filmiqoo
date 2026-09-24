package com.filmiqoo.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object FilmiqooPush {
    private const val PREFS="filmiqoo_push"
    private const val PENDING_TOKEN="pending_fcm_token"
    const val CHANNEL_ID="filmiqoo_default"

    fun isConfigured():Boolean =
        BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_APP_ID.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
            BuildConfig.FIREBASE_SENDER_ID.isNotBlank()

    fun initialize(context:Context):Boolean {
        val appContext=context.applicationContext
        if(FirebaseApp.getApps(appContext).isNotEmpty()) return true
        if(!isConfigured()) return false

        val options=FirebaseOptions.Builder()
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build()

        FirebaseApp.initializeApp(appContext,options)
        ensureChannel(appContext)
        return FirebaseApp.getApps(appContext).isNotEmpty()
    }

    suspend fun registerIfPossible(
        context:Context,
        backend:BackendRepository
    ) {
        val appContext=context.applicationContext
        if(!backend.session.isLoggedIn || !initialize(appContext)) return

        val prefs=appContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val pending=prefs.getString(PENDING_TOKEN,null)
        val token=if(!pending.isNullOrBlank()) pending else currentToken()
        if(token.isBlank()) return

        PushRegistrationRepository(appContext,backend)
            .registerFcmToken(token)
        prefs.edit().remove(PENDING_TOKEN).apply()
    }

    fun rememberToken(context:Context,token:String) {
        if(token.isBlank()) return
        context.applicationContext
            .getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .edit()
            .putString(PENDING_TOKEN,token)
            .apply()
    }

    private suspend fun currentToken():String =
        suspendCoroutine { continuation ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    continuation.resume(token.orEmpty())
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(error)
                }
        }

    fun ensureChannel(context:Context) {
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.O) return
        val manager=context.getSystemService(NotificationManager::class.java)
        val channel=NotificationChannel(
            CHANNEL_ID,
            "Filmiqoo",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description="پیام‌ها، انتشارها و اعلان‌های Filmiqoo"
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun deepLinkFor(data:Map<String,String>):Uri? {
        val entityType=data["entityType"].orEmpty()
        val entityId=data["entityId"].orEmpty()
        if(entityId.isBlank()) return null

        val raw=when(entityType) {
            "room" -> "filmiqoo://room/$entityId"
            "release","series","availability","media" -> "filmiqoo://title/$entityId"
            "watch_party","party" -> "filmiqoo://party/$entityId"
            "reel" -> "filmiqoo://reel/$entityId"
            "channel" -> "filmiqoo://channel/$entityId"
            "collection" -> "filmiqoo://collection/$entityId"
            else -> null
        }
        return raw?.let(Uri::parse)
    }
}

class FilmiqooMessagingService:FirebaseMessagingService() {
    private val serviceScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)

    override fun onNewToken(token:String) {
        super.onNewToken(token)
        FilmiqooPush.rememberToken(applicationContext,token)
        val backend=BackendRepository(applicationContext)
        if(!backend.session.isLoggedIn) return

        serviceScope.launch {
            runCatching {
                FilmiqooPush.initialize(applicationContext)
                PushRegistrationRepository(applicationContext,backend)
                    .registerFcmToken(token)
            }
        }
    }

    override fun onMessageReceived(message:RemoteMessage) {
        super.onMessageReceived(message)
        FilmiqooPush.ensureChannel(applicationContext)

        val title=message.notification?.title
            ?: message.data["title"]
            ?: "Filmiqoo"
        val body=message.notification?.body
            ?: message.data["body"]
            ?: ""
        val deepLink=FilmiqooPush.deepLinkFor(message.data)

        val intent=Intent(this,MainActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if(deepLink!=null) {
                action=Intent.ACTION_VIEW
                data=deepLink
            }
        }
        val pendingIntent=PendingIntent.getActivity(
            this,
            message.messageId?.hashCode() ?: System.currentTimeMillis().toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification=NotificationCompat.Builder(this,FilmiqooPush.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_filmiqoo)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        if(
            Build.VERSION.SDK_INT<Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )==PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(this).notify(
                message.messageId?.hashCode() ?: System.currentTimeMillis().toInt(),
                notification
            )
        }
    }
}
