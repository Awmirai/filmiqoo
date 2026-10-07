package com.filmiqoo.app

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
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

data class RemotePlaybackSource(
    val url:String,
    val contentType:String,
    val expiresAt:Long
)

data class AutoSubtitleMatch(
    val url: String,
    val mimeType: String,
    val language: String,
    val release: String,
    val provider: String,
    val exactRelease: Boolean
)

data class PlaybackTarget(
    val mediaVersionId: String,
    val title: String,
    val mediaTitleId: String? = null,
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
    val kind: String,
    val title: String,
    val originalTitle: String,
    val overview: String,
    val year: Int,
    val posterUrl: String,
    val backdropUrl: String,
    val rating: Double,
    val versions: List<PlatformVersion>,
    val seasons: List<PlatformSeason>,
    val hasPersianDub:Boolean=false,val hasPersianSubtitle:Boolean=false,
    val dubbedEpisodeCount:Int?=null,val availableEpisodeCount:Int?=null,
    val genreIds:List<Int> = emptyList(),val originalLanguage:String="",val originCountries:List<String> = emptyList(),
    val runtimeMinutes:Int?=null,val seriesStatus:String?=null,val seriesType:String?=null,val seasonCount:Int?=null,val episodeCount:Int?=null
) {
    fun asMediaItem(): MediaItem = MediaItem(
        id=tmdbId ?: 0,
        type=if(kind=="movie") MediaType.MOVIE else MediaType.TV,
        title=title.ifBlank { originalTitle },
        originalTitle=originalTitle,
        overview=overview,
        posterPath=posterUrl.takeIf(String::isNotBlank),
        backdropPath=backdropUrl.takeIf(String::isNotBlank),
        vote=rating,
        date=year.takeIf { it>0 }?.toString().orEmpty(),
        backendId=id,
        mediaVersionId=versions.firstOrNull { it.preferred && it.streamReady }?.id
            ?: versions.firstOrNull { it.streamReady }?.id,
        streamReady=versions.any { it.streamReady } || seasons.any{season->season.episodes.any{it.streamReady}},
        quality=versions.firstOrNull { it.preferred }?.quality
            ?: versions.firstOrNull()?.quality.orEmpty(),
        hasPersianDub=hasPersianDub,hasPersianSubtitle=hasPersianSubtitle,dubbedEpisodeCount=dubbedEpisodeCount,availableEpisodeCount=availableEpisodeCount,
        genreIds=genreIds,originalLanguage=originalLanguage,originCountries=originCountries,runtimeMinutes=runtimeMinutes,seriesStatus=seriesStatus,seasonCount=seasonCount,episodeCount=episodeCount
    )
}

data class PlatformVersion(
    val id: String,
    val quality: String,
    val codec: String,
    val hdr: String,
    val fileSizeBytes: Long,
    val durationMs: Long,
    val streamReady: Boolean,
    val preferred: Boolean,
    val audioTracks: List<String> = emptyList(),
    val subtitleTracks: List<String> = emptyList(),
    val isDubbed:Boolean=false,val isPersianDubbed:Boolean=false,val hasPersianSubtitle:Boolean=false,
    val detectionSource:String="",val detectionConfidence:String="NONE",val detectionEvidence:List<String> = emptyList()
)

internal fun cinemaTrackLabels(array: org.json.JSONArray?): List<String> = buildList {
    if (array != null) for (index in 0 until array.length()) {
        val value = array.opt(index)
        val label = if (value is JSONObject) {
            listOf("label", "title", "name", "language", "lang").firstNotNullOfOrNull { key ->
                value.optString(key).trim().takeIf { it.isNotBlank() && it != "null" }
            }.orEmpty()
        } else (value as? String).orEmpty().trim()
        if (label.isNotBlank() && label != "null") add(when (label.lowercase(Locale.ROOT)) {
            "fa", "fas", "per", "persian" -> "فارسی"
            "en", "eng", "english" -> "English"
            "ko", "kor", "korean" -> "Korean"
            "hi", "hin", "hindi" -> "Hindi"
            else -> label
        })
    }
}.distinct()

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
    val creditsStartMs: Long? = null,
    val isDubbed:Boolean=false,val isPersianDubbed:Boolean=false,val hasPersianSubtitle:Boolean=false,
    val hasPersianDub:Boolean=false,val versions:List<PlatformVersion> = emptyList()
)

