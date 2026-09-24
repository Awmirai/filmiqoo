package com.filmiqoo.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okio.BufferedSink

data class AuthUser(
    val id: String,
    val email: String,
    val username: String,
    val displayName: String
)

data class AuthResult(
    val user: AuthUser,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)

data class UploadTicket(
    val uploadId: String,
    val uploadUrl: String,
    val objectKey: String,
    val mediaUrl: String,
    val mimeType: String,
    val fileName: String,
    val sizeBytes: Long
)

data class PlaybackVariant(
    val mediaVersionId: String,
    val label: String,
    val codec: String = "",
    val hdr: String = ""
)

data class PlaybackQueueItem(
    val mediaVersionId: String,
    val title: String,
    val subtitle: String = "",
    val posterUrl: String? = null
)

data class PlaybackTarget(
    val mediaVersionId: String,
    val title: String,
    val subtitle: String = "",
    val posterUrl: String? = null,
    val startPositionMs: Long = 0L,
    val variants: List<PlaybackVariant> = emptyList(),
    val introEndMs: Long? = null,
    val recapEndMs: Long? = null,
    val creditsStartMs: Long? = null,
    val resumePositionMs: Long = 0L,
    val resumeDurationMs: Long = 0L,
    val resumeCompleted: Boolean = false,
    val nextMediaVersionId: String? = null,
    val nextTitle: String? = null,
    val nextSubtitle: String? = null,
    val previousMediaVersionId: String? = null,
    val previousTitle: String? = null,
    val previousSubtitle: String? = null,
    val upNext: List<PlaybackQueueItem> = emptyList(),
    val localUri: String? = null
)

data class AccountProfile(
    val id: String,
    val email: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String,
    val coverUrl: String,
    val verified: Boolean,
    val privateAccount: Boolean,
    val followers: Long,
    val following: Long
)

data class LibraryStats(
    val distinctTitles: Long,
    val completedVersions: Long,
    val watchTimeMinutes: Long,
    val favorites: Long
)

data class ContinueWatchingItem(
    val target: PlaybackTarget,
    val media: MediaItem,
    val progress: Float,
    val positionMs: Long,
    val durationMs: Long,
    val episodeLabel: String
)

data class PlatformDetail(
    val id: String,
    val tmdbId: Int?,
    val title: String,
    val overview: String,
    val versions: List<PlatformVersion>,
    val seasons: List<PlatformSeason>
)

data class PlatformVersion(
    val id: String,
    val quality: String,
    val codec: String,
    val hdr: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val streamReady: Boolean,
    val preferred: Boolean
)

data class PlatformEpisode(
    val id: String,
    val number: Int,
    val name: String,
    val overview: String,
    val stillUrl: String,
    val runtimeMinutes: Int,
    val mediaVersionId: String?,
    val quality: String?,
    val streamReady: Boolean,
    val introStartMs: Long? = null,
    val introEndMs: Long? = null,
    val recapStartMs: Long? = null,
    val recapEndMs: Long? = null,
    val creditsStartMs: Long? = null
)

data class PlatformSeason(
    val id: String,
    val number: Int,
    val name: String,
    val posterUrl: String,
    val episodes: List<PlatformEpisode>
)

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("filmiqoo_session_v1", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", BuildConfig.FILMIQOO_API_BASE_URL).orEmpty()
            .ifBlank { BuildConfig.FILMIQOO_API_BASE_URL }
            .trimEnd('/')
        set(value) { prefs.edit().putString("base_url", value.trim().trimEnd('/')).apply() }

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) { prefs.edit().putString("access_token", value).apply() }

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) { prefs.edit().putString("refresh_token", value).apply() }

    var displayName: String?
        get() = prefs.getString("display_name", null)
        set(value) { prefs.edit().putString("display_name", value).apply() }

    val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()

    fun save(result: AuthResult) {
        accessToken = result.accessToken
        refreshToken = result.refreshToken
        displayName = result.user.displayName
    }

    fun clear() {
        prefs.edit()
            .remove("access_token")
            .remove("refresh_token")
            .remove("display_name")
            .apply()
    }
}

