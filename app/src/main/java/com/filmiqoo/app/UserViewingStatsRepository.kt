package com.filmiqoo.app

data class CinemaTasteShare(val key:String,val label:String,val fraction:Float)
data class UserViewingStats(
    val totalWatchMs:Long,val moviesWatched:Int,val seriesWatched:Int,val seriesStarted:Int,
    val episodesWatched:Int,val completedTitles:Int,val currentlyWatching:Int,val historyTitles:Int,
    val watchlistCount:Int,val favoriteCount:Int,val tasteSampleSize:Int,
    val genres:List<CinemaTasteShare>,val countries:List<CinemaTasteShare>,val legacyHistoryWithoutTime:Boolean,
    val completedMovieTmdbIds:Set<Int>?=null
)
class UserViewingStatsRepository(private val backend:BackendRepository){
    suspend fun load():UserViewingStats{
        val root=backend.getJson("/v1/library/viewing-stats",authorized=true)
        check(root.optInt("schemaVersion")==1){"آمار دقیق تماشا به نسخهٔ جدید سرور نیاز دارد."}
        fun count(key:String)=root.optInt(key).coerceAtLeast(0)
        fun shares(key:String)=buildList{
            val items=root.optJSONArray(key)?:return@buildList
            for(i in 0 until items.length()){val item=items.optJSONObject(i)?:continue
                val fraction=item.optDouble("fraction").takeIf{it.isFinite()&&it in 0.0..1.0}?:continue
                add(CinemaTasteShare(item.optString("key"),item.optString("label"),fraction.toFloat()))
            }
        }
        return UserViewingStats(root.optLong("totalWatchMs").coerceAtLeast(0),count("moviesWatched"),count("seriesWatched"),
            count("seriesStarted"),count("episodesWatched"),count("completedTitles"),count("currentlyWatching"),count("historyTitles"),
            count("watchlistCount"),count("favoriteCount"),count("tasteSampleSize"),shares("genres"),shares("countries"),
            root.optBoolean("legacyHistoryWithoutTime"),root.optJSONArray("completedMovieTmdbIds")?.let{values->
                buildSet{for(i in 0 until values.length()){
                    val value=values.opt(i) as? Number ?: continue;val id=value.toLong();val number=value.toDouble()
                    if(number.isFinite()&&number==id.toDouble()&&id in 1L..Int.MAX_VALUE.toLong())add(id.toInt())
                }}
            })
    }
}