data class PlatformSeason(
    val id: String,
    val number: Int,
    val name: String,
    val posterUrl: String,
    val episodes: List<PlatformEpisode>
)

class SessionStore(context: Context) {
    private val viewerProfiles = ViewerProfileStore(context.applicationContext)
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
    /** Used only to isolate local preferences, never to authorize requests. */
    val localAccountScope: String? get() {
        if (!isLoggedIn) return null
        return runCatching {
            val segment=accessToken.orEmpty().split('.').getOrNull(1) ?: return@runCatching null
            val body=String(android.util.Base64.decode(segment,android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP),Charsets.UTF_8)
            JSONObject(body).optString("sub").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun save(result: AuthResult) {
        accessToken = result.accessToken
        refreshToken = result.refreshToken
        displayName = result.user.displayName
    }

    fun clear() {
        // An expired account must not supply its viewer ID to a subsequent login.
        // Personal lists remain scoped to their profile; only the active selection is cleared.
        viewerProfiles.clear()
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
        .addInterceptor(IranAccessInterceptor())
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val refreshMutex = Mutex()

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

    suspend fun requestPasswordReset(email:String) = withContext(Dispatchers.IO) {
        val body=JSONObject().put("email",email.trim().lowercase())
        val req=Request.Builder().url(session.baseUrl+"/v1/auth/password/forgot")
            .post(body.toString().toRequestBody(jsonType)).build()
        client.newCall(req).execute().use { res ->
            val raw=res.body?.string().orEmpty()
            if(!res.isSuccessful) throw IllegalStateException(apiError(raw,res.code))
        }
    }

    suspend fun resetPassword(token:String,password:String) = withContext(Dispatchers.IO) {
        val body=JSONObject().put("token",token.trim()).put("password",password)
        val req=Request.Builder().url(session.baseUrl+"/v1/auth/password/reset")
            .post(body.toString().toRequestBody(jsonType)).build()
        client.newCall(req).execute().use { res ->
            val raw=res.body?.string().orEmpty()
            if(!res.isSuccessful) throw IllegalStateException(apiError(raw,res.code))
        }
    }

    private suspend fun postAuth(path: String, body: JSONObject): AuthResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(session.baseUrl + path)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { res ->
            val raw = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw BackendHttpException(res.code, runCatching { JSONObject(raw).optString("error") }.getOrDefault(""), apiError(raw, res.code))
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

    suspend fun tmdbMetadata(path: String, params: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        val url = (session.baseUrl + "/v1/tmdb").toHttpUrl().newBuilder()
            .addQueryParameter("path", path)
            .apply {
                params.forEach { (key, value) -> addQueryParameter(key, value) }
            }
            .build()
        executeJson(Request.Builder().url(url).get(), authorized = false)
    }

    suspend fun catalogHome():List<MediaItem> = withContext(Dispatchers.IO) {
        val root=getJson("/v1/catalog/home",authorized=session.isLoggedIn)
        parseTitles(root.optJSONArray("items"),null,true).map { it.media }
    }

    suspend fun detail(id: String): PlatformDetail = withContext(Dispatchers.IO) {
        val o = getJson("/v1/catalog/" + id, authorized = session.isLoggedIn)
        val versions = buildList {
            val a = o.optJSONArray("versions")
            if (a != null) for (i in 0 until a.length()) {
                val x = a.optJSONObject(i) ?: continue
                add(
                    platformVersionFromJson(x)
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
                                mediaVersionId = x.optString("mediaVersionId").takeIf { it.isNotBlank() && it != "null" },
                                quality = x.optString("quality").takeIf(String::isNotBlank),
                                streamReady = x.optBoolean("streamReady"),
                                introStartMs = if(x.isNull("introStartMs")) null else x.optLong("introStartMs"),
                                introEndMs = if(x.isNull("introEndMs")) null else x.optLong("introEndMs"),
                                recapStartMs = if(x.isNull("recapStartMs")) null else x.optLong("recapStartMs"),
                                recapEndMs = if(x.isNull("recapEndMs")) null else x.optLong("recapEndMs"),
                                creditsStartMs = if(x.isNull("creditsStartMs")) null else x.optLong("creditsStartMs"),
                                isDubbed=x.optBoolean("isDubbed"),isPersianDubbed=x.optBoolean("isPersianDubbed"),hasPersianSubtitle=x.optBoolean("hasPersianSubtitle"),hasPersianDub=x.optBoolean("hasPersianDub"),versions=platformEpisodeVersions(x)
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
            kind = o.optString("kind"),
            title = o.optString("title"),
            originalTitle = o.optString("originalTitle"),
            overview = o.optString("overview"),
            year = o.optInt("year"),
            posterUrl = o.optString("posterUrl"),
            backdropUrl = o.optString("backdropUrl"),
            rating = o.optDouble("rating",0.0),
            versions = versions,seasons = seasons,
            hasPersianDub=o.optBoolean("hasPersianDub"),hasPersianSubtitle=o.optBoolean("hasPersianSubtitle"),dubbedEpisodeCount=discoveryOptionalInt(o,"dubbedEpisodeCount"),availableEpisodeCount=discoveryOptionalInt(o,"availableEpisodeCount"),
            genreIds=discoveryInts(o.optJSONArray("genreIds")),originalLanguage=discoveryClean(o,"originalLanguage"),originCountries=discoveryStrings(o.optJSONArray("originCountries")),runtimeMinutes=discoveryOptionalInt(o,"runtimeMinutes")?.takeIf{it>0},
            seriesStatus=discoveryClean(o,"seriesStatus").takeIf(String::isNotBlank),seriesType=discoveryClean(o,"seriesType").takeIf(String::isNotBlank),seasonCount=discoveryOptionalInt(o,"seasonCount"),episodeCount=discoveryOptionalInt(o,"episodeCount")
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

    suspend fun uploadFile(
        file: File,
        mimeType: String,
        kind: String
    ): UploadTicket = withContext(Dispatchers.IO) {
        val mime=mimeType.trim().ifBlank { "application/octet-stream" }
        val size=file.length()
        if(!file.isFile || size<=0L) throw IllegalStateException("فایل قابل خواندن نیست")

        val ticketJson=postJson(
            "/v1/uploads/presign",
            JSONObject()
                .put("kind",kind)
                .put("mimeType",mime)
                .put("fileName",file.name)
                .put("sizeBytes",size),
            authorized=true
        )

        val ticket=UploadTicket(
            uploadId=ticketJson.getString("uploadId"),
            uploadUrl=ticketJson.getString("uploadUrl"),
            objectKey=ticketJson.getString("objectKey"),
            mediaUrl=ticketJson.getString("mediaUrl"),
            mimeType=mime,
            fileName=file.name,
            sizeBytes=size
        )

        val body=object: RequestBody() {
            override fun contentType()=mime.toMediaTypeOrNull()
            override fun contentLength()=size
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { input ->
                    val buffer=ByteArray(DEFAULT_BUFFER_SIZE)
                    while(true) {
                        val read=input.read(buffer)
                        if(read<0) break
                        sink.write(buffer,0,read)
                    }
                }
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
                            mediaTitleId=media.backendId,
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

    suspend fun playbackUrl(
        mediaVersionId: String,
        download: Boolean = false,
        remote: Boolean = false
    ): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("mediaVersionId", mediaVersionId)
                .put("download", download)
                .put("remote", remote)
            val obj = postJson("/v1/playback/token", body, authorized = true)
            obj.getString("url")
        }

    suspend fun remotePlaybackSource(
        mediaVersionId:String
    ):RemotePlaybackSource = withContext(Dispatchers.IO) {
        val body=JSONObject()
            .put("mediaVersionId",mediaVersionId)
            .put("download",false)
            .put("remote",true)
        val o=postJson(
            "/v1/playback/token",
            body,
            authorized=true
        )
        RemotePlaybackSource(
            url=o.getString("url"),
            contentType=o.optString("contentType").ifBlank { "video/mp4" },
            expiresAt=o.optLong("expiresAt")
        )
    }

    suspend fun autoSubtitle(
        mediaVersionId:String,
        language:String="fa"
    ):AutoSubtitleMatch = withContext(Dispatchers.IO) {
        val url=(session.baseUrl+"/v1/playback/"+mediaVersionId+"/subtitles/auto")
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("lang",language)
            .build()
        val o=executeJson(
            Request.Builder().url(url).get(),
            authorized=true
        )
        AutoSubtitleMatch(
            url=o.getString("url"),
            mimeType=o.optString("mimeType").ifBlank { "application/x-subrip" },
            language=o.optString("language").ifBlank { language },
            release=o.optString("release").ifBlank { "Auto match" },
            provider=o.optString("provider").ifBlank { "opensubtitles" },
            exactRelease=o.optBoolean("exactRelease",false)
        )
    }

    suspend fun autoPersianSubtitleFallback(
        target:PlaybackTarget,
        mediaVersionId:String
    ):AutoSubtitleMatch = withContext(Dispatchers.IO) {
        val mediaTitleId=target.mediaTitleId
            ?: throw IllegalStateException("اطلاعات عنوان برای جستجوی زیرنویس کامل نیست.")

        val media=detail(mediaTitleId)
        val tmdbId=media.tmdbId
            ?: throw IllegalStateException("شناسه عنوان برای جستجوی زیرنویس پیدا نشد.")

        var seasonNumber:Int?=null
        var episodeNumber:Int?=null

        media.seasons.firstNotNullOfOrNull { season ->
            season.episodes.firstOrNull { it.mediaVersionId==mediaVersionId }
                ?.let { episode -> season.number to episode.number }
        }?.let { pair ->
            seasonNumber=pair.first
            episodeNumber=pair.second
        }

        if(media.kind!="movie" && (seasonNumber==null || episodeNumber==null)) {
            val text=(target.subtitle+" "+target.title)
            val se=Regex("(?i)S(\\d{1,2})E(\\d{1,3})").find(text)
                ?: Regex("(?i)(\\d{1,2})x(\\d{1,3})").find(text)
            if(se!=null) {
                seasonNumber=se.groupValues.getOrNull(1)?.toIntOrNull()
                episodeNumber=se.groupValues.getOrNull(2)?.toIntOrNull()
            }
        }

        if(media.kind!="movie" && (seasonNumber==null || episodeNumber==null)) {
            throw IllegalStateException("فصل و قسمت این ویدیو برای زیرنویس خودکار مشخص نیست.")
        }

        val metadataPath=
            if(media.kind=="movie") "movie/$tmdbId"
            else "tv/$tmdbId"
        val metadata=tmdbMetadata(
            metadataPath,
            mapOf("append_to_response" to "external_ids")
        )
        val imdbId=metadata
            .optJSONObject("external_ids")
            ?.optString("imdb_id")
            ?.trim()
            .orEmpty()
        if(!imdbId.startsWith("tt")) {
            throw IllegalStateException("شناسه IMDb این عنوان برای زیرنویس پیدا نشد.")
        }

        val base="https://stremio.alirostami.com/subtitles"
        val endpoint=
            if(media.kind=="movie") {
                "$base/movie/$imdbId.json"
            } else {
                "$base/series/$imdbId:"+seasonNumber+":"+episodeNumber+".json"
            }

        val req=Request.Builder()
            .url(endpoint)
            .header("Accept","application/json")
            .header("User-Agent","Filmiqoo/1.0 subtitle-client")
            .get()
            .build()

        val raw=client.newCall(req).execute().use { res ->
            val body=res.body?.string().orEmpty()
            if(!res.isSuccessful) {
                throw IllegalStateException("سرویس زیرنویس فارسی فعلاً در دسترس نیست.")
            }
            body
        }

        val arr=runCatching { JSONObject(raw).optJSONArray("subtitles") }
            .getOrNull()
            ?: throw IllegalStateException("برای این عنوان زیرنویس فارسی پیدا نشد.")

        val hint=(target.subtitle+" "+target.title).lowercase(Locale.ROOT)
        val quality=Regex("(?i)(2160p|1080p|720p|480p)").find(hint)
            ?.groupValues?.getOrNull(1)?.lowercase(Locale.ROOT)
        val episodeTag=
            if(seasonNumber!=null && episodeNumber!=null)
                "s%02de%02d".format(Locale.US,seasonNumber,episodeNumber).lowercase(Locale.ROOT)
            else null

        var bestUrl:String?=null
        var bestTitle:String?=null
        var bestScore=Int.MIN_VALUE

        for(i in 0 until arr.length()) {
            val item=arr.optJSONObject(i) ?: continue
            val lang=item.optString("lang").lowercase(Locale.ROOT)
            if(lang.isNotBlank() && lang !in setOf("fa","fas","per","persian","farsi")) continue

            val rawUrl=item.optString("url").trim()
            if(rawUrl.isBlank()) continue
            val safeUrl=when {
                rawUrl.startsWith("https://") -> rawUrl
                rawUrl.startsWith("http://stremio.alirostami.com/") ->
                    "https://"+rawUrl.removePrefix("http://")
                else -> continue
            }

            val title=item.optString("title").trim()
            val normalized=title.lowercase(Locale.ROOT)
            var score=1000-i
            if(!quality.isNullOrBlank() && normalized.contains(quality)) score+=120
            if(!episodeTag.isNullOrBlank()) {
                val compact=normalized.replace(Regex("[^a-z0-9]"),"")
                if(compact.contains(episodeTag)) score+=160
            }
            if(score>bestScore) {
                bestScore=score
                bestUrl=safeUrl
                bestTitle=title
            }
        }

        val url=bestUrl
            ?: throw IllegalStateException("برای این عنوان زیرنویس فارسی مناسبی پیدا نشد.")

        AutoSubtitleMatch(
            url=url,
            mimeType="application/x-subrip",
            language="fa",
            release=bestTitle?.ifBlank { "Persian subtitle" } ?: "Persian subtitle",
            provider="SubSource",
            exactRelease=false
        )
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
                mediaTitleId=o.optString("mediaTitleId").takeIf(String::isNotBlank),
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
        networkType:String,
        watchedTotalMs:Long?=null
    ) {
        withContext(Dispatchers.IO) {
            postJson(
                "/v1/playback/sessions/"+sessionId+"/heartbeat",
                JSONObject()
                    .put("currentMediaVersionId",currentMediaVersionId)
                    .put("positionMs",positionMs)
                    .put("durationMs",durationMs)
                    .put("watchedDeltaMs",watchedDeltaMs)
                    .put("watchedTotalMs",watchedTotalMs)
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
        exitReason:String,
        watchedTotalMs:Long?=null
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
                    .put("watchedTotalMs",watchedTotalMs)
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

    internal suspend fun getJsonScoped(path:String, assertScope:()->Unit):JSONObject =
        executeJson(Request.Builder().url(session.baseUrl+path).get(),true,assertScope)

    internal suspend fun postJsonScoped(path:String,body:JSONObject,assertScope:()->Unit):JSONObject =
        executeJson(Request.Builder().url(session.baseUrl+path).post(body.toString().toRequestBody(jsonType)),true,assertScope)

    private suspend fun executeJson(builder: Request.Builder, authorized: Boolean, assertScope:(()->Unit)?=null): JSONObject =
        withContext(Dispatchers.IO) {
            assertScope?.invoke()
            var requestBuilder = builder
            var accessUsed: String? = null
            if (authorized) {
                ensureAccessToken()
                accessUsed = session.accessToken
                if (!accessUsed.isNullOrBlank()) {
                    requestBuilder = requestBuilder.header("Authorization", "Bearer " + accessUsed)
                }
                viewerProfiles.activeId()?.let {
                    requestBuilder = requestBuilder.header("X-Filmiqoo-Viewer-Profile", it)
                }
            }
            assertScope?.invoke()
            var request = requestBuilder.build()
            var response = client.newCall(request).execute()
            if (authorized && response.code == 401 && run { try { assertScope?.invoke();refreshSession(accessUsed).also { assertScope?.invoke() } } catch(failure:Throwable) { response.close();throw failure } }) {
                response.close()
                request = request.newBuilder()
                    .header("Authorization", "Bearer " + session.accessToken.orEmpty())
                    .build()
                response = client.newCall(request).execute()
            }

            response.use { res ->
                assertScope?.invoke()
                val raw = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw BackendHttpException(res.code,
                    runCatching { JSONObject(raw).optString("error") }.getOrDefault(""), apiError(raw, res.code))
                if (raw.isBlank()) JSONObject() else JSONObject(raw)
            }
        }

    private suspend fun ensureAccessToken() {
        if (!session.isLoggedIn) throw IllegalStateException("برای ادامه باید وارد حساب Filmiqoo شوی.")
    }

    private suspend fun refreshSession(staleAccessToken: String?): Boolean =
        refreshMutex.withLock {
            val currentAccess = session.accessToken
            if (
                !currentAccess.isNullOrBlank() &&
                currentAccess != staleAccessToken
            ) {
                return@withLock true
            }

            val refresh = session.refreshToken ?: return@withLock false
            runCatching {
                val body = JSONObject().put("refreshToken", refresh)
                val req = Request.Builder()
                    .url(session.baseUrl + "/v1/auth/refresh")
                    .post(body.toString().toRequestBody(jsonType))
                    .build()
                client.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) {
                        // A temporary server/rate-limit failure is not session revocation.
                        if (res.code == 401 && session.refreshToken == refresh) {
                            session.clear()
                        }
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
        val message=runCatching { JSONObject(raw).optString("error") }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return "خطای سرور ("+code+")"

        return when(message) {
            "invalid email" ->
                "ایمیل واردشده معتبر نیست."
            "username must be 3-24 characters using letters, numbers, _ or ." ->
                "نام کاربری باید ۳ تا ۲۴ کاراکتر و فقط شامل حروف انگلیسی، عدد، _ یا . باشد."
            "displayName must be 2-50 characters" ->
                "نام نمایشی باید بین ۲ تا ۵۰ کاراکتر باشد."
            "password must be 10-128 characters" ->
                "رمز عبور باید بین ۱۰ تا ۱۲۸ کاراکتر باشد."
            "email or username already exists" ->
                "این ایمیل یا نام کاربری قبلاً استفاده شده است."
            "login and password are required" ->
                "ایمیل/نام کاربری و رمز عبور را وارد کن."
            "invalid credentials" ->
                "ایمیل، نام کاربری یا رمز عبور درست نیست."
            "invalid channel slug or name" ->
                "نام یا شناسه کانال معتبر نیست."
            "channel slug already exists" ->
                "این شناسه کانال قبلاً گرفته شده است."
            "invalid visibility" ->
                "سطح دسترسی انتخاب‌شده معتبر نیست."
            "post body must be 1-5000 characters" ->
                "متن محتوا باید بین ۱ تا ۵۰۰۰ کاراکتر باشد."
            "poll requires 2-6 unique options" ->
                "نظرسنجی باید بین ۲ تا ۶ گزینه متفاوت داشته باشد."
            "scheduledAt is too far in the future" ->
                "زمان انتشار را حداکثر تا یک سال آینده انتخاب کن."
            "comment must be 1-2000 characters" ->
                "کامنت باید بین ۱ تا ۲۰۰۰ کاراکتر باشد."
            "message is empty" ->
                "پیام نمی‌تواند خالی باشد."
            "message is too long" ->
                "پیام حداکثر می‌تواند ۴۰۰۰ کاراکتر باشد."
            "bio is too long" ->
                "معرفی پروفایل حداکثر ۳۰۰ کاراکتر است."
            "profile images must come from your Filmiqoo uploads" ->
                "عکس پروفایل و کاور باید از فایل‌های آپلودشده خودت در Filmiqoo باشند."
            "unsupported TMDB metadata path" ->
                "سرویس اطلاعات عنوان برای زیرنویس آماده نیست. دوباره امتحان کن."
            else -> message
        }
    }

    private fun sanitizeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(90)
}

internal class BackendHttpException(val status:Int,val reason:String,message:String):IllegalStateException(message)