class BackendRepository(context: Context) {
    private val appContext = context.applicationContext
    val session = SessionStore(appContext)
    val viewerProfiles = ViewerProfileStore(appContext)
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun health(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(session.baseUrl + "/healthz").get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun register(
        email: String,
        username: String,
        displayName: String,
        password: String,
        deviceName: String
    ): AuthResult = postAuth(
        "/v1/auth/register",
        JSONObject()
            .put("email", email.trim())
            .put("username", username.trim())
            .put("displayName", displayName.trim())
            .put("password", password)
            .put("deviceName", deviceName)
    )

    suspend fun login(login: String, password: String, deviceName: String): AuthResult =
        postAuth(
            "/v1/auth/login",
            JSONObject()
                .put("login", login.trim())
                .put("password", password)
                .put("deviceName", deviceName)
        )

    private suspend fun postAuth(path: String, body: JSONObject): AuthResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(session.baseUrl + path)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException(apiError(raw, res.code))
            val obj = JSONObject(raw)
            val user = obj.getJSONObject("user")
            AuthResult(
                user = AuthUser(
                    id = user.optString("id"),
                    email = user.optString("email"),
                    username = user.optString("username"),
                    displayName = user.optString("displayName")
                ),
                accessToken = obj.getString("accessToken"),
                refreshToken = obj.getString("refreshToken"),
                expiresIn = obj.optLong("expiresIn", 900)
            ).also(session::save)
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val refresh = session.refreshToken
        if (!refresh.isNullOrBlank()) {
            runCatching {
                val body = JSONObject().put("refreshToken", refresh)
                val req = Request.Builder()
                    .url(session.baseUrl + "/v1/auth/logout")
                    .post(body.toString().toRequestBody(jsonType))
                    .build()
                client.newCall(req).execute().close()
            }
        }
        session.clear()
    }

