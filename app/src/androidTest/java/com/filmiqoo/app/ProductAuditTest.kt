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

/** Deterministic visual fixtures; the baseline maps the new lobby to its previous party screen. */
class ProductAuditTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val server = MockWebServer()
    private var original: Triple<String,String?,String?>? = null
    private var auditLabel = "uninitialized"
    private var auditViewport = "unavailable"

    // These markers only read cached viewport text: requesting compose.activity while the
    // main thread is stalled would itself synchronize and obscure the blocked phase.
    private fun auditPhase(page: String, phase: String, detail: String = "") {
        android.util.Log.i("FilmiqooProductAudit",
            "label=$auditLabel page=$page phase=$phase uptimeMs=${android.os.SystemClock.uptimeMillis()} " +
                "thread=${Thread.currentThread().name} $auditViewport $detail")
    }
    private inline fun <T> auditStep(page: String, phase: String, action: () -> T): T {
        auditPhase(page, "$phase:begin")
        return try {
            action().also { auditPhase(page, "$phase:end") }
        } catch (failure: Throwable) {
            android.util.Log.e("FilmiqooProductAudit", "label=$auditLabel page=$page phase=$phase:failed", failure)
            throw failure
        }
    }
    private fun describeViewport(activity: ComponentActivity): String {
        val config = activity.resources.configuration
        val display = activity.resources.displayMetrics
        val decor = activity.window.decorView
        return "dp=${config.screenWidthDp}x${config.screenHeightDp} font=${config.fontScale} " +
            "orientation=${config.orientation} displayPx=${display.widthPixels}x${display.heightPixels} " +
            "density=${display.density} densityDpi=${display.densityDpi} decorPx=${decor.width}x${decor.height}"
    }
    @After fun cleanup() {
        auditStep("cleanup", "remove-content") {
            compose.runOnUiThread { compose.activity.setContentView(android.widget.FrameLayout(compose.activity)) }
        }
        original?.let { SessionStore(compose.activity).apply { baseUrl=it.first; accessToken=it.second; refreshToken=it.third } }
        auditStep("cleanup", "server-shutdown") { server.shutdown() }
    }
    @Test fun captureProductPages() {
        val args=InstrumentationRegistry.getArguments()
        val label=args.getString("auditLabel")?:"default"
        auditLabel=label.take(120)
        val activity=compose.activity
        auditViewport=describeViewport(activity)
        auditPhase("setup", "initial-configuration")
        fun readImeState(): Pair<Boolean, Int> {
            var result = false to 0
            compose.runOnUiThread {
                val insets=androidx.core.view.ViewCompat.getRootWindowInsets(activity.window.decorView)
                val type=androidx.core.view.WindowInsetsCompat.Type.ime()
                result=(insets?.isVisible(type)==true) to (insets?.getInsets(type)?.bottom?:0)
            }
            return result
        }
        server.start()
        val art = Bitmap.createBitmap(720,1080,Bitmap.Config.ARGB_8888)
        Canvas(art).apply {
            drawColor(Color.rgb(20,37,47)); val p=Paint(Paint.ANTI_ALIAS_FLAG)
            p.color=Color.rgb(191,90,55); drawCircle(480f,290f,220f,p)
            p.color=Color.rgb(48,83,88); drawRect(0f,580f,720f,1080f,p)
            p.color=Color.WHITE; p.textSize=58f; drawText("CINEMA / TEST",55f,930f,p)
        }
        val bytes=ByteArrayOutputStream().also { art.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        art.recycle()
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
        auditPhase("setup", "set-content:begin")
        compose.setContent { FilmiqooTheme {
            when(page) {
                "movie" -> CinemaDetailContent(data(movie))
                "series", "episodes" -> CinemaDetailContent(data(series))
                "settings" -> SettingsScreen(backend,{})
                "inbox" -> InboxScreen(backend,{},{})
                "notifications" -> ConnectedNotificationsScreen(backend,{},{_,_->},{},{},{},{},{},{},{},{})
                "auth" -> AuthScreen(backend,{},{})
                "comments" -> Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) { TitleDiscussion(movie,backend,{}) }
                else -> AuditShell(when(page){"search"->1;"club"->2;"library","profile"->3;"party"->4;else->0}) {
                    Box(Modifier.fillMaxSize()) { when(page) {
                        "home" -> CinemaHomeScreen(repository,backend,false,{},{},{_,_->},{},{},{},{},{},{})
                        "search" -> PremiumSearchScreen(repository,backend,{},{},{},{},{})
                        "library" -> CinemaLibraryScreen(backend,repository,{},{},{},showBack=false)
                        "club" -> CinemaSocialScreen(SocialRepository(backend),backend,false,{},{},{},{},{},{},{},{},{},{_,_->})
                        "profile" -> AuditProfile(backend,repository)
                        "party" -> PartyLobbyScreen(backend,{_,_->},{},{})
                    } }
                }
            }
        } }
        auditPhase("setup", "set-content:end")
        auditStep("setup", "measure-viewport") { auditViewport=describeViewport(activity) }
        val metrics=org.json.JSONArray()
        for(name in listOf("home","search","movie","series","club","comments","library","profile","party","auth","episodes","settings","inbox","notifications")) {
            auditStep(name, "page-change") {
                compose.runOnIdle { page=name; auditPhase(name, "page-assigned") }
            }
            // Wait for bounded HTTP/image work and a complete frame; this is screenshot stabilization, not a benchmark.
            auditStep(name, "initial-idle") { compose.waitForIdle() }
            auditStep(name, "initial-stabilization") { Thread.sleep(900) }
            auditStep(name, "stabilized-idle") { compose.waitForIdle() }
            if(name=="search" && args.getString("auditStage")=="after") {
                // Await the real fixture response rather than capture a transient empty catalog.
                var tagQueries=0
                auditStep(name, "poster-tag-wait") {
                    compose.waitUntil(10_000) {
                        // Log at most three queries; each query implicitly waits for layout/draw.
                        // The thumbnail can be below a short window's lazy-grid viewport.
                        tagQueries++
                        fun ready() = compose.onAllNodesWithTag("search-poster-atmosphere",useUnmergedTree=true)
                            .fetchSemanticsNodes().isNotEmpty()
                        if(tagQueries<=3) auditStep(name, "poster-tag-query-$tagQueries") { ready() } else ready()
                    }
                }
                // Semantics can become ready before the asynchronously loaded artwork
                // has been drawn by the native renderer used by takeScreenshot().
                auditStep(name, "artwork-idle") { compose.waitForIdle() }
                auditStep(name, "artwork-stabilization") { Thread.sleep(900) }
                auditStep(name, "artwork-stabilized-idle") { compose.waitForIdle() }
            }
            if(name=="episodes") {
                auditStep(name, "episode-scroll") { compose.onNodeWithTag("detail-scroll").performScrollToNode(hasTestTag("episode-e1")) }
                auditStep(name, "episode-scroll-idle") { compose.waitForIdle() }
            }
            if(label.contains("keyboard") && name in listOf("search","auth")) {
                auditStep(name, "keyboard-system-dialog-guard") { ensureNoSystemErrorDialog() }
                auditStep(name, "keyboard-open") { compose.onAllNodes(hasSetTextAction()).onFirst().performClick() }
                auditStep(name, "keyboard-open-idle") { compose.waitForIdle() }
                auditStep(name, "keyboard-stabilization") { Thread.sleep(300) }
                // Focus can finish before the OS shows its keyboard. Capture only the actual
                // visible IME state, including its applied bottom inset, rather than a timed guess.
                auditStep(name, "ime-visible-wait") {
                    compose.waitUntil(5_000) {
                        val ime=readImeState()
                        ime.first && ime.second>0
                    }
                }
                auditStep(name, "ime-visible-idle") { compose.waitForIdle() }
            }
            auditStep(name, "capture-system-dialog-guard") { ensureNoSystemErrorDialog() }
            val imeAtCapture=auditStep(name, "read-capture-ime") { readImeState() }
            auditPhase(name, "capture-ime", "imeVisible=${imeAtCapture.first} imeBottomPx=${imeAtCapture.second}")
            val screenshot=auditStep(name, "take-screenshot") {
                requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            }
            auditPhase(name, "screenshot-dimensions", "bitmapPx=${screenshot.width}x${screenshot.height}")
            auditStep(name, "compress-screenshot") {
                PlatformTestStorageRegistry.getInstance().openOutputFile("audit-$label-$name.png").use { Assert.assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG,100,it)) }
            }
            val pageMetrics=auditStep(name, "collect-metrics") {
                val config=compose.activity.resources.configuration
                val memory=android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
                org.json.JSONObject().put("page",name).put("widthDp",config.screenWidthDp).put("heightDp",config.screenHeightDp)
                    .put("fontScale",config.fontScale).put("screenshotWidthPx",screenshot.width).put("screenshotHeightPx",screenshot.height).put("totalPssKb",memory.totalPss)
                    .put("imeVisible",imeAtCapture.first).put("imeBottomPx",imeAtCapture.second)
            }
            metrics.put(pageMetrics)
            auditPhase(name, "capture-metrics", pageMetrics.toString())
            // Retain completed-page evidence even if a later page never returns.
            auditStep(name, "write-page-metrics") {
                PlatformTestStorageRegistry.getInstance().openOutputFile("audit-$label-$name-observation.json").use { it.write(pageMetrics.toString(2).toByteArray()) }
            }
            if(name=="home" && label=="393x852-font1.0-gesture") {
                val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
                val packageName=compose.activity.packageName
                fun shell(command:String):ByteArray = android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
                shell("dumpsys gfxinfo $packageName reset")
                repeat(3){index->
                    auditStep(name,"scroll-up-$index"){compose.onNodeWithTag("cinema-home").performTouchInput{swipeUp()}}
                    auditStep(name,"scroll-up-idle-$index"){compose.waitForIdle()}
                }
                repeat(3){index->
                    auditStep(name,"scroll-down-$index"){compose.onNodeWithTag("cinema-home").performTouchInput{swipeDown()}}
                    auditStep(name,"scroll-down-idle-$index"){compose.waitForIdle()}
                }
                PlatformTestStorageRegistry.getInstance().openOutputFile("home-scroll-frames.txt").use{it.write(shell("dumpsys gfxinfo $packageName framestats"))}
            }
            if(label.contains("keyboard") && name in listOf("search","auth")) {
                // Hide only the IME; Back on a page without a keyboard would finish the test Activity.
                auditStep(name, "keyboard-hide") {
                    compose.runOnUiThread {
                        val activity=compose.activity
                        androidx.core.view.WindowCompat.getInsetsController(activity.window,activity.window.decorView)
                            .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                    }
                }
                auditStep(name, "keyboard-hidden-idle") { compose.waitForIdle() }
            }
            // A tablet screenshot owns several MiB of native memory; release each capture
            // before advancing through the fourteen-page matrix.
            auditStep(name, "recycle-screenshot") { screenshot.recycle() }
        }
        auditStep("complete", "write-metrics") {
            PlatformTestStorageRegistry.getInstance().openOutputFile("audit-$label-metrics.json").use { it.write(metrics.toString(2).toByteArray()) }
        }
    }
}

@Composable
private fun AuditShell(selected:Int,content:@Composable ()->Unit) {
    CinemaAppShell(selected,false,{},content)
}

@Composable
private fun AuditProfile(backend:BackendRepository,repository:TmdbRepository) {
    ConnectedProfileScreen(backend,repository,
        onMedia={},onPlay={},onCommunity={},onDownloads={},onLibrary={},onSocialSaves={},onHistory={},
        onCreatorStudio={},onInbox={},onSettings={},onViewerProfiles={},onParentalControls={},onSecurity={},onSafety={},
        onFollowRequests={},onCloseFriends={},onEditProfile={},onFilmDna={},onReputation={},onSeriesCalendar={},onSocialCollections={},onLoggedOut={},loggedIn=false)
}
