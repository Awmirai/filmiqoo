package com.filmiqoo.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList

class NotificationJourneyTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val server=MockWebServer()
    private var original:Triple<String,String?,String?>?=null
    private val requests=CopyOnWriteArrayList<RecordedRequest>()
    @After fun cleanup(){
        compose.runOnUiThread{compose.activity.setContentView(android.widget.FrameLayout(compose.activity))}
        original?.let{v->SessionStore(compose.activity).apply{baseUrl=v.first;accessToken=v.second;refreshToken=v.third}}
        server.shutdown()
    }
    @Test fun metadataOnlyEpisodeNotificationKeepsScopeAndNullArtworkWhenOpened(){
        server.start()
        val backend=BackendRepository(compose.activity)
        original=Triple(backend.session.baseUrl,backend.session.accessToken,backend.session.refreshToken)
        backend.session.baseUrl=server.url("/").toString();backend.session.accessToken="test";backend.session.refreshToken="test"
        val body="""{"items":[{"id":"notification","type":"discussion_reply","entityType":"title_comment","entityId":"comment","discussionScope":"series:77:s1:e2","title":"پاسخ تازه به دیدگاهت","body":"گفت‌وگوی همین قسمت را ببین","read":false,"createdAt":"2026-10-06T08:00:00Z","actor":null,"media":{"id":null,"tmdbId":77,"kind":"series","title":"سریال آزمون","originalTitle":null,"overview":null,"posterUrl":null,"backdropUrl":null,"mediaVersionId":null,"quality":null,"year":null,"rating":null}}],"unread":1}"""
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            requests+=request
            return MockResponse().setHeader("Content-Type","application/json").setBody(if(request.method=="POST")"{}"else body)
        }}
        val parsed=kotlinx.coroutines.runBlocking{MessagingRepository(backend).notifications().first.single()}
        assertEquals("series:77:s1:e2",parsed.discussionScope)
        val media=parsed.media!!
        assertEquals(MediaType.TV,media.type);assertEquals(77,media.id);assertNull(media.backendId)
        assertNull(media.posterPath);assertNull(media.backdropPath);assertNull(media.mediaVersionId)
        assertEquals("",media.originalTitle);assertEquals("",media.overview);assertEquals("",media.quality);assertEquals("",media.date)
        var opened:Pair<MediaItem,String>?=null
        compose.setContent{FilmiqooTheme{ConnectedNotificationsScreen(backend,onBack={},onOpenRoom={_,_->},onOpenCreator={},onOpenClip={},onOpenPost={},onOpenMedia={error("episode notification must preserve its scope")},onOpenCollection={},onOpenWatchParty={},onOpenLive={},onFollowRequests={},onOpenDiscussion={title,scope->opened=title to scope})}}
        compose.waitUntil(10000){compose.onAllNodesWithText("پاسخ تازه به دیدگاهت").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("پاسخ تازه به دیدگاهت").performClick()
        compose.runOnIdle{assertEquals(MediaType.TV,opened!!.first.type);assertEquals("series:77:s1:e2",opened!!.second);assertNull(opened!!.first.posterPath)}
        compose.waitUntil(10000){requests.any{it.method=="POST"&&it.requestUrl?.encodedPath=="/v1/notifications/notification/read"}}
    }
}
