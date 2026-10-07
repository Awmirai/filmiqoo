package com.filmiqoo.app
import org.json.JSONObject

internal fun platformVersionFromJson(x:JSONObject)=PlatformVersion(
 id=discoveryClean(x,"id"),quality=discoveryClean(x,"quality"),codec=discoveryClean(x,"codec"),hdr=discoveryClean(x,"hdr"),fileSizeBytes=x.optLong("fileSizeBytes"),durationMs=x.optLong("durationMs"),streamReady=x.optBoolean("streamReady"),preferred=x.optBoolean("preferred"),
 audioTracks=cinemaTrackLabels(x.optJSONArray("audioTracks")),subtitleTracks=cinemaTrackLabels(x.optJSONArray("subtitleTracks")),isDubbed=x.optBoolean("isDubbed"),isPersianDubbed=x.optBoolean("isPersianDubbed"),hasPersianSubtitle=x.optBoolean("hasPersianSubtitle"),
 detectionSource=discoveryClean(x,"detectionSource"),detectionConfidence=discoveryClean(x,"detectionConfidence").takeIf{it in setOf("HIGH","MEDIUM","LOW","NONE")} ?: "NONE",detectionEvidence=discoveryStrings(x.optJSONArray("detectionEvidence")))
internal fun platformEpisodeVersions(x:JSONObject):List<PlatformVersion> = buildList{
 val arr=x.optJSONArray("versions") ?: return@buildList;for(i in 0 until arr.length()){val item=arr.optJSONObject(i) ?: continue;add(platformVersionFromJson(item))}}
internal fun mediaCatalogExtras(media:MediaItem,o:JSONObject):MediaItem=media.copy(
 hasPersianDub=o.optBoolean("hasPersianDub"),hasPersianSubtitle=o.optBoolean("hasPersianSubtitle"),dubbedEpisodeCount=discoveryOptionalInt(o,"dubbedEpisodeCount"),availableEpisodeCount=discoveryOptionalInt(o,"availableEpisodeCount"),
 genreIds=discoveryInts(o.optJSONArray("genreIds")),originalLanguage=discoveryClean(o,"originalLanguage"),originCountries=discoveryStrings(o.optJSONArray("originCountries")),runtimeMinutes=discoveryOptionalInt(o,"runtimeMinutes")?.takeIf{it>0},
 seriesStatus=discoveryClean(o,"seriesStatus").takeIf(String::isNotBlank),seasonCount=discoveryOptionalInt(o,"seasonCount"),episodeCount=discoveryOptionalInt(o,"episodeCount"))
internal fun discoveryClean(o:JSONObject,key:String):String=discoveryClean(o.optString(key)).orEmpty()
internal fun discoveryOptionalInt(o:JSONObject,key:String):Int?=if(!o.has(key)||o.isNull(key))null else o.optInt(key).takeIf{it>=0}
internal fun discoveryInts(arr:org.json.JSONArray?):List<Int> = buildList{if(arr!=null)for(i in 0 until arr.length()){val value=arr.optInt(i);if(value>0)add(value)}}.distinct()
internal fun discoveryStrings(arr:org.json.JSONArray?):List<String> = buildList{if(arr!=null)for(i in 0 until arr.length()){val value=(arr.opt(i) as? String).orEmpty().trim();if(value.isNotBlank()&&value!="null")add(value)}}.distinct()

internal fun cinemaStoredMediaJson(media:MediaItem):JSONObject=JSONObject()
 .put("id",media.id).put("type",media.type.name).put("title",media.title).put("originalTitle",media.originalTitle)
 .put("poster",media.posterPath).put("backdrop",media.backdropPath).put("date",media.date).put("vote",media.vote.takeIf(Double::isFinite)?:0.0).put("backendId",media.backendId)
 .put("hasPersianDub",media.hasPersianDub).put("hasPersianSubtitle",media.hasPersianSubtitle).put("dubbedEpisodeCount",media.dubbedEpisodeCount).put("availableEpisodeCount",media.availableEpisodeCount)
 .put("genreIds",org.json.JSONArray(media.genreIds)).put("originCountries",org.json.JSONArray(media.originCountries)).put("originalLanguage",media.originalLanguage)
 .put("runtimeMinutes",media.runtimeMinutes).put("seriesStatus",media.seriesStatus).put("seasonCount",media.seasonCount).put("episodeCount",media.episodeCount)
