package com.filmiqoo.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.Buffer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class WatchPartyJourneyTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val server=MockWebServer()
    private var original:Triple<String,String?,String?>?=null
    private fun backend(logged:Boolean):BackendRepository {
        server.start()
        return BackendRepository(compose.activity).also {
            original=Triple(it.session.baseUrl,it.session.accessToken,it.session.refreshToken)
            it.session.baseUrl=server.url("/").toString()
            it.session.accessToken=if(logged)"party-test-token" else null
            it.session.refreshToken=if(logged)"party-test-refresh" else null
        }
    }
    private fun hideKeyboard() {
        compose.runOnUiThread{WindowCompat.getInsetsController(compose.activity.window,compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime())}
        compose.waitUntil(5000){var visible=false;compose.runOnUiThread{
            visible=ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true
        };!visible}
        compose.waitForIdle()
    }
    private fun snapshot(file:String) {
        compose.waitForIdle()
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try { PlatformTestStorageRegistry.getInstance().openOutputFile(file).use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))} }
        finally { bitmap.recycle() }
    }
    private fun findPlayer(view:View):PlayerView? {
        if(view is PlayerView)return view
        if(view is ViewGroup)for(i in 0 until view.childCount)findPlayer(view.getChildAt(i))?.let{return it}
        return null
    }
    @After fun cleanup() {
        compose.runOnUiThread{compose.activity.setContentView(android.widget.FrameLayout(compose.activity))}
        original?.let{state->SessionStore(compose.activity).apply{baseUrl=state.first;accessToken=state.second;refreshToken=state.third}}
        server.shutdown()
    }
    @Test fun roomListShowsServerDataFiltersAndOpensExistingRoom() {
        val backend=backend(true);val calls=CopyOnWriteArrayList<RecordedRequest>();var opened:String?=null
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            calls+=request
            return MockResponse().setBody("""{"items":[{"id":"live-room","title":"Live room","state":"live","participants":2,"host":{"displayName":"میزبان واقعی"},"media":{"title":"فیلم واقعی"}},{"id":"scheduled-room","title":"Later","state":"scheduled","participants":1,"host":{"displayName":"دوست"},"media":{"title":"قرارِ فردا"}}]}""")
        }}
        compose.setContent{FilmiqooTheme{PartyLobbyScreen(backend,{id,_->opened=id},{},{})}}
        compose.waitUntil(10000){calls.isNotEmpty()}
        compose.onNodeWithTag("party-lobby").performScrollToNode(hasTestTag("party-filter-live"))
        compose.onNodeWithTag("party-filter-live").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("party-filter-live").assertIsSelected()
        compose.onNodeWithTag("party-lobby").performScrollToNode(hasTestTag("party-open-live-room"))
        compose.onNodeWithText("فیلم واقعی").assertExists()
        compose.onNodeWithText("2 عضو").assertExists()
        snapshot("watch-party-public-room.png")
        compose.onNodeWithTag("party-open-live-room").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000){opened!=null}
        assertEquals("live-room",opened)
        assertTrue(calls.all{it.method=="GET" && it.requestUrl?.encodedPath=="/v1/watch-parties"})
    }
    @Test fun inviteValidationAndStateRestorationPreserveLinkAndCode() {
        val backend=backend(true)
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setBody("""{"items":[]}""")}
        val id="12345678-1234-4234-8234-123456789abc"
        var opened:Pair<String,String?>?=null
        val restoration=StateRestorationTester(compose)
        restoration.setContent{FilmiqooTheme{PartyLobbyScreen(backend,{party,code->opened=party to code},{},{})}}
        compose.onNodeWithTag("party-lobby").performScrollToNode(hasTestTag("party-invite-input"))
        compose.onNodeWithTag("party-invite-input").performTextInput("bad-code")
        compose.onNodeWithTag("party-join").performScrollTo().performClick()
        compose.onNodeWithTag("party-invite-error").assertExists()
        assertNull(opened)
        val link="filmiqoo://party/$id?invite=private-token"
        compose.onNodeWithTag("party-invite-input").performTextReplacement(link)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("party-invite-input").assertTextContains(link)
        compose.runOnIdle{assertTrue("authenticated invite entry needs access and refresh tokens",backend.session.isLoggedIn)}
        hideKeyboard()
        compose.onNodeWithTag("party-join").performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5000){opened!=null}
        assertEquals(id to "private-token",opened)
    }
    @Test fun failedListCanRetryAndUnauthenticatedCreateRequiresLogin() {
        val backend=backend(false);val requests=AtomicInteger();var authRequests=0;var creations=0
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=
            if(requests.incrementAndGet()==1)MockResponse().setResponseCode(503).setBody("{}")
            else MockResponse().setBody("""{"items":[]}""")}
        compose.setContent{FilmiqooTheme{PartyLobbyScreen(backend,{_,_->},{creations++},{authRequests++})}}
        compose.waitUntil(10000){compose.onAllNodesWithTag("party-list-error").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("party-lobby").performScrollToNode(hasTestTag("party-refresh"))
        compose.onNodeWithTag("party-refresh").performClick()
        compose.waitUntil(10000){requests.get()>=2 && compose.onAllNodesWithTag("party-list-error").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("party-create").performScrollTo().performClick()
        assertEquals(1,authRequests);assertEquals(0,creations)
    }
    @Test fun oldServerExplainsUpgradeWithoutRequestingPlaybackOrClaimingSync() {
        val backend=backend(true);val paths=CopyOnWriteArrayList<String>()
        val party="12345678-1234-4234-8234-123456789abc"
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val path=request.requestUrl!!.encodedPath;paths+=path
            val body=when {
                path=="/v1/me"->"""{"id":"host"}"""
                path.endsWith("/join")->"""{"roomId":"test-room"}"""
                path.endsWith("/lobby")->"""{"myRole":"host","participantCount":1,"state":"live","members":[],"requests":[]}"""
                path=="/v1/watch-parties/$party"->"""{"id":"$party","title":"Legacy room","state":"live","isPlaying":true,"positionMs":1500,"participants":1,"roomId":"test-room","host":{"id":"host","displayName":"میزبان"},"media":{"id":"title","title":"فیلم","mediaVersionId":"version"}}"""
                else->"""{"items":[]}"""
            }
            return MockResponse().setBody(body)
        }}
        compose.setContent{FilmiqooTheme{ConnectedWatchPartyScreen(null,party,null,backend,SocialRepository(backend),TmdbRepository(compose.activity),{},{})}}
        compose.waitUntil(10000){compose.onAllNodesWithTag("party-server-upgrade").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("party-play-control").assertDoesNotExist()
        Thread.sleep(1200)
        assertTrue("capability test must actually join as an authenticated user",paths.contains("/v1/watch-parties/$party/join"))
        assertFalse(paths.any{it.contains("playback")||it.contains("realtime")||it.endsWith("/state")})
    }
    @Test fun partyTimelineKeepsPhysicalLeftEarlyAndRightLateInRtl() {
        backend(true)
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("player-fixture.mp4").use{it.readBytes()}
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=
            MockResponse().setHeader("Content-Type","video/mp4").setBody(Buffer().write(bytes))}
        lateinit var player:ExoPlayer
        compose.runOnUiThread {
            player=ExoPlayer.Builder(compose.activity).build()
            player.setMediaItem(androidx.media3.common.MediaItem.fromUri(server.url("/media.mp4").toString()))
            player.prepare()
        }
        val seeks=CopyOnWriteArrayList<Long>()
        val party=WatchPartyInfo("id","Fixture","live","invite",null,0,false,1,"room",
            WatchPartyHost("host","host","میزبان","",false),WatchPartyMedia("title","فیلم آزمون",null,null,"version",null),serverTimed=true)
        val lobby=WatchPartyLobby("host",true,false,1,1,"live",emptyList(),emptyList(),onlineCount=1,presenceKnown=true)
        try {
            compose.setContent{FilmiqooTheme{CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl){
                PremiumWatchPartyStage(party=party,player=player,lobby=lobby,myUserId="host",canHostControl=true,
                    realtimeConnected=true,syncing=false,privateJoinRequired=false,joinRequestPending=false,lobbyBusy=false,
                    reminderEnabled=false,reminderBusy=false,reactions=emptyList(),messages=emptyList(),messageText="",
                    sendingMessage=false,error=null,listState=rememberLazyListState(),onBack={},onPrimaryControl={},onInvite={},
                    onQueue={},onShare={},onLobby={},onRequestJoin={},onToggleReminder={},onToggleReady={},onReact={},
                    onMessageTextChange={},onSendMessage={},onSeek={seeks+=it})
            }}}
            compose.waitUntil(10000){compose.onAllNodesWithTag("party-seek").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("party-seek").performScrollTo()
            compose.onNodeWithTag("party-seek").performTouchInput{click(androidx.compose.ui.geometry.Offset(width*.2f,height*.5f))}
            compose.onNodeWithTag("party-seek").performTouchInput{click(androidx.compose.ui.geometry.Offset(width*.8f,height*.5f))}
            assertEquals(2,seeks.size)
            var total=0L;compose.runOnUiThread{total=player.duration}
            assertTrue("physical left must seek early",seeks[0]<total*.35)
            assertTrue("physical right must seek late",seeks[1]>total*.65)
        } finally {
            compose.runOnUiThread{compose.activity.setContentView(android.widget.FrameLayout(compose.activity));player.release()}
        }
    }
    @Test fun authenticatedCreationUsesActualCatalogFileAndKeepsInvitePrivacy() {
        val backend=backend(true);val posted=CopyOnWriteArrayList<JSONObject>()
        val title="12345678-1234-4234-8234-123456789abc"
        val party="22345678-1234-4234-8234-123456789abc"
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val path=request.requestUrl!!.encodedPath
            val body=when {
                path=="/v1/catalog/$title"->"""{"id":"$title","tmdbId":77,"kind":"movie","title":"فیلمِ قرار شما","originalTitle":"Test movie","year":2026,"versions":[{"id":"playable-version","streamReady":true,"preferred":true,"quality":"1080p","durationMs":120000}]}"""
                path=="/v1/me"->"""{"id":"host"}"""
                path=="/v1/watch-parties" && request.method=="POST"->{
                    posted+=JSONObject(request.body.readUtf8())
                    """{"id":"$party","roomId":"test-room","state":"live","inviteCode":"test-invite"}"""
                }
                path=="/v1/watch-parties/$party"->"""{"id":"$party","title":"قرار شما","state":"live","visibility":"invite","participants":1,"roomId":"test-room","host":{"id":"host","displayName":"میزبان"},"media":{"title":"فیلمِ قرار شما"}}"""
                path.endsWith("/join")->"""{"roomId":"test-room"}"""
                path.endsWith("/lobby")->"""{"myRole":"host","participantCount":1,"state":"live","members":[],"requests":[]}"""
                else->"""{"items":[]}"""
            }
            return MockResponse().setBody(body)
        }}
        val media=MediaItem(77,MediaType.MOVIE,"فیلمِ قرار شما",backendId=title,mediaVersionId="playable-version",streamReady=true)
        compose.setContent{FilmiqooTheme{ConnectedWatchPartyScreen(media,backend=backend,social=SocialRepository(backend),repository=TmdbRepository(compose.activity),onBack={},onRequireAuth={fail("already authenticated")})}}
        compose.waitUntil(15000){compose.onAllNodesWithText("نسخهٔ پخش آماده است").fetchSemanticsNodes().isNotEmpty()}
        snapshot("watch-party-creation-title.png")
        compose.onNodeWithTag("party-creation").performScrollToNode(hasTestTag("party-confirm-create"))
        compose.onNodeWithTag("party-confirm-create").performScrollTo().assertIsDisplayed().assertIsEnabled()
        snapshot("watch-party-creation-options.png")
        compose.onNodeWithTag("party-confirm-create").performClick()
        compose.waitUntil(10000){posted.size==1}
        assertEquals(title,posted.single().getString("mediaTitleId"))
        assertEquals("invite",posted.single().getString("visibility"))
        assertFalse("immediate room must not invent a scheduled time",posted.single().has("scheduledAt"))
    }
    @Test fun connectedPartyJoinsDecodesVideoAndReceivesLiveSnapshot() {
        val backend=backend(true);val paths=CopyOnWriteArrayList<String>()
        val party="12345678-1234-4234-8234-123456789abc"
        val startedAt=android.os.SystemClock.elapsedRealtime()
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("player-fixture.mp4").use{it.readBytes()}
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val path=request.requestUrl!!.encodedPath;paths+=path
            if(path=="/v1/realtime/watch-parties/$party")return MockResponse().withWebSocketUpgrade(object:WebSocketListener(){
                override fun onOpen(webSocket:WebSocket,response:okhttp3.Response){webSocket.send("""{"type":"watchparty.state","state":"live","positionMs":1000,"isPlaying":true,"revision":1,"controllerUserId":"host","serverTime":"2026-10-06T00:00:00Z"}""")}
            })
            if(path=="/fixture.mp4")return MockResponse().setHeader("Content-Type","video/mp4").setBody(Buffer().write(bytes))
            val body=when {
                path=="/v1/me"->"""{"id":"viewer"}"""
                path.endsWith("/join")->"""{"roomId":"test-room"}"""
                path.endsWith("/lobby")->"""{"myRole":"viewer","participantCount":2,"onlineCount":2,"state":"live","members":[{"id":"host","displayName":"میزبان","role":"host","online":true},{"id":"viewer","displayName":"همراه","role":"viewer","online":true}],"requests":[]}"""
                path=="/v1/watch-parties/$party"->"""{"id":"$party","title":"یک قرارِ سینمایی","state":"live","visibility":"invite","isPlaying":true,"positionMs":${1000+android.os.SystemClock.elapsedRealtime()-startedAt},"revision":1,"controllerUserId":"host","serverTime":"2026-10-06T00:00:00Z","participants":2,"roomId":"test-room","host":{"id":"host","displayName":"میزبان"},"media":{"id":"title","title":"فیلمِ آزمون پخش","mediaVersionId":"playable-version","quality":"1080p"}}"""
                path=="/v1/playback/token"->JSONObject().put("url",server.url("/fixture.mp4").toString()).toString()
                path=="/v1/rooms/test-room/messages"->"""{"items":[{"id":"hello","body":"همه آماده‌ایم؛ شروع کنیم!","type":"text","author":{"id":"host","displayName":"میزبان"}}]}"""
                else->"""{"items":[]}"""
            }
            return MockResponse().setBody(body)
        }}
        compose.setContent{FilmiqooTheme{ConnectedWatchPartyScreen(null,party,null,backend,SocialRepository(backend),TmdbRepository(compose.activity),{},{fail("already authenticated")})}}
        compose.waitUntil(30000){var decoded=false;compose.runOnUiThread{
            val player=findPlayer(compose.activity.window.decorView)?.player
            decoded=player?.isPlaying==true && player.currentPosition>=1000 && player.videoSize.width==320
        };decoded}
        compose.onNodeWithTag("party-stage").performScrollToNode(hasText("اتصال زنده برقرار است"))
        compose.onNodeWithTag("party-connection-status").assertTextEquals("اتصال زنده برقرار است")
        compose.onNodeWithTag("party-play-control").assertDoesNotExist()
        assertTrue(paths.contains("/v1/watch-parties/$party/join"))
        assertTrue(paths.contains("/v1/realtime/watch-parties/$party"))
        assertTrue(paths.contains("/fixture.mp4"))
        snapshot("watch-party-connected-stage.png")
        compose.onNodeWithTag("party-stage").performScrollToNode(hasTestTag("party-message-hello"))
        compose.onNodeWithTag("party-message-hello").assertIsDisplayed()
        snapshot("watch-party-connected-chat.png")
    }
}
