package com.filmiqoo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchDeepLink=intent?.dataString
        setContent {
            FilmiqooTheme {
                FilmiqooApp(initialDeepLink=launchDeepLink)
            }
        }
    }
}

@Composable
fun FilmiqooApp(initialDeepLink:String?=null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { TmdbRepository(context.applicationContext) }
    val backend = remember { BackendRepository(context.applicationContext) }
    val social = remember { SocialRepository(backend) }
    val messaging = remember { MessagingRepository(backend) }
    val viewerProfilesRepository = remember { ViewerProfilesRepository(backend) }
    val viewerStore = remember { backend.viewerProfiles }
    val store = remember { LocalStore(context.applicationContext) }
    val appScope = rememberCoroutineScope()

    var authenticated by remember { mutableStateOf(backend.session.isLoggedIn) }
    var previewMode by remember { mutableStateOf(false) }
    var configuredPreview by remember { mutableStateOf(repository.hasApiKey()) }
    var tab by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<OverlayRoute?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var activeViewer by remember { mutableStateOf(viewerStore.active()) }
    var viewerReady by remember { mutableStateOf(!authenticated) }
    var deepLinkHandled by remember(initialDeepLink) { mutableStateOf(false) }

    LaunchedEffect(authenticated) {
        if(!authenticated) {
            activeViewer=null
            viewerReady=true
        } else {
            viewerReady=false
            runCatching { viewerProfilesRepository.list() }
                .onSuccess { profiles->
                    val currentId=viewerStore.activeId()
                    val resolved=profiles.firstOrNull { it.id==currentId }
                        ?: profiles.firstOrNull()
                    if(resolved!=null) {
                        if(resolved.pinProtected) {
                            viewerStore.clear()
                            activeViewer=null
                            overlay=OverlayRoute.ViewerProfiles
                        } else {
                            viewerStore.activate(resolved)
                            activeViewer=resolved
                        }
                    }
                }
            viewerReady=true
        }
    }

    LaunchedEffect(initialDeepLink,authenticated,viewerReady,activeViewer?.id) {
        val raw=initialDeepLink
        if(
            !deepLinkHandled &&
            authenticated &&
            viewerReady &&
            activeViewer!=null &&
            !raw.isNullOrBlank()
        ) {
            val uri=runCatching { android.net.Uri.parse(raw) }.getOrNull()
            val versionId=uri?.takeIf {
                it.scheme=="filmiqoo" && it.host=="play"
            }?.pathSegments?.firstOrNull()
            if(!versionId.isNullOrBlank()) {
                val position=uri.getQueryParameter("t")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                runCatching { backend.playbackContext(versionId) }
                    .onSuccess { target->
                        overlay=OverlayRoute.Player(
                            target.copy(startPositionMs=position)
                        )
                        deepLinkHandled=true
                    }
                    .onFailure {
                        deepLinkHandled=true
                    }
            } else {
                deepLinkHandled=true
            }
        }
    }

    val closeOverlay = {
        overlay = null
        showSearch = false
    }

    BackHandler(enabled = overlay != null || showSearch) {
        closeOverlay()
    }

    Surface(Modifier.fillMaxSize(),color=FqBg) {
        if (!authenticated && !previewMode && overlay !is OverlayRoute.Auth) {
            AuthScreen(
                backend=backend,
                onSuccess={
                    viewerReady=false
                    authenticated=true
                },
                onPreview={ previewMode=true }
            )
            return@Surface
        }

        if (previewMode && !configuredPreview && !authenticated) {
            TmdbSetupScreen(
                onSave = { value ->
                    repository.setApiKey(value)
                    configuredPreview = repository.hasApiKey()
                },
                onSkip = { configuredPreview=true }
            )
            return@Surface
        }

        if(authenticated && !viewerReady) {
            LoadingPage("در حال آماده‌سازی پروفایل تماشا...")
            return@Surface
        }

        when {
            showSearch -> PremiumSearchScreen(
                repository=repository,
                backend=backend,
                onBack={showSearch=false},
                onMedia={ overlay=OverlayRoute.Detail(it); showSearch=false },
                onCreator={ overlay=OverlayRoute.CreatorPage(it); showSearch=false },
                onOpenReels={
                    showSearch=false
                    tab=1
                }
            )
            overlay != null -> when(val route=overlay!!) {
                is OverlayRoute.Detail -> PremiumDetailScreen(
                    media=route.media,
                    repository=repository,
                    backend=backend,
                    store=store,
                    onBack=closeOverlay,
                    onMedia={ overlay=OverlayRoute.Detail(it) },
                    onChat={ overlay=OverlayRoute.Chat("روم رسمی " + it.title,it) },
                    onWatchParty={ overlay=OverlayRoute.WatchParty(it) },
                    onPlay={ target ->
                        if (backend.session.isLoggedIn) {
                            overlay=OverlayRoute.Player(target)
                        } else {
                            overlay=OverlayRoute.Auth
                        }
                    },
                    onPerson={person->
                        overlay=OverlayRoute.PersonPage(person.id,person.name)
                    },
                    onRequireAuth={overlay=OverlayRoute.Auth}
                )
                is OverlayRoute.Player -> FilmiqooPlayerScreen(
                    target=route.target,
                    backend=backend,
                    onBack=closeOverlay
                )
                OverlayRoute.Downloads -> DownloadsScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onPlay={overlay=OverlayRoute.Player(it)}
                )
                OverlayRoute.Library -> LibraryScreen(
                    backend=backend,
                    repository=repository,
                    onBack=closeOverlay,
                    onMedia={overlay=OverlayRoute.Detail(it)}
                )
                OverlayRoute.SocialSaves -> SavedSocialScreen(
                    social=social,
                    repository=repository,
                    onBack=closeOverlay,
                    onCreator={overlay=OverlayRoute.CreatorPage(it)},
                    onMedia={overlay=OverlayRoute.Detail(it)}
                )
                OverlayRoute.History -> WatchHistoryScreen(
                    backend=backend,
                    repository=repository,
                    onBack=closeOverlay,
                    onPlay={overlay=OverlayRoute.Player(it)},
                    onMedia={overlay=OverlayRoute.Detail(it)}
                )
                is OverlayRoute.Room -> ConnectedRoomScreen(
                    roomId=route.roomId,
                    title=route.title,
                    social=social,
                    backend=backend,
                    loggedIn=backend.session.isLoggedIn,
                    onRequireAuth={overlay=OverlayRoute.Auth},
                    onBack=closeOverlay
                )
                is OverlayRoute.Auth -> AuthScreen(
                    backend=backend,
                    onSuccess={
                        viewerReady=false
                        authenticated=true
                        overlay=null
                    },
                    onPreview={
                        previewMode=true
                        overlay=null
                    }
                )
                is OverlayRoute.Story -> StoryViewer(
                    media=route.media,
                    repository=repository,
                    onClose=closeOverlay,
                    onMedia={ overlay=OverlayRoute.Detail(it) }
                )
                is OverlayRoute.SocialStories -> SocialStoryViewerScreen(
                    stories=route.stories,
                    startIndex=route.index,
                    social=social,
                    loggedIn=backend.session.isLoggedIn,
                    onRequireAuth={overlay=OverlayRoute.Auth},
                    onMedia={overlay=OverlayRoute.Detail(it)},
                    onClose=closeOverlay
                )
                is OverlayRoute.Chat -> ChatRoomScreen(
                    title=route.title,
                    media=route.media,
                    repository=repository,
                    onBack=closeOverlay,
                    onMedia={ overlay=OverlayRoute.Detail(it) }
                )
                is OverlayRoute.PersonPage -> PersonScreen(
                    personId=route.personId,
                    initialName=route.name,
                    repository=repository,
                    onBack=closeOverlay,
                    onMedia={overlay=OverlayRoute.Detail(it)}
                )
                is OverlayRoute.CreatorPage -> PremiumCreatorChannelScreen(
                    creator=route.creator,
                    backend=backend,
                    social=social,
                    onBack=closeOverlay,
                    onMedia={overlay=OverlayRoute.Detail(it)},
                    onOpenRoom={overlay=OverlayRoute.Room(it.id,it.name)},
                    onStory={stories,index->overlay=OverlayRoute.SocialStories(stories,index)},
                    onOpenReels={
                        overlay=null
                        tab=1
                    },
                    onStartDm={userId,title->
                        if(!backend.session.isLoggedIn) {
                            overlay=OverlayRoute.Auth
                        } else {
                            appScope.launch {
                                runCatching { messaging.ensureDm(userId) }
                                    .onSuccess { dm->
                                        overlay=OverlayRoute.Room(dm.id,dm.title.ifBlank { title })
                                    }
                            }
                        }
                    },
                    onManageChannel={channelId,name->
                        overlay=OverlayRoute.ChannelManage(channelId,name)
                    },
                    onRequireAuth={overlay=OverlayRoute.Auth}
                )
                is OverlayRoute.ChannelManage -> ChannelManageScreen(
                    channelId=route.channelId,
                    backend=backend,
                    onBack=closeOverlay,
                    onOpenRoom={id,name->overlay=OverlayRoute.Room(id,name)}
                )
                OverlayRoute.CreatorStudio -> CreatorStudioScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onLive={overlay=OverlayRoute.LiveHub}
                )
                OverlayRoute.LiveHub -> LiveHubScreen(
                    backend=backend,
                    repository=repository,
                    onBack=closeOverlay,
                    onOpenRoom={id,title->overlay=OverlayRoute.Room(id,title)},
                    onMedia={overlay=OverlayRoute.Detail(it)},
                    onRequireAuth={overlay=OverlayRoute.Auth}
                )
                OverlayRoute.Inbox -> InboxScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onOpenRoom={conversation->
                        overlay=OverlayRoute.Room(conversation.id,conversation.title)
                    }
                )
                OverlayRoute.Settings -> SettingsScreen(
                    backend=backend,
                    onBack=closeOverlay
                )
                OverlayRoute.ViewerProfiles -> ViewerProfilesScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onActivated={ profile->
                        viewerStore.activate(profile)
                        activeViewer=profile
                        tab=0
                        overlay=null
                    }
                )
                OverlayRoute.ParentalGate -> ParentalGateScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onVerified={overlay=OverlayRoute.ViewerProfiles}
                )
                OverlayRoute.ParentalControls -> ParentalControlsScreen(
                    backend=backend,
                    onBack=closeOverlay
                )
                OverlayRoute.Security -> SecurityScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onCurrentSessionRevoked={
                        authenticated=false
                        previewMode=false
                        overlay=null
                    }
                )
                OverlayRoute.Safety -> SafetyCenterScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onCreator={overlay=OverlayRoute.CreatorPage(it)}
                )
                OverlayRoute.FollowRequests -> FollowRequestsScreen(
                    social=social,
                    onBack=closeOverlay,
                    onCreator={overlay=OverlayRoute.CreatorPage(it)}
                )
                OverlayRoute.EditProfile -> EditProfileScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onSaved={overlay=null}
                )
                OverlayRoute.FilmDna -> FilmDnaScreen(
                    backend=backend,
                    onBack=closeOverlay
                )
                is OverlayRoute.SocialCollections -> SocialCollectionsScreen(
                    backend=backend,
                    repository=repository,
                    loggedIn=backend.session.isLoggedIn,
                    initialCollectionId=route.collectionId,
                    onBack=closeOverlay,
                    onMedia={overlay=OverlayRoute.Detail(it)},
                    onCreator={overlay=OverlayRoute.CreatorPage(it)},
                    onRequireAuth={overlay=OverlayRoute.Auth}
                )
                OverlayRoute.Releases -> ReleaseCenterScreen(
                    backend=backend,
                    repository=repository,
                    onBack=closeOverlay,
                    onMedia={overlay=OverlayRoute.Detail(it)},
                    onRequireAuth={overlay=OverlayRoute.Auth}
                )
                OverlayRoute.SeriesCalendar -> SeriesCalendarScreen(
                    backend=backend,
                    repository=repository,
                    onBack=closeOverlay,
                    onMedia={overlay=OverlayRoute.Detail(it)}
                )
                is OverlayRoute.WatchParty -> ConnectedWatchPartyScreen(
                    media=route.media,
                    initialPartyId=route.partyId,
                    backend=backend,
                    social=social,
                    repository=repository,
                    onBack=closeOverlay,
                    onRequireAuth={overlay=OverlayRoute.Auth}
                )
                OverlayRoute.Create -> PremiumCreateHubScreen(
                    social=social,
                    backend=backend,
                    repository=repository,
                    loggedIn=backend.session.isLoggedIn,
                    onRequireAuth={overlay=OverlayRoute.Auth},
                    onBack=closeOverlay
                )
                OverlayRoute.Notifications -> ConnectedNotificationsScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onOpenRoom={id,title->overlay=OverlayRoute.Room(id,title)},
                    onOpenCreator={overlay=OverlayRoute.CreatorPage(it)},
                    onOpenMedia={overlay=OverlayRoute.Detail(it)},
                    onOpenCollection={overlay=OverlayRoute.SocialCollections(it)},
                    onFollowRequests={overlay=OverlayRoute.FollowRequests}
                )
            }
            else -> Scaffold(
                containerColor=FqBg,
                bottomBar={
                    FilmiqooBottomBar(
                        selected=tab,
                        kidsMode=activeViewer?.kidsMode==true,
                        onSelected={ index ->
                            if(index==2) {
                                overlay=OverlayRoute.Create
                            } else {
                                tab=index
                            }
                        }
                    )
                }
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    when(tab) {
                        0 -> PremiumHomeScreen(
                            repository=repository,
                            backend=backend,
                            loggedIn=backend.session.isLoggedIn,
                            onMedia={overlay=OverlayRoute.Detail(it)},
                            onPlay={overlay=OverlayRoute.Player(it)},
                            onStory={m,i->overlay=OverlayRoute.Story(m,i)},
                            onSearch={
                                if(activeViewer?.kidsMode!=true) {
                                    showSearch=true
                                }
                            },
                            onNotifications={overlay=OverlayRoute.Notifications},
                            onReleases={overlay=OverlayRoute.Releases},
                            onWatchParty={overlay=OverlayRoute.WatchParty(it)}
                        )
                        1 -> ConnectedExploreScreen(
                            social=social,
                            backend=backend,
                            repository=repository,
                            store=store,
                            loggedIn=backend.session.isLoggedIn,
                            onMedia={overlay=OverlayRoute.Detail(it)},
                            onChat={overlay=OverlayRoute.Chat("گفت‌وگو درباره " + it.title,it)},
                            onCreator={overlay=OverlayRoute.CreatorPage(it)},
                            onRequireAuth={overlay=OverlayRoute.Auth}
                        )
                        3 -> CommunityScreen(
                            social=social,
                            backend=backend,
                            loggedIn=backend.session.isLoggedIn,
                            onOpenRoom={overlay=OverlayRoute.Room(it.id,it.name)},
                            onCreator={overlay=OverlayRoute.CreatorPage(it)},
                            onStory={stories,index->overlay=OverlayRoute.SocialStories(stories,index)},
                            onInbox={overlay=OverlayRoute.Inbox},
                            onRequireAuth={overlay=OverlayRoute.Auth}
                        )
                        else -> {
                            if(backend.session.isLoggedIn) {
                                ConnectedProfileScreen(
                                    backend=backend,
                                    repository=repository,
                                    kidsMode=activeViewer?.kidsMode==true,
                                    onMedia={overlay=OverlayRoute.Detail(it)},
                                    onPlay={overlay=OverlayRoute.Player(it)},
                                    onCommunity={tab=3},
                                    onDownloads={overlay=OverlayRoute.Downloads},
                                    onLibrary={overlay=OverlayRoute.Library},
                                    onSocialSaves={overlay=OverlayRoute.SocialSaves},
                                    onHistory={overlay=OverlayRoute.History},
                                    onCreatorStudio={overlay=OverlayRoute.CreatorStudio},
                                    onInbox={overlay=OverlayRoute.Inbox},
                                    onSettings={overlay=OverlayRoute.Settings},
                                    onViewerProfiles={
                                        overlay=if(activeViewer?.kidsMode==true)
                                            OverlayRoute.ParentalGate
                                        else
                                            OverlayRoute.ViewerProfiles
                                    },
                                    onParentalControls={overlay=OverlayRoute.ParentalControls},
                                    onSecurity={overlay=OverlayRoute.Security},
                                    onSafety={overlay=OverlayRoute.Safety},
                                    onFollowRequests={overlay=OverlayRoute.FollowRequests},
                                    onEditProfile={overlay=OverlayRoute.EditProfile},
                                    onFilmDna={overlay=OverlayRoute.FilmDna},
                                    onSeriesCalendar={overlay=OverlayRoute.SeriesCalendar},
                                    onSocialCollections={overlay=OverlayRoute.SocialCollections()},
                                    onLoggedOut={
                                        backend.viewerProfiles.clear()
                                        activeViewer=null
                                        authenticated=false
                                        previewMode=false
                                    }
                                )
                            } else {
                                ProfileScreen(
                                    repository=repository,
                                    store=store,
                                    onCreator={
                                        overlay=OverlayRoute.CreatorPage(
                                            Creator("Preview","@preview","","حالت نمایشی Filmiqoo")
                                        )
                                    },
                                    onWatchParty={overlay=OverlayRoute.WatchParty(null)},
                                    onMessages={tab=3},
                                    onMedia={overlay=OverlayRoute.Detail(it)}
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilmiqooBottomBar(
    selected: Int,
    kidsMode: Boolean = false,
    onSelected: (Int) -> Unit
) {
    val entries = if(kidsMode) {
        listOf(
            Triple(Icons.Default.Home,"خانه",0),
            Triple(Icons.Default.PersonOutline,"پروفایل",4)
        )
    } else {
        listOf(
            Triple(Icons.Default.Home,"خانه",0),
            Triple(Icons.Default.Explore,"اکسپلور",1),
            Triple(Icons.Default.Add,"",2),
            Triple(Icons.Default.Groups,"اجتماعی",3),
            Triple(Icons.Default.PersonOutline,"پروفایل",4)
        )
    }

    NavigationBar(
        containerColor=Color(0xFF0B0D11),
        tonalElevation=10.dp
    ) {
        entries.forEach { item ->
            if(item.third==2) {
                NavigationBarItem(
                    selected=false,
                    onClick={onSelected(2)},
                    icon={
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(FqGold),
                            contentAlignment=Alignment.Center
                        ) {
                            Icon(Icons.Default.Add,null,tint=Color.Black,modifier=Modifier.size(28.dp))
                        }
                    },
                    label=null,
                    alwaysShowLabel=false
                )
            } else {
                NavigationBarItem(
                    selected=selected==item.third,
                    onClick={onSelected(item.third)},
                    icon={Icon(item.first,null)},
                    label={Text(item.second,fontSize=9.sp)},
                    colors=NavigationBarItemDefaults.colors(
                        selectedIconColor=FqGold,
                        selectedTextColor=FqGold,
                        indicatorColor=FqSurface2,
                        unselectedIconColor=FqMuted,
                        unselectedTextColor=FqMuted
                    )
                )
            }
        }
    }
}

@Composable
private fun TmdbSetupScreen(
    onSave: (String) -> Unit,
    onSkip: () -> Unit
) {
    var value by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(
                listOf(Color(0xFF111722), FqBg)
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(FqGold),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow,null,tint=Color.Black,modifier=Modifier.size(54.dp))
            }
            Text("FILMIQOO",color=FqGold,fontSize=30.sp,modifier=Modifier.padding(top=14.dp))
            Text("Preview Mode",color=FqMuted,fontSize=12.sp,modifier=Modifier.padding(top=4.dp))

            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(20.dp),
                modifier=Modifier.fillMaxWidth().padding(top=28.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("TMDB برای حالت Preview",fontSize=18.sp)
                    Text(
                        "اگر Backend Filmiqoo را اجرا نمی‌کنی، می‌توانی برای نمایش محتوای نمونه کلید TMDB را وارد کنی.",
                        color=FqMuted,
                        fontSize=11.sp,
                        lineHeight=18.sp,
                        modifier=Modifier.padding(top=7.dp)
                    )
                    OutlinedTextField(
                        value=value,
                        onValueChange={value=it},
                        label={Text("TMDB API Key / Token")},
                        singleLine=false,
                        minLines=2,
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=14.dp)
                    )
                    Button(
                        onClick={ onSave(value.trim()) },
                        enabled=value.trim().length>=20,
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=12.dp)
                    ) {
                        Text("فعال‌کردن Preview")
                    }
                    TextButton(
                        onClick={showHelp=!showHelp},
                        modifier=Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text("راهنما",color=FqGold)
                    }
                    if(showHelp) {
                        Text(
                            "در حالت Production کلید TMDB داخل APK قرار نمی‌گیرد و Backend Filmiqoo آن را مدیریت می‌کند.",
                            color=FqMuted,
                            fontSize=10.sp,
                            lineHeight=17.sp,
                            modifier=Modifier.padding(top=4.dp)
                        )
                    }
                }
            }
            TextButton(onClick=onSkip,modifier=Modifier.padding(top=8.dp)) {
                Text("رد کردن",color=FqMuted)
            }
        }
    }
}
