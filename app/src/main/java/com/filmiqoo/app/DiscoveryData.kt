package com.filmiqoo.app

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

data class DiscoveryTitle(val media:MediaItem,val genreIds:List<Int> = media.genreIds,val voteCount:Int=0,
    val originalLanguage:String=media.originalLanguage,val originCountries:List<String> = media.originCountries,
    val runtimeMinutes:Int?=media.runtimeMinutes,val status:String?=media.seriesStatus,val seasonCount:Int?=media.seasonCount,
    val seriesType:String?=null,val episodeCount:Int?=media.episodeCount,val isPersianDubbed:Boolean=media.hasPersianDub,
    val hasPersianSubtitle:Boolean=media.hasPersianSubtitle,val dubbedEpisodeCount:Int?=media.dubbedEpisodeCount,
    val availableEpisodeCount:Int?=media.availableEpisodeCount) { val hasPersianDub:Boolean get()=isPersianDubbed }
data class DiscoveryPage(val items:List<DiscoveryTitle>,val page:Int,val totalPages:Int) { val hasMore:Boolean get()=page<totalPages.coerceAtMost(500) }
data class DiscoveryGenre(val id:Int,val name:String)
data class DiscoveryCountry(val code:String,val name:String,val englishName:String)
data class DiscoveryLanguage(val code:String,val name:String,val englishName:String)
data class DiscoveryPerson(val id:Int,val name:String,val profilePath:String?,val knownFor:List<DiscoveryTitle> = emptyList())
data class DiscoverySearchPage(val titles:List<DiscoveryTitle>,val people:List<DiscoveryPerson>,val page:Int,val totalPages:Int,
    val catalogAvailable:Boolean=true,val metadataAvailable:Boolean=true) { val hasMore:Boolean get()=page<totalPages.coerceAtMost(500) }
class DiscoveryCapabilityException(message:String="این فیلتر به ارتقای سرویس کاتالوگ نیاز دارد. فیلتر را بردار یا پس از ارتقای سرور دوباره تلاش کن."):IllegalStateException(message)

fun mergeDiscoveryTitles(catalog:List<MediaItem>,metadata:List<DiscoveryTitle>):List<DiscoveryTitle> {
    val byIdentity=catalog.filter { it.id>0 }.associateBy { it.type to it.id };val consumed=mutableSetOf<String>()
    val enriched=metadata.map { entry ->
        val local=byIdentity[entry.media.type to entry.media.id] ?: return@map entry
        consumed.add(local.key)
        val genres=entry.genreIds.ifEmpty { local.genreIds };val language=entry.originalLanguage.ifBlank { local.originalLanguage }
        val countries=(entry.originCountries+local.originCountries).distinct();val runtime=entry.runtimeMinutes?:local.runtimeMinutes
        val status=entry.status?:local.seriesStatus;val seasons=entry.seasonCount?:local.seasonCount;val episodes=entry.episodeCount?:local.episodeCount
        val merged=entry.media.copy(backendId=local.backendId,mediaVersionId=local.mediaVersionId,streamReady=local.streamReady,
            quality=local.quality,hasPersianDub=local.hasPersianDub,hasPersianSubtitle=local.hasPersianSubtitle,
            dubbedEpisodeCount=local.dubbedEpisodeCount,availableEpisodeCount=local.availableEpisodeCount,
            genreIds=genres,originalLanguage=language,originCountries=countries,runtimeMinutes=runtime,seriesStatus=status,seasonCount=seasons,episodeCount=episodes)
        entry.copy(media=merged,isPersianDubbed=local.hasPersianDub,hasPersianSubtitle=local.hasPersianSubtitle,
            dubbedEpisodeCount=local.dubbedEpisodeCount,availableEpisodeCount=local.availableEpisodeCount,
            genreIds=genres,originalLanguage=language,originCountries=countries,runtimeMinutes=runtime,status=status,seasonCount=seasons,episodeCount=episodes)
    }
    return (enriched+catalog.filter { it.key !in consumed }.map { DiscoveryTitle(it) }).distinctBy { cinemaMediaKey(it.media) }
}

