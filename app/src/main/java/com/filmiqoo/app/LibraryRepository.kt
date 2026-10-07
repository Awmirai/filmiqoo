package com.filmiqoo.app

import org.json.JSONObject
import kotlinx.coroutines.ensureActive

data class MediaCollection(
    val id: String,
    val name: String,
    val description: String,
    val emoji: String,
    val visibility: String,
    val itemCount: Int,
    val posterUrl: String?
)

data class MediaCollectionDetail(
    val summary: MediaCollection,
    val items: List<MediaItem>
)

class LibraryRepository(
    private val backend: BackendRepository
) {
    var watchlistLimited:Boolean=false;private set
    var favoritesLimited:Boolean=false;private set
    suspend fun favorites():List<MediaItem> = completeMediaList("/v1/library/favorites",true)
    suspend fun watchlist():List<MediaItem> = completeMediaList("/v1/library/watchlist",false)

    private suspend fun completeMediaList(path:String,favorite:Boolean):List<MediaItem> {
        val account=backend.session.localAccountScope;val viewer=backend.viewerProfiles.activeId()
        val maturity=backend.viewerProfiles.active()?.maturityLevel
        fun limited(value:Boolean){if(favorite)favoritesLimited=value else watchlistLimited=value}
        fun ensureScope(){
            if(account!=backend.session.localAccountScope||viewer!=backend.viewerProfiles.activeId()||maturity!=backend.viewerProfiles.active()?.maturityLevel)
                throw kotlinx.coroutines.CancellationException("library account/profile scope changed")
        }
        val items=LinkedHashMap<String,MediaItem>();val sourceKeys=HashSet<String>();limited(false)
        for(page in 1..500){
            kotlinx.coroutines.currentCoroutineContext().ensureActive();ensureScope()
            val root=backend.getJson(path+"?page="+page,authorized=true)
            kotlinx.coroutines.currentCoroutineContext().ensureActive();ensureScope()
            val array=root.optJSONArray("items") ?: error("پاسخ فهرست کامل نیست؛ دوباره تلاش کن.")
            if(root.optInt("libraryVersion")<1){
                check(page==1){"دریافت کامل فهرست به نسخهٔ جدید سرور نیاز دارد."}
                limited(true);return parseMediaList(root)
            }
            check(root.optInt("page")==page){"صفحهٔ بعدی فهرست معتبر نیست؛ دوباره تلاش کن."}
            val more=root.opt("hasMore") as? Boolean ?: error("وضعیت ادامهٔ فهرست دریافت نشد.")
            val parsed=buildList{for(i in 0 until array.length()){
                val row=array.optJSONObject(i) ?: error("بخشی از اطلاعات فهرست معتبر نیست؛ دوباره تلاش کن.")
                add(parseDiscoveryTitle(row,null,true)?.media ?: error("بخشی از اطلاعات فهرست معتبر نیست؛ دوباره تلاش کن."))
            }};val before=sourceKeys.size
            parsed.forEach{sourceKeys.add(it.key)}
            parsed.forEach{items[cinemaMediaKey(it)]=it}
            if(!more)return items.values.toList()
            check(array.length()>0&&parsed.isNotEmpty()&&sourceKeys.size>before){"صفحهٔ بعدی فهرست معتبر نیست؛ دوباره تلاش کن."}
        }
        error("فهرست از سقف دریافت یک‌باره بزرگ‌تر است؛ تمام موارد دریافت نشده‌اند.")
    }

    suspend fun toggleWatchlist(mediaId:String): Boolean =
        backend.postJson(
            "/v1/library/watchlist/"+mediaId+"/toggle",
            JSONObject(),
            authorized=true
        ).optBoolean("inWatchlist")

    suspend fun collections(): List<MediaCollection> {
        val root=backend.getJson("/v1/library/collections",authorized=true)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(parseCollection(x))
            }
        }
    }

    suspend fun createCollection(
        name:String,
        description:String="",
        emoji:String="🎬",
        visibility:String="private"
    ): MediaCollection {
        val x=backend.postJson(
            "/v1/library/collections",
            JSONObject()
                .put("name",name)
                .put("description",description)
                .put("emoji",emoji)
                .put("visibility",visibility),
            authorized=true
        )
        return parseCollection(x)
    }

    suspend fun collection(id:String): MediaCollectionDetail {
        val root=backend.getJson("/v1/library/collections/"+id,authorized=true)
        val summary=MediaCollection(
            id=root.optString("id"),
            name=root.optString("name"),
            description=root.optString("description"),
            emoji=root.optString("emoji","🎬"),
            visibility=root.optString("visibility","private"),
            itemCount=root.optInt("itemCount"),
            posterUrl=null
        )
        return MediaCollectionDetail(
            summary=summary,
            items=parseMediaArray(root.optJSONArray("items"))
        )
    }

    suspend fun toggleCollectionItem(collectionId:String,mediaId:String):Boolean =
        backend.postJson(
            "/v1/library/collections/"+collectionId+"/items/"+mediaId+"/toggle",
            JSONObject(),
            authorized=true
        ).optBoolean("included")

    suspend fun deleteCollection(id:String):Boolean =
        backend.postJson(
            "/v1/library/collections/"+id+"/delete",
            JSONObject(),
            authorized=true
        ).optBoolean("deleted")

    private fun parseMediaList(root:JSONObject):List<MediaItem> =
        parseMediaArray(root.optJSONArray("items"))

    private fun parseMediaArray(arr:org.json.JSONArray?):List<MediaItem> = parseTitles(arr,null,true).map { it.media }

    private fun parseCollection(x:JSONObject)=MediaCollection(
        id=x.optString("id"),
        name=x.optString("name"),
        description=x.optString("description"),
        emoji=x.optString("emoji","🎬"),
        visibility=x.optString("visibility","private"),
        itemCount=x.optInt("itemCount"),
        posterUrl=x.optString("posterUrl").takeIf(String::isNotBlank)
    )
}
