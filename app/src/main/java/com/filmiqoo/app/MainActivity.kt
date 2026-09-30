package com.filmiqoo.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val deepLinkState=mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FilmiqooCrashStore.install(applicationContext)
        deepLinkState.value=intent?.dataString
        setContent {
            FilmiqooTheme {
                FilmiqooApp(initialDeepLink=deepLinkState.value)
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
    var overlay by remember { mutableStateOf<OverlayRoute?>(null) }
    val overlayBackStack=remember { mutableStateListOf<OverlayRoute>() }
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

    DisposableEffect(backend,context) {
        val prefs=context.applicationContext.getSharedPreferences(
            "filmiqoo_session_v1",
            android.content.Context.MODE_PRIVATE
        )
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,key ->
            if(key=="access_token" || key=="refresh_token") {
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
                "creator","channel","collection","party","room","room-invite","reel","post","live"
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
                        overlay=null
                        showSearch=false
                        deepLinkPostId=id
                        tab=2
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

    val openMediaRoom: (MediaItem)->Unit = { media ->
        val mediaId=media.backendId
        val sourceRoute=overlay
        if(mediaId.isNullOrBlank()) {
            overlayBackStack.clear()
            overlay=null
            tab=2
        } else {
            appScope.launch {
                runCatching { social.roomForMedia(mediaId) }
                    .onSuccess { room ->
                        if(overlay==sourceRoute) {
                            if(room!=null) {
                                sourceRoute?.let(overlayBackStack::add)
                                overlay=OverlayRoute.Room(room.id,room.name)
                            } else {
                                overlayBackStack.clear()
                                overlay=null
                                tab=2
                            }
                        }
                    }
                    .onFailure {
                        if(overlay==sourceRoute) {
                            overlayBackStack.clear()
                            overlay=null
                            tab=2
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
                    deepLinkPostId=postId
                    tab=2
                },
                onOpenClip={ clipId ->
                    showSearch=false
                    overlay=OverlayRoute.Clips(clipId)
                }
            )
            overlay != null -> when(val route=overlay!!) {
                is OverlayRoute.Detail -> PremiumDetailScreen(
                    media=route.media,
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
                                            overlayBackStack.clear()
                                            overlay=null
                                            tab=2
                                        }
                                    }
                                }
                                .onFailure {
                                    if(overlay==route) {
                                        overlayBackStack.clear()
                                        overlay=null
                                        tab=2
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
                OverlayRoute.Library -> LibraryScreen(
                    backend=backend,
                    repository=repository,
                    onBack=closeOverlay,
                    onMedia={pushOverlay(OverlayRoute.Detail(it))},
                    onPlay={pushOverlay(OverlayRoute.Player(it))},
                    onDownloads={pushOverlay(OverlayRoute.Downloads)},
                    onHistory={pushOverlay(OverlayRoute.History)},
                    onFilmDna={pushOverlay(OverlayRoute.FilmDna)},
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)}
                )
                OverlayRoute.SocialSaves -> SavedSocialScreen(
                    social=social,
                    onBack=closeOverlay,
                    onCreator={pushOverlay(OverlayRoute.CreatorPage(it))},
                    onMedia={pushOverlay(OverlayRoute.Detail(it))},
                    onOpenPost={ postId ->
                        overlayBackStack.add(route)
                        overlay=null
                        deepLinkPostId=postId
                        tab=2
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
                        overlayBackStack.add(route)
                        overlay=null
                        deepLinkPostId=postId
                        tab=2
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
                OverlayRoute.Create -> PremiumCreateHubScreen(
                    social=social,
                    backend=backend,
                    repository=repository,
                    loggedIn=backend.session.isLoggedIn,
                    onRequireAuth={pushOverlay(OverlayRoute.Auth)},
                    onOpenClub={
                        overlayBackStack.clear()
                        overlay=null
                        tab=2
                    },
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
                        overlayBackStack.add(route)
                        overlay=null
                        deepLinkPostId=postId
                        tab=2
                    },
                    onOpenMedia={
                        socialBadgeRefresh++
                        pushOverlay(OverlayRoute.Detail(it))
                    },
                    onOpenCollection={pushOverlay(OverlayRoute.SocialCollections(it))},
                    onOpenWatchParty={pushOverlay(OverlayRoute.WatchParty(partyId=it))},
                    onOpenLive={pushOverlay(OverlayRoute.LiveHub(it))},
                    onFollowRequests={pushOverlay(OverlayRoute.FollowRequests)}
                )
            }
            else -> Scaffold(
                containerColor=FqBg,
                bottomBar={
                    FilmiqooBottomBar(
                        selected=tab,
                        kidsMode=activeViewer?.kidsMode==true,
                        onSelected={ index ->
                            overlayBackStack.clear()
                            tab=index
                            if(index!=2) deepLinkPostId=null
                        }
                    )
                }
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    bottomTabStateHolder.SaveableStateProvider(
                        key="main-tab-"+tab
                    ) {
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
                                    showSearch=false
                                    tab=1
                                }
                            },
                            onNotifications=openNotifications,
                            onReleases={overlay=OverlayRoute.Releases},
                            onClips={tab=2},
                            onClub={tab=2},
                            onWatchParty={overlay=OverlayRoute.WatchParty(it)},
                            badgeRefreshKey=socialBadgeRefresh
                        )
                        1 -> StreamingExploreScreen(
                            repository=repository,
                            onMedia={overlay=OverlayRoute.Detail(it)},
                            onSearchAll={showSearch=true}
                        )
                        2 -> CinePulseScreen(
                            social=social,
                            backend=backend,
                            repository=repository,
                            loggedIn=backend.session.isLoggedIn,
                            onMedia={overlay=OverlayRoute.Detail(it)},
                            onOpenPost={ postId ->
                                deepLinkPostId=postId
                                overlay=null
                                tab=2
                            },
                            onOpenClip={ clipId ->
                                overlay=OverlayRoute.Clips(clipId)
                            },
                            onOpenRoom={overlay=OverlayRoute.Room(it.id,it.name)},
                            onCreator={overlay=OverlayRoute.CreatorPage(it)},
                            onSearch={showSearch=true},
                            onInbox={overlay=OverlayRoute.Inbox},
                            onCreate={overlay=OverlayRoute.Create},
                            onRequireAuth={overlay=OverlayRoute.Auth},
                            initialPostId=deepLinkPostId,
                            onFocusedPostConsumed={deepLinkPostId=null}
                        )
                        3 -> LibraryScreen(
                            backend=backend,
                            repository=repository,
                            onBack={tab=0},
                            onMedia={overlay=OverlayRoute.Detail(it)},
                            onPlay={overlay=OverlayRoute.Player(it)},
                            onDownloads={overlay=OverlayRoute.Downloads},
                            onHistory={overlay=OverlayRoute.History},
                            onFilmDna={overlay=OverlayRoute.FilmDna},
                            onRequireAuth={overlay=OverlayRoute.Auth},
                            showBack=false
                        )
                        else -> {
                            if(backend.session.isLoggedIn) {
                                MeScreen(
                                    backend=backend,
                                    repository=repository,
                                    kidsMode=activeViewer?.kidsMode==true,
                                    onMedia={overlay=OverlayRoute.Detail(it)},
                                    onPlay={overlay=OverlayRoute.Player(it)},
                                    onClips={overlay=OverlayRoute.Clips()},
                                    onCommunity={tab=2},
                                    onOpenPost={ postId ->
                                        deepLinkPostId=postId
                                        tab=2
                                    },
                                    onOpenClip={ clipId ->
                                        overlay=OverlayRoute.Clips(clipId)
                                    },
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
                                    onCloseFriends={overlay=OverlayRoute.CloseFriends},
                                    onEditProfile={overlay=OverlayRoute.EditProfile},
                                    onFilmDna={overlay=OverlayRoute.FilmDna},
                                    onReputation={userId->overlay=OverlayRoute.Reputation(userId)},
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
                                MeSignedOutScreen(
                                    onLogin={overlay=OverlayRoute.Auth},
                                    onClub={tab=2},
                                    onClips={overlay=OverlayRoute.Clips()}
                                )
                            }
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
    selected:Int,
    kidsMode:Boolean=false,
    onSelected:(Int)->Unit
) {
    val haptic=LocalHapticFeedback.current
    val entries=if(kidsMode) {
        listOf(
            Triple(Icons.Default.Home,"خانه",0),
            Triple(Icons.Default.PersonOutline,"من",4)
        )
    } else {
        listOf(
            Triple(Icons.Default.Home,"خانه",0),
            Triple(Icons.Default.Explore,"کشف",1),
            Triple(Icons.Default.AutoAwesomeMosaic,"استودیوی من",3),
            Triple(Icons.Default.Explore,"کشف",2),
            Triple(Icons.Default.PersonOutline,"من",4)
        )
    }

    Box(
        Modifier.fillMaxWidth()
            .background(Color.Transparent)
            .navigationBarsPadding()
            .padding(start=10.dp,end=10.dp,bottom=8.dp,top=3.dp)
    ) {
        Surface(
            color=Color(0xF20A0D12),
            shape=RoundedCornerShape(24.dp),
            tonalElevation=0.dp,
            shadowElevation=18.dp,
            border=androidx.compose.foundation.BorderStroke(
                1.dp,
                Color.White.copy(alpha=.08f)
            ),
            modifier=Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal=5.dp),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.SpaceEvenly
            ) {
                entries.forEach { item ->
                    val active=selected==item.third
                    val pillColor by animateColorAsState(
                        if(active) FqGold.copy(alpha=.14f)
                        else Color.Transparent,
                        label="bottomBarPill"
                    )
                    val iconColor by animateColorAsState(
                        if(active) FqGoldSoft else FqMuted,
                        label="bottomBarIcon"
                    )
                    val indicatorWidth by animateDpAsState(
                        if(active) 18.dp else 0.dp,
                        label="bottomBarIndicator"
                    )
                    Surface(
                        color=Color.Transparent,
                        contentColor=iconColor,
                        shape=RoundedCornerShape(18.dp),
                        modifier=Modifier.weight(1f)
                            .fillMaxHeight()
                            .clickable {
                                if(!active) {
                                    haptic.performHapticFeedback(
                                        HapticFeedbackType.TextHandleMove
                                    )
                                    onSelected(item.third)
                                }
                            }
                    ) {
                        Column(
                            Modifier.fillMaxSize().padding(vertical=6.dp),
                            horizontalAlignment=Alignment.CenterHorizontally,
                            verticalArrangement=Arrangement.Center
                        ) {
                            Box(
                                Modifier.width(40.dp)
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(pillColor),
                                contentAlignment=Alignment.Center
                            ) {
                                Icon(
                                    item.first,
                                    contentDescription=item.second,
                                    tint=iconColor,
                                    modifier=Modifier.size(if(active)22.dp else 20.dp)
                                )
                            }
                            Text(
                                item.second,
                                color=iconColor,
                                style=MaterialTheme.typography.labelSmall,
                                fontWeight=if(active) FontWeight.Bold else FontWeight.Medium,
                                maxLines=1,
                                modifier=Modifier.padding(top=2.dp)
                            )
                            Box(
                                Modifier.padding(top=3.dp)
                                    .width(indicatorWidth)
                                    .height(2.dp)
                                    .clip(CircleShape)
                                    .background(if(active) FqGold else Color.Transparent)
                            )
                        }
                    }
                }
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
