package com.filmiqoo.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore

data class OfflineDownloadItem(
    val id: String,
    val mediaVersionId: String,
    val title: String,
    val subtitle: String,
    val posterUrl: String?,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val localPath: String?,
    val createdAt: Long,
    val error: String?,
    val speedBytesPerSecond: Long = 0L,
    val etaSeconds: Long? = null,
    val nextMediaVersionId: String? = null,
    val nextTitle: String? = null,
    val nextSubtitle: String? = null,
    val smartManaged: Boolean = false
) {
    val progress: Float
        get() = if(totalBytes>0) (downloadedBytes.toFloat()/totalBytes.toFloat()).coerceIn(0f,1f) else 0f
}

object OfflineDownloadManager {
    private const val PREFS="filmiqoo_offline_downloads_v2"
    private const val KEY_ITEMS="items"
    private const val KEY_WIFI_ONLY="wifi_only"
    private val lock=Any()

    fun list(context: Context): List<OfflineDownloadItem> = synchronized(lock) {
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        val raw=prefs.getString(KEY_ITEMS,"[]") ?: "[]"
        val arr=runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
        buildList {
            for(i in 0 until arr.length()) {
                val o=arr.optJSONObject(i) ?: continue
                add(
                    OfflineDownloadItem(
                        id=o.optString("id"),
                        mediaVersionId=o.optString("mediaVersionId"),
                        title=o.optString("title"),
                        subtitle=o.optString("subtitle"),
                        posterUrl=o.optString("posterUrl").takeIf(String::isNotBlank),
                        status=o.optString("status","queued"),
                        downloadedBytes=o.optLong("downloadedBytes"),
                        totalBytes=o.optLong("totalBytes"),
                        localPath=o.optString("localPath").takeIf(String::isNotBlank),
                        createdAt=o.optLong("createdAt"),
                        error=o.optString("error").takeIf(String::isNotBlank),
                        speedBytesPerSecond=o.optLong("speedBytesPerSecond"),
                        etaSeconds=if(o.has("etaSeconds") && !o.isNull("etaSeconds")) o.optLong("etaSeconds") else null,
                        nextMediaVersionId=o.optString("nextMediaVersionId").takeIf(String::isNotBlank),
                        nextTitle=o.optString("nextTitle").takeIf(String::isNotBlank),
                        nextSubtitle=o.optString("nextSubtitle").takeIf(String::isNotBlank),
                        smartManaged=o.optBoolean("smartManaged")
                    )
                )
            }
        }.sortedByDescending { it.createdAt }
    }

    fun get(context: Context,id: String): OfflineDownloadItem? =
        list(context).firstOrNull { it.id==id }

    fun wifiOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .getBoolean(KEY_WIFI_ONLY,false)

