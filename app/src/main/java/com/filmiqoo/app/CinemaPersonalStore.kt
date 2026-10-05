package com.filmiqoo.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Local lists also support metadata-only titles. They are explicitly separate from downloaded files. */
class CinemaPersonalStore(context: Context, profileId: String?) {
    private val prefs = context.applicationContext.getSharedPreferences("filmiqoo_cinema_library_v1", Context.MODE_PRIVATE)
    private val prefix = "profile:" + (profileId?.takeIf(String::isNotBlank) ?: "guest") + ":"

    fun saved(): List<MediaItem> = readItems("watchlist")
    fun favorites(): List<MediaItem> = readItems("favorites")
    fun contains(bucket: String, media: MediaItem): Boolean = readItems(bucket).any { cinemaMediaKey(it) == cinemaMediaKey(media) }
    fun setSaved(bucket: String, media: MediaItem, saved: Boolean) {
        require(bucket in setOf("watchlist", "favorites"))
        val items = readItems(bucket).filterNot { cinemaMediaKey(it) == cinemaMediaKey(media) }.toMutableList()
        if (saved) items.add(0, media)
        val array = JSONArray()
        items.take(2000).forEach {
            array.put(JSONObject().put("id", it.id).put("type", it.type.name).put("title", it.title)
                .put("originalTitle", it.originalTitle).put("poster", it.posterPath).put("backdrop", it.backdropPath)
                .put("date", it.date).put("vote", it.vote.takeIf(Double::isFinite) ?: 0.0).put("backendId", it.backendId))
        }
        prefs.edit().putString(prefix + bucket, array.toString()).apply()
    }
    fun note(media: MediaItem): String = prefs.getString(prefix + "note:" + cinemaMediaKey(media), "").orEmpty()
    fun saveNote(media: MediaItem, text: String) { prefs.edit().putString(prefix + "note:" + cinemaMediaKey(media), text.take(2000)).apply() }
    fun seen(media: MediaItem): Boolean = prefs.getBoolean(prefix + "seen:" + cinemaMediaKey(media), false)
    fun markSeen(media: MediaItem, value: Boolean) { prefs.edit().putBoolean(prefix + "seen:" + cinemaMediaKey(media), value).apply(); setSaved("seenItems",media,value) }
    fun seenItems():List<MediaItem> = (readItems("seenItems") + saved() + favorites()).distinctBy(::cinemaMediaKey).filter(::seen)
    fun hideSpoilers(): Boolean = prefs.getBoolean(prefix + "hideSpoilers", true)
    fun setHideSpoilers(value: Boolean) { prefs.edit().putBoolean(prefix + "hideSpoilers", value).apply() }

    private fun readItems(bucket: String): List<MediaItem> {
        val arr = runCatching { JSONArray(prefs.getString(prefix + bucket, "[]")) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = runCatching { MediaType.valueOf(o.optString("type")) }.getOrNull() ?: continue
                val title = o.optString("title")
                if (title.isBlank()) continue
                add(MediaItem(o.optInt("id"), type, title, o.optString("originalTitle"),
                    posterPath = o.optString("poster").takeIf { it.isNotBlank() && it != "null" },
                    backdropPath = o.optString("backdrop").takeIf { it.isNotBlank() && it != "null" },
                    vote = o.optDouble("vote", 0.0), date = o.optString("date"),
                    backendId = o.optString("backendId").takeIf { it.isNotBlank() && it != "null" }))
            }
        }
    }
}
