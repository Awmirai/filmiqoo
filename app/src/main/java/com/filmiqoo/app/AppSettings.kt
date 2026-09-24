package com.filmiqoo.app

import android.content.Context
import org.json.JSONObject

data class AppSettings(
    val autoplayNext: Boolean = true,
    val autoplayPreviews: Boolean = true,
    val wifiOnlyDownloads: Boolean = false,
    val dataSaver: Boolean = false,
    val spoilerShield: Boolean = true,
    val skipIntro: Boolean = false,
    val skipRecap: Boolean = false,
    val skipCredits: Boolean = false,
    val subtitleScale: Float = 1f,
    val subtitleBottomPadding: Float = 0.08f,
    val subtitleTextColor: String = "white",
    val subtitleBackgroundOpacity: Float = 0.45f,
    val subtitleEdgeStyle: String = "outline",
    val playerResizeMode: String = "fit",
    val smartDownloads: Boolean = false,
    val downloadStorageLimitMb: Long = 10240L,
    val defaultPlaybackSpeed: Float = 1f,
    val defaultAudioLanguage: String = "fa",
    val defaultSubtitleLanguage: String = "fa",
    val subtitlesEnabled: Boolean = true,
    val notificationsSocial: Boolean = true,
    val notificationsMessages: Boolean = true,
    val notificationsReleases: Boolean = true,
    val privateAccount: Boolean = false
)

class AppPreferences(context: Context) {
    private val prefs=context.applicationContext
        .getSharedPreferences("filmiqoo_app_preferences_v1",Context.MODE_PRIVATE)

    fun read(): AppSettings=AppSettings(
        autoplayNext=prefs.getBoolean("autoplay_next",true),
        autoplayPreviews=prefs.getBoolean("autoplay_previews",true),
        wifiOnlyDownloads=prefs.getBoolean("wifi_only_downloads",false),
        dataSaver=prefs.getBoolean("data_saver",false),
        spoilerShield=prefs.getBoolean("spoiler_shield",true),
        skipIntro=prefs.getBoolean("skip_intro",false),
        skipRecap=prefs.getBoolean("skip_recap",false),
        skipCredits=prefs.getBoolean("skip_credits",false),
        subtitleScale=prefs.getFloat("subtitle_scale",1f),
        subtitleBottomPadding=prefs.getFloat("subtitle_bottom_padding",0.08f),
        subtitleTextColor=prefs.getString("subtitle_text_color","white") ?: "white",
        subtitleBackgroundOpacity=prefs.getFloat("subtitle_background_opacity",0.45f),
        subtitleEdgeStyle=prefs.getString("subtitle_edge_style","outline") ?: "outline",
        playerResizeMode=prefs.getString("player_resize_mode","fit") ?: "fit",
        smartDownloads=prefs.getBoolean("smart_downloads",false),
        downloadStorageLimitMb=prefs.getLong("download_storage_limit_mb",10240L),
        defaultPlaybackSpeed=prefs.getFloat("playback_speed",1f),
        defaultAudioLanguage=prefs.getString("audio_lang","fa") ?: "fa",
        defaultSubtitleLanguage=prefs.getString("subtitle_lang","fa") ?: "fa",
        subtitlesEnabled=prefs.getBoolean("subtitles_enabled",true),
        notificationsSocial=prefs.getBoolean("notify_social",true),
        notificationsMessages=prefs.getBoolean("notify_messages",true),
        notificationsReleases=prefs.getBoolean("notify_releases",true),
        privateAccount=prefs.getBoolean("private_account",false)
    )

    fun write(s: AppSettings) {
        prefs.edit()
            .putBoolean("autoplay_next",s.autoplayNext)
            .putBoolean("autoplay_previews",s.autoplayPreviews)
            .putBoolean("wifi_only_downloads",s.wifiOnlyDownloads)
            .putBoolean("data_saver",s.dataSaver)
            .putBoolean("spoiler_shield",s.spoilerShield)
            .putBoolean("skip_intro",s.skipIntro)
            .putBoolean("skip_recap",s.skipRecap)
            .putBoolean("skip_credits",s.skipCredits)
            .putFloat("subtitle_scale",s.subtitleScale)
            .putFloat("subtitle_bottom_padding",s.subtitleBottomPadding)
            .putString("subtitle_text_color",s.subtitleTextColor)
            .putFloat("subtitle_background_opacity",s.subtitleBackgroundOpacity)
            .putString("subtitle_edge_style",s.subtitleEdgeStyle)
            .putString("player_resize_mode",s.playerResizeMode)
            .putBoolean("smart_downloads",s.smartDownloads)
            .putLong("download_storage_limit_mb",s.downloadStorageLimitMb)
            .putFloat("playback_speed",s.defaultPlaybackSpeed)
            .putString("audio_lang",s.defaultAudioLanguage)
            .putString("subtitle_lang",s.defaultSubtitleLanguage)
            .putBoolean("subtitles_enabled",s.subtitlesEnabled)
            .putBoolean("notify_social",s.notificationsSocial)
            .putBoolean("notify_messages",s.notificationsMessages)
            .putBoolean("notify_releases",s.notificationsReleases)
            .putBoolean("private_account",s.privateAccount)
            .apply()
    }
}

