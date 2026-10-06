package com.filmiqoo.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
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
            it.session.refreshToken=null
        }
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
        compose.onNodeWithTag("party-lobby").performScrollToNode(hasText("در حال تماشا",substring=false))
        compose.onNodeWithText("در حال تماشا").performClick()
        compose.onNodeWithTag("party-lobby").performScrollToNode(hasTestTag("party-open-live-room"))
        compose.onNodeWithText("فیلم واقعی").assertExists()
        compose.onNodeWithText("2 عضو").assertExists()
        compose.onNodeWithTag("party-open-live-room").performClick()
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
        compose.onNodeWithTag("party-join").performScrollTo().performClick()
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
}
