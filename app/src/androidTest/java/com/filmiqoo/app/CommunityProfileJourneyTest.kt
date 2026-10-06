package com.filmiqoo.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class CommunityProfileJourneyTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val server=MockWebServer()
    private var original:Triple<String,String?,String?>?=null
    private var originalViewer:ViewerProfile?=null
    private var restoreViewer=false
    private val requests=CopyOnWriteArrayList<RecordedRequest>()
    private fun backend(logged:Boolean):BackendRepository {
        server.start()
        return BackendRepository(compose.activity).also{
            original=Triple(it.session.baseUrl,it.session.accessToken,it.session.refreshToken)
            it.session.baseUrl=server.url("/").toString();it.session.accessToken=if(logged)"test"else null;it.session.refreshToken=if(logged)"test"else null
        }
    }
    private fun dispatch(block:(RecordedRequest)->MockResponse){server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{requests+=request;return block(request)}}}
    private fun json(body:String)=MockResponse().setHeader("Content-Type","application/json").setBody(body)
    private fun scrollLazyTo(container:String,tag:String){
        // Lazy items are composed on demand. Scroll the list semantics, not the outer Box.
        var lastFailure:Throwable?=null
        try {
            compose.waitUntil(10000){
                try {compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag));true}
                catch(failure:AssertionError){lastFailure=failure;false}
            }
        }catch(failure:ComposeTimeoutException){throw AssertionError("Could not scroll $container to $tag",lastFailure?:failure)}
    }
    private fun scrollCommunityTo(tag:String)=scrollLazyTo("community-scroll",tag)
    private fun post(id:String="post",author:String="owner",spoiler:Boolean=false)=
        """{"id":"$id","type":"review","body":"پایان پنهان فیلم","spoiler":$spoiler,"likes":4,"comments":0,"author":{"id":"$author","displayName":"سینمادوست","username":"fan"},"media":{"id":"catalog","title":"فیلم آزمون","kind":"movie","tmdbId":77}}"""
    private fun community(backend:BackendRepository,logged:Boolean,onAuth:()->Unit={},onClip:(String)->Unit={},onParty:()->Unit={},refreshInterval:Long=45_000L) {
        val social=SocialRepository(backend)
        compose.setContent{FilmiqooTheme{CinemaSocialScreen(social,backend,logged,{},{onClip(it)},{},{},{},{},{},onAuth,{},{_,_->},onWatchParty=onParty,foregroundRefreshIntervalMillis=refreshInterval)}}
    }
    @After fun cleanup(){
        compose.runOnUiThread{compose.activity.setContentView(android.widget.FrameLayout(compose.activity))}
        original?.let{v->SessionStore(compose.activity).apply{baseUrl=v.first;accessToken=v.second;refreshToken=v.third}}
        if(restoreViewer)ViewerProfileStore(compose.activity).apply{originalViewer?.let(::activate)?:clear()}
        server.shutdown()
    }
    @Test fun spoilerIsHiddenAndFailedLikePreservesServerStateUntilRetry(){
        val backend=backend(true);val likes=AtomicInteger()
        dispatch{r->when {
            r.requestUrl!!.encodedPath=="/v1/me"->json("""{"id":"owner"}""")
            r.requestUrl!!.encodedPath.endsWith("/like")->if(likes.incrementAndGet()==1)json("{}").setResponseCode(503)else json("""{"liked":true}""")
            else->json("""{"items":[${post(spoiler=true)}]}""")
        }}
        community(backend,true)
        scrollCommunityTo("community-post-post")
        compose.onNodeWithTag("community-post-post").performScrollTo()
        compose.onNodeWithText("پایان پنهان فیلم").assertDoesNotExist()
        compose.onNodeWithTag("community-reveal-post").performScrollTo().performClick()
        compose.onNodeWithText("پایان پنهان فیلم").assertExists()
        compose.onNodeWithTag("community-like-post").performScrollTo().performClick()
        compose.waitUntil(10000){likes.get()==1}
        compose.waitUntil(10000){compose.onAllNodesWithText("تغییر ثبت نشد؛ وضعیت قبلی حفظ شد.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("community-like-post").assertTextContains("4")
        compose.onNodeWithTag("community-like-post").performClick()
        compose.waitUntil(10000){runCatching{compose.onNodeWithTag("community-like-post").assertTextContains("5");true}.getOrDefault(false)}
    }
    @Test fun followingUsesServerScopeAndLoggedOutNeverRequestsIt(){
        val backend=backend(false);val auth=AtomicInteger()
        dispatch{r->json("""{"items":[],"nextCursor":null,"scope":"${if(r.requestUrl?.queryParameter("scope")=="following")"following"else "discovery"}"}""")}
        community(backend,false,{auth.incrementAndGet()})
        compose.waitUntil(10000){compose.onAllNodesWithText("اولین گفت‌وگو را تو شروع کن").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("community-scroll").performScrollToNode(hasText("لیست‌های سینمایی کاربران"))
        compose.onNodeWithText("بیشتر ببین").assertDoesNotExist()
        scrollCommunityTo("community-watch-together")
        compose.onNodeWithTag("community-watch-together").performScrollTo().performClick()
        assertEquals(1,auth.get())
        scrollCommunityTo("community-tab-1")
        compose.onNodeWithTag("community-tab-1").performScrollTo().performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("سلیقه‌های نزدیک به تو").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("ورود").performScrollTo().performClick()
        assertEquals(2,auth.get());assertFalse(requests.any{it.requestUrl?.queryParameter("scope")=="following"})
        compose.runOnUiThread{backend.session.accessToken="test";backend.session.refreshToken="test"}
        // The repository's authenticated following contract is separate from discovery.
        kotlinx.coroutines.runBlocking{SocialRepository(backend).feedPage(followingOnly=true)}
        assertTrue(requests.any{it.requestUrl?.encodedPath=="/v1/social/feed/personalized"&&it.requestUrl?.queryParameter("scope")=="following"})
    }
    @Test fun oldServerCannotMislabelDiscoveryAsFollowingAndNullMediaIsAbsent()=kotlinx.coroutines.runBlocking {
        val backend=backend(true)
        // The real unlinked-reel response has a media object whose LEFT JOIN fields are all null.
        val absentMedia="""{"id":null,"tmdbId":null,"kind":null,"title":null,"originalTitle":null,"posterUrl":null,"backdropUrl":null,"year":null,"rating":null}"""
        fun clipItem(media:String)="""{"id":"unrelated","body":"قدیمی","caption":"کلیپ بدون اثر پیوندشده","author":{"id":"other"},"media":$media}"""
        fun clipDispatch(media:String){dispatch{r->
            val item=clipItem(media)
            if(r.requestUrl?.encodedPath=="/v1/social/reels/unrelated/viewer")json(item)
            else json("""{"items":[$item],"nextCursor":null}""")
        }}
        clipDispatch(absentMedia)
        val social=SocialRepository(backend)
        val first=social.feedPage()
        assertNull(first.items.single().media);assertNull(first.nextCursor)
        val reels=social.reelsPage()
        assertNull(reels.nextCursor);assertNull(reels.items.single().media)
        assertNull(social.reel("unrelated").media)
        assertNull(social.mediaClips("catalog").single().media)
        assertNull(social.savedReels().single().media)
        val failure=runCatching{social.feedPage(followingOnly=true)}.exceptionOrNull()
        assertTrue(failure is CommunityServerUpgradeRequired)
        assertTrue(failure!!.message!!.contains("ارتقای سرور"))
        clipDispatch("""{"id":"catalog","tmdbId":77,"kind":"movie","title":"فیلم واقعی","originalTitle":null,"posterUrl":null,"backdropUrl":null,"year":2011,"rating":8.2}""")
        val linked=requireNotNull(social.reelsPage().items.single().media)
        assertEquals("catalog",linked.backendId);assertEquals(MediaType.MOVIE,linked.type)
        assertEquals("فیلم واقعی",linked.asMediaItem()?.title);assertEquals(77,linked.asMediaItem()?.id)
        assertEquals("",linked.originalTitle);assertNull(linked.posterUrl);assertNull(linked.backdropUrl)
        assertEquals(2011,linked.year);assertEquals(8.2,requireNotNull(linked.rating),0.0)
        for(cursor in listOf("","   ","null"," NULL ")) {
            dispatch{json(JSONObject().put("items",org.json.JSONArray()).put("nextCursor",cursor).toString())}
            assertNull(social.feedPage().nextCursor)
            assertNull(social.reelsPage().nextCursor)
        }
    }
    @Test fun onlyOwnerCanSeeDeleteAndFailedRemovalKeepsPost(){
        val backend=backend(true);val removals=AtomicInteger()
        dispatch{r->when {
            r.requestUrl!!.encodedPath=="/v1/me"->json("""{"id":"owner"}""")
            r.requestUrl!!.encodedPath.endsWith("/remove")->if(removals.incrementAndGet()==1)json("{}").setResponseCode(503)else json("""{"removed":true}""")
            else->json("""{"items":[${post()},${post("other","different")}]}""")
        }}
        community(backend,true)
        scrollCommunityTo("community-post-other")
        compose.onNodeWithTag("community-remove-other").assertDoesNotExist()
        scrollCommunityTo("community-remove-post")
        compose.onNodeWithTag("community-remove-post").performScrollTo().performClick()
        compose.onNodeWithTag("community-confirm-remove").performClick()
        compose.waitUntil(10000){removals.get()==1}
        compose.waitUntil(10000){compose.onAllNodesWithText("تغییر ثبت نشد؛ وضعیت قبلی حفظ شد.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("community-post-post").assertExists()
        compose.onNodeWithTag("community-confirm-remove").performClick()
        compose.waitUntil(10000){removals.get()==2&&compose.onAllNodesWithTag("community-post-post").fetchSemanticsNodes().isEmpty()}
    }
    @Test fun replyDraftSurvivesFailureAndParentIdentityIsSentOnRetry(){
        val backend=backend(true);val sent=CopyOnWriteArrayList<JSONObject>()
        val author=SocialAuthor("owner","fan","سینمادوست","",false)
        val post=SocialPost("post","post","بدنه",true,0,1,0,0,null,author=author,media=null)
        dispatch{r->when {
            r.method=="POST"&&r.requestUrl!!.encodedPath.endsWith("/comments")-> {sent+=JSONObject(r.body.readUtf8());if(sent.size==1)json("{}").setResponseCode(503)else json("""{"id":"new"}""")}
            r.requestUrl!!.encodedPath.endsWith("viewer-states")->json("""{"items":[]}""")
            else->json("""{"items":[{"id":"parent","body":"اسپویل پنهان پاسخ","spoiler":true,"author":{"id":"someone","displayName":"دوست"}}]}""")
        }}
        compose.setContent{FilmiqooTheme{CommunityReplySheet(post,SocialRepository(backend),true,{},{})}}
        compose.waitUntil(10000){compose.onAllNodesWithTag("community-reply-parent").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("اسپویل پنهان پاسخ").assertDoesNotExist()
        compose.onNodeWithTag("community-reply-to-parent").performClick()
        compose.onNodeWithTag("community-reply-draft").performTextInput("این پاسخ محفوظ می‌ماند")
        compose.onNodeWithTag("community-reply-send").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("ارسال نشد؛ متن محفوظ است. دوباره تلاش کن.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("community-reply-draft").assertTextContains("این پاسخ محفوظ می‌ماند")
        compose.onNodeWithTag("community-reply-send").performClick()
        compose.waitUntil(10000){sent.size==2}
        assertEquals("parent",sent[0].getString("parentCommentId"));assertEquals(sent[0].getString("body"),sent[1].getString("body"));assertEquals("parent",sent[1].getString("parentCommentId"))
    }
    @Test fun spoilerClipNeverAutoplaysAndWatchTogetherOpensRealCallback(){
        val backend=backend(true);var opened:String?=null;val party=AtomicInteger()
        dispatch{r->if(r.requestUrl!!.encodedPath.contains("reels"))json("""{"items":[{"id":"clip","caption":"اسپویل کلیپ پنهان","spoiler":true,"playbackUrl":"https://invalid.example/video.mp4","author":{"displayName":"سازنده"}}]}""")else json("""{"items":[]}""")}
        community(backend,true,onAuth={error("Logged-in watch together requested auth")},onClip={opened=it},onParty={party.incrementAndGet()})
        compose.onNodeWithTag("community-watch-together").performScrollTo().performClick();assertEquals(1,party.get())
        compose.onNodeWithTag("community-tab-3").performScrollTo().performClick()
        scrollCommunityTo("community-clip-clip")
        compose.onNodeWithText("اسپویل کلیپ پنهان").assertDoesNotExist()
        compose.onNodeWithTag("community-clip-clip").performScrollTo().performClick();assertEquals("clip",opened)
        assertFalse(requests.any{it.requestUrl?.encodedPath?.endsWith("/playback-event")==true})
    }
    @Test fun profilePartialFailureKeepsLibraryDownloadsAndSocialRoutesAvailable(){
        val backend=backend(true);val library=AtomicInteger();val downloads=AtomicInteger();val create=AtomicInteger()
        dispatch{r->when(r.requestUrl!!.encodedPath){
            "/v1/me"->json("""{"id":"owner","username":"cinema","displayName":"پروفایل آزمون","followers":12,"following":3}""")
            "/v1/library/stats"->json("{}").setResponseCode(503)
            else->json("""{"items":[]}""")
        }}
        compose.setContent{FilmiqooTheme{ConnectedProfileScreen(backend,TmdbRepository(compose.activity),onMedia={},onPlay={},onCommunity={},onDownloads={downloads.incrementAndGet()},onLibrary={library.incrementAndGet()},onSocialSaves={},onHistory={},onCreatorStudio={},onInbox={},onSettings={},onViewerProfiles={},onParentalControls={},onSecurity={},onSafety={},onFollowRequests={},onCloseFriends={},onEditProfile={},onFilmDna={},onReputation={},onSeriesCalendar={},onSocialCollections={},onLoggedOut={},onCreate={create.incrementAndGet()})}}
        compose.waitUntil(10000){compose.onAllNodesWithText("پروفایل آزمون").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("همگام‌سازی کامل نشد").performScrollTo().assertExists()
        scrollLazyTo("profile-scroll","profile-library")
        compose.onNodeWithTag("profile-library").performClick()
        scrollLazyTo("profile-scroll","profile-downloads")
        compose.onNodeWithTag("profile-downloads").performClick()
        scrollLazyTo("profile-scroll","profile-create")
        compose.onNodeWithTag("profile-create").performClick()
        assertEquals(1,library.get());assertEquals(1,downloads.get());assertEquals(1,create.get())
    }
    @Test fun kidsProfileHidesAccountEditAndSettingsButKeepsSafeLibraryAndParentExit(){
        val backend=backend(true);val parent=AtomicInteger();val library=AtomicInteger();val downloads=AtomicInteger()
        originalViewer=backend.viewerProfiles.active();restoreViewer=true
        backend.viewerProfiles.activate(ViewerProfile("test-kid","تماشاگر کوچک","",true,"all","fa","fa",true,false))
        dispatch{r->if(r.requestUrl!!.encodedPath=="/v1/me")json("""{"id":"adult","displayName":"حساب والدین","username":"adult"}""")else json("""{"items":[]}""")}
        compose.setContent{FilmiqooTheme{ConnectedProfileScreen(backend,TmdbRepository(compose.activity),kidsMode=true,onMedia={},onPlay={},onCommunity={error("kids social")},onDownloads={downloads.incrementAndGet()},onLibrary={library.incrementAndGet()},onSocialSaves={},onHistory={},onCreatorStudio={},onInbox={},onSettings={error("kids settings")},onViewerProfiles={parent.incrementAndGet()},onParentalControls={},onSecurity={},onSafety={},onFollowRequests={},onCloseFriends={},onEditProfile={error("kids account edit")},onFilmDna={},onReputation={},onSeriesCalendar={},onSocialCollections={},onLoggedOut={})}}
        compose.onNodeWithText("تماشاگر کوچک").assertExists()
        compose.onNodeWithText("ویرایش پروفایل").assertDoesNotExist()
        compose.onNodeWithTag("profile-settings").assertDoesNotExist()
        compose.onNodeWithTag("profile-more").assertDoesNotExist()
        compose.onNodeWithTag("profile-create").assertDoesNotExist()
        compose.onNodeWithTag("profile-identity-action").performScrollTo().performClick()
        scrollLazyTo("profile-scroll","profile-library")
        compose.onNodeWithTag("profile-library").performClick()
        scrollLazyTo("profile-scroll","profile-downloads")
        compose.onNodeWithTag("profile-downloads").performClick()
        assertEquals(1,parent.get());assertEquals(1,library.get());assertEquals(1,downloads.get())
    }
    @Test fun foregroundNewPostBannerWaitsForUserAndFailedRefreshKeepsReadableFeed(){
        val backend=backend(false)
        val feedCalls=AtomicInteger();val stage=AtomicInteger(0)
        dispatch{r->
            if(r.requestUrl!!.encodedPath in setOf("/v1/social/feed","/v1/social/feed/personalized")) {
                feedCalls.incrementAndGet()
                when(stage.get()) {
                    0->json("""{"items":[${post("old")}],"scope":"discovery"}""")
                    1->json("""{"items":[${post("new")},${post("old")}],"scope":"discovery"}""")
                    2->json("{}").setResponseCode(503)
                    else->json("""{"items":[${post("new")},${post("old")}],"scope":"discovery"}""")
                }
            }else json("""{"items":[]}""")
        }
        community(backend,false,refreshInterval=1000L)
        scrollCommunityTo("community-post-old")
        assertTrue(requests.any{it.requestUrl?.encodedPath=="/v1/social/feed"})
        assertTrue(feedCalls.get()>0)
        compose.onNodeWithTag("community-post-new").assertDoesNotExist()
        val initialCalls=feedCalls.get()
        stage.set(1)
        compose.waitUntil(10000){feedCalls.get()>initialCalls}
        scrollCommunityTo("community-new-posts")
        compose.onNodeWithTag("community-new-posts").assertTextContains("1 پست تازه")
        // Polling announces unseen IDs; it never injects a post while someone is reading.
        compose.onNodeWithTag("community-post-new").assertDoesNotExist()
        stage.set(2)
        compose.onNodeWithTag("community-new-posts").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("اتصال کامل نشد").fetchSemanticsNodes().isNotEmpty()}
        scrollCommunityTo("community-post-old")
        compose.onNodeWithTag("community-post-old").assertExists()
        compose.onNodeWithTag("community-post-new").assertDoesNotExist()
        stage.set(3)
        scrollCommunityTo("community-new-posts")
        compose.onNodeWithTag("community-new-posts").performClick()
        scrollCommunityTo("community-post-new")
        compose.onNodeWithTag("community-post-new").assertExists()
        scrollCommunityTo("community-post-old")
        compose.onNodeWithTag("community-post-old").assertExists()
        compose.onNodeWithTag("community-new-posts").assertDoesNotExist()
    }
}