class SettingsRepository(
    private val context: Context,
    private val backend: BackendRepository
) {
    private val local=AppPreferences(context)

    fun local(): AppSettings=local.read()

    suspend fun load(): AppSettings {
        val o=backend.getJson("/v1/settings",authorized=true)
        return parse(o).also {
            local.write(it)
            OfflineDownloadManager.setWifiOnly(context,it.wifiOnlyDownloads)
        }
    }

    suspend fun save(settings: AppSettings): AppSettings {
        val o=backend.postJson(
            "/v1/settings",
            JSONObject()
                .put("autoplayNext",settings.autoplayNext)
                .put("autoplayPreviews",settings.autoplayPreviews)
                .put("wifiOnlyDownloads",settings.wifiOnlyDownloads)
                .put("dataSaver",settings.dataSaver)
                .put("spoilerShield",settings.spoilerShield)
                .put("skipIntro",settings.skipIntro)
                .put("skipRecap",settings.skipRecap)
                .put("skipCredits",settings.skipCredits)
                .put("subtitleScale",settings.subtitleScale.toDouble())
                .put("subtitleBottomPadding",settings.subtitleBottomPadding.toDouble())
                .put("subtitleTextColor",settings.subtitleTextColor)
                .put("subtitleBackgroundOpacity",settings.subtitleBackgroundOpacity.toDouble())
                .put("subtitleEdgeStyle",settings.subtitleEdgeStyle)
                .put("playerResizeMode",settings.playerResizeMode)
                .put("smartDownloads",settings.smartDownloads)
                .put("downloadStorageLimitMb",settings.downloadStorageLimitMb)
                .put("defaultPlaybackSpeed",settings.defaultPlaybackSpeed.toDouble())
                .put("defaultAudioLanguage",settings.defaultAudioLanguage)
                .put("defaultSubtitleLanguage",settings.defaultSubtitleLanguage)
                .put("subtitlesEnabled",settings.subtitlesEnabled)
                .put("notificationsSocial",settings.notificationsSocial)
                .put("notificationsMessages",settings.notificationsMessages)
                .put("notificationsReleases",settings.notificationsReleases)
                .put("privateAccount",settings.privateAccount),
            authorized=true
        )
        return parse(o).also {
            local.write(it)
            OfflineDownloadManager.setWifiOnly(context,it.wifiOnlyDownloads)
        }
    }

    private fun parse(o: JSONObject)=AppSettings(
        autoplayNext=o.optBoolean("autoplayNext",true),
        autoplayPreviews=o.optBoolean("autoplayPreviews",true),
        wifiOnlyDownloads=o.optBoolean("wifiOnlyDownloads"),
        dataSaver=o.optBoolean("dataSaver"),
        spoilerShield=o.optBoolean("spoilerShield",true),
        skipIntro=o.optBoolean("skipIntro"),
        skipRecap=o.optBoolean("skipRecap"),
        skipCredits=o.optBoolean("skipCredits"),
        subtitleScale=o.optDouble("subtitleScale",1.0).toFloat(),
        subtitleBottomPadding=o.optDouble("subtitleBottomPadding",0.08).toFloat(),
        subtitleTextColor=o.optString("subtitleTextColor","white"),
        subtitleBackgroundOpacity=o.optDouble("subtitleBackgroundOpacity",0.45).toFloat(),
        subtitleEdgeStyle=o.optString("subtitleEdgeStyle","outline"),
        playerResizeMode=o.optString("playerResizeMode","fit"),
        smartDownloads=o.optBoolean("smartDownloads"),
        downloadStorageLimitMb=o.optLong("downloadStorageLimitMb",10240L),
        defaultPlaybackSpeed=o.optDouble("defaultPlaybackSpeed",1.0).toFloat(),
        defaultAudioLanguage=o.optString("defaultAudioLanguage","fa"),
        defaultSubtitleLanguage=o.optString("defaultSubtitleLanguage","fa"),
        subtitlesEnabled=o.optBoolean("subtitlesEnabled",true),
        notificationsSocial=o.optBoolean("notificationsSocial",true),
        notificationsMessages=o.optBoolean("notificationsMessages",true),
        notificationsReleases=o.optBoolean("notificationsReleases",true),
        privateAccount=o.optBoolean("privateAccount")
    )
}
