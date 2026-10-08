package com.filmiqoo.app

internal fun cinemaHubNumber(value:Long):String=value.coerceAtLeast(0).toString().map { if(it in '0'..'9')('۰'.code+(it-'0')).toChar() else it }.joinToString("")
/** Recorded playing time only; a seek/resume position is never used as elapsed viewing time. */
internal fun cinemaHubWatchDuration(recordedMs:Long):String {
    val safe=recordedMs.coerceAtLeast(0)
    if(safe==0L)return "۰ دقیقه"
    if(safe<60_000L)return "کمتر از یک دقیقه"
    val minutes=safe/60_000L;val hours=minutes/60L;val rest=minutes%60L
    return when { hours==0L->cinemaHubNumber(minutes)+" دقیقه";rest==0L->cinemaHubNumber(hours)+" ساعت";else->cinemaHubNumber(hours)+" ساعت و "+cinemaHubNumber(rest)+" دقیقه" }
}
internal fun cinemaHubTasteAvailable(stats:UserViewingStats):Boolean=stats.tasteSampleSize>=10&&(stats.genres.isNotEmpty()||stats.countries.isNotEmpty())
internal fun cinemaHubTasteShares(shares:List<CinemaTasteShare>):List<CinemaTasteShare> = shares.filter{it.label.isNotBlank()&&it.fraction.isFinite()&&it.fraction>0f&&it.fraction<=1f}.distinctBy{it.key}.sortedByDescending{it.fraction}