    fun setWifiOnly(context: Context,value: Boolean) {
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WIFI_ONLY,value).apply()
    }

    fun enqueue(
        context: Context,
        target: PlaybackTarget,
        smartManaged: Boolean = false
    ): String = synchronized(lock) {
        val existing=list(context).firstOrNull {
            it.mediaVersionId==target.mediaVersionId &&
                it.status in setOf("queued","downloading","paused","completed")
        }
        if(existing!=null) {
            if(smartManaged || !target.nextMediaVersionId.isNullOrBlank()) {
                update(context,existing.id) {
                    it.copy(
                        smartManaged=it.smartManaged || smartManaged,
                        nextMediaVersionId=target.nextMediaVersionId ?: it.nextMediaVersionId,
                        nextTitle=target.nextTitle ?: it.nextTitle,
                        nextSubtitle=target.nextSubtitle ?: it.nextSubtitle
                    )
                }
            }
            if(existing.status=="paused") resume(context,existing.id)
            return@synchronized existing.id
        }

        val id=UUID.randomUUID().toString()
        val item=OfflineDownloadItem(
            id=id,
            mediaVersionId=target.mediaVersionId,
            title=target.title,
            subtitle=target.subtitle,
            posterUrl=target.posterUrl,
            status="queued",
            downloadedBytes=0L,
            totalBytes=0L,
            localPath=downloadFile(context,id).absolutePath,
            createdAt=System.currentTimeMillis(),
            error=null,
            speedBytesPerSecond=0L,
            etaSeconds=null,
            nextMediaVersionId=target.nextMediaVersionId,
            nextTitle=target.nextTitle,
            nextSubtitle=target.nextSubtitle,
            smartManaged=smartManaged
        )
        saveItem(context,item)
        schedule(context,id)
        id
    }

    fun pause(context: Context,id: String) {
        update(context,id) { it.copy(status="paused",error=null) }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
    }

    fun resume(context: Context,id: String) {
        val item=get(context,id) ?: return
        update(context,id) { it.copy(status="queued",error=null) }
        schedule(context,item.id)
    }

    fun retry(context: Context,id: String) {
        update(context,id) { it.copy(status="queued",error=null) }
        schedule(context,id)
    }

    fun pauseAll(context: Context) {
        list(context)
            .filter { it.status=="queued" || it.status=="downloading" }
            .forEach { pause(context,it.id) }
    }

    fun resumeAll(context: Context) {
        list(context)
            .filter { it.status=="paused" }
            .forEach { resume(context,it.id) }
    }

    fun retryFailed(context: Context) {
        list(context)
            .filter { it.status=="failed" }
            .forEach { retry(context,it.id) }
    }

    fun clearCompleted(context: Context) {
        list(context)
            .filter { it.status=="completed" }
            .forEach { delete(context,it.id) }
    }

    fun delete(context: Context,id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        get(context,id)?.localPath?.let { runCatching { File(it).delete() } }
        synchronized(lock) {
            val all=list(context).filterNot { it.id==id }
            saveAll(context,all)
        }
    }

    internal fun updateProgress(
        context: Context,
        id: String,
        status: String,
        downloaded: Long,
        total: Long,
        error: String?=null,
        speedBytesPerSecond: Long=0L,
        etaSeconds: Long?=null
    ) {
        update(context,id) {
            it.copy(
                status=status,
                downloadedBytes=downloaded,
                totalBytes=total,
                localPath=it.localPath ?: downloadFile(context,id).absolutePath,
                error=error,
                speedBytesPerSecond=speedBytesPerSecond,
                etaSeconds=etaSeconds
            )
        }
    }

    private fun schedule(context: Context,id: String) {
        val constraints=Constraints.Builder()
            .setRequiredNetworkType(
                if(wifiOnly(context)) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()

        val request=OneTimeWorkRequestBuilder<FilmiqooDownloadWorker>()
            .setInputData(workDataOf("download_id" to id))
            .setConstraints(constraints)
            .addTag("filmiqoo_download")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(id),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    private fun update(
        context: Context,
        id: String,
        block: (OfflineDownloadItem) -> OfflineDownloadItem
    ) = synchronized(lock) {
        val all=list(context).toMutableList()
        val index=all.indexOfFirst { it.id==id }
        if(index<0) return@synchronized
        all[index]=block(all[index])
        saveAll(context,all)
    }

    private fun saveItem(context: Context,item: OfflineDownloadItem) = synchronized(lock) {
        val all=list(context).filterNot { it.id==item.id }.toMutableList()
        all += item
        saveAll(context,all)
    }

    private fun saveAll(context: Context,items: List<OfflineDownloadItem>) {
        val arr=JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject()
                    .put("id",item.id)
                    .put("mediaVersionId",item.mediaVersionId)
                    .put("title",item.title)
                    .put("subtitle",item.subtitle)
                    .put("posterUrl",item.posterUrl ?: "")
                    .put("status",item.status)
                    .put("downloadedBytes",item.downloadedBytes)
                    .put("totalBytes",item.totalBytes)
                    .put("localPath",item.localPath ?: "")
                    .put("createdAt",item.createdAt)
                    .put("error",item.error ?: "")
                    .put("speedBytesPerSecond",item.speedBytesPerSecond)
                    .put("etaSeconds",item.etaSeconds ?: JSONObject.NULL)
                    .put("nextMediaVersionId",item.nextMediaVersionId ?: "")
                    .put("nextTitle",item.nextTitle ?: "")
                    .put("nextSubtitle",item.nextSubtitle ?: "")
                    .put("smartManaged",item.smartManaged)
            )
        }
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS,arr.toString()).apply()
    }

    internal fun updatePlaybackContext(
        context: Context,
        id: String,
        target: PlaybackTarget
    ) {
        update(context,id) {
            it.copy(
                nextMediaVersionId=target.nextMediaVersionId,
                nextTitle=target.nextTitle,
                nextSubtitle=target.nextSubtitle
            )
        }
    }

    fun consumeCompleted(context: Context,mediaVersionId: String): Boolean {
        val settings=AppPreferences(context.applicationContext).read()
        if(!settings.smartDownloads) return false

        val item=list(context).firstOrNull {
            it.mediaVersionId==mediaVersionId &&
                it.status=="completed" &&
                it.smartManaged
        } ?: return false

        val nextId=item.nextMediaVersionId
        val nextTitle=item.nextTitle
        val nextSubtitle=item.nextSubtitle

        delete(context,item.id)

        if(!nextId.isNullOrBlank()) {
            enqueue(
                context,
                PlaybackTarget(
                    mediaVersionId=nextId,
                    title=nextTitle ?: "قسمت بعدی",
                    subtitle=nextSubtitle.orEmpty()
                ),
                smartManaged=true
            )
        }

        trimSmartToLimit(
            context,
            settings.downloadStorageLimitMb,
            protectedId=null
        )
        return true
    }

    internal fun trimSmartToLimit(
        context: Context,
        limitMb: Long,
        protectedId: String? = null
    ) {
        if(limitMb<=0L) return
        val limitBytes=limitMb*1024L*1024L
        synchronized(lock) {
            var all=list(context).toMutableList()
            fun usedBytes()=all.filter { it.status=="completed" }
                .sumOf { if(it.totalBytes>0)it.totalBytes else it.downloadedBytes }

            if(usedBytes()<=limitBytes) return@synchronized

            val removable=all
                .filter {
                    it.status=="completed" &&
                        it.smartManaged &&
                        it.id!=protectedId
                }
                .sortedBy { it.createdAt }

            for(item in removable) {
                if(usedBytes()<=limitBytes) break
                item.localPath?.let { path -> runCatching { File(path).delete() } }
                all.removeAll { it.id==item.id }
            }
            saveAll(context,all)
        }
    }

    internal fun downloadFile(context: Context,id: String): File {
        val root=File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "Filmiqoo"
        )
        if(!root.exists()) root.mkdirs()
        return File(root,id+".media")
    }

    private fun workName(id: String)="filmiqoo_download_"+id
}

