package com.filmiqoo.app

import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class EpisodeWatchState(
    val seasonId:String,val seasonNumber:Int,val episodeId:String,val episodeNumber:Int,
    val positionMs:Long,val durationMs:Long,val completed:Boolean,val progress:Float,
    val manualSeen:Boolean=false
)
data class SeriesWatchProgress(
    val mediaTitleId:String,val watchedCount:Long,val totalCount:Long,val progress:Float,
    val episodes:Map<String,EpisodeWatchState>,val manualMarksVersion:Int=0,val manualSeenCount:Long=0
)
class SeriesProgressRepository(private val backend:BackendRepository) {
    private data class Capability(val scope:String,val episodes:Set<String>,val seasons:Set<String>)
    private var capability:Capability?=null
    private fun scopeKey()=listOf(backend.session.localAccountScope,backend.session.baseUrl,
        backend.session.isLoggedIn,backend.viewerProfiles.activeId(),backend.viewerProfiles.active()?.maturityLevel).joinToString("|")
    private fun ensureScope(key:String){check(key==scopeKey()){"حساب یا پروفایل تغییر کرده است؛ وضعیت قسمت‌ها را دوباره دریافت کن."}}
    suspend fun load(mediaTitleId:String):SeriesWatchProgress {
        val key=scopeKey();capability=null
        val root=backend.getJsonScoped("/v1/watch/series/"+mediaTitleId+"/progress"){ensureScope(key)}
        currentCoroutineContext().ensureActive();ensureScope(key)
        check(root.optInt("manualMarksVersion")==1){"وضعیت امن قسمت‌ها به ارتقای سرور نیاز دارد؛ علامت دستی فعلاً در دسترس نیست."}
        check(root.optString("mediaTitleId")==mediaTitleId){"پاسخ وضعیت مربوط به این سریال نیست."}
        val arr=root.optJSONArray("items")?:error("وضعیت قسمت‌ها دریافت نشد.")
        val states=buildMap<String,EpisodeWatchState> {
            for(i in 0 until arr.length()){
                val x=arr.getJSONObject(i)
                val state=EpisodeWatchState(x.getString("seasonId"),x.getInt("seasonNumber"),x.getString("episodeId"),x.getInt("episodeNumber"),
                    x.getLong("positionMs"),x.getLong("durationMs"),x.getBoolean("completed"),x.getDouble("progress").toFloat().coerceIn(0f,1f),x.getBoolean("manualSeen"))
                check(state.episodeId.isNotBlank()&&state.seasonId.isNotBlank()&&!containsKey(state.episodeId)){"وضعیت قسمت معتبر نیست."}
                put(state.episodeId,state)
            }
        }
        ensureScope(key);capability=Capability(key,states.keys,states.values.map{it.seasonId}.toSet())
        return SeriesWatchProgress(mediaTitleId,root.getLong("watchedCount"),root.getLong("totalCount"),root.getDouble("progress").toFloat().coerceIn(0f,1f),states,1,root.getLong("manualSeenCount"))
    }
    private suspend fun mark(id:String,watched:Boolean,season:Boolean){
        currentCoroutineContext().ensureActive()
        val cap=checkNotNull(capability){"برای علامت دستی، ابتدا وضعیت امن قسمت‌ها را از سرور جدید دریافت کن."}
        ensureScope(cap.scope)
        check(id in if(season)cap.seasons else cap.episodes){"این قسمت یا فصل در وضعیت دریافت‌شده نیست."}
        val root=backend.postJsonScoped("/v1/watch/"+(if(season)"seasons/"else"episodes/")+id+"/status",
            JSONObject().put("watched",watched).put("manualMarksVersion",1)){ensureScope(cap.scope)}
        currentCoroutineContext().ensureActive();ensureScope(cap.scope)
        check(root.optInt("manualMarksVersion")==1){"پاسخ علامت دستی معتبر نیست."}
    }
    suspend fun setEpisodeWatched(episodeId:String,watched:Boolean)=mark(episodeId,watched,false)
    suspend fun setSeasonWatched(seasonId:String,watched:Boolean)=mark(seasonId,watched,true)
}
