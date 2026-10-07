package com.filmiqoo.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CinemaUserRating(val media: MediaItem, val value: Int, val updatedAt: Long)

/** Local lists also support metadata-only titles. They are explicitly separate from downloaded files. */
class CinemaPersonalStore(context: Context, profileId: String?) {
    private val prefs = context.applicationContext.getSharedPreferences("filmiqoo_cinema_library_v1", Context.MODE_PRIVATE)
    // Existing viewer IDs keep their original storage namespace; account-only and anonymous data are separate.
    private val prefix = profileId?.takeIf(String::isNotBlank)?.let { "profile:$it:" }
        ?: SessionStore(context.applicationContext).localAccountScope?.let { "account:$it:" }
        ?: "profile:guest:"

    fun saved(): List<MediaItem> = readItems("watchlist")
    fun favorites(): List<MediaItem> = readItems("favorites")
    fun contains(bucket: String, media: MediaItem): Boolean = readItems(bucket).any { cinemaMediaKey(it) == cinemaMediaKey(media) }
    fun setSaved(bucket: String, media: MediaItem, saved: Boolean) {
        require(bucket in setOf("watchlist", "favorites", "seenItems"))
        val items = readItems(bucket).filterNot { cinemaMediaKey(it) == cinemaMediaKey(media) }.toMutableList()
        if (saved) items.add(0, media)
        val array = JSONArray()
        items.take(2000).forEach {
            array.put(cinemaStoredMediaJson(it))
        }
        prefs.edit().putString(prefix + bucket, array.toString()).apply()
    }
    fun note(media: MediaItem): String = prefs.getString(prefix + "note:" + cinemaMediaKey(media), "").orEmpty()
    fun saveNote(media: MediaItem, text: String) { prefs.edit().putString(prefix + "note:" + cinemaMediaKey(media), text.take(2000)).apply() }
    fun seen(media: MediaItem): Boolean = prefs.getBoolean(prefix + "seen:" + cinemaMediaKey(media), false)
    fun markSeen(media: MediaItem, value: Boolean) { prefs.edit().putBoolean(prefix + "seen:" + cinemaMediaKey(media), value).apply(); setSaved("seenItems",media,value) }
    fun seenItems():List<MediaItem> = (readItems("seenItems") + saved() + favorites()).distinctBy(::cinemaMediaKey).filter(::seen)
    fun hideSpoilers(): Boolean = prefs.getBoolean(prefix + "hideSpoilers", true)
    fun rating(media: MediaItem): Int? = prefs.getInt(prefix + "rating:" + cinemaMediaKey(media), 0).takeIf { it in 1..10 }
    /** Explicit ratings on this device/profile, independently of cloud lists and playback history. */
    fun setRating(media: MediaItem, value: Int?) {
        require(value == null || value in 1..10)
        val key = prefix + "rating:" + cinemaMediaKey(media)
        val timeKey = prefix + "ratedAt:" + cinemaMediaKey(media)
        val editor = prefs.edit()
        if (value == null) editor.remove(key).remove(timeKey)
        else editor.putInt(key, value).putLong(timeKey, System.currentTimeMillis())
        editor.apply()
        val items = readItems("ratedItems").filterNot { cinemaMediaKey(it) == cinemaMediaKey(media) }.toMutableList()
        if (value != null) items.add(0, media)
        val array = JSONArray()
        items.take(2000).forEach { item ->
            array.put(cinemaStoredMediaJson(item))
        }
        prefs.edit().putString(prefix + "ratedItems", array.toString()).apply()
    }
    fun ratings(): List<CinemaUserRating> = readItems("ratedItems").mapNotNull { item ->
        rating(item)?.let { CinemaUserRating(item, it, prefs.getLong(prefix + "ratedAt:" + cinemaMediaKey(item), 0L)) }
    }.sortedByDescending { it.updatedAt }
    fun setHideSpoilers(value: Boolean) { prefs.edit().putBoolean(prefix + "hideSpoilers", value).apply() }

    private fun readItems(bucket: String): List<MediaItem> {
        val arr = runCatching { JSONArray(prefs.getString(prefix + bucket, "[]")) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = runCatching { MediaType.valueOf(o.optString("type")) }.getOrNull() ?: continue
                val title = o.optString("title")
                if (title.isBlank()) continue
                add(mediaCatalogExtras(MediaItem(o.optInt("id"), type, title, o.optString("originalTitle"),
                    posterPath = o.optString("poster").takeIf { it.isNotBlank() && it != "null" },
                    backdropPath = o.optString("backdrop").takeIf { it.isNotBlank() && it != "null" },
                    vote = o.optDouble("vote", 0.0), date = o.optString("date"),
                    backendId = o.optString("backendId").takeIf { it.isNotBlank() && it != "null" }),o))
            }
        }
    }
}