class FilmiqooDownloadWorker(
    appContext: Context,
    params: WorkerParameters
): CoroutineWorker(appContext,params) {

    companion object {
        private val downloadGate=Semaphore(2,true)
    }

    private val client=OkHttpClient.Builder()
        .connectTimeout(15,TimeUnit.SECONDS)
        .readTimeout(60,TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id=inputData.getString("download_id") ?: return@withContext Result.failure()
        val item=OfflineDownloadManager.get(applicationContext,id)
            ?: return@withContext Result.failure()

        downloadGate.acquire()
        try {
        try {
            setForeground(createForegroundInfo(item,0,false))
            OfflineDownloadManager.updateProgress(
                applicationContext,id,"downloading",
                item.downloadedBytes,item.totalBytes
            )

            val backend=BackendRepository(applicationContext)
            val url=backend.playbackUrl(item.mediaVersionId,download=true)
            val file=OfflineDownloadManager.downloadFile(applicationContext,id)
            var existing=if(file.exists()) file.length() else 0L

            val builder=Request.Builder().url(url)
            if(existing>0L) builder.header("Range","bytes="+existing+"-")

            client.newCall(builder.build()).execute().use { response ->
                if(!response.isSuccessful) {
                    throw IllegalStateException("HTTP "+response.code)
                }

                val append=response.code==206 && existing>0L
                if(!append) {
                    if(file.exists()) file.delete()
                    existing=0L
                }

                val body=response.body ?: throw IllegalStateException("Empty response")
                val remaining=body.contentLength().coerceAtLeast(0L)
                val total=if(remaining>0L) existing+remaining else 0L

                FileOutputStream(file,append).use { output ->
                    body.byteStream().use { input ->
                        val buffer=ByteArray(256*1024)
                        var downloaded=existing
                        var lastUpdate=0L
                        var sampleAt=System.currentTimeMillis()
                        var sampleBytes=downloaded

                        while(true) {
                            if(isStopped) {
                                val current=OfflineDownloadManager.get(applicationContext,id)
                                val status=if(current?.status=="paused") "paused" else "queued"
                                OfflineDownloadManager.updateProgress(
                                    applicationContext,id,status,downloaded,total
                                )
                                return@withContext Result.success()
                            }

                            val count=input.read(buffer)
                            if(count<0) break
                            output.write(buffer,0,count)
                            downloaded+=count

                            val now=System.currentTimeMillis()
                            if(now-lastUpdate>600L) {
                                val progress=if(total>0L) {
                                    ((downloaded*100L)/total).toInt().coerceIn(0,100)
                                } else 0
                                val elapsed=(now-sampleAt).coerceAtLeast(1L)
                                val speed=(((downloaded-sampleBytes).coerceAtLeast(0L)*1000L)/elapsed)
                                    .coerceAtLeast(0L)
                                val eta=if(total>downloaded && speed>0L) {
                                    ((total-downloaded)/speed).coerceAtLeast(0L)
                                } else null
                                setProgress(workDataOf(
                                    "downloaded" to downloaded,
                                    "total" to total,
                                    "progress" to progress,
                                    "speed" to speed,
                                    "etaSeconds" to (eta ?: -1L)
                                ))
                                setForeground(createForegroundInfo(item,progress,total<=0L))
                                OfflineDownloadManager.updateProgress(
                                    applicationContext,
                                    id,
                                    "downloading",
                                    downloaded,
                                    total,
                                    speedBytesPerSecond=speed,
                                    etaSeconds=eta
                                )
                                sampleAt=now
                                sampleBytes=downloaded
                                lastUpdate=now
                            }
                        }

                        output.flush()

                        val contextTarget=runCatching {
                            backend.playbackContext(item.mediaVersionId)
                        }.getOrNull()
                        if(contextTarget!=null) {
                            OfflineDownloadManager.updatePlaybackContext(
                                applicationContext,
                                id,
                                contextTarget
                            )
                        }

                        OfflineDownloadManager.updateProgress(
                            applicationContext,id,"completed",downloaded,
                            if(total>0L)total else downloaded
                        )

                        if(item.smartManaged) {
                            val settings=AppPreferences(applicationContext).read()
                            OfflineDownloadManager.trimSmartToLimit(
                                applicationContext,
                                settings.downloadStorageLimitMb,
                                protectedId=id
                            )
                        }

                        setForeground(createForegroundInfo(item,100,false))
                    }
                }
            }
            Result.success()
        } catch(t: CancellationException) {
            return@withContext Result.success()
        } catch(t: Throwable) {
            val current=OfflineDownloadManager.get(applicationContext,id)
            if(current?.status!="paused") {
                OfflineDownloadManager.updateProgress(
                    applicationContext,
                    id,
                    "failed",
                    current?.downloadedBytes ?: 0L,
                    current?.totalBytes ?: 0L,
                    t.message ?: "Download failed"
                )
            }
            Result.retry()
        }
        } finally {
            downloadGate.release()
        }
    }

    private fun createForegroundInfo(
        item: OfflineDownloadItem,
        progress: Int,
        indeterminate: Boolean
    ): ForegroundInfo {
        val channelId="filmiqoo_downloads"
        val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Filmiqoo Downloads",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val notification=NotificationCompat.Builder(applicationContext,channelId)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(item.title)
            .setContentText(if(progress>=100)"دانلود کامل شد" else "در حال دانلود برای تماشای آفلاین")
            .setOnlyAlertOnce(true)
            .setOngoing(progress<100)
            .setProgress(100,progress,indeterminate)
            .build()

        val notificationId=item.id.hashCode().let { if(it==Int.MIN_VALUE) 1 else kotlin.math.abs(it) }
        return if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId,notification)
        }
    }
}
