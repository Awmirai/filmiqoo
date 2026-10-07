package com.filmiqoo.app
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Account/catalog/artwork are deterministic TEST FIXTURES, never production data. */
internal class ProductAudit090Fixture {
 val server=MockWebServer();val requests=CopyOnWriteArrayList<RecordedRequest>()
 val slowEntered=CountDownLatch(1);val slowRelease=CountDownLatch(1);@Volatile var offlineFailure=true
 var intercept:((RecordedRequest)->MockResponse?)?=null
 private val titles=ConcurrentHashMap<Int,String>();private val artBytes=ConcurrentHashMap<Int,ByteArray>()
 fun start(){server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=respond(r)};server.start()}
 fun base(mode:String="")=server.url(if(mode.isEmpty())"/"else"/$mode/").toString()
 fun close(){slowRelease.countDown();server.shutdown()}
 private fun json(body:JSONObject)=MockResponse().setHeader("Content-Type","application/json").setBody(body.toString())
 private fun envelope(items:JSONArray)=JSONObject().put("items",items)
 private fun art(index:Int):ByteArray=artBytes.getOrPut(index){
  val bitmap=Bitmap.createBitmap(480,720,Bitmap.Config.ARGB_8888)
  Canvas(bitmap).apply{val p=Paint(Paint.ANTI_ALIAS_FLAG);drawColor(Color.rgb(15+index%4*7,28+index%3*10,44+index%5*7));p.color=if(index%2==0)Color.rgb(210,143,66)else Color.rgb(65,156,177);drawCircle(340f,200f,165f,p);p.color=Color.rgb(20,27,40);drawRect(0f,440f,480f,720f,p);p.color=Color.WHITE;p.textSize=37f;drawText("CINEMA / QA",28f,615f,p);p.textSize=20f;drawText("DETERMINISTIC FIXTURE",28f,654f,p)}
  ByteArrayOutputStream().use{out->bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle();out.toByteArray()}
 }
 private fun metadata(tv:Boolean,country:String,index:Int,language:String?=null):JSONObject{
  val id=(country[0].code*100+country[1].code)*10+index+if(tv)0 else 500000
  val name=if(country=="IR")"داستان شهر $index"else"$country ${if(tv)"Series"else"Cinema"} $index"
  val image=server.url("/art/$index.png").toString();val lang=language?:when(country){"IR"->"fa";"TR"->"tr";"KR"->"ko";"IN"->"hi";else->"en"}
  return JSONObject().put("id",id).put("media_type",if(tv)"tv"else"movie").put("name",name).put("title",name).put("original_name",name).put("original_title",name)
   .put("poster_path",image).put("backdrop_path",image).put("origin_country",JSONArray().put(country)).put("production_countries",JSONArray().put(JSONObject().put("iso_3166_1",country)))
   .put("original_language",lang).put("genre_ids",JSONArray().put(18).put(9648)).put("genres",JSONArray().put(JSONObject().put("id",18).put("name","درام")).put(JSONObject().put("id",9648).put("name","معمایی")))
   .put("vote_average",8.1+index*.1).put("vote_count",600+index*40).put("popularity",30+index).put("release_date","2025-03-15").put("first_air_date","2025-03-15")
   .put("overview","در این دادهٔ آزمایشی، یک تصمیم کوچک مسیر زندگی چند نفر را تغییر می‌دهد. جزئیات برای بررسی چیدمان و خوانایی هستند.")
   .put("runtime",112).put("episode_run_time",JSONArray().put(48)).put("status",if(tv)"Returning Series"else"Released").put("number_of_seasons",if(tv)2 else 0).put("number_of_episodes",if(tv)16 else 0)
   .put("seasons",if(tv)JSONArray().put(JSONObject().put("id",id+1).put("season_number",1).put("name","فصل اول").put("episode_count",8).put("poster_path",image))else JSONArray())
   .put("credits",JSONObject().put("cast",JSONArray()).put("crew",JSONArray())).put("videos",JSONObject().put("results",JSONArray())).put("recommendations",JSONObject().put("results",JSONArray())).put("similar",JSONObject().put("results",JSONArray())).put("images",JSONObject().put("logos",JSONArray())).also{titles[id]=it.toString()}
 }
 private fun local(tv:Boolean,country:String,index:Int):JSONObject{
  val source=metadata(tv,country,index);val id="09000000-0000-4000-8000-"+source.getInt("id").toString().padStart(12,'0')
  return JSONObject().put("id",id).put("tmdbId",source.getInt("id")).put("kind",if(tv)"series"else"movie").put("title",source.getString("title")).put("originalTitle",source.getString("title"))
   .put("posterUrl",source.getString("poster_path")).put("backdropUrl",source.getString("backdrop_path")).put("overview",source.getString("overview")).put("year",2025).put("rating",source.getDouble("vote_average"))
   .put("genreIds",source.getJSONArray("genre_ids")).put("originalLanguage",source.getString("original_language")).put("originCountries",JSONArray().put(country)).put("streamReady",index==1)
   .put("mediaVersionId",if(index==1)"fixture-version-${if(tv)"series"else"movie"}-$country"else JSONObject.NULL).put("hasPersianDub",index==1).put("hasPersianSubtitle",index==1)
   .put("dubbedEpisodeCount",if(tv)if(index==1)3 else 0 else JSONObject.NULL).put("availableEpisodeCount",if(tv)if(index==1)4 else 0 else JSONObject.NULL).put("quality","1080p")
 }
 private fun metadataResponse(r:RecordedRequest,body:JSONObject):MockResponse{
  val applied=JSONObject();val url=requireNotNull(r.requestUrl);url.queryParameterNames.filter{it!="path"}.forEach{applied.put(it,url.queryParameter(it))}
  return json(body.put("_filmiqooDiscovery",JSONObject().put("version",1).put("provider","tmdb").put("appliedParameters",applied)))
 }
 private fun catalog()=JSONArray().apply{listOf("IR","TR","KR","IN","US","JP").forEach{put(local(false,it,1));put(local(true,it,1))}}
 private fun continueEntries()=JSONArray().apply{listOf(false,true).forEach{tv->val m=local(tv,if(tv)"KR"else"IR",1);put(JSONObject().put("media",m).put("mediaVersionId",m.getString("mediaVersionId")).put("positionMs",1_200_000).put("durationMs",if(tv)2_880_000 else 6_720_000).put("progress",if(tv).4166667 else .1785714).put("quality","1080p").put("episode",if(tv)JSONObject().put("id","qa-episode-3").put("seasonNumber",1).put("episodeNumber",3).put("name","تصمیم تازه")else JSONObject.NULL))}}
 private fun history()=JSONArray().apply{
  val ongoing=continueEntries();for(i in 0 until ongoing.length())put(ongoing.getJSONObject(i).put("completed",false).put("updatedAt","2026-10-07T10:00:00Z").put("streamReady",true))
  repeat(12){i->put(JSONObject().put("media",local(false,if(i<7)"FR"else"KR",i+1)).put("mediaVersionId","completed-fixture-$i").put("positionMs",6_000_000).put("durationMs",6_000_000).put("completed",true).put("updatedAt","2026-10-06T12:00:00Z").put("streamReady",false).put("isPersianDubbed",true).put("hasPersianSubtitle",true).put("quality","1080p"))}
 }
 private fun viewingStats()=JSONObject().put("schemaVersion",1).put("totalWatchMs",74_400_000).put("moviesWatched",12).put("seriesWatched",0).put("seriesStarted",1).put("episodesWatched",0).put("completedTitles",12).put("currentlyWatching",2).put("historyTitles",14).put("watchlistCount",2).put("favoriteCount",2).put("tasteSampleSize",12).put("legacyHistoryWithoutTime",false)
  .put("genres",JSONArray().put(JSONObject().put("key","18").put("label","درام").put("fraction",.5)).put(JSONObject().put("key","9648").put("label","معمایی").put("fraction",.5)))
  .put("countries",JSONArray().put(JSONObject().put("key","FR").put("label","فرانسه").put("fraction",7.0/12)).put(JSONObject().put("key","KR").put("label","کرهٔ جنوبی").put("fraction",5.0/12)))
 private fun version(id:String,dub:Boolean,preferred:Boolean,lang:String,duration:Long)=JSONObject().put("id",id).put("quality","1080p").put("codec","H.264").put("hdr","SDR").put("durationMs",duration).put("fileSizeBytes",400_000_000).put("streamReady",true).put("preferred",preferred).put("isDubbed",dub).put("isPersianDubbed",dub).put("hasPersianSubtitle",true).put("audioTracks",JSONArray().put(if(dub)"fa"else lang)).put("subtitleTracks",JSONArray().put("fa"))
 private fun episodeVersions(index:Int,lang:String)=JSONArray().apply{if(index<4){put(version("qa-episode-version-${index+1}",false,true,lang,2_880_000));if(index<3)put(version("qa-episode-dub-${index+1}",true,false,lang,2_880_000))}}
 private fun platform(id:String):JSONObject?{
  val all=catalog();val m=(0 until all.length()).map{all.getJSONObject(it)}.firstOrNull{it.optString("id")==id}?:return null
  val tv=m.getString("kind")=="series";val v=m.getString("mediaVersionId");val lang=m.getString("originalLanguage")
  return JSONObject(m.toString()).put("versions",if(tv)JSONArray()else JSONArray().put(version(v,true,true,lang,6_720_000)).put(version("$v-original",false,false,lang,6_720_000)))
   .put("seasons",if(tv)JSONArray().put(JSONObject().put("id","qa-season-1").put("number",1).put("name","فصل اول").put("posterUrl",m.getString("posterUrl")).put("episodes",JSONArray().apply{repeat(8){i->put(JSONObject().put("id","qa-episode-${i+1}").put("number",i+1).put("name","داستان ${i+1}").put("overview","قسمت آزمایشی برای بررسی دسترسی و خوانایی").put("stillUrl",server.url("/art/${i+1}.png").toString()).put("runtimeMinutes",48).put("mediaVersionId","qa-episode-version-${i+1}").put("quality","1080p").put("streamReady",i<4).put("isPersianDubbed",false).put("hasPersianDub",i<3).put("hasPersianSubtitle",i<4).put("versions",episodeVersions(i,lang)))}}))else JSONArray())
 }
 private fun respond(r:RecordedRequest):MockResponse{
  requests+=r;intercept?.invoke(r)?.let{return it};val url=requireNotNull(r.requestUrl);var p=url.encodedPath.replace(Regex("/+"),"/");val slow=p.startsWith("/slow/");val offline=p.startsWith("/offline/")
  if(slow)p=p.removePrefix("/slow");if(offline)p=p.removePrefix("/offline")
  if(p.startsWith("/art/")){val i=p.substringAfterLast('/').substringBefore('.').toIntOrNull()?:1;return MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(art(i)))}
  if(p=="/v1/tmdb"){
   val mp=url.queryParameter("path").orEmpty()
   if(slow&&mp=="trending/movie/week"){slowEntered.countDown();check(slowRelease.await(12,TimeUnit.SECONDS))}
   if(offline&&offlineFailure&&mp=="trending/tv/week")return MockResponse().setResponseCode(503).setBody("{\"error\":\"fixture service unavailable\"}")
   if(mp.startsWith("genre/"))return json(JSONObject().put("genres",JSONArray().put(JSONObject().put("id",18).put("name","درام")).put(JSONObject().put("id",9648).put("name","معمایی"))))
   if(mp=="configuration/countries")return json(JSONObject().put("results",JSONArray().apply{listOf("IR" to "ایران","TR" to "ترکیه","KR" to "کرهٔ جنوبی","IN" to "هند","US" to "آمریکا","JP" to "ژاپن","ZA" to "آفریقای جنوبی").forEach{(code,name)->put(JSONObject().put("iso_3166_1",code).put("native_name",name).put("english_name",code))}}))
   if(mp=="configuration/languages")return json(JSONObject().put("results",JSONArray().apply{listOf("hi","ta","te","ml","kn","bn","fa","tr","ko","en").forEach{put(JSONObject().put("iso_639_1",it).put("name",it).put("english_name",it))}}))
   if(Regex("^(movie|tv)/[0-9]+$").matches(mp)){val known=titles[mp.substringAfter('/').toInt()];return if(known!=null)metadataResponse(r,JSONObject(known))else MockResponse().setResponseCode(404).setBody("{}")}
   val tv=mp.contains("/tv")||mp.startsWith("tv/");val country=url.queryParameter("with_origin_country")?.takeIf{it.length==2}?:if(tv)"KR"else"IR";val page=url.queryParameter("page")?.toIntOrNull()?:1
   val items=JSONArray();repeat(4){i->val item=metadata(tv,country,(page-1)*4+i+1,url.queryParameter("with_original_language"));if(url.queryParameter("with_status")=="3|4")item.put("status","Ended");if(url.queryParameter("with_type")=="2")item.put("type","Miniseries");items.put(item)}
   if(mp=="search/multi")items.put(metadata(true,"KR",1))
   return metadataResponse(r,JSONObject().put("results",items).put("page",page).put("total_pages",2))
  }
  if(p=="/v1/catalog/home")return json(envelope(catalog()))
  if(p=="/v1/catalog/discovery"){val tv=url.queryParameter("type")=="series";val c=url.queryParameter("country")?:if(tv)"KR"else"IR";return json(envelope(JSONArray().put(local(tv,c,1))).put("discoveryVersion",1).put("page",1).put("totalPages",1))}
  if(p.startsWith("/v1/catalog/"))return platform(p.substringAfterLast('/'))?.let(::json)?:MockResponse().setResponseCode(404).setBody("{}")
  if(p=="/v1/search")return json(JSONObject().put("media",catalog()).put("users",JSONArray()).put("channels",JSONArray()).put("posts",JSONArray()).put("reels",JSONArray()))
  if(p=="/v1/me")return json(JSONObject().put("id","qa-account-090").put("username","cinema_qa").put("displayName","حساب آزمایشی سینما").put("bio","دادهٔ مشخص برای بررسی نسخهٔ ۰.۹").put("followers",0).put("following",0))
  if(p=="/v1/library/viewing-stats")return json(viewingStats())
  if(p=="/v1/watch/continue")return json(envelope(continueEntries()))
  if(p=="/v1/watch/history")return json(envelope(history()).put("historyVersion",1).put("page",1).put("hasMore",false))
  if(p=="/v1/library/favorites"||p=="/v1/library/watchlist")return json(envelope(JSONArray().put(local(false,"IR",1)).put(local(true,"KR",1))))
  if(p=="/v1/series/calendar")return json(envelope(JSONArray().put(JSONObject().put("media",local(true,"KR",1)).put("episode",JSONObject().put("id","qa-calendar-next").put("seasonNumber",1).put("episodeNumber",4).put("name","قسمت تازه").put("airDate","2026-10-08").put("runtimeMinutes",48).put("streamReady",false)))))
  return json(JSONObject().put("items",JSONArray()).put("results",JSONArray()).put("nextCursor",JSONObject.NULL))
 }
}