class DiscoveryRepository(context:Context,private val backend:BackendRepository=BackendRepository(context.applicationContext)) {
    private val appContext=context.applicationContext;private val resolver=TmdbRepository(appContext)
    private suspend fun metadata(path:String,params:Map<String,String>):JSONObject {
        val key=backend.session.baseUrl+"|"+path+"|"+params.toSortedMap()
        return requestLocks[(key.hashCode() and Int.MAX_VALUE)%requestLocks.size].withLock {
            synchronized(cache) { cache[key]?.takeIf { SystemClock.elapsedRealtime()-it.at<300_000 }?.let { return@withLock JSONObject(it.body) } }
            val result=try { resolver.discoveryMetadata(path,params,backend) } catch(failure:BackendHttpException) {
                if(failure.reason=="unsupported TMDB metadata path"&&!path.startsWith("trending/"))throw DiscoveryCapabilityException()
                throw failure
            }
            val unsupported=params.keys.filter { it !in legacyParameters }
            if(unsupported.isNotEmpty()) {
                val applied=result.optJSONObject("_filmiqooDiscovery")?.optJSONObject("appliedParameters")
                if(unsupported.any { applied?.optString(it)!=params[it] }) throw DiscoveryCapabilityException()
            }
            // An empty discovery response must be refreshable when new content appears.
            if(result.optJSONArray("results")?.length()!=0)
                synchronized(cache) { cache[key]=Cached(SystemClock.elapsedRealtime(),result.toString());while(cache.size>64)cache.remove(cache.keys.first()) }
            result
        }
    }
    suspend fun page(type:MediaType,section:DiscoverySection,filters:DiscoveryFilters=DiscoveryFilters(),page:Int=1):DiscoveryPage {
        require(page in 1..500)
        require(type!=MediaType.MOVIE || filters.status==null && section !in setOf(DiscoverySection.AIRING,DiscoverySection.COMPLETED,DiscoverySection.MINISERIES))
        require(section!=DiscoverySection.TRENDING || filters==DiscoveryFilters())
        if(section in setOf(DiscoverySection.DUBBED,DiscoverySection.SUBTITLED)||filters.persianDubbedOnly||filters.persianSubtitleOnly)return localPage(type,section,filters,page)
        if(section==DiscoverySection.TOP_RATED&&page>13)return DiscoveryPage(emptyList(),page,13)
        val query=discoveryQuery(type,section,filters,page);if(query.empty)return DiscoveryPage(emptyList(),page,0)
        val raw=try { metadata(query.path,query.parameters) } catch(failure:BackendHttpException) {
            if(section==DiscoverySection.TRENDING&&failure.reason=="unsupported TMDB metadata path")metadata("trending/all/week",query.parameters) else throw failure
        }
        var items=parseTitles(raw.optJSONArray("results"),type,false).filter { it.media.type==type }
        var total=raw.optInt("total_pages",page).coerceIn(0,500)
        if(section==DiscoverySection.TOP_RATED){items=items.take((250-(page-1)*20).coerceAtLeast(0));total=total.coerceAtMost(13)}
        return DiscoveryPage(items,page,total)
    }
    suspend fun popular(type:MediaType,page:Int=1)=page(type,DiscoverySection.POPULAR,page=page)
    private suspend fun localPage(type:MediaType,section:DiscoverySection,filters:DiscoveryFilters,page:Int):DiscoveryPage {
        val params=linkedMapOf("type" to if(type==MediaType.MOVIE)"movie" else "series","page" to page.toString(),"limit" to "20",
            "sort" to filters.sort.name.lowercase(Locale.ROOT),"section" to section.name.lowercase(Locale.ROOT))
        filters.genreId?.let{params["genreId"]=it.toString()};filters.yearFrom?.let{params["yearFrom"]=it.toString()};filters.yearTo?.let{params["yearTo"]=it.toString()}
        if(filters.minRating>0)params["minRating"]=filters.minRating.toString()
        filters.language?.let{params["language"]=it.lowercase(Locale.ROOT)};filters.country?.let{params["country"]=it.uppercase(Locale.ROOT)}
        filters.runtimeMax?.let{params["runtimeMax"]=it.toString()};filters.status?.let{params["status"]=discoveryStatusCode(it)!!}
        if(filters.persianDubbedOnly||section==DiscoverySection.DUBBED)params["persianDubbedOnly"]="true"
        if(filters.persianSubtitleOnly||section==DiscoverySection.SUBTITLED)params["persianSubtitleOnly"]="true"
        val raw=try { backend.getJson("/v1/catalog/discovery?"+params.entries.joinToString("&"){enc(it.key)+"="+enc(it.value)},authorized=backend.session.isLoggedIn) }
        catch(failure:BackendHttpException) { if(failure.status==404)throw DiscoveryCapabilityException();throw failure }
        if(raw.optInt("discoveryVersion")<1)throw DiscoveryCapabilityException()
        return DiscoveryPage(parseTitles(raw.optJSONArray("items"),type,true),page,raw.optInt("totalPages").coerceIn(0,500))
    }
    suspend fun recentCatalog(type:MediaType):List<DiscoveryTitle> {
        val raw=backend.getJson("/v1/catalog/home",authorized=backend.session.isLoggedIn)
        return parseTitles(raw.optJSONArray("items"),null,true).filter{it.media.type==type}
    }
    suspend fun hero(media:MediaItem):DiscoveryTitle?=cinemaOptional {
        val namespace=if(media.type==MediaType.MOVIE)"movie/" else "tv/"
        val result=metadata(namespace+media.id,mapOf("language" to "en-US"))
        val title=parseDiscoveryTitle(result,media.type,false) ?: return@cinemaOptional null
        val merged=mergeDiscoveryTitles(listOf(media),listOf(title)).first()
        if(merged.originCountries.contains("IR")||merged.originalLanguage=="fa"){
            val localized=metadata(namespace+media.id,mapOf("language" to "fa-IR"));val persian=parseDiscoveryTitle(localized,media.type,false)
            if(persian!=null)return@cinemaOptional merged.copy(media=merged.media.copy(title=persian.media.title,overview=persian.media.overview.ifBlank{merged.media.overview}))
        };merged
    }
    suspend fun genres(type:MediaType):List<DiscoveryGenre> {
        val raw=metadata("genre/"+(if(type==MediaType.MOVIE)"movie/list" else "tv/list"),mapOf("language" to "fa-IR"));val arr=raw.optJSONArray("genres") ?: return emptyList()
        return (0 until arr.length()).mapNotNull{arr.optJSONObject(it)?.let{x->val id=x.optInt("id");val name=clean(x.optString("name"));if(id>0&&name!=null)DiscoveryGenre(id,name)else null}}.distinctBy{it.id}
    }
    suspend fun countries():List<DiscoveryCountry> {
        val arr=metadata("configuration/countries",mapOf("language" to "fa-IR")).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull{arr.optJSONObject(it)?.let{x->val code=clean(x.optString("iso_3166_1"))?.uppercase(Locale.ROOT) ?: return@let null
            val english=clean(x.optString("english_name")).orEmpty();DiscoveryCountry(code,clean(x.optString("native_name")) ?: english,english)}}.distinctBy{it.code}.sortedBy{it.name}
    }
    suspend fun languages():List<DiscoveryLanguage> {
        val arr=metadata("configuration/languages",emptyMap()).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull{arr.optJSONObject(it)?.let{x->val code=clean(x.optString("iso_639_1"))?.lowercase(Locale.ROOT) ?: return@let null
            val english=clean(x.optString("english_name")).orEmpty();DiscoveryLanguage(code,clean(x.optString("name")) ?: english,english)}}.distinctBy{it.code}.sortedBy{it.englishName}
    }
    suspend fun searchMetadata(query:String,page:Int=1):DiscoverySearchPage {
        require(page in 1..500);require(query.trim().length<=120)
        if(query.isBlank())return DiscoverySearchPage(emptyList(),emptyList(),page,0,catalogAvailable=false)
        val raw=metadata("search/multi",mapOf("query" to query.trim(),"language" to "en-US","include_adult" to "false","page" to page.toString()));val arr=raw.optJSONArray("results")
        val people=buildList {if(arr!=null)for(i in 0 until arr.length()){
            val x=arr.optJSONObject(i) ?: continue;if(x.optString("media_type")!="person"||x.optBoolean("adult"))continue
            val id=x.optInt("id");val name=clean(x.optString("name")) ?: continue
            if(id>0)add(DiscoveryPerson(id,name,clean(x.optString("profile_path")),parseTitles(x.optJSONArray("known_for"),null,false)))
        }}
        return DiscoverySearchPage(parseTitles(arr,null,false),people,page,raw.optInt("total_pages",page).coerceIn(0,500),catalogAvailable=false)
    }
    suspend fun search(query:String,page:Int=1):DiscoverySearchPage=supervisorScope {
        val catalogJob=async{cinemaOptional{UniversalSearchRepository(appContext,backend).search(query).media}}
        val metadataJob=async{cinemaOptional{searchMetadata(query,page)}};val catalog=catalogJob.await();val upstream=metadataJob.await()
        if(catalog==null&&upstream==null)error("جستجو دریافت نشد؛ اتصال را بررسی و دوباره تلاش کن.")
        DiscoverySearchPage(mergeDiscoveryTitles(catalog.orEmpty(),upstream?.titles.orEmpty()),upstream?.people.orEmpty(),page,upstream?.totalPages ?: page,catalog!=null,upstream!=null)
    }
    suspend fun collections(seed:MediaItem):List<FranchiseInfo> {
        if(seed.type!=MediaType.MOVIE||seed.id<=0)return emptyList()
        val id=metadata("movie/"+seed.id,mapOf("language" to "en-US")).optJSONObject("belongs_to_collection")?.optInt("id") ?: return emptyList();if(id<=0)return emptyList()
        val c=metadata("collection/"+id,mapOf("language" to "en-US"))
        return listOf(FranchiseInfo(id,clean(c.optString("name")).orEmpty(),clean(c.optString("poster_path")),clean(c.optString("backdrop_path")),parseTitles(c.optJSONArray("parts"),MediaType.MOVIE,false).map{it.media}.sortedBy{it.date}))
    }
    private fun enc(value:String)=URLEncoder.encode(value,"UTF-8")
    private data class Cached(val at:Long,val body:String)
    companion object {
        private val cache=LinkedHashMap<String,Cached>(64,.75f,true);private val requestLocks=Array(16){Mutex()}
        private val legacyParameters=setOf("primary_release_date.gte","primary_release_date.lte","first_air_date.gte","first_air_date.lte","vote_count.gte",
            "language","page","sort_by","with_origin_country","include_adult","with_original_language","with_genres","query","append_to_response")
    }
}
internal fun discoveryClean(value:String?):String?=value?.trim()?.takeUnless{it.isBlank()||it.equals("null",true)}
private fun clean(value:String?):String?=discoveryClean(value)
private fun integers(arr:JSONArray?):List<Int> = buildList{if(arr!=null)for(i in 0 until arr.length()){val id=arr.optInt(i);if(id>0)add(id)}}.distinct()
private fun strings(arr:JSONArray?):List<String> = buildList{if(arr!=null)for(i in 0 until arr.length())clean(arr.optString(i))?.let(::add)}.distinct()
private fun positive(o:JSONObject,key:String):Int?=if(o.isNull(key))null else o.optInt(key).takeIf{it>0}
internal fun parseDiscoveryTitle(o:JSONObject,fallback:MediaType?,local:Boolean):DiscoveryTitle? {
    if(o.optBoolean("adult"))return null
    val type=when(o.optString(if(local)"kind" else "media_type")){"movie"->MediaType.MOVIE;"tv","series","anime"->MediaType.TV;"person"->return null;else->fallback ?: return null}
    val id=if(local)positive(o,"tmdbId") ?: 0 else o.optInt("id");val backendId=if(local)clean(o.optString("id"))else null
    if(id<=0&&backendId==null)return null
    val language=clean(o.optString(if(local)"originalLanguage" else "original_language")).orEmpty()
    val countries=(strings(o.optJSONArray(if(local)"originCountries" else "origin_country"))+buildList{
        val production=o.optJSONArray("production_countries");if(production!=null)for(i in 0 until production.length())clean(production.optJSONObject(i)?.optString("iso_3166_1"))?.let(::add)
    }).map{it.uppercase(Locale.ROOT)}.distinct()
    val genreIds=integers(o.optJSONArray(if(local)"genreIds" else "genre_ids")).ifEmpty{buildList{
        val genres=o.optJSONArray("genres");if(genres!=null)for(i in 0 until genres.length())genres.optJSONObject(i)?.optInt("id")?.takeIf{it>0}?.let(::add)
    }.distinct()}
    val original=clean(o.optString(if(local)"originalTitle" else if(type==MediaType.MOVIE)"original_title" else "original_name")).orEmpty()
    val display=clean(o.optString(if(local||type==MediaType.MOVIE)"title" else "name")).orEmpty()
    val title=if(language=="fa")original.ifBlank{display}else display.ifBlank{original};if(title.isBlank())return null
    val runtime=positive(o,if(local)"runtimeMinutes" else "runtime") ?: o.optJSONArray("episode_run_time")?.optInt(0)?.takeIf{it>0}
    val status=clean(o.optString(if(local)"seriesStatus" else "status"));val seasons=positive(o,if(local)"seasonCount" else "number_of_seasons");val episodes=positive(o,if(local)"episodeCount" else "number_of_episodes")
    val dubbed=o.optBoolean("hasPersianDub");val sub=o.optBoolean("hasPersianSubtitle")
    val dubbedCount=if(o.isNull("dubbedEpisodeCount"))null else o.optInt("dubbedEpisodeCount").coerceAtLeast(0)
    val available=if(o.isNull("availableEpisodeCount"))null else o.optInt("availableEpisodeCount").coerceAtLeast(0)
    val vote=o.optDouble(if(local)"rating" else "vote_average").takeIf{it.isFinite()&&it in 0.0..10.0} ?: 0.0
    val media=MediaItem(id,type,title,original,clean(o.optString("overview")).orEmpty(),clean(o.optString(if(local)"posterUrl" else "poster_path")),
        clean(o.optString(if(local)"backdropUrl" else "backdrop_path")),vote,if(local)positive(o,"year")?.toString().orEmpty()else clean(o.optString(if(type==MediaType.MOVIE)"release_date" else "first_air_date")).orEmpty(),
        o.optDouble("popularity").takeIf(Double::isFinite) ?: 0.0,backendId,clean(o.optString("mediaVersionId")),local&&o.optBoolean("streamReady"),clean(o.optString("quality")).orEmpty(),
        hasPersianDub=dubbed,hasPersianSubtitle=sub,dubbedEpisodeCount=dubbedCount,availableEpisodeCount=available,genreIds=genreIds,originalLanguage=language,originCountries=countries,runtimeMinutes=runtime,seriesStatus=status,seasonCount=seasons,episodeCount=episodes)
    return DiscoveryTitle(media,genreIds,o.optInt(if(local)"voteCount" else "vote_count").coerceAtLeast(0),language,countries,runtime,status,seasons,clean(o.optString(if(local)"seriesType" else "type")),episodes,dubbed,sub,dubbedCount,available)
}
internal fun cinemaPreferPlayableCandidate(current:MediaItem?,candidate:MediaItem):Boolean {
    fun playable(media:MediaItem)=media.streamReady&&!media.backendId.isNullOrBlank()&&!media.mediaVersionId.isNullOrBlank()
    return current==null||!playable(current)&&playable(candidate)
}
internal fun parseTitles(arr:JSONArray?,fallback:MediaType?,local:Boolean):List<DiscoveryTitle> {
    val entries=buildList{if(arr!=null)for(i in 0 until arr.length())arr.optJSONObject(i)?.let{parseDiscoveryTitle(it,fallback,local)?.let(::add)}}
    if(!local)return entries.distinctBy{cinemaMediaKey(it.media)}
    val selected=LinkedHashMap<String,DiscoveryTitle>()
    entries.forEach{entry->val key=cinemaMediaKey(entry.media);if(cinemaPreferPlayableCandidate(selected[key]?.media,entry.media))selected[key]=entry}
    return selected.values.toList()
}