    suspend fun catalogHome(): List<MediaItem> = withContext(Dispatchers.IO) {
        val obj = getJson("/v1/catalog/home", authorized = false)
        val arr = obj.optJSONArray("items") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val x = arr.optJSONObject(i) ?: continue
                val tmdbId = if (x.isNull("tmdbId")) 0 else x.optInt("tmdbId")
                add(
                    MediaItem(
                        id = tmdbId,
                        type = if (x.optString("kind") == "movie") MediaType.MOVIE else MediaType.TV,
                        title = x.optString("title").ifBlank { x.optString("originalTitle") },
                        originalTitle = x.optString("originalTitle"),
                        overview = x.optString("overview"),
                        posterPath = x.optString("posterUrl").takeIf(String::isNotBlank),
                        backdropPath = x.optString("backdropUrl").takeIf(String::isNotBlank),
                        vote = x.optDouble("rating", 0.0),
                        date = x.optInt("year", 0).takeIf { it > 0 }?.toString().orEmpty(),
                        popularity = 0.0,
                        backendId = x.optString("id"),
                        mediaVersionId = x.optString("mediaVersionId").takeIf(String::isNotBlank),
                        streamReady = x.optBoolean("streamReady", false),
                        quality = x.optString("quality")
                    )
                )
            }
        }
    }

    suspend fun detail(id: String): PlatformDetail = withContext(Dispatchers.IO) {
        val o = getJson("/v1/catalog/" + id, authorized = false)
        val versions = buildList {
            val a = o.optJSONArray("versions")
            if (a != null) for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                add(
                    PlatformVersion(
                        id = x.optString("id"),
                        quality = x.optString("quality"),
                        codec = x.optString("codec"),
                        hdr = x.optString("hdr"),
                        fileSizeBytes = x.optLong("fileSizeBytes"),
                        durationMs = x.optLong("durationMs"),
                        streamReady = x.optBoolean("streamReady"),
                        preferred = x.optBoolean("preferred")
                    )
                )
            }
        }

        val seasons = buildList {
            val a = o.optJSONArray("seasons")
            if (a != null) for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                val episodes = buildList {
                    val e = s.optJSONArray("episodes")
                    if (e != null) for (j in 0 until e.length()) {
                        val x = e.optJSONObject(j) ?: continue
                        add(
                            PlatformEpisode(
                                id = x.optString("id"),
                                number = x.optInt("number"),
                                name = x.optString("name"),
                                overview = x.optString("overview"),
                                stillUrl = x.optString("stillUrl"),
                                runtimeMinutes = x.optInt("runtimeMinutes"),
                                mediaVersionId = x.optString("mediaVersionId").takeIf(String::isNotBlank),
                                quality = x.optString("quality").takeIf(String::isNotBlank),
                                streamReady = x.optBoolean("streamReady"),
                                introStartMs = if(x.isNull("introStartMs")) null else x.optLong("introStartMs"),
                                introEndMs = if(x.isNull("introEndMs")) null else x.optLong("introEndMs"),
                                recapStartMs = if(x.isNull("recapStartMs")) null else x.optLong("recapStartMs"),
                                recapEndMs = if(x.isNull("recapEndMs")) null else x.optLong("recapEndMs"),
                                creditsStartMs = if(x.isNull("creditsStartMs")) null else x.optLong("creditsStartMs")
                            )
                        )
                    }
                }
                add(
                    PlatformSeason(
                        id = s.optString("id"),
                        number = s.optInt("number"),
                        name = s.optString("name"),
                        posterUrl = s.optString("posterUrl"),
                        episodes = episodes
                    )
                )
            }
        }

        PlatformDetail(
            id = o.optString("id"),
            tmdbId = if (o.isNull("tmdbId")) null else o.optInt("tmdbId"),
            title = o.optString("title"),
            overview = o.optString("overview"),
            versions = versions,
            seasons = seasons
        )
    }

    suspend fun uploadMedia(
        context: Context,
        uri: Uri,
        kind: String
    ): UploadTicket = withContext(Dispatchers.IO) {
        val resolver=context.contentResolver
        val mime=resolver.getType(uri).orEmpty().ifBlank { "application/octet-stream" }
        var name="upload.bin"
        var size=-1L
        resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use { cursor ->
            if(cursor.moveToFirst()) {
                val nameIndex=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex=cursor.getColumnIndex(OpenableColumns.SIZE)
                if(nameIndex>=0) name=cursor.getString(nameIndex) ?: name
                if(sizeIndex>=0 && !cursor.isNull(sizeIndex)) size=cursor.getLong(sizeIndex)
            }
        }
        if(size<=0) {
            resolver.openAssetFileDescriptor(uri,"r")?.use { size=it.length }
        }
        if(size<=0) throw IllegalStateException("اندازه فایل قابل تشخیص نیست")

        val ticketJson=postJson(
            "/v1/uploads/presign",
            JSONObject()
                .put("kind",kind)
                .put("mimeType",mime)
                .put("fileName",name)
                .put("sizeBytes",size),
            authorized=true
        )

        val ticket=UploadTicket(
            uploadId=ticketJson.getString("uploadId"),
            uploadUrl=ticketJson.getString("uploadUrl"),
            objectKey=ticketJson.getString("objectKey"),
            mediaUrl=ticketJson.getString("mediaUrl"),
            mimeType=mime,
            fileName=name,
            sizeBytes=size
        )

        val body=object: RequestBody() {
            override fun contentType()=mime.toMediaTypeOrNull()
            override fun contentLength()=size
            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(uri)?.use { input ->
                    val buffer=ByteArray(DEFAULT_BUFFER_SIZE)
                    while(true) {
                        val read=input.read(buffer)
                        if(read<0) break
                        sink.write(buffer,0,read)
                    }
                } ?: throw IllegalStateException("فایل قابل خواندن نیست")
            }
        }

        val put=Request.Builder().url(ticket.uploadUrl).put(body).build()
        client.newCall(put).execute().use { res ->
            if(!res.isSuccessful) throw IllegalStateException("آپلود فایل ناموفق بود ("+res.code+")")
        }

        postJson("/v1/uploads/"+ticket.uploadId+"/complete",JSONObject(),authorized=true)
        ticket
    }

    suspend fun me(): AccountProfile = withContext(Dispatchers.IO) {
        val o=getJson("/v1/me",authorized=true)
        AccountProfile(
            id=o.optString("id"),
            email=o.optString("email"),
            username=o.optString("username"),
            displayName=o.optString("displayName"),
            bio=o.optString("bio"),
            avatarUrl=o.optString("avatarUrl"),
            coverUrl=o.optString("coverUrl"),
            verified=o.optBoolean("verified"),
            privateAccount=o.optBoolean("private"),
            followers=o.optLong("followers"),
            following=o.optLong("following")
        )
    }

    suspend fun updateProfile(
        username: String,
        displayName: String,
        bio: String,
        avatarUrl: String,
        coverUrl: String,
        privateAccount: Boolean
    ): AccountProfile = withContext(Dispatchers.IO) {
        val o=postJson(
            "/v1/me",
            JSONObject()
                .put("username",username.trim())
                .put("displayName",displayName.trim())
                .put("bio",bio.trim())
                .put("avatarUrl",avatarUrl.trim())
                .put("coverUrl",coverUrl.trim())
                .put("privateAccount",privateAccount),
            authorized=true
        )
        AccountProfile(
            id=o.optString("id"),
            email=o.optString("email"),
            username=o.optString("username"),
            displayName=o.optString("displayName"),
            bio=o.optString("bio"),
            avatarUrl=o.optString("avatarUrl"),
            coverUrl=o.optString("coverUrl"),
            verified=o.optBoolean("verified"),
            privateAccount=o.optBoolean("private"),
            followers=o.optLong("followers"),
            following=o.optLong("following")
        ).also { session.displayName=it.displayName }
    }

    suspend fun libraryStats(): LibraryStats = withContext(Dispatchers.IO) {
        val o=getJson("/v1/library/stats",authorized=true)
        LibraryStats(
            distinctTitles=o.optLong("distinctTitles"),
            completedVersions=o.optLong("completedVersions"),
            watchTimeMinutes=o.optLong("watchTimeMinutes"),
            favorites=o.optLong("favorites")
        )
    }

    suspend fun continueWatching(): List<ContinueWatchingItem> = withContext(Dispatchers.IO) {
        val root=getJson("/v1/watch/continue",authorized=true)
        val arr=root.optJSONArray("items") ?: return@withContext emptyList()
        buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                val m=x.optJSONObject("media") ?: continue
                val e=x.optJSONObject("episode")
                val media=parsePlatformMedia(m)
                val versionId=x.optString("mediaVersionId")
                if(versionId.isBlank()) continue
                val season=if(e==null || e.isNull("seasonNumber")) null else e.optInt("seasonNumber")
                val episode=if(e==null || e.isNull("episodeNumber")) null else e.optInt("episodeNumber")
                val episodeName=e?.optString("name").orEmpty()
                val label=if(season!=null && episode!=null) {
                    "S"+season.toString().padStart(2,'0')+"E"+episode.toString().padStart(2,'0')+
                        if(episodeName.isBlank())"" else " • "+episodeName
                } else x.optString("quality")
                add(
                    ContinueWatchingItem(
                        target=PlaybackTarget(
                            mediaVersionId=versionId,
                            title=media.title,
                            subtitle=label,
                            posterUrl=media.posterPath,
                            startPositionMs=x.optLong("positionMs")
                        ),
                        media=media.copy(
                            mediaVersionId=versionId,
                            streamReady=true,
                            quality=x.optString("quality")
                        ),
                        progress=x.optDouble("progress",0.0).toFloat().coerceIn(0f,1f),
                        positionMs=x.optLong("positionMs"),
                        durationMs=x.optLong("durationMs"),
                        episodeLabel=label
                    )
                )
            }
        }
    }

    suspend fun favorites(): List<MediaItem> = withContext(Dispatchers.IO) {
        val root=getJson("/v1/library/favorites",authorized=true)
        val arr=root.optJSONArray("items") ?: return@withContext emptyList()
        buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(parsePlatformMedia(x))
            }
        }
    }

    suspend fun toggleFavorite(mediaBackendId: String): Boolean = withContext(Dispatchers.IO) {
        postJson("/v1/library/favorites/"+mediaBackendId+"/toggle",JSONObject(),authorized=true)
            .optBoolean("favorite")
    }

    private fun parsePlatformMedia(x: JSONObject): MediaItem {
        val tmdbId=if(x.isNull("tmdbId"))0 else x.optInt("tmdbId")
        return MediaItem(
            id=tmdbId,
            type=if(x.optString("kind")=="movie")MediaType.MOVIE else MediaType.TV,
            title=x.optString("title").ifBlank{x.optString("originalTitle")},
            originalTitle=x.optString("originalTitle"),
            overview=x.optString("overview"),
            posterPath=x.optString("posterUrl").takeIf(String::isNotBlank),
            backdropPath=x.optString("backdropUrl").takeIf(String::isNotBlank),
            vote=x.optDouble("rating",0.0),
            date=x.optInt("year",0).takeIf{it>0}?.toString().orEmpty(),
            backendId=x.optString("id")
        )
    }

    suspend fun playbackUrl(mediaVersionId: String, download: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("mediaVersionId", mediaVersionId)
                .put("download", download)
            val obj = postJson("/v1/playback/token", body, authorized = true)
            obj.getString("url")
        }

    suspend fun playbackContext(mediaVersionId:String):PlaybackTarget =
        withContext(Dispatchers.IO) {
            val o=getJson(
                "/v1/playback/"+mediaVersionId+"/context",
                authorized=true
            )
            val variants=buildList {
                val arr=o.optJSONArray("variants")
                if(arr!=null) for(i in 0 until arr.length()) {
                    val x=arr.optJSONObject(i) ?: continue
                    add(
                        PlaybackVariant(
                            mediaVersionId=x.optString("mediaVersionId"),
                            label=x.optString("label").ifBlank{"Auto"},
                            codec=x.optString("codec"),
                            hdr=x.optString("hdr")
                        )
                    )
                }
            }
            val upNext=buildList {
                val arr=o.optJSONArray("upNext")
                if(arr!=null) for(i in 0 until arr.length()) {
                    val x=arr.optJSONObject(i) ?: continue
                    val id=x.optString("mediaVersionId")
                    if(id.isBlank()) continue
                    add(
                        PlaybackQueueItem(
                            mediaVersionId=id,
                            title=x.optString("title"),
                            subtitle=x.optString("subtitle"),
                            posterUrl=x.optString("posterUrl").takeIf(String::isNotBlank)
                        )
                    )
                }
            }
            PlaybackTarget(
                mediaVersionId=o.optString("mediaVersionId").ifBlank{mediaVersionId},
                title=o.optString("title"),
                subtitle=o.optString("subtitle"),
                posterUrl=o.optString("posterUrl").takeIf(String::isNotBlank),
                variants=variants,
                introEndMs=if(o.isNull("introEndMs"))null else o.optLong("introEndMs"),
                recapEndMs=if(o.isNull("recapEndMs"))null else o.optLong("recapEndMs"),
                creditsStartMs=if(o.isNull("creditsStartMs"))null else o.optLong("creditsStartMs"),
                resumePositionMs=o.optLong("resumePositionMs"),
                resumeDurationMs=o.optLong("resumeDurationMs"),
                resumeCompleted=o.optBoolean("resumeCompleted"),
                nextMediaVersionId=o.optString("nextMediaVersionId").takeIf(String::isNotBlank),
                nextTitle=o.optString("nextTitle").takeIf(String::isNotBlank),
                nextSubtitle=o.optString("nextSubtitle").takeIf(String::isNotBlank),
                previousMediaVersionId=o.optString("previousMediaVersionId").takeIf(String::isNotBlank),
                previousTitle=o.optString("previousTitle").takeIf(String::isNotBlank),
                previousSubtitle=o.optString("previousSubtitle").takeIf(String::isNotBlank),
                upNext=upNext
            )
        }

    suspend fun saveProgress(mediaVersionId: String, positionMs: Long, durationMs: Long) {
        withContext(Dispatchers.IO) {
            runCatching {
                postJson(
                    "/v1/watch/progress",
                    JSONObject()
                        .put("mediaVersionId", mediaVersionId)
                        .put("positionMs", positionMs)
                        .put("durationMs", durationMs),
                    authorized = true
                )
            }
        }
    }

    suspend fun startPlaybackSession(
        mediaVersionId:String,
        positionMs:Long,
        networkType:String,
        deviceName:String,
        appVersion:String
    ):String = withContext(Dispatchers.IO) {
        postJson(
            "/v1/playback/sessions",
            JSONObject()
                .put("mediaVersionId",mediaVersionId)
                .put("positionMs",positionMs)
                .put("networkType",networkType)
                .put("deviceName",deviceName)
                .put("appVersion",appVersion),
            authorized=true
        ).getString("id")
    }

    suspend fun heartbeatPlaybackSession(
        sessionId:String,
        currentMediaVersionId:String,
        positionMs:Long,
        durationMs:Long,
        watchedDeltaMs:Long,
        bufferCountDelta:Int,
        bufferMsDelta:Long,
        qualitySwitchDelta:Int,
        networkType:String
    ) {
        withContext(Dispatchers.IO) {
            postJson(
                "/v1/playback/sessions/"+sessionId+"/heartbeat",
                JSONObject()
                    .put("currentMediaVersionId",currentMediaVersionId)
                    .put("positionMs",positionMs)
                    .put("durationMs",durationMs)
                    .put("watchedDeltaMs",watchedDeltaMs)
                    .put("bufferCountDelta",bufferCountDelta)
                    .put("bufferMsDelta",bufferMsDelta)
                    .put("qualitySwitchDelta",qualitySwitchDelta)
                    .put("networkType",networkType),
                authorized=true
            )
        }
    }

    suspend fun endPlaybackSession(
        sessionId:String,
        currentMediaVersionId:String,
        positionMs:Long,
        durationMs:Long,
        watchedDeltaMs:Long,
        bufferCountDelta:Int,
        bufferMsDelta:Long,
        qualitySwitchDelta:Int,
        networkType:String,
        completed:Boolean,
        exitReason:String
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                postJson(
                    "/v1/playback/sessions/"+sessionId+"/end",
                    JSONObject()
                        .put("currentMediaVersionId",currentMediaVersionId)
                        .put("positionMs",positionMs)
                        .put("durationMs",durationMs)
                        .put("watchedDeltaMs",watchedDeltaMs)
                        .put("bufferCountDelta",bufferCountDelta)
                        .put("bufferMsDelta",bufferMsDelta)
                        .put("qualitySwitchDelta",qualitySwitchDelta)
                        .put("networkType",networkType)
                        .put("completed",completed)
                        .put("exitReason",exitReason),
                    authorized=true
                )
            }
        }
    }

    suspend fun enqueueDownload(context: Context, target: PlaybackTarget): String =
        withContext(Dispatchers.IO) {
            val settings=AppPreferences(context.applicationContext).read()
            OfflineDownloadManager.enqueue(
                context,
                target,
                smartManaged=settings.smartDownloads && !target.nextMediaVersionId.isNullOrBlank()
            )
        }

    internal suspend fun getJson(path: String, authorized: Boolean): JSONObject =
        executeJson(Request.Builder().url(session.baseUrl + path).get(), authorized)

    internal suspend fun postJson(path: String, body: JSONObject, authorized: Boolean): JSONObject =
        executeJson(
            Request.Builder()
                .url(session.baseUrl + path)
                .post(body.toString().toRequestBody(jsonType)),
            authorized
        )

    private suspend fun executeJson(builder: Request.Builder, authorized: Boolean): JSONObject =
        withContext(Dispatchers.IO) {
            var requestBuilder = builder
            if (authorized) {
                ensureAccessToken()
                val access = session.accessToken
                if (!access.isNullOrBlank()) {
                    requestBuilder = requestBuilder.header("Authorization", "Bearer " + access)
                }
                viewerProfiles.activeId()?.let {
                    requestBuilder = requestBuilder.header("X-Filmiqoo-Viewer-Profile", it)
                }
            }

            var request = requestBuilder.build()
            var response = client.newCall(request).execute()
            if (authorized && response.code == 401 && refreshSession()) {
                response.close()
                request = request.newBuilder()
                    .header("Authorization", "Bearer " + session.accessToken.orEmpty())
                    .build()
                response = client.newCall(request).execute()
            }

            response.use { res ->
                val raw = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException(apiError(raw, res.code))
                if (raw.isBlank()) JSONObject() else JSONObject(raw)
            }
        }

    private suspend fun ensureAccessToken() {
        if (!session.isLoggedIn) throw IllegalStateException("برای ادامه باید وارد حساب Filmiqoo شوی.")
    }

    private suspend fun refreshSession(): Boolean = withContext(Dispatchers.IO) {
        val refresh = session.refreshToken ?: return@withContext false
        runCatching {
            val body = JSONObject().put("refreshToken", refresh)
            val req = Request.Builder()
                .url(session.baseUrl + "/v1/auth/refresh")
                .post(body.toString().toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    session.clear()
                    return@use false
                }
                val o = JSONObject(res.body?.string().orEmpty())
                session.accessToken = o.getString("accessToken")
                session.refreshToken = o.getString("refreshToken")
                true
            }
        }.getOrDefault(false)
    }

    private fun apiError(raw: String, code: Int): String {
        return runCatching { JSONObject(raw).optString("error") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: "خطای سرور (" + code + ")"
    }

    private fun sanitizeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(90)
}
