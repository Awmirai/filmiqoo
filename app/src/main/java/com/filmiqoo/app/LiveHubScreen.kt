package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun LiveHubScreen(
    backend:BackendRepository,
    repository:TmdbRepository,
    onBack:()->Unit,
    onOpenRoom:(String,String)->Unit,
    onMedia:(MediaItem)->Unit,
    onRequireAuth:()->Unit
) {
    val live=remember { LiveRepository(backend) }
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var events by remember { mutableStateOf<List<LiveEvent>>(emptyList()) }
    var selected by remember { mutableStateOf<LiveEvent?>(null) }
    var myUserId by remember { mutableStateOf<String?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler {
        if(selected!=null) selected=null else onBack()
    }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        if(backend.session.isLoggedIn && myUserId==null) {
            myUserId=runCatching { backend.me().id }.getOrNull()
        }
        events=runCatching { live.events() }
            .onFailure { error=it.message }
            .getOrDefault(emptyList())
        loading=false
    }

    selected?.let { event ->
        LiveEventDetailScreen(
            event=event,
            backend=backend,
            live=live,
            isHost=myUserId==event.host.id,
            loggedIn=backend.session.isLoggedIn,
            onBack={selected=null;refresh++},
            onOpenRoom=onOpenRoom,
            onMedia=onMedia,
            onRequireAuth=onRequireAuth,
            onUpdated={updated->
                selected=updated
                events=events.map { if(it.id==updated.id)updated else it }
            },
            onError={error=it}
        )
        return
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(listOf(Color(0xFF2B130E),Color(0xFF111827),FqBg))
            )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(9.dp).background(FqDanger,CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text("LIVE",color=FqDanger,fontSize=10.sp,fontWeight=FontWeight.Black)
                    }
                    Text("Live & Premiere",fontSize=24.sp,fontWeight=FontWeight.Black)
                    Text("پخش زنده، پریمیر و Chat همزمان",color=FqMuted,fontSize=8.sp)
                }
                IconButton(onClick={refresh++}){Icon(Icons.Default.Refresh,null)}
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("رویدادها",fontSize=15.sp,fontWeight=FontWeight.Bold)
                Text("Liveهای در حال پخش و Premiereهای آینده",color=FqMuted,fontSize=8.sp)
            }
            Button(
                onClick={
                    if(backend.session.isLoggedIn) showCreate=true else onRequireAuth()
                },
                colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                shape=RoundedCornerShape(13.dp)
            ) {
                Icon(Icons.Default.Add,null,tint=Color.Black)
                Spacer(Modifier.width(4.dp))
                Text("ساخت",color=Color.Black,fontSize=8.sp)
            }
        }

        if(loading) {
            LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        }

        error?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=8.sp,
                modifier=Modifier.fillMaxWidth()
                    .background(FqDanger.copy(alpha=.08f))
                    .padding(10.dp)
            )
        }

        if(!loading && events.isEmpty()) {
            PremiumEmptyState(
                Icons.Default.LiveTv,
                "رویداد فعالی نیست",
                "Creatorها می‌تونن Live یا Premiere جدید بسازن.",
                "ساخت رویداد"
            ) {
                if(backend.session.isLoggedIn) showCreate=true else onRequireAuth()
            }
        } else {
            LazyColumn(
                contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp),
                modifier=Modifier.fillMaxSize()
            ) {
                items(events,key={it.id}) { event ->
                    LiveEventCard(
                        event=event,
                        onClick={
                            scope.launch {
                                loading=true
                                selected=runCatching { live.detail(event.id) }
                                    .onFailure { error=it.message }
                                    .getOrDefault(event)
                                loading=false
                            }
                        }
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if(showCreate) {
        CreateLiveEventDialog(
            backend=backend,
            repository=repository,
            onDismiss={showCreate=false},
            onCreated={id->
                showCreate=false
                scope.launch {
                    runCatching { live.detail(id) }
                        .onSuccess { selected=it }
                        .onFailure { refresh++ }
                }
            },
            onError={error=it}
        )
    }
}

@Composable
private fun LiveEventCard(
    event:LiveEvent,
    onClick:()->Unit
) {
    val image=event.coverUrl.takeIf(String::isNotBlank)
        ?: event.media?.backdropPath
        ?: event.media?.posterPath

    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(20.dp),
        modifier=Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(174.dp)) {
                RemoteImage(image,Modifier.fillMaxSize(),ContentScale.Crop)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.78f)))
                    )
                )

                LiveStateBadge(
                    event=event,
                    modifier=Modifier.align(Alignment.TopStart).padding(10.dp)
                )

                Row(
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment=Alignment.Bottom
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            event.title,
                            fontSize=15.sp,
                            fontWeight=FontWeight.Black,
                            maxLines=2,
                            overflow=TextOverflow.Ellipsis
                        )
                        Text(
                            if(event.eventType=="premiere")"Premiere" else "Live Stream",
                            color=FqGold,
                            fontSize=8.sp
                        )
                    }
                    if(event.state=="live") {
                        Text(
                            compactLiveCount(event.viewers)+" بیننده",
                            fontSize=8.sp,
                            modifier=Modifier.background(
                                Color.Black.copy(alpha=.55f),
                                RoundedCornerShape(8.dp)
                            ).padding(horizontal=7.dp,vertical=4.dp)
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(11.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                RemoteImage(
                    event.host.avatarUrl.takeIf(String::isNotBlank),
                    Modifier.size(38.dp).clip(CircleShape),
                    ContentScale.Crop
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(event.host.displayName,fontSize=9.sp,fontWeight=FontWeight.Bold)
                        if(event.host.verified) {
                            Spacer(Modifier.width(3.dp))
                            Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(12.dp))
                        }
                    }
                    Text(
                        liveScheduleLabel(event),
                        color=FqMuted,
                        fontSize=7.sp
                    )
                }
                Icon(Icons.Default.ChevronLeft,null,tint=FqGold)
            }
        }
    }
}

