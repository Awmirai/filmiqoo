package com.filmiqoo.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private val deepLinkState=mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FilmiqooCrashStore.install(applicationContext)
        deepLinkState.value=intent?.dataString
        setContent {
            FilmiqooTheme {
                CinemaSplash { IranAccessGate { FilmiqooApp(initialDeepLink=deepLinkState.value) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkState.value=intent.dataString
    }
}

@Composable
fun FilmiqooApp(initialDeepLink:String?=null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { TmdbRepository(context.applicationContext) }
    val backend = remember { BackendRepository(context.applicationContext) }
    val telemetry = remember {
        TelemetryRepository(context.applicationContext,backend)
    }
    val social = remember { SocialRepository(backend) }
    val messaging = remember { MessagingRepository(backend) }
    val creatorChannels = remember { CreatorChannelRepository(backend) }
    val viewerProfilesRepository = remember { ViewerProfilesRepository(backend) }
    val playbackHandoffRepository = remember {
        PlaybackHandoffRepository(context.applicationContext,backend)
    }
    val viewerStore = remember { backend.viewerProfiles }
    val store = remember { LocalStore(context.applicationContext) }
    val appScope = rememberCoroutineScope()
    val notificationPermissionLauncher=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    var authenticated by remember { mutableStateOf(backend.session.isLoggedIn) }
    var previewMode by remember { mutableStateOf(false) }
    var configuredPreview by remember { mutableStateOf(repository.hasApiKey()) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val bottomTabStateHolder=rememberSaveableStateHolder()
    val overlayStateHolder=rememberSaveableStateHolder()
    val navigation=remember(context) { androidx.lifecycle.ViewModelProvider(context as androidx.lifecycle.ViewModelStoreOwner)[CinemaNavigationState::class.java] }
    var overlay by navigation.overlay
    val overlayBackStack=navigation.backStack
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var activeViewer by remember { mutableStateOf(viewerStore.active()) }
    var viewerReady by remember { mutableStateOf(!authenticated) }
    var deepLinkHandled by rememberSaveable(initialDeepLink) { mutableStateOf(false) }
    var deepLinkReelId by rememberSaveable { mutableStateOf<String?>(null) }
    var clipResumeReelId by rememberSaveable { mutableStateOf<String?>(null) }
    var deepLinkPostId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingHandoff by remember { mutableStateOf<PendingPlaybackHandoff?>(null) }
    var handoffActionBusy by remember { mutableStateOf(false) }
    var showNotificationPrimer by rememberSaveable { mutableStateOf(false) }
    var socialBadgeRefresh by rememberSaveable { mutableIntStateOf(0) }
    var navigationError by remember { mutableStateOf<String?>(null) }

    fun resetNavigation() {
        (0..4).forEach { bottomTabStateHolder.removeState("cinema090-tab-"+(activeViewer?.id ?: "guest")+"-"+it) }
        (overlayBackStack.toList()+listOfNotNull(overlay)).forEach { overlayStateHolder.removeState(it.javaClass.name+":"+it.hashCode()) }
        overlayBackStack.clear();overlay=null;tab=0;showSearch=false
        deepLinkPostId=null;deepLinkReelId=null;pendingHandoff=null
    }

    DisposableEffect(backend,context) {
        val prefs=context.applicationContext.getSharedPreferences(
            "filmiqoo_session_v1",
            android.content.Context.MODE_PRIVATE
        )
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
            if(key=="access_token" || key=="refresh_token") {
                if(authenticated && !backend.session.isLoggedIn) resetNavigation()
                authenticated=backend.session.isLoggedIn
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    LaunchedEffect(Unit) {
        telemetry.flushPendingCrash()
        telemetry.event(
            type="app_started",
            metadata=org.json.JSONObject()
                .put("authenticated",backend.session.isLoggedIn)
        )
    }

    LaunchedEffect(authenticated) {
        if(authenticated && FilmiqooPush.initialize(context.applicationContext)) {
            runCatching {
                FilmiqooPush.registerIfPossible(
                    context.applicationContext,
                    backend
                )
            }.onFailure {
                telemetry.event(
                    type="push_registration_failed",
                    severity="warning",
                    message=it.message.orEmpty()
                )
            }
        }


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

    LaunchedEffect(authenticated,viewerReady,activeViewer?.id) {
        if(!authenticated || !viewerReady || activeViewer==null) return@LaunchedEffect

        while(true) {
            runCatching { playbackHandoffRepository.heartbeat() }

            runCatching { playbackHandoffRepository.pending() }
                .onSuccess { incoming->
                    if(incoming!=null && pendingHandoff?.id!=incoming.id) {
                        pendingHandoff=incoming
                    }
                }

            delay(5_000)
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
            val supported=uri?.scheme=="filmiqoo"
            if(!supported) {
                deepLinkHandled=true
                return@LaunchedEffect
            }

            val host=uri.host.orEmpty().lowercase()
            val id=uri.pathSegments.firstOrNull().orEmpty()
            val kidsMode=activeViewer?.kidsMode==true
            val socialHost=host in setOf(
                "creator","channel","collection","party","room","room-invite","reel","post","live","notifications"
            )

            if(kidsMode && socialHost) {
                overlay=null
                showSearch=false
                tab=0
                deepLinkHandled=true
                return@LaunchedEffect
            }

            runCatching {
                when(host) {
                    "notifications" -> overlay=OverlayRoute.Notifications
                    "play" -> {
                        require(id.isNotBlank())
                        val position=uri.getQueryParameter("t")
                            ?.toLongOrNull()
                            ?.coerceAtLeast(0L)
                            ?: 0L
                        val target=backend.playbackContext(id)
                        overlay=OverlayRoute.Player(
                            target.copy(startPositionMs=position)
                        )
                    }

                    "title" -> {
                        require(id.isNotBlank())
                        overlay=OverlayRoute.Detail(
                            backend.detail(id).asMediaItem()
                        )
                    }

                    "creator" -> {
                        require(id.isNotBlank())
                        val p=creatorChannels.userProfile(id)
                        overlay=OverlayRoute.CreatorPage(
                            Creator(
                                name=p.displayName,
                                handle="@"+p.username,
                                followers=p.followers.toString(),
                                bio=p.bio,
                                verified=p.verified,
                                id=p.id,
                                entityType="user",
                                avatarUrl=p.avatarUrl,
                                coverUrl=p.coverUrl
                            )
                        )
                    }

                    "channel" -> {
                        require(id.isNotBlank())
                        val p=creatorChannels.channelProfile(id)
                        overlay=OverlayRoute.CreatorPage(
                            Creator(
                                name=p.name,
                                handle="@"+p.slug,
                                followers=p.followers.toString(),
                                bio=p.bio,
                                verified=p.verified,
                                id=p.id,
                                entityType="channel",
                                avatarUrl=p.avatarUrl,
                                coverUrl=p.coverUrl
                            )
                        )
                    }

                    "collection" -> {
                        require(id.isNotBlank())
                        overlay=OverlayRoute.SocialCollections(id)
                    }

                    "party" -> {
                        require(id.isNotBlank())
                        overlay=OverlayRoute.WatchParty(
                            media=null,
                            partyId=id,
                            inviteCode=uri.getQueryParameter("invite")
                                ?.takeIf(String::isNotBlank)
                        )
                    }

                    "live" -> {
                        require(id.isNotBlank())
                        overlay=OverlayRoute.LiveHub(id)
                    }

                    "room" -> {
                        require(id.isNotBlank())
                        overlay=OverlayRoute.Room(
                            roomId=id,
                            title=uri.getQueryParameter("title")
                                ?.takeIf(String::isNotBlank)
                                ?: "Filmiqoo Room"
                        )
                    }

                    "room-invite" -> {
                        require(id.isNotBlank())
                        val joined=messaging.joinRoomInvite(id)
                        overlay=OverlayRoute.Room(
                            roomId=joined.id,
                            title=joined.title.ifBlank { "Filmiqoo Group" }
                        )
                    }

                    "reel" -> {
                        require(id.isNotBlank())
                        showSearch=false
                        overlay=OverlayRoute.Clips(id)
                    }

                    "post" -> {
                        require(id.isNotBlank())
                        overlay=OverlayRoute.Post(id)
                        showSearch=false
                    }

                    else -> Unit
                }
            }
            deepLinkHandled=true
        }
    }

    val pushOverlay:(OverlayRoute)->Unit = { next ->
        overlay?.let { current ->
            overlayBackStack.add(current)
        }
        overlay=next
    }

    val closeOverlay:()->Unit = {
        overlay?.let { overlayStateHolder.removeState(it.javaClass.name+":"+it.hashCode()) }
        overlay=if(overlayBackStack.isNotEmpty()) {
            overlayBackStack.removeAt(overlayBackStack.lastIndex)
        } else {
            null
        }
        showSearch=false
        socialBadgeRefresh++
        Unit
    }

    val openNotifications = {
        if(activeViewer?.kidsMode==true)overlay=OverlayRoute.ParentalGate
        else if(!backend.session.isLoggedIn)overlay=OverlayRoute.Auth
        else {
          overlay=OverlayRoute.Notifications
          if(
            Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU &&
            FilmiqooPush.isConfigured() &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            )!=PackageManager.PERMISSION_GRANTED
          ) {
            showNotificationPrimer=true
          }
        }
    }

    val openMediaRoom: (MediaItem)->Unit = { media ->
        val mediaId=media.backendId
        val sourceRoute=overlay
        if(mediaId.isNullOrBlank()) {
            pushOverlay(OverlayRoute.Detail(media))
        } else {
            appScope.launch {
                runCatching { social.roomForMedia(mediaId) }
                    .onSuccess { room ->
                        if(overlay==sourceRoute) {
                            if(room!=null) {
                                sourceRoute?.let(overlayBackStack::add)
                                overlay=OverlayRoute.Room(room.id,room.name)
                            } else {
                                pushOverlay(OverlayRoute.Detail(media))
                            }
                        }
                    }
                    .onFailure {
                        if(overlay==sourceRoute) {
                            navigationError="گفت‌وگو دریافت نشد؛ اتصال را بررسی کن و دوباره تلاش کن."
                        }
                    }
            }
        }
    }

    BackHandler(
        enabled=overlay!=null || showSearch || overlayBackStack.isNotEmpty()
    ) {
        if(overlay==null && !showSearch && overlayBackStack.isNotEmpty()) {
            overlay=overlayBackStack.removeAt(overlayBackStack.lastIndex)
            deepLinkReelId=null
            deepLinkPostId=null
        } else {
            closeOverlay()
        }
    }

    BackHandler(enabled=overlay==null && !showSearch && tab!=0) { tab=0 }

    navigationError?.let { message -> AlertDialog(onDismissRequest={navigationError=null},
        title={Text("گفت‌وگو در دسترس نیست")},text={Text(message)},
        confirmButton={TextButton({navigationError=null}){Text("باشه")}}) }

    if(showNotificationPrimer) {
        AlertDialog(
            onDismissRequest={showNotificationPrimer=false},
            icon={
                Icon(
                    Icons.Default.NotificationsActive,
                    null,
                    tint=FqGold
                )
            },
            title={Text("اعلان‌های Filmiqoo")},
            text={
                Text(
                    "برای Like، Comment، پیام، قسمت جدید و Watch Party به‌موقع باخبر شو. کنترل اعلان‌ها همیشه دست خودته."
                )
            },
            confirmButton={
                Button(
                    onClick={
                        showNotificationPrimer=false
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                ) {
                    Text("فعال کردن")
                }
            },
            dismissButton={
                TextButton(onClick={showNotificationPrimer=false}) {
                    Text("بعداً")
                }
            }
        )
    }

    pendingHandoff?.let { handoff ->
        AlertDialog(
            onDismissRequest={
                if(!handoffActionBusy) {
                    handoffActionBusy=true
                    appScope.launch {
                        runCatching { playbackHandoffRepository.cancel(handoff.id) }
                        pendingHandoff=null
                        handoffActionBusy=false
                    }
                }
            },
            icon={
                Icon(
                    Icons.Default.SendToMobile,
                    null,
                    tint=FqGold
                )
            },
            title={Text("ادامه تماشا روی این دستگاه؟")},
            text={
                Column {
                    Text(
                        handoff.title,
                        fontWeight=androidx.compose.ui.text.font.FontWeight.Bold
                    )
                    if(handoff.subtitle.isNotBlank()) {
                        Text(
                            handoff.subtitle,
                            color=FqMuted,
                            fontSize=11.sp,
                            modifier=Modifier.padding(top=4.dp)
                        )
                    }
                    Text(
                        "از "+formatSceneTime(handoff.positionMs)+
                            if(handoff.sourceDeviceName.isNotBlank())
                                " • از "+handoff.sourceDeviceName
                            else "",
                        color=FqGold,
                        fontSize=11.sp,
                        modifier=Modifier.padding(top=9.dp)
                    )
                }
            },
            confirmButton={
                Button(
                    enabled=!handoffActionBusy,
                    onClick={
                        handoffActionBusy=true
                        appScope.launch {
                            val target=runCatching {
                                backend.playbackContext(handoff.mediaVersionId)
                            }.getOrElse {
                                handoff.asTarget()
                            }.copy(startPositionMs=handoff.positionMs)

                            runCatching {
                                playbackHandoffRepository.accept(handoff.id)
                            }.onSuccess {
                                pendingHandoff=null
                                overlay=OverlayRoute.Player(target)
                            }.onFailure {
                                pendingHandoff=null
                            }
                            handoffActionBusy=false
                        }
                    },
                    colors=ButtonDefaults.buttonColors(containerColor=FqGold)
                ) {
                    if(handoffActionBusy) {
                        CircularProgressIndicator(
                            color=Color.Black,
                            strokeWidth=2.dp,
                            modifier=Modifier.size(17.dp)
                        )
                    } else {
                        Icon(Icons.Default.PlayArrow,null,tint=Color.Black)
                    }
                    Spacer(Modifier.width(5.dp))
                    Text("ادامه تماشا",color=Color.Black)
                }
            },
            dismissButton={
                TextButton(
                    enabled=!handoffActionBusy,
                    onClick={
                        handoffActionBusy=true
                        appScope.launch {
                            runCatching { playbackHandoffRepository.cancel(handoff.id) }
                            pendingHandoff=null
                            handoffActionBusy=false
                        }
                    }
                ) { Text("رد کردن") }
            }
        )
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
                onOpenPost={ postId ->
                    showSearch=false
                    overlay=OverlayRoute.Post(postId)
                },
                onOpenClip={ clipId ->
                    showSearch=false
                    overlay=OverlayRoute.Clips(clipId)
                },
                onPerson={id,name->showSearch=false;overlay=OverlayRoute.PersonPage(id,name)}
            )
            overlay != null -> overlayStateHolder.SaveableStateProvider(overlay!!.javaClass.name+":"+overlay.hashCode()) { when(val route=overlay!!) {
                is OverlayRoute.Post -> FocusedPostScreen(route.postId,social,backend,closeOverlay,
                    {pushOverlay(OverlayRoute.Detail(it))},{pushOverlay(OverlayRoute.CreatorPage(it))},
                    {pushOverlay(OverlayRoute.Auth)})
                is OverlayRoute.Detail -> CinemaDetailScreen(
                    media=route.media,
                    initialDiscussionScope=route.discussionScope,
                    repository=repository,
                    backend=backend,
                    store=store,
                    onBack=closeOverlay,
                    onMedia={ pushOverlay(OverlayRoute.Detail(it)) },
                    onChat=openMediaRoom,
                    onWatchParty={ pushOverlay(OverlayRoute.WatchParty(it)) },
                    onClip={ clipId ->
                        pushOverlay(OverlayRoute.Clips(clipId))
                    },
                    onPlay={ target ->
                        if (backend.session.isLoggedIn) {
                            pushOverlay(OverlayRoute.Player(target))
                        } else {
                            overlay=OverlayRoute.Auth
                        }
                    },
                    onPerson={person->
                        pushOverlay(OverlayRoute.PersonPage(person.id,person.name))
                    },
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)}
                )
                is OverlayRoute.Player -> FilmiqooPlayerScreen(
                    target=route.target,
                    backend=backend,
                    onBack=closeOverlay,
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)},
                    onDiscussion={ mediaId ->
                        appScope.launch {
                            runCatching { social.roomForMedia(mediaId) }
                                .onSuccess { room ->
                                    if(overlay==route) {
                                        if(room!=null) {
                                            pushOverlay(OverlayRoute.Room(room.id,room.name))
                                        } else {
                                            navigationError="برای این عنوان اتاق گفت‌وگو در دسترس نیست."
                                        }
                                    }
                                }
                                .onFailure {
                                    if(overlay==route) {
                                        navigationError="گفت‌وگو دریافت نشد؛ دوباره تلاش کن."
                                    }
                                }
                        }
                    }
                )
                is OverlayRoute.Clips -> ClipsScreen(
                    social=social,
                    backend=backend,
                    store=store,
                    loggedIn=backend.session.isLoggedIn,
                    initialReelId=route.initialReelId,
                    resumeReelId=clipResumeReelId,
                    onInitialReelConsumed={},
                    onVisibleReelChanged={clipResumeReelId=it},
                    onMedia={pushOverlay(OverlayRoute.Detail(it))},
                    onCreator={pushOverlay(OverlayRoute.CreatorPage(it))},
                    onSearch={showSearch=true},
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)}
                )
                OverlayRoute.Downloads -> DownloadsScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onPlay={pushOverlay(OverlayRoute.Player(it))}
                )
                OverlayRoute.Library -> CinemaLibraryScreen(
                    backend=backend,
                    repository=repository,
                    onBack=closeOverlay,
                    onMedia={pushOverlay(OverlayRoute.Detail(it))},
                    onPlay={pushOverlay(OverlayRoute.Player(it))},
                    onDownloads={pushOverlay(OverlayRoute.Downloads)},
                    onHistory={pushOverlay(OverlayRoute.History)},
                    onFilmDna={pushOverlay(OverlayRoute.FilmDna)},
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)},
                    onAccount={overlayBackStack.clear();overlay=null;tab=3}
                )
                OverlayRoute.SocialSaves -> SavedSocialScreen(
                    social=social,
                    onBack=closeOverlay,
                    onCreator={pushOverlay(OverlayRoute.CreatorPage(it))},
                    onMedia={pushOverlay(OverlayRoute.Detail(it))},
                    onOpenPost={ postId ->
                        pushOverlay(OverlayRoute.Post(postId))
                    },
                    onOpenClip={ clipId ->
                        pushOverlay(OverlayRoute.Clips(clipId))
                    }
                )
                OverlayRoute.History -> WatchHistoryScreen(
                    backend=backend,
                    repository=repository,
                    onBack=closeOverlay,
                    onPlay={pushOverlay(OverlayRoute.Player(it))},
                    onMedia={pushOverlay(OverlayRoute.Detail(it))}
                )
                is OverlayRoute.Room -> ConnectedRoomScreen(
                    roomId=route.roomId,
                    title=route.title,
                    social=social,
                    backend=backend,
                    loggedIn=backend.session.isLoggedIn,
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)},
                    onBack=closeOverlay
                )
                is OverlayRoute.Auth -> AuthScreen(
                    backend=backend,
                    onSuccess={
                        viewerReady=false
                        authenticated=true
                        closeOverlay()
                    },
                    onPreview={
                        previewMode=true
                        overlayBackStack.clear()
                        overlay=null
                    }
                )
                is OverlayRoute.Story -> StoryViewer(
                    media=route.media,
                    repository=repository,
                    onClose=closeOverlay,
                    onMedia={ pushOverlay(OverlayRoute.Detail(it)) }
                )
                is OverlayRoute.SocialStories -> SocialStoryViewerScreen(
                    stories=route.stories,
                    startIndex=route.index,
                    social=social,
                    loggedIn=backend.session.isLoggedIn,
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)},
                    onMedia={pushOverlay(OverlayRoute.Detail(it))},
                    onClose=closeOverlay
                )
                is OverlayRoute.PersonPage -> PersonScreen(
                    personId=route.personId,
                    initialName=route.name,
                    repository=repository,
                    onBack=closeOverlay,
                    onMedia={pushOverlay(OverlayRoute.Detail(it))}
                )
                is OverlayRoute.CreatorPage -> PremiumCreatorChannelScreen(
                    creator=route.creator,
                    backend=backend,
                    social=social,
                    onBack=closeOverlay,
                    onMedia={
                        pushOverlay(OverlayRoute.Detail(it))
                    },
                    onOpenRoom={
                        pushOverlay(OverlayRoute.Room(it.id,it.name))
                    },
                    onStory={stories,index->
                        pushOverlay(OverlayRoute.SocialStories(stories,index))
                    },
                    onOpenClip={ clipId ->
                        pushOverlay(OverlayRoute.Clips(clipId))
                    },
                    onOpenPost={ postId ->
                        pushOverlay(OverlayRoute.Post(postId))
                    },
                    onStartDm={userId,title->
                        if(!backend.session.isLoggedIn) {
                            overlay=OverlayRoute.Auth
                        } else {
                            appScope.launch {
                                runCatching { messaging.ensureDm(userId) }
                                    .onSuccess { dm->
                                        if(overlay==route) {
                                            pushOverlay(
                                                OverlayRoute.Room(
                                                    dm.id,
                                                    dm.title.ifBlank { title }
                                                )
                                            )
                                        }
                                    }
                            }
                        }
                    },
                    onManageChannel={channelId,name->
                        pushOverlay(OverlayRoute.ChannelManage(channelId,name))
                    },
                    onReputation={userId->
                        pushOverlay(OverlayRoute.Reputation(userId))
                    },
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)}
                )
                is OverlayRoute.ChannelManage -> ChannelManageScreen(
                    channelId=route.channelId,
                    backend=backend,
                    onBack=closeOverlay,
                    onOpenRoom={id,name->pushOverlay(OverlayRoute.Room(id,name))}
                )
                OverlayRoute.CreatorStudio -> CreatorStudioScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onLive={pushOverlay(OverlayRoute.LiveHub())}
                )
                is OverlayRoute.LiveHub -> LiveHubScreen(
                    backend=backend,
                    repository=repository,
                    initialEventId=route.eventId,
                    onBack=closeOverlay,
                    onOpenRoom={id,title->pushOverlay(OverlayRoute.Room(id,title))},
                    onMedia={pushOverlay(OverlayRoute.Detail(it))},
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)}
                )
                OverlayRoute.Inbox -> InboxScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onOpenRoom={conversation->
                        socialBadgeRefresh++
                        pushOverlay(OverlayRoute.Room(conversation.id,conversation.title))
                    }
                )
                OverlayRoute.Account -> {
                    LaunchedEffect(Unit){overlay=null;tab=3}
                }
                OverlayRoute.Discover -> CinemaDiscoverScreen(repository,{pushOverlay(OverlayRoute.Detail(it))},{showSearch=true},onBack=closeOverlay)
                OverlayRoute.Settings -> SettingsScreen(
                    backend=backend,
                    onBack=closeOverlay
                )
                OverlayRoute.ViewerProfiles -> ViewerProfilesScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onActivated={ profile->
                        resetNavigation()
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
                    onCreator={pushOverlay(OverlayRoute.CreatorPage(it))}
                )
                OverlayRoute.FollowRequests -> FollowRequestsScreen(
                    social=social,
                    onBack=closeOverlay,
                    onCreator={pushOverlay(OverlayRoute.CreatorPage(it))}
                )
                OverlayRoute.CloseFriends -> CloseFriendsScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onCreator={pushOverlay(OverlayRoute.CreatorPage(it))}
                )
                OverlayRoute.FriendActivity -> FriendActivityScreen(
                    backend=backend,
                    repository=repository,
                    onBack=closeOverlay,
                    onMedia={pushOverlay(OverlayRoute.Detail(it))},
                    onCreator={pushOverlay(OverlayRoute.CreatorPage(it))}
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
                is OverlayRoute.Reputation -> ReputationScreen(
                    userId=route.userId,
                    backend=backend,
                    onBack=closeOverlay
                )
                is OverlayRoute.SocialCollections -> SocialCollectionsScreen(
                    backend=backend,
                    repository=repository,
                    loggedIn=backend.session.isLoggedIn,
                    initialCollectionId=route.collectionId,
                    onBack=closeOverlay,
                    onMedia={pushOverlay(OverlayRoute.Detail(it))},
                    onCreator={pushOverlay(OverlayRoute.CreatorPage(it))},
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)}
                )
                OverlayRoute.Releases -> ReleaseCenterScreen(
                    backend=backend,
                    repository=repository,
                    onBack=closeOverlay,
                    onMedia={pushOverlay(OverlayRoute.Detail(it))},
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)}
                )
                OverlayRoute.SeriesCalendar -> SeriesCalendarScreen(
                    backend=backend,
                    repository=repository,
                    onBack=closeOverlay,
                    onMedia={pushOverlay(OverlayRoute.Detail(it))}
                )
                is OverlayRoute.WatchParty -> ConnectedWatchPartyScreen(
                    media=route.media,
                    initialPartyId=route.partyId,
                    initialInviteCode=route.inviteCode,
                    backend=backend,
                    social=social,
                    repository=repository,
                    onBack=closeOverlay,
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)}
                )
                OverlayRoute.Create -> CinemaCreateScreen(
                    social=social,
                    backend=backend,
                    repository=repository,
                    loggedIn=backend.session.isLoggedIn,
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)},
                    onOpenClub=closeOverlay,
                    onOpenClips={
                        overlayBackStack.clear()
                        overlay=OverlayRoute.Clips()
                    },
                    onOpenStudio={
                        overlayBackStack.clear()
                        overlay=OverlayRoute.CreatorStudio
                    },
                    onBack=closeOverlay
                )
                OverlayRoute.Notifications -> ConnectedNotificationsScreen(
                    backend=backend,
                    onBack=closeOverlay,
                    onOpenRoom={id,title->
                        socialBadgeRefresh++
                        pushOverlay(OverlayRoute.Room(id,title))
                    },
                    onOpenCreator={
                        socialBadgeRefresh++
                        pushOverlay(OverlayRoute.CreatorPage(it))
                    },
                    onOpenClip={ clipId ->
                        socialBadgeRefresh++
                        pushOverlay(OverlayRoute.Clips(clipId))
                    },
                    onOpenPost={ postId ->
                        socialBadgeRefresh++
                        pushOverlay(OverlayRoute.Post(postId))
                    },
                    onOpenMedia={
                        socialBadgeRefresh++
                        pushOverlay(OverlayRoute.Detail(it))
                    },
                    onOpenCollection={pushOverlay(OverlayRoute.SocialCollections(it))},
                    onOpenWatchParty={pushOverlay(OverlayRoute.WatchParty(partyId=it))},
                    onOpenLive={pushOverlay(OverlayRoute.LiveHub(it))},
                    onFollowRequests={pushOverlay(OverlayRoute.FollowRequests)},
                    onOpenDiscussion={media,scope->socialBadgeRefresh++;pushOverlay(OverlayRoute.Detail(media,scope))}
                )
            }
            }
            else -> CinemaAppShell(tab,activeViewer?.kidsMode==true,{ index ->
                overlayBackStack.clear(); tab=index; if(index!=2)deepLinkPostId=null
            }) {
                    bottomTabStateHolder.SaveableStateProvider(
                        key="cinema090-tab-"+(activeViewer?.id ?: "guest")+"-"+tab
                    ) {
                    when(tab) {
                        0 -> CinemaHomeScreen(
                            repository=repository,
                            backend=backend,
                            loggedIn=backend.session.isLoggedIn,
                            onMedia={overlay=OverlayRoute.Detail(it)},
                            onPlay={overlay=OverlayRoute.Player(it)},
                            onStory={m,i->overlay=OverlayRoute.Story(m,i)},
                            onSearch={
                                if(activeViewer?.kidsMode!=true) {
                                    tab=1
                                }
                            },
                            onNotifications=openNotifications,
                            onReleases={overlay=OverlayRoute.Releases},
                            onClips={overlay=if(activeViewer?.kidsMode==true)OverlayRoute.ParentalGate else OverlayRoute.Clips()},
                            onClub={if(activeViewer?.kidsMode==true)overlay=OverlayRoute.ParentalGate else tab=2},
                            onWatchParty={media->if(activeViewer?.kidsMode==true)overlay=OverlayRoute.ParentalGate else if(media==null)tab=4 else overlay=OverlayRoute.WatchParty(media)},
                            badgeRefreshKey=socialBadgeRefresh,
                            onAccount={tab=3}
                        )
                        1 -> PremiumSearchScreen(repository,backend,{tab=0},{overlay=OverlayRoute.Detail(it)},
                            {overlay=OverlayRoute.CreatorPage(it)},{overlay=OverlayRoute.Post(it)},{overlay=OverlayRoute.Clips(it)},
                            onDiscover={overlay=OverlayRoute.Discover},onPerson={id,name->overlay=OverlayRoute.PersonPage(id,name)})
                        2 -> MoviesDiscoveryScreen(repository,backend,
                            onMedia={overlay=OverlayRoute.Detail(it)},onSearch={tab=1},
                            onRequireAuth={overlay=OverlayRoute.Auth})
                        3 -> ConnectedProfileScreen(
                            backend=backend,repository=repository,kidsMode=activeViewer?.kidsMode==true,
                            onMedia={overlay=OverlayRoute.Detail(it)},onPlay={overlay=OverlayRoute.Player(it)},
                            onCommunity={tab=2},onDownloads={pushOverlay(OverlayRoute.Downloads)},
                            onLibrary={pushOverlay(OverlayRoute.Library)},onSocialSaves={pushOverlay(OverlayRoute.SocialSaves)},
                            onHistory={pushOverlay(OverlayRoute.History)},onCreatorStudio={pushOverlay(OverlayRoute.CreatorStudio)},
                            onInbox={pushOverlay(OverlayRoute.Inbox)},onSettings={pushOverlay(OverlayRoute.Settings)},
                            onViewerProfiles={overlay=if(activeViewer?.kidsMode==true)OverlayRoute.ParentalGate else OverlayRoute.ViewerProfiles},
                            onParentalControls={pushOverlay(OverlayRoute.ParentalControls)},onSecurity={pushOverlay(OverlayRoute.Security)},
                            onSafety={pushOverlay(OverlayRoute.Safety)},onFollowRequests={pushOverlay(OverlayRoute.FollowRequests)},
                            onCloseFriends={pushOverlay(OverlayRoute.CloseFriends)},onEditProfile={pushOverlay(OverlayRoute.EditProfile)},
                            onFilmDna={pushOverlay(OverlayRoute.FilmDna)},onReputation={overlay=OverlayRoute.Reputation(it)},
                            onSeriesCalendar={pushOverlay(OverlayRoute.SeriesCalendar)},onSocialCollections={overlay=OverlayRoute.SocialCollections()},
                            onLoggedOut={resetNavigation();backend.viewerProfiles.clear();activeViewer=null;authenticated=false;previewMode=false},
                            loggedIn=backend.session.isLoggedIn,onRequireAuth={overlay=OverlayRoute.Auth},
                            onClips={overlay=OverlayRoute.Clips()},onCreate={overlay=OverlayRoute.Create},
                            onNotifications=openNotifications,onMovies={tab=2},onSeries={tab=4},
                            onOpenPost={overlay=OverlayRoute.Post(it)},onOpenClip={overlay=OverlayRoute.Clips(it)}
                        )
                        4 -> SeriesDiscoveryScreen(repository,backend,
                            onMedia={overlay=OverlayRoute.Detail(it)},onSearch={tab=1},
                            onCalendar={overlay=if(backend.session.isLoggedIn)OverlayRoute.SeriesCalendar else OverlayRoute.Auth},
                            onRequireAuth={overlay=OverlayRoute.Auth})

                    }
                
                    }
            }
        }
    }
}

@Composable
private fun FilmiqooBottomBar(
    selected:Int,
    kidsMode:Boolean=false,
    onSelected:(Int)->Unit
) {
    CinemaBottomBar(selected, kidsMode, onSelected)
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
