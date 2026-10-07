package com.filmiqoo.app

import java.time.LocalDate
import java.util.Locale

enum class DiscoverySort { POPULAR, NEWEST, RATING, VOTES }
enum class DiscoverySection { TRENDING, POPULAR, NEW, ACCLAIMED, HIDDEN_GEMS, TOP_RATED, AIRING, COMPLETED, MINISERIES, DUBBED, SUBTITLED }

data class DiscoveryFilters(
    val genreId:Int?=null, val yearFrom:Int?=null, val yearTo:Int?=null,
    val minRating:Double=0.0, val language:String?=null, val country:String?=null,
    val runtimeMax:Int?=null, val status:String?=null, val sort:DiscoverySort=DiscoverySort.POPULAR,
    val persianDubbedOnly:Boolean=false, val persianSubtitleOnly:Boolean=false
) {
    init {
        require(genreId==null || genreId>0)
        require(yearFrom==null || yearFrom in 1000..9999)
        require(yearTo==null || yearTo in 1000..9999)
        require(yearFrom==null || yearTo==null || yearFrom<=yearTo)
        require(minRating.isFinite() && minRating in 0.0..10.0)
        require(runtimeMax==null || runtimeMax in 1..1440)
        require(language==null || language.matches(Regex("[A-Za-z]{2}")))
        require(country==null || country.matches(Regex("[A-Za-z]{2}")))
        require(status==null || discoveryStatusCode(status)!=null) { "Unknown series status" }
    }
}
internal fun discoveryStatusCode(status:String):String?=when(status.trim().lowercase(Locale.ROOT)) {
    "returning series","returning","airing","0" -> "0"
    "planned","1" -> "1"
    "in production","2" -> "2"
    "ended","3" -> "3"
    "canceled","cancelled","4" -> "4"
    "pilot","5" -> "5"
    else -> null
}
internal data class DiscoveryQuery(val path:String,val parameters:Map<String,String>,val empty:Boolean=false)

/** Filters are upstream queries, never invented facts on a result card. */
internal fun discoveryQuery(type:MediaType,section:DiscoverySection,filters:DiscoveryFilters,page:Int,today:LocalDate=LocalDate.now()):DiscoveryQuery {
    require(page in 1..500)
    require(type==MediaType.TV || section !in setOf(DiscoverySection.AIRING,DiscoverySection.COMPLETED,DiscoverySection.MINISERIES))
    require(type==MediaType.TV || filters.status==null)
    val namespace=if(type==MediaType.MOVIE) "movie" else "tv"
    val locale=if(filters.country.equals("IR",true)||filters.language.equals("fa",true)) "fa-IR" else "en-US"
    if(section==DiscoverySection.TRENDING && filters==DiscoveryFilters())
        return DiscoveryQuery("trending/$namespace/week",mapOf("language" to locale,"page" to page.toString()))
    require(section!=DiscoverySection.TRENDING) { "برای استفاده از فیلترها، بخش کشف یا محبوب‌ترین‌ها را انتخاب کن." }
    require(section !in setOf(DiscoverySection.DUBBED,DiscoverySection.SUBTITLED) && !filters.persianDubbedOnly && !filters.persianSubtitleOnly) { "Audio/subtitle availability uses the local catalog" }
    val dateKey=if(type==MediaType.MOVIE) "primary_release_date" else "first_air_date"
    var earliest=filters.yearFrom?.let { LocalDate.of(it,1,1) }
    val latest=filters.yearTo?.let { LocalDate.of(it,12,31).coerceAtMost(today) } ?: today
    if(section==DiscoverySection.NEW) earliest=maxOf(earliest ?: today.minusYears(1),today.minusYears(1))
    val parameters=linkedMapOf("language" to locale,"include_adult" to "false","page" to page.toString(),"$dateKey.lte" to latest.toString())
    earliest?.let { parameters["$dateKey.gte"]=it.toString() }
    filters.genreId?.let { parameters["with_genres"]=it.toString() }
    filters.language?.let { parameters["with_original_language"]=it.lowercase(Locale.ROOT) }
    filters.country?.let { parameters["with_origin_country"]=it.uppercase(Locale.ROOT) }
    filters.runtimeMax?.let { parameters["with_runtime.lte"]=it.toString() }
    filters.status?.let { parameters["with_status"]=discoveryStatusCode(it)!! }
    var rating=filters.minRating
    var sort=filters.sort
    when(section) {
        DiscoverySection.NEW -> if(sort==DiscoverySort.POPULAR) sort=DiscoverySort.NEWEST
        DiscoverySection.ACCLAIMED -> { rating=maxOf(rating,7.0);parameters["vote_count.gte"]="200";if(sort==DiscoverySort.POPULAR)sort=DiscoverySort.VOTES }
        DiscoverySection.HIDDEN_GEMS -> { rating=maxOf(rating,7.2);parameters["vote_count.gte"]="100";parameters["vote_count.lte"]="2500" }
        DiscoverySection.TOP_RATED -> { parameters["vote_count.gte"]="200";sort=DiscoverySort.RATING }
        DiscoverySection.AIRING -> { parameters["with_status"]="0";parameters["air_date.gte"]=today.minusDays(7).toString();parameters["air_date.lte"]=today.plusDays(7).toString();parameters["timezone"]="Asia/Tehran" }
        DiscoverySection.COMPLETED -> parameters["with_status"]="3|4"
        DiscoverySection.MINISERIES -> parameters["with_type"]="2"
        else -> Unit
    }
    val statusConflict=filters.status!=null && when(section) {
        DiscoverySection.AIRING -> discoveryStatusCode(filters.status)!="0"
        DiscoverySection.COMPLETED -> discoveryStatusCode(filters.status) !in setOf("3","4")
        else -> false
    }
    if(filters.status!=null && section==DiscoverySection.COMPLETED) parameters["with_status"]=discoveryStatusCode(filters.status)!!
    if(rating>0) parameters["vote_average.gte"]=rating.toString()
    if(sort==DiscoverySort.RATING && "vote_count.gte" !in parameters) parameters["vote_count.gte"]="200"
    parameters["sort_by"]=when(sort) { DiscoverySort.POPULAR -> "popularity.desc";DiscoverySort.NEWEST -> "$dateKey.desc";DiscoverySort.RATING -> "vote_average.desc";DiscoverySort.VOTES -> "vote_count.desc" }
    return DiscoveryQuery("discover/$namespace",parameters,statusConflict || (earliest!=null && earliest>latest))
}
