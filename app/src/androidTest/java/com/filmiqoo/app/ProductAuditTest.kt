package com.filmiqoo.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import java.io.ByteArrayOutputStream

/** Runs unchanged against the original and redesigned source. Test data never ships in the APK. */
class ProductAuditTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private var original: Triple<String,String?,String?>? = null
    @After fun cleanup() {
        compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        original?.let { SessionStore(compose.activity).apply { baseUrl=it.first; accessToken=it.second; refreshToken=it.third } }
        server.shutdown()
    }
    @Test fun captureProductPages() {
        server.start()
        val art = Bitmap.createBitmap(720,1080,Bitmap.Config.ARGB_8888)
        Canvas(art).apply {
            drawColor(Color.rgb(20,37,47)); val p=Paint(Paint.ANTI_ALIAS_FLAG)
            p.color=Color.rgb(191,90,55); drawCircle(480f,290f,220f,p)
            p.color=Color.rgb(48,83,88); drawRect(0f,580f,720f,1080f,p)
            p.color=Color.WHITE; p.textSize=58f; drawText("CINEMA / TEST",55f,930f,p)
        }
        val bytes=ByteArrayOutputStream().also { art.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        val image=server.url("/art.png").toString()
        val mediaJson="""{"id":77,"media_type":"movie","title":"جدایی نادر از سیمین","name":"My Liberation Notes","original_title":"A Separation","poster_path":"$image","backdrop_path":"$image","vote_average":8.1,"release_date":"2011-03-15"}"""
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(r:RecordedRequest):MockResponse {
                val path=r.requestUrl!!.encodedPath
                if(path=="/art.png") return MockResponse().setHeader("Content-Type","image/png").setBody(Buffer().write(bytes))
                val body=when {
                    path=="/v1/tmdb" -> """{"results":[$mediaJson],"total_pages":1,"page":1}"""
                    path.contains("discussions") -> """{"items":[{"id":"test-comment","scope":"movie:77","title":"جدایی نادر از سیمین","authorName":"کاربر آزمایشی","body":"داستان پنهان آزمایشی","spoiler":true,"createdAt":"2026-10-05T12:00:00Z"}],"nextCursor":null}"""
                    path.contains("search") -> """{"media":[],"users":[],"channels":[],"posts":[],"reels":[]}"""
                    else -> """{"items":[],"posts":[],"stories":[],"rooms":[],"results":[],"nextCursor":null}"""
                }
                return MockResponse().setHeader("Content-Type","application/json").setBody(body)
            }
        }
        val backend=BackendRepository(compose.activity)
        original=Triple(backend.session.baseUrl,backend.session.accessToken,backend.session.refreshToken)
        backend.session.baseUrl=server.url("/").toString(); backend.session.accessToken=null; backend.session.refreshToken=null
        val repository=TmdbRepository(compose.activity)
        val movie=MediaItem(77,MediaType.MOVIE,"جدایی نادر از سیمین","A Separation",posterPath=image,backdropPath=image,vote=8.1,date="2011")
        val series=movie.copy(id=100,type=MediaType.TV,title="My Liberation Notes",originalTitle="My Liberation Notes")
        val eps=(1..8).map { PlatformEpisode("e$it",it,"Episode $it","داستان پنهان قسمت",image,60,"v$it","1080p",true) }
        fun data(m:MediaItem):CinemaTitleData {
            val p=PlatformDetail("fixture",m.id,if(m.type==MediaType.TV)"series" else "movie",m.title,m.originalTitle,"خلاصهٔ آزمایشی",2011,image,image,8.1,
                if(m.type==MediaType.MOVIE)listOf(PlatformVersion("v1","1080p","H.264","SDR",1000000,60000,true,true))else emptyList(),
                if(m.type==MediaType.TV)listOf(PlatformSeason("s1",1,"فصل اول",image,eps))else emptyList())
            return CinemaTitleData(MediaDetail(m,"",listOf("درام"),123,"Released",emptyList(),null,emptyList(),emptyList()),p)
        }
        var page by mutableStateOf("home")
        compose.setContent { FilmiqooTheme {
            when(page) {
                "movie" -> CinemaDetailContent(data(movie))
                "series", "episodes" -> CinemaDetailContent(data(series))
                "settings" -> SettingsScreen(backend,{})
                "inbox" -> InboxScreen(backend,{},{})
                "notifications" -> ConnectedNotificationsScreen(backend,{},{_,_->},{},{},{},{},{},{},{},{})
                "auth" -> AuthScreen(backend,{},{})
                "comments" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { TitleDiscussion(movie,backend,{}) }
                else -> AuditShell(when(page){"search"->1;"club"->2;"library"->3;else->0}) {
                    Box(Modifier.fillMaxSize()) { when(page) {
                        "home" -> CinemaHomeScreen(repository,backend,false,{},{},{_,_->},{},{},{},{},{},{})
                        "search" -> PremiumSearchScreen(repository,backend,{},{},{},{},{})
                        "library" -> CinemaLibraryScreen(backend,repository,{},{},{},showBack=false)
                        "club" -> CinemaSocialScreen(SocialRepository(backend),backend,false,{},{},{},{},{},{},{},{},{},{_,_->})
                    } }
                }
            }
        } }
        val args=InstrumentationRegistry.getArguments()
        val label=args.getString("auditLabel")?:"default"
        val metrics=org.json.JSONArray()
        for(name in listOf("home","search","movie","series","club","comments","library","auth","episodes","settings","inbox","notifications")) {
            compose.runOnIdle { page=name }
            // Wait for bounded HTTP/image work and a complete frame; this is screenshot stabilization, not a benchmark.
            compose.waitForIdle(); Thread.sleep(900); compose.waitForIdle()
            if(name=="episodes") {
                compose.onNodeWithTag("detail-scroll").performScrollToNode(hasTestTag("episode-e1"))
                compose.waitForIdle()
            }
            if(label.contains("keyboard") && name in listOf("search","auth")) {
                compose.onAllNodes(hasSetTextAction()).onFirst().performClick()
                compose.waitForIdle(); Thread.sleep(300)
            }
            val screenshot=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            PlatformTestStorageRegistry.getInstance().openOutputFile("audit-$label-$name.png").use { Assert.assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,it)) }
            val config=compose.activity.resources.configuration
            val memory=android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
            metrics.put(org.json.JSONObject().put("page",name).put("widthDp",config.screenWidthDp).put("heightDp",config.screenHeightDp)
                .put("fontScale",config.fontScale).put("screenshotWidthPx",screenshot.width).put("screenshotHeightPx",screenshot.height).put("totalPssKb",memory.totalPss))
            if(label.contains("keyboard")) androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        }
        PlatformTestStorageRegistry.getInstance().openOutputFile("audit-$label-metrics.json").use { it.write(metrics.toString(2).toByteArray()) }
    }
}

@Composable
private fun AuditShell(selected:Int,content:@Composable ()->Unit) {
    CinemaAppShell(selected,false,{},content)
}