@Composable
private fun LiveStateBadge(event:LiveEvent,modifier:Modifier=Modifier) {
    val live=event.state=="live"
    Surface(
        color=if(live)FqDanger else Color.Black.copy(alpha=.65f),
        shape=RoundedCornerShape(9.dp),
        modifier=modifier
    ) {
        Row(
            Modifier.padding(horizontal=8.dp,vertical=5.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            if(live) {
                Box(Modifier.size(7.dp).background(Color.White,CircleShape))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                when(event.state) {
                    "live" -> "LIVE"
                    "scheduled" -> if(event.eventType=="premiere")"PREMIERE" else "SCHEDULED"
                    "ended" -> "ENDED"
                    else -> event.state.uppercase()
                },
                fontSize=7.sp,
                fontWeight=FontWeight.Black
            )
        }
    }
}

@Composable
private fun LiveEventDetailScreen(
    event:LiveEvent,
    backend:BackendRepository,
    live:LiveRepository,
    isHost:Boolean,
    loggedIn:Boolean,
    onBack:()->Unit,
    onOpenRoom:(String,String)->Unit,
    onMedia:(MediaItem)->Unit,
    onRequireAuth:()->Unit,
    onUpdated:(LiveEvent)->Unit,
    onError:(String)->Unit
) {
    val scope=rememberCoroutineScope()
    var localEvent by remember(event.id,event.state,event.playbackUrl) { mutableStateOf(event) }
    var joined by remember(event.id) { mutableStateOf(false) }
    var showSource by remember { mutableStateOf(false) }

    LaunchedEffect(event.id,loggedIn) {
        if(loggedIn) {
            runCatching { live.join(event.id) }
                .onSuccess {
                    joined=true
                    localEvent=localEvent.copy(viewers=it)
                }
                .onFailure { onError(it.message ?: "ورود به Live ناموفق بود") }

            while(isActive && joined) {
                delay(30_000)
                runCatching { live.heartbeat(event.id) }
                    .onSuccess { localEvent=localEvent.copy(viewers=it) }
            }
        }
    }

    DisposableEffect(event.id,joined) {
        onDispose {
            if(joined) {
                scope.launch { runCatching { live.leave(event.id) } }
            }
        }
    }

    BackHandler { onBack() }

    LazyColumn(
        Modifier.fillMaxSize().background(FqBg),
        contentPadding=PaddingValues(bottom=28.dp)
    ) {
        item {
            Box(Modifier.fillMaxWidth()) {
                if(localEvent.state=="live" && localEvent.playbackUrl.isNotBlank()) {
                    LivePlaybackSurface(
                        url=localEvent.playbackUrl,
                        modifier=Modifier.fillMaxWidth().aspectRatio(16f/9f)
                    )
                } else {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f/9f)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF21100E),Color(0xFF1A2131))
                                )
                            )
                    ) {
                        RemoteImage(
                            localEvent.coverUrl.takeIf(String::isNotBlank)
                                ?: localEvent.media?.backdropPath
                                ?: localEvent.media?.posterPath,
                            Modifier.fillMaxSize(),
                            ContentScale.Crop
                        )
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.48f)))
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment=Alignment.CenterHorizontally
                        ) {
                            Icon(
                                if(localEvent.eventType=="premiere")Icons.Default.Movie else Icons.Default.LiveTv,
                                null,
                                tint=FqGold,
                                modifier=Modifier.size(48.dp)
                            )
                            Text(
                                if(localEvent.state=="scheduled")
                                    liveScheduleLabel(localEvent)
                                else
                                    "پخش پایان یافته",
                                fontSize=11.sp,
                                modifier=Modifier.padding(top=7.dp)
                            )
                        }
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick=onBack,
                        modifier=Modifier.background(Color.Black.copy(alpha=.45f),CircleShape)
                    ) { Icon(Icons.Default.ArrowBack,null) }
                    Spacer(Modifier.weight(1f))
                    LiveStateBadge(localEvent)
                }
            }
        }

        item {
            Column(Modifier.padding(16.dp)) {
                Text(localEvent.title,fontSize=24.sp,fontWeight=FontWeight.Black)
                if(localEvent.description.isNotBlank()) {
                    Text(
                        localEvent.description,
                        color=Color.White.copy(alpha=.76f),
                        fontSize=9.sp,
                        lineHeight=16.sp,
                        modifier=Modifier.padding(top=7.dp)
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(top=12.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    RemoteImage(
                        localEvent.host.avatarUrl.takeIf(String::isNotBlank),
                        Modifier.size(44.dp).clip(CircleShape),
                        ContentScale.Crop
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(localEvent.host.displayName,fontSize=10.sp,fontWeight=FontWeight.Bold)
                        Text("@"+localEvent.host.username,color=FqMuted,fontSize=7.sp)
                    }
                    if(localEvent.state=="live") {
                        Icon(Icons.Default.Visibility,null,tint=FqGold,modifier=Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(compactLiveCount(localEvent.viewers),fontSize=8.sp)
                    }
                }
            }
        }

        localEvent.media?.let { media ->
            item {
                Surface(
                    color=FqSurface,
                    shape=RoundedCornerShape(18.dp),
                    modifier=Modifier.fillMaxWidth()
                        .padding(horizontal=14.dp)
                        .clickable { onMedia(media) }
                ) {
                    Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                        RemoteImage(
                            media.posterPath,
                            Modifier.width(52.dp).height(74.dp).clip(RoundedCornerShape(10.dp)),
                            ContentScale.Crop
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(media.title,fontSize=10.sp,fontWeight=FontWeight.Bold)
                            Text("متصل به Catalog Filmiqoo",color=FqMuted,fontSize=7.sp)
                        }
                        Icon(Icons.Default.ChevronLeft,null,tint=FqGold)
                    }
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth().padding(14.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                val liveRoomId=localEvent.roomId
                if(localEvent.allowChat && liveRoomId!=null) {
                    Button(
                        onClick={
                            if(loggedIn) onOpenRoom(liveRoomId,localEvent.title)
                            else onRequireAuth()
                        },
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                        modifier=Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Chat,null,tint=Color.Black)
                        Spacer(Modifier.width(5.dp))
                        Text("Live Chat",color=Color.Black)
                    }
                }

                if(isHost) {
                    OutlinedButton(
                        onClick={
                            when(localEvent.state) {
                                "scheduled" -> {
                                    if(localEvent.playbackUrl.isBlank()) showSource=true
                                    else scope.launch {
                                        runCatching {
                                            live.updateState(localEvent.id,"live",localEvent.playbackUrl)
                                            live.detail(localEvent.id)
                                        }.onSuccess {
                                            localEvent=it
                                            onUpdated(it)
                                        }.onFailure { onError(it.message ?: "شروع Live ناموفق بود") }
                                    }
                                }
                                "live" -> scope.launch {
                                    runCatching {
                                        live.updateState(localEvent.id,"ended")
                                        live.detail(localEvent.id)
                                    }.onSuccess {
                                        localEvent=it
                                        onUpdated(it)
                                    }.onFailure { onError(it.message ?: "پایان Live ناموفق بود") }
                                }
                            }
                        },
                        modifier=Modifier.weight(1f),
                        enabled=localEvent.state=="scheduled" || localEvent.state=="live"
                    ) {
                        Icon(
                            if(localEvent.state=="live")Icons.Default.StopCircle else Icons.Default.PlayCircle,
                            null
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(if(localEvent.state=="live")"پایان Live" else "شروع Live")
                    }
                }
            }
        }

        if(isHost) {
            item {
                Surface(
                    color=FqSurface,
                    shape=RoundedCornerShape(18.dp),
                    modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp)
                ) {
                    Column(Modifier.padding(13.dp)) {
                        Text("Host Analytics",fontSize=11.sp,fontWeight=FontWeight.Bold)
                        Row(
                            Modifier.fillMaxWidth().padding(top=9.dp),
                            horizontalArrangement=Arrangement.spacedBy(8.dp)
                        ) {
                            PremiumStat(compactLiveCount(localEvent.viewers),"الان",Modifier.weight(1f))
                            PremiumStat(compactLiveCount(localEvent.peakViewers),"Peak",Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    if(showSource) {
        LiveSourceDialog(
            initial=localEvent.playbackUrl,
            onDismiss={showSource=false},
            onStart={url->
                showSource=false
                scope.launch {
                    runCatching {
                        live.updateState(localEvent.id,"live",url)
                        live.detail(localEvent.id)
                    }.onSuccess {
                        localEvent=it
                        onUpdated(it)
                    }.onFailure { onError(it.message ?: "شروع Live ناموفق بود") }
                }
            }
        )
    }
}

@Composable
private fun LivePlaybackSurface(
    url:String,
    modifier:Modifier=Modifier
) {
    val context=LocalContext.current
    val player=remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(url))
            playWhenReady=true
            prepare()
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    AndroidView(
        factory={ctx->
            PlayerView(ctx).apply {
                useController=true
                this.player=player
            }
        },
        update={it.player=player},
        modifier=modifier.background(Color.Black)
    )
}

@Composable
private fun CreateLiveEventDialog(
    backend:BackendRepository,
    repository:TmdbRepository,
    onDismiss:()->Unit,
    onCreated:(String)->Unit,
    onError:(String)->Unit
) {
    val live=remember { LiveRepository(backend) }
    val scope=rememberCoroutineScope()
    var type by remember { mutableStateOf("live") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var visibility by remember { mutableStateOf("public") }
    var playbackUrl by remember { mutableStateOf("") }
    var allowChat by remember { mutableStateOf(true) }
    var scheduledText by remember {
        mutableStateOf(
            LocalDateTime.now().plusHours(1)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        )
    }
    var taggedMedia by remember { mutableStateOf<MediaItem?>(null) }
    var showMediaPicker by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val scheduledIso=parseLiveDateTime(scheduledText)
    val canCreate=title.trim().length>=2 &&
        scheduledIso!=null &&
        (type!="premiere" || !taggedMedia?.backendId.isNullOrBlank())

    AlertDialog(
        onDismissRequest={if(!busy)onDismiss()},
        title={Text("Live / Premiere جدید")},
        text={
            Column(Modifier.heightIn(max=560.dp)) {
                Row(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                    FilterChip(
                        selected=type=="live",
                        onClick={type="live"},
                        label={Text("Live")}
                    )
                    FilterChip(
                        selected=type=="premiere",
                        onClick={type="premiere"},
                        label={Text("Premiere")}
                    )
                }

                OutlinedTextField(
                    value=title,
                    onValueChange={title=it.take(120)},
                    label={Text("عنوان")},
                    singleLine=true,
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                )

                OutlinedTextField(
                    value=description,
                    onValueChange={description=it.take(2000)},
                    label={Text("توضیح")},
                    minLines=2,
                    maxLines=4,
                    modifier=Modifier.fillMaxWidth().padding(top=7.dp)
                )

                OutlinedTextField(
                    value=scheduledText,
                    onValueChange={scheduledText=it.take(16)},
                    label={Text("زمان • YYYY-MM-DD HH:mm")},
                    isError=scheduledIso==null,
                    singleLine=true,
                    modifier=Modifier.fillMaxWidth().padding(top=7.dp)
                )

                if(type=="premiere") {
                    Surface(
                        color=FqSurface2,
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                            .clickable { showMediaPicker=true }
                    ) {
                        Row(Modifier.padding(11.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Default.Movie,null,tint=FqGold)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(taggedMedia?.title ?: "انتخاب فیلم یا سریال",fontSize=9.sp)
                                Text(
                                    "Premiere از نسخه Stream-ready Catalog پخش می‌شه.",
                                    color=FqMuted,
                                    fontSize=7.sp
                                )
                            }
                            Icon(Icons.Default.ChevronLeft,null)
                        }
                    }
                } else {
                    OutlinedTextField(
                        value=playbackUrl,
                        onValueChange={playbackUrl=it.take(2000)},
                        label={Text("HLS playback URL • اختیاری")},
                        supportingText={Text("می‌تونی بعداً قبل از شروع Live هم واردش کنی.",fontSize=7.sp)},
                        modifier=Modifier.fillMaxWidth().padding(top=7.dp)
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(top=8.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Text("Live Chat",fontSize=9.sp,modifier=Modifier.weight(1f))
                    Switch(checked=allowChat,onCheckedChange={allowChat=it})
                }

                Row(
                    Modifier.fillMaxWidth().padding(top=5.dp),
                    horizontalArrangement=Arrangement.spacedBy(5.dp)
                ) {
                    listOf("public" to "عمومی","private" to "خصوصی","invite" to "دعوتی").forEach {
                        FilterChip(
                            selected=visibility==it.first,
                            onClick={visibility=it.first},
                            label={Text(it.second,fontSize=7.sp)}
                        )
                    }
                }
            }
        },
        confirmButton={
            Button(
                enabled=canCreate && !busy,
                onClick={
                    val iso=scheduledIso ?: return@Button
                    busy=true
                    scope.launch {
                        runCatching {
                            live.create(
                                eventType=type,
                                title=title,
                                description=description,
                                visibility=visibility,
                                playbackUrl=playbackUrl,
                                coverUrl=taggedMedia?.backdropPath ?: taggedMedia?.posterPath ?: "",
                                allowChat=allowChat,
                                scheduledAtIso=iso,
                                mediaTitleId=taggedMedia?.backendId
                            )
                        }.onSuccess {
                            busy=false
                            onCreated(it.first)
                        }.onFailure {
                            busy=false
                            onError(it.message ?: "ساخت رویداد ناموفق بود")
                        }
                    }
                },
                colors=ButtonDefaults.buttonColors(containerColor=FqGold)
            ) {
                if(busy) {
                    CircularProgressIndicator(
                        color=Color.Black,
                        strokeWidth=2.dp,
                        modifier=Modifier.size(16.dp)
                    )
                } else {
                    Icon(Icons.Default.Event,null,tint=Color.Black)
                }
                Spacer(Modifier.width(5.dp))
                Text("ساخت",color=Color.Black)
            }
        },
        dismissButton={TextButton(onClick=onDismiss,enabled=!busy){Text("لغو")}}
    )

    if(showMediaPicker) {
        MediaTagPickerDialog(
            backend=backend,
            repository=repository,
            onDismiss={showMediaPicker=false},
            onSelected={
                taggedMedia=it
                showMediaPicker=false
            }
        )
    }
}

@Composable
private fun LiveSourceDialog(
    initial:String,
    onDismiss:()->Unit,
    onStart:(String)->Unit
) {
    var url by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest=onDismiss,
        icon={Icon(Icons.Default.LiveTv,null,tint=FqDanger)},
        title={Text("اتصال منبع Live")},
        text={
            Column {
                Text(
                    "آدرس HLS خروجی Encoder/Live pipeline رو وارد کن. Premiere این مرحله رو لازم نداره.",
                    color=FqMuted,
                    fontSize=8.sp,
                    lineHeight=14.sp
                )
                OutlinedTextField(
                    value=url,
                    onValueChange={url=it.take(2000)},
                    label={Text("https://.../master.m3u8")},
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                )
            }
        },
        confirmButton={
            Button(
                onClick={onStart(url.trim())},
                enabled=url.trim().startsWith("http")
            ) { Text("شروع Live") }
        },
        dismissButton={TextButton(onClick=onDismiss){Text("لغو")}}
    )
}

private fun parseLiveDateTime(value:String):String? =
    runCatching {
        LocalDateTime.parse(
            value.trim(),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        ).atZone(ZoneId.systemDefault()).toInstant().toString()
    }.getOrNull()

private fun liveScheduleLabel(event:LiveEvent):String {
    if(event.state=="live") return "همین الان در حال پخش"
    val raw=event.scheduledAt ?: return "زمان‌بندی نشده"
    return raw.replace("T"," ").replace("Z"," UTC").take(20)
}

private fun compactLiveCount(value:Long):String=when {
    value>=1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
