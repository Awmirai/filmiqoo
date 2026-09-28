package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.launch
import java.util.Locale

private enum class SeriesEpisodeFilter { ALL, UNWATCHED, READY }

private sealed interface PremiumDetailLoad {
    data object Loading : PremiumDetailLoad
    data class Ready(
        val tmdb: MediaDetail,
        val platform: PlatformDetail?
    ) : PremiumDetailLoad
    data class Error(val message: String) : PremiumDetailLoad
}

@Composable
fun PremiumDetailScreen(
    media: MediaItem,
    repository: TmdbRepository,
    backend: BackendRepository,
    store: LocalStore,
    onBack: () -> Unit,
    onMedia: (MediaItem) -> Unit,
    onChat: (MediaItem) -> Unit,
    onWatchParty: (MediaItem) -> Unit,
    onClip: (String) -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
    onPerson: (CastMember) -> Unit,
    onRequireAuth: () -> Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val library=remember { LibraryRepository(backend) }
    val seriesAlerts=remember { SeriesAlertsRepository(backend) }
    val pulseRepository=remember { PulseRepository(backend) }
    val socialRepository=remember { SocialRepository(backend) }

    var reload by remember(media.key) { mutableIntStateOf(0) }
    var state by remember(media.key) { mutableStateOf<PremiumDetailLoad>(PremiumDetailLoad.Loading) }
    var favorite by remember(media.key) { mutableStateOf(store.contains("favorites",media.key)) }
    var favoriteBusy by remember { mutableStateOf(false) }
    var watchlist by remember(media.key) { mutableStateOf(false) }
    var watchlistBusy by remember { mutableStateOf(false) }
    var seriesFollowing by remember(media.key) { mutableStateOf(false) }
    var seriesFollowBusy by remember { mutableStateOf(false) }
    var showCollections by remember { mutableStateOf(false) }
    var showAvailabilityAlerts by remember { mutableStateOf(false) }
    var downloadBusy by remember { mutableStateOf(false) }
    var pulse by remember(media.key) { mutableStateOf<PulseState?>(null) }
    var relatedClips by remember(media.key) { mutableStateOf<List<ReelFeedItem>>(emptyList()) }
    var pulseBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(media.key,reload) {
        state=PremiumDetailLoad.Loading
        state=runCatching {
            val tmdb=repository.detail(media)
            val platform=if(!media.backendId.isNullOrBlank()) {
                runCatching { backend.detail(media.backendId) }.getOrNull()
            } else null
            if(backend.session.isLoggedIn && !media.backendId.isNullOrBlank()) {
                watchlist=runCatching {
                    library.watchlist().any { it.backendId==media.backendId }
                }.getOrDefault(false)
                if(media.type==MediaType.TV) {
                    seriesFollowing=runCatching {
                        seriesAlerts.status(media.backendId).following
                    }.getOrDefault(false)
                }
            }
            PremiumDetailLoad.Ready(tmdb,platform)
        }.getOrElse { PremiumDetailLoad.Error(it.message ?: "خطا در دریافت اطلاعات") }
    }

    LaunchedEffect(media.backendId,reload) {
        val id=media.backendId
        if(id.isNullOrBlank()) {
            pulse=null
            relatedClips=emptyList()
        } else {
            pulse=runCatching { pulseRepository.load(id) }.getOrNull()
            relatedClips=runCatching { socialRepository.mediaClips(id) }
                .getOrDefault(emptyList())
        }
    }

    BackHandler { onBack() }

    when(val s=state) {
        PremiumDetailLoad.Loading -> LoadingPage("در حال آماده‌سازی صفحه عنوان...")
        is PremiumDetailLoad.Error -> ErrorPage(s.message) { reload++ }
        is PremiumDetailLoad.Ready -> {
            val d=s.tmdb
            val platform=s.platform
            val versions=platform?.versions.orEmpty().filter { it.streamReady }
            val preferred=versions.firstOrNull { it.preferred } ?: versions.firstOrNull()
            val fallbackVersion=d.media.mediaVersionId
            var selectedVersionId by remember(platform?.id,versions) {
                mutableStateOf(preferred?.id ?: fallbackVersion)
            }

            val selectedVersion=versions.firstOrNull { it.id==selectedVersionId }
            val canPlay=!selectedVersionId.isNullOrBlank() &&
                (selectedVersion?.streamReady == true || d.media.streamReady)

            val tabs=remember(d.media.type) {
                if(d.media.type==MediaType.TV) {
                    listOf("معرفی","قسمت‌ها","بازیگران","کلاب","اطلاعات")
                } else {
                    listOf("معرفی","بازیگران","کلاب","اطلاعات")
                }
            }
            var tab by rememberSaveable(d.media.key) { mutableIntStateOf(0) }

            Box(Modifier.fillMaxSize().background(FqBg)) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item {
                        PremiumDetailHero(
                            detail=d,
                            platform=platform,
                            repository=repository,
                            selectedVersion=selectedVersion,
                            canPlay=canPlay,
                            onBack=onBack,
                            onPlay={
                                val id=selectedVersionId ?: return@PremiumDetailHero
                                onPlay(
                                    PlaybackTarget(
                                        mediaVersionId=id,
                                        title=d.media.title,
                                        mediaTitleId=d.media.backendId,
                                        subtitle=buildString {
                                            append(d.media.year)
                                            val quality=selectedVersion?.quality ?: d.media.quality
                                            if(quality.isNotBlank()) {
                                                if(isNotBlank()) append(" • ")
                                                append(quality)
                                            }
                                        },
                                        posterUrl=repository.poster(d.media.posterPath),
                                        variants=versions.map {
                                            PlaybackVariant(
                                                mediaVersionId=it.id,
                                                label=it.quality.ifBlank { "Auto" },
                                                codec=it.codec,
                                                hdr=it.hdr
                                            )
                                        }
                                    )
                                )
                            },
                            onTrailer={
                                d.trailerKey?.let { openYoutube(context,it) }
                            },
                            onShare={
                                val label=d.media.title +
                                    if(d.media.year.isBlank()) "" else " ("+d.media.year+")"
                                val backendId=d.media.backendId
                                if(backendId.isNullOrBlank()) {
                                    shareText(context,label)
                                } else {
                                    FilmiqooDeepLinks.share(
                                        context,
                                        label,
                                        FilmiqooDeepLinks.title(backendId)
                                    )
                                }
                            }
                        )
                    }

                    item {
                        PremiumDetailActions(
                            favorite=favorite,
                            favoriteBusy=favoriteBusy,
                            watchlist=watchlist,
                            watchlistBusy=watchlistBusy,
                            showSeriesFollow=d.media.type==MediaType.TV && !d.media.backendId.isNullOrBlank(),
                            showAvailabilityAlerts=!d.media.backendId.isNullOrBlank(),
                            seriesFollowing=seriesFollowing,
                            seriesFollowBusy=seriesFollowBusy,
                            downloadBusy=downloadBusy,
                            canDownload=canPlay,
                            onFavorite={
                                if(favoriteBusy) return@PremiumDetailActions
                                val before=favorite
                                favorite=!before
                                favoriteBusy=true
                                scope.launch {
                                    runCatching {
                                        if(!d.media.backendId.isNullOrBlank() && backend.session.isLoggedIn) {
                                            backend.toggleFavorite(d.media.backendId)
                                        } else {
                                            store.toggle("favorites",d.media.key)
                                        }
                                    }.onSuccess {
                                        favorite=it
                                    }.onFailure {
                                        favorite=before
                                        message=it.message
                                    }
                                    favoriteBusy=false
                                }
                            },
                            onWatchlist={
                                val id=d.media.backendId
                                if(id.isNullOrBlank()) {
                                    message="این عنوان هنوز به کاتالوگ Filmiqoo متصل نیست."
                                    return@PremiumDetailActions
                                }
                                if(!backend.session.isLoggedIn) {
                                    onRequireAuth()
                                    return@PremiumDetailActions
                                }
                                if(watchlistBusy) return@PremiumDetailActions
                                val before=watchlist
                                watchlist=!before
                                watchlistBusy=true
                                scope.launch {
                                    runCatching { library.toggleWatchlist(id) }
                                        .onSuccess { watchlist=it }
                                        .onFailure {
                                            watchlist=before
                                            message=it.message
                                        }
                                    watchlistBusy=false
                                }
                            },
                            onSeriesFollow={
                                val id=d.media.backendId
                                if(id.isNullOrBlank()) return@PremiumDetailActions
                                if(!backend.session.isLoggedIn) {
                                    onRequireAuth()
                                    return@PremiumDetailActions
                                }
                                if(seriesFollowBusy) return@PremiumDetailActions
                                val before=seriesFollowing
                                seriesFollowing=!before
                                seriesFollowBusy=true
                                scope.launch {
                                    runCatching {
                                        seriesAlerts.update(
                                            mediaId=id,
                                            following=!before,
                                            notifyNewEpisode=true,
                                            notifyStreamReady=true
                                        )
                                    }.onSuccess {
                                        seriesFollowing=it.following
                                        message=if(it.following)
                                            "اعلان قسمت‌های جدید و نسخه آماده پخش فعال شد."
                                        else
                                            "دنبال‌کردن این سریال متوقف شد."
                                    }.onFailure {
                                        seriesFollowing=before
                                        message=it.message
                                    }
                                    seriesFollowBusy=false
                                }
                            },
                            onAvailabilityAlerts={
                                if(!backend.session.isLoggedIn) {
                                    onRequireAuth()
                                } else if(d.media.backendId.isNullOrBlank()) {
                                    message="این عنوان هنوز به کاتالوگ Filmiqoo متصل نیست."
                                } else {
                                    showAvailabilityAlerts=true
                                }
                            },
                            onCollections={
                                if(!backend.session.isLoggedIn) {
                                    onRequireAuth()
                                } else if(d.media.backendId.isNullOrBlank()) {
                                    message="این عنوان هنوز به Catalog واقعی Filmiqoo متصل نیست."
                                } else {
                                    showCollections=true
                                }
                            },
                            onDownload={
                                val id=selectedVersionId
                                if(id.isNullOrBlank()) return@PremiumDetailActions
                                if(!backend.session.isLoggedIn) {
                                    onRequireAuth()
                                    return@PremiumDetailActions
                                }
                                downloadBusy=true
                                scope.launch {
                                    runCatching {
                                        backend.enqueueDownload(
                                            context,
                                            PlaybackTarget(
                                                mediaVersionId=id,
                                                title=d.media.title,
                                                mediaTitleId=d.media.backendId,
                                                subtitle=selectedVersion?.quality.orEmpty(),
                                                posterUrl=repository.poster(d.media.posterPath)
                                            )
                                        )
                                    }.onSuccess {
                                        message="دانلود به صف دستگاه اضافه شد."
                                    }.onFailure {
                                        message=it.message
                                    }
                                    downloadBusy=false
                                }
                            },
                            onWatchParty={onWatchParty(d.media)},
                            onChat={onChat(d.media)}
                        )
                    }

                    d.media.backendId?.takeIf(String::isNotBlank)?.let { pulseMediaId ->
                        item {
                            FilmiqooPulseCard(
                                state=pulse,
                                busy=pulseBusy,
                                loggedIn=backend.session.isLoggedIn,
                                onRequireAuth=onRequireAuth,
                                onReact={ emoji ->
                                    if(!backend.session.isLoggedIn) {
                                        onRequireAuth()
                                    } else if(!pulseBusy) {
                                        pulseBusy=true
                                        scope.launch {
                                            runCatching {
                                                pulseRepository.react(
                                                    mediaId=pulseMediaId,
                                                    emoji=emoji
                                                )
                                            }.onSuccess {
                                                pulse=it
                                            }.onFailure {
                                                message=it.message
                                            }
                                            pulseBusy=false
                                        }
                                    }
                                }
                            )
                        }
                    }

                    if(versions.size>1) {
                        item {
                            PremiumSectionHeader(
                                title="نسخه‌های قابل پخش",
                                subtitle="کیفیت موردنظرت رو انتخاب کن",
                                icon=Icons.Default.HighQuality
                            )
                        }
                        item {
                            VersionSelector(
                                versions=versions,
                                selectedId=selectedVersionId,
                                onSelected={selectedVersionId=it}
                            )
                        }
                    }

                    item {
                        LazyRow(
                            contentPadding=PaddingValues(
                                horizontal=FqDimens.Screen,
                                vertical=12.dp
                            ),
                            horizontalArrangement=Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(tabs) { index,label ->
                                PremiumChip(
                                    label=label,
                                    active=tab==index,
                                    onClick={tab=index}
                                )
                            }
                        }
                    }

                    when {
                        tab==0 -> {
                            item {
                                OverviewSection(
                                    detail=d,
                                    platform=platform
                                )
                            }
                            d.franchise?.takeIf { it.parts.size>1 }?.let { franchise ->
                                item {
                                    PremiumSectionHeader(
                                        title="ترتیب تماشای مجموعه",
                                        subtitle=franchise.name.ifBlank { "Franchise" },
                                        icon=Icons.Default.PlaylistPlay
                                    )
                                }
                                item {
                                    FranchiseWatchOrderRow(
                                        franchise=franchise,
                                        current=d.media,
                                        repository=repository,
                                        onMedia=onMedia
                                    )
                                }
                            }
                            if(d.cast.isNotEmpty()) {
                                item {
                                    PremiumSectionHeader(
                                        title="بازیگران",
                                        subtitle="چهره‌های اصلی",
                                        icon=Icons.Default.Groups
                                    )
                                }
                                item { PremiumCastRow(d.cast,repository,onPerson) }
                            }
                            if(d.directors.isNotEmpty()) {
                                item {
                                    PremiumSectionHeader(
                                        title=if(d.media.type==MediaType.MOVIE)"کارگردان" else "سازندگان و کارگردانان",
                                        subtitle="عوامل کلیدی پشت دوربین",
                                        icon=Icons.Default.MovieCreation
                                    )
                                }
                                item { PremiumCastRow(d.directors,repository,onPerson) }
                            }
                            if(d.recommendations.isNotEmpty()) {
                                item {
                                    PremiumSectionHeader(
                                        title="پیشنهادهای مشابه",
                                        subtitle="اگر این عنوان رو دوست داشتی",
                                        icon=Icons.Default.AutoAwesome
                                    )
                                }
                                item {
                                    PremiumRecommendationRow(
                                        list=d.recommendations,
                                        repository=repository,
                                        onMedia=onMedia
                                    )
                                }
                            }
                        }

                        d.media.type==MediaType.TV && tab==1 -> {
                            if(platform?.seasons.orEmpty().isNotEmpty()) {
                                item {
                                    PremiumSeriesPanel(
                                        title=d.media.title,
                                        seasons=platform!!.seasons,
                                        backend=backend,
                                        mediaTitleId=d.media.backendId,
                                        loggedIn=backend.session.isLoggedIn,
                                        onRequireAuth=onRequireAuth,
                                        onPlay=onPlay,
                                        onDownload={ ep ->
                                            val id=ep.mediaVersionId ?: return@PremiumSeriesPanel
                                            if(!backend.session.isLoggedIn) {
                                                message="برای دانلود باید وارد حساب Filmiqoo شوی."
                                            } else {
                                                scope.launch {
                                                    runCatching {
                                                        val fallback=PlaybackTarget(
                                                            mediaVersionId=id,
                                                            title=ep.name.ifBlank { d.media.title+" • قسمت "+ep.number },
                                                            mediaTitleId=d.media.backendId,
                                                            subtitle=ep.quality.orEmpty()
                                                        )
                                                        val target=runCatching {
                                                            backend.playbackContext(id)
                                                        }.getOrDefault(fallback)
                                                        backend.enqueueDownload(context,target)
                                                    }.onSuccess {
                                                        message="دانلود قسمت به صف اضافه شد."
                                                    }.onFailure {
                                                        message=it.message
                                                    }
                                                }
                                            }
                                        },
                                        onDownloadSeason={ selectedSeason ->
                                            if(!backend.session.isLoggedIn) {
                                                message="برای دانلود فصل باید وارد حساب Filmiqoo شوی."
                                            } else {
                                                val readyEpisodes=selectedSeason.episodes.filter {
                                                    it.streamReady && !it.mediaVersionId.isNullOrBlank()
                                                }
                                                if(readyEpisodes.isEmpty()) {
                                                    message="برای این فصل فایل آماده دانلود وجود ندارد."
                                            } else {
                                                    scope.launch {
                                                        var queued=0
                                                        var failed=0
                                                        readyEpisodes.forEach { ep ->
                                                            val id=ep.mediaVersionId ?: return@forEach
                                                            runCatching {
                                                                val fallback=PlaybackTarget(
                                                                    mediaVersionId=id,
                                                                    title=ep.name.ifBlank {
                                                                        d.media.title+" • قسمت "+ep.number
                                                                    },
                                                                    mediaTitleId=d.media.backendId,
                                                                    subtitle="S"+
                                                                        selectedSeason.number.toString().padStart(2,'0')+
                                                                        "E"+ep.number.toString().padStart(2,'0')+
                                                                        if(ep.quality.isNullOrBlank())"" else " • "+ep.quality,
                                                                    posterUrl=repository.poster(d.media.posterPath)
                                                                )
                                                                val target=runCatching {
                                                                    backend.playbackContext(id)
                                                                }.getOrDefault(fallback)
                                                                backend.enqueueDownload(context,target)
                                                            }.onSuccess {
                                                                queued++
                                                            }.onFailure {
                                                                failed++
                                                            }
                                                        }
                                                        message=when {
                                                            queued>0 && failed==0 ->
                                                                queued.toString()+" قسمت از فصل "+selectedSeason.number+" به صف دانلود اضافه شد."
                                                            queued>0 ->
                                                                queued.toString()+" قسمت صف شد؛ "+failed+" قسمت خطا داشت."
                                                            else -> "دانلود فصل شروع نشد."
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            } else {
                                item {
                                    PremiumSectionHeader(
                                        title="فصل‌ها",
                                        subtitle="اطلاعات فصل‌های ثبت‌شده",
                                        icon=Icons.Default.VideoLibrary
                                    )
                                }
                                items(d.seasons,key={it.number}) { season ->
                                    PreviewSeasonCard(season,repository)
                                }
                            }
                        }

                        isCastTab(d.media.type,tab) -> {
                            item {
                                PremiumSectionHeader(
                                    title="بازیگران و عوامل",
                                    subtitle="اطلاعات بازیگران اصلی",
                                    icon=Icons.Default.TheaterComedy
                                )
                            }
                            if(d.directors.isNotEmpty()) {
                                item {
                                    Text(
                                        if(d.media.type==MediaType.MOVIE)"کارگردان" else "سازندگان و کارگردانان",
                                        fontSize=11.sp,
                                        fontWeight=FontWeight.Bold,
                                        color=FqGold,
                                        modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp)
                                    )
                                }
                                items(d.directors,key={"director_"+it.id}) { person ->
                                    PremiumCastListItem(person,repository,onPerson)
                                }
                                item {
                                    Text(
                                        "بازیگران",
                                        fontSize=11.sp,
                                        fontWeight=FontWeight.Bold,
                                        color=FqGold,
                                        modifier=Modifier.padding(start=16.dp,end=16.dp,top=14.dp,bottom=6.dp)
                                    )
                                }
                            }
                            items(d.cast,key={it.id}) { actor ->
                                PremiumCastListItem(actor,repository,onPerson)
                            }
                        }

                        isCommunityTab(d.media.type,tab) -> {
                            item {
                                PremiumCommunityPanel(
                                    media=d.media,
                                    backend=backend,
                                    loggedIn=backend.session.isLoggedIn,
                                    relatedClips=relatedClips,
                                    onRequireAuth=onRequireAuth,
                                    onChat=onChat,
                                    onWatchParty=onWatchParty,
                                    onClip=onClip
                                )
                            }
                        }

                        else -> {
                            item {
                                TechnicalInfoSection(
                                    media=d.media,
                                    detail=d,
                                    versions=platform?.versions.orEmpty()
                                )
                            }
                        }
                    }

                    item { Spacer(Modifier.height(50.dp)) }
                }

                message?.let {
                    Snackbar(
                        modifier=Modifier.align(Alignment.BottomCenter).padding(16.dp),
                        action={
                            TextButton(onClick={message=null}) { Text("باشه") }
                        }
                    ) { Text(it) }
                }
            }

            if(showAvailabilityAlerts && !d.media.backendId.isNullOrBlank()) {
                AvailabilityAlertsSheet(
                    mediaId=d.media.backendId,
                    backend=backend,
                    onDismiss={showAvailabilityAlerts=false}
                )
            }

            if(showCollections) {
                CollectionPickerSheet(
                    backend=backend,
                    media=d.media,
                    onDismiss={showCollections=false},
                    onMessage={
                        message=it
                        showCollections=false
                    }
                )
            }
        }
    }
}

@Composable
private fun PremiumDetailHero(
    detail: MediaDetail,
    platform: PlatformDetail?,
    repository: TmdbRepository,
    selectedVersion: PlatformVersion?,
    canPlay: Boolean,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onTrailer: () -> Unit,
    onShare: () -> Unit
) {
    Box(Modifier.fillMaxWidth().height(570.dp)) {
        RemoteImage(
            repository.backdrop(detail.media.backdropPath ?: detail.media.posterPath),
            Modifier.fillMaxSize(),
            ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha=.18f),
                    .45f to Color.Black.copy(alpha=.14f),
                    .72f to Color.Black.copy(alpha=.58f),
                    1f to FqBg
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha=.76f),
                    .62f to Color.Transparent,
                    1f to Color.Black.copy(alpha=.08f)
                )
            )
        )

        Row(
            Modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal=12.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Surface(
                color=Color.Black.copy(alpha=.42f),
                contentColor=Color.White,
                shape=CircleShape,
                border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.10f)),
                modifier=Modifier.size(44.dp).clickable(onClick=onBack)
            ) {
                Box(contentAlignment=Alignment.Center) {
                    Icon(Icons.Default.ArrowBack,null)
                }
            }
            Spacer(Modifier.weight(1f))
            Surface(
                color=Color.Black.copy(alpha=.42f),
                contentColor=Color.White,
                shape=CircleShape,
                border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.10f)),
                modifier=Modifier.size(44.dp).clickable(onClick=onShare)
            ) {
                Box(contentAlignment=Alignment.Center) {
                    Icon(Icons.Default.Share,null)
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal=18.dp)
                .padding(bottom=28.dp)
        ) {
            Row(
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(7.dp)
            ) {
                Surface(
                    color=FqGold,
                    contentColor=Color.White,
                    shape=RoundedCornerShape(8.dp)
                ) {
                    Text(
                        if(detail.media.type==MediaType.MOVIE)"فیلم" else "سریال",
                        fontSize=8.sp,
                        fontWeight=FontWeight.Black,
                        modifier=Modifier.padding(horizontal=7.dp,vertical=4.dp)
                    )
                }
                if(detail.media.vote>0) {
                    DetailMetaPill(Icons.Default.Star,"IMDb "+formatVote(detail.media.vote))
                }
                selectedVersion?.quality?.takeIf(String::isNotBlank)?.let {
                    DetailMetaPill(Icons.Default.HighQuality,it)
                }
                selectedVersion?.hdr?.takeIf(String::isNotBlank)?.let {
                    DetailMetaPill(Icons.Default.HdrOn,it)
                }
            }

            Text(
                detail.media.title,
                color=Color.White,
                fontSize=32.sp,
                lineHeight=38.sp,
                fontWeight=FontWeight.Black,
                maxLines=2,
                overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=12.dp)
            )

            if(detail.media.originalTitle.isNotBlank() &&
                detail.media.originalTitle!=detail.media.title) {
                Text(
                    detail.media.originalTitle,
                    color=Color.White.copy(alpha=.58f),
                    fontSize=11.sp,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=2.dp)
                )
            }

            val meta=listOf(
                detail.media.year,
                detail.runtime.takeIf { it>0 }?.let { it.toString()+" دقیقه" }.orEmpty(),
                detail.genres.take(2).joinToString(" • ")
            ).filter(String::isNotBlank).joinToString(" • ")
            if(meta.isNotBlank()) {
                Text(
                    meta,
                    color=Color.White.copy(alpha=.72f),
                    fontSize=10.sp,
                    modifier=Modifier.padding(top=8.dp)
                )
            }

            if(detail.media.overview.isNotBlank()) {
                Text(
                    detail.media.overview,
                    color=Color.White.copy(alpha=.80f),
                    fontSize=11.sp,
                    lineHeight=18.sp,
                    maxLines=3,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=10.dp).fillMaxWidth(.92f)
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top=18.dp),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(9.dp)
            ) {
                Button(
                    onClick=onPlay,
                    enabled=canPlay,
                    colors=ButtonDefaults.buttonColors(
                        containerColor=Color.White,
                        contentColor=Color.Black,
                        disabledContainerColor=Color.White.copy(alpha=.16f),
                        disabledContentColor=Color.White.copy(alpha=.55f)
                    ),
                    shape=RoundedCornerShape(14.dp),
                    modifier=Modifier.height(50.dp)
                ) {
                    Icon(Icons.Default.PlayArrow,null)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if(canPlay)"تماشا" else "فعلاً قابل پخش نیست",
                        fontWeight=FontWeight.Black
                    )
                }

                if(detail.trailerKey!=null) {
                    FilledTonalButton(
                        onClick=onTrailer,
                        colors=ButtonDefaults.filledTonalButtonColors(
                            containerColor=Color.Black.copy(alpha=.48f),
                            contentColor=Color.White
                        ),
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.height(50.dp)
                    ) {
                        Icon(Icons.Default.OndemandVideo,null)
                        Spacer(Modifier.width(5.dp))
                        Text("تریلر")
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailMetaPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Surface(
        color=Color.Black.copy(alpha=.62f),
        shape=RoundedCornerShape(9.dp)
    ) {
        Row(
            Modifier.padding(horizontal=7.dp,vertical=4.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Icon(icon,null,tint=FqGold,modifier=Modifier.size(12.dp))
            Spacer(Modifier.width(3.dp))
            Text(text,fontSize=11.sp)
        }
    }
}

@Composable
private fun PremiumDetailActions(
    favorite: Boolean,
    favoriteBusy: Boolean,
    watchlist: Boolean,
    watchlistBusy: Boolean,
    showSeriesFollow: Boolean,
    showAvailabilityAlerts: Boolean,
    seriesFollowing: Boolean,
    seriesFollowBusy: Boolean,
    downloadBusy: Boolean,
    canDownload: Boolean,
    onFavorite: () -> Unit,
    onWatchlist: () -> Unit,
    onSeriesFollow: () -> Unit,
    onAvailabilityAlerts: () -> Unit,
    onCollections: () -> Unit,
    onDownload: () -> Unit,
    onWatchParty: () -> Unit,
    onChat: () -> Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp),
        horizontalArrangement=Arrangement.spacedBy(9.dp)
    ) {
        item {
            ActionTile(
                icon=if(favorite)Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                label=if(favorite)"علاقه‌مندی ✓" else "علاقه‌مندی",
                active=favorite,
                loading=favoriteBusy,
                onClick=onFavorite
            )
        }
        item {
            ActionTile(
                icon=if(watchlist)Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                label=if(watchlist)"برای بعد ✓" else "بعداً می‌بینم",
                active=watchlist,
                loading=watchlistBusy,
                onClick=onWatchlist
            )
        }
        if(showSeriesFollow) {
            item {
                ActionTile(
                    icon=if(seriesFollowing)Icons.Default.NotificationsActive else Icons.Default.AddAlert,
                    label=if(seriesFollowing)"دنبال می‌کنی" else "اعلان سریال",
                    active=seriesFollowing,
                    loading=seriesFollowBusy,
                    onClick=onSeriesFollow
                )
            }
        }
        if(showAvailabilityAlerts) {
            item {
                ActionTile(
                    icon=Icons.Default.AddAlert,
                    label="دوبله / 4K",
                    onClick=onAvailabilityAlerts
                )
            }
        }
        item {
            ActionTile(
                icon=Icons.Default.CollectionsBookmark,
                label="افزودن به لیست",
                onClick=onCollections
            )
        }
        item {
            ActionTile(
                icon=Icons.Default.Download,
                label="دانلود",
                active=false,
                loading=downloadBusy,
                enabled=canDownload,
                onClick=onDownload
            )
        }
        item {
            ActionTile(
                icon=Icons.Default.Groups,
                label="تماشای گروهی",
                onClick=onWatchParty
            )
        }
        item {
            ActionTile(
                icon=Icons.Default.Forum,
                label="گفت‌وگو",
                onClick=onChat
            )
        }
    }
}

@Composable
private fun ActionTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean=false,
    loading: Boolean=false,
    enabled: Boolean=true,
    onClick: () -> Unit
) {
    Column(
        Modifier.width(88.dp)
            .clickable(enabled=enabled && !loading,onClick=onClick),
        horizontalAlignment=Alignment.CenterHorizontally
    ) {
        Surface(
            color=if(active)FqGold else FqSurface2,
            contentColor=if(active)Color.White else if(enabled)Color.White else FqMuted,
            shape=CircleShape,
            border=androidx.compose.foundation.BorderStroke(
                1.dp,
                if(active)FqGold else Color.White.copy(alpha=.07f)
            ),
            modifier=Modifier.size(48.dp)
        ) {
            Box(contentAlignment=Alignment.Center) {
                if(loading) {
                    CircularProgressIndicator(
                        color=if(active)Color.White else FqGold,
                        strokeWidth=2.dp,
                        modifier=Modifier.size(19.dp)
                    )
                } else {
                    Icon(icon,null,modifier=Modifier.size(20.dp))
                }
            }
        }
        Text(
            label,
            color=if(enabled)FqMutedStrong else FqMuted,
            fontSize=8.sp,
            maxLines=2,
            lineHeight=11.sp,
            textAlign=androidx.compose.ui.text.style.TextAlign.Center,
            modifier=Modifier.padding(top=6.dp)
        )
    }
}
@Composable
private fun VersionSelector(
    versions: List<PlatformVersion>,
    selectedId: String?,
    onSelected: (String) -> Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(8.dp)
    ) {
        items(versions,key={it.id}) { version ->
            val selected=version.id==selectedId
            Surface(
                color=if(selected)FqGold else FqSurface,
                contentColor=if(selected)Color.Black else Color.White,
                shape=RoundedCornerShape(16.dp),
                modifier=Modifier.clickable { onSelected(version.id) }
            ) {
                Column(Modifier.padding(horizontal=14.dp,vertical=10.dp)) {
                    Text(
                        version.quality.ifBlank { "Auto" },
                        fontSize=12.sp,
                        fontWeight=FontWeight.Black
                    )
                    val tech=listOf(version.codec,version.hdr)
                        .filter(String::isNotBlank).joinToString(" • ")
                    if(tech.isNotBlank()) {
                        Text(
                            tech,
                            fontSize=11.sp,
                            color=if(selected)Color.Black.copy(alpha=.7f) else FqMuted,
                            modifier=Modifier.padding(top=2.dp)
                        )
                    }
                    if(version.fileSizeBytes>0) {
                        Text(
                            formatBytes(version.fileSizeBytes),
                            fontSize=11.sp,
                            color=if(selected)Color.Black.copy(alpha=.7f) else FqMuted,
                            modifier=Modifier.padding(top=2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewSection(
    detail: MediaDetail,
    platform: PlatformDetail?
) {
    Column(Modifier.fillMaxWidth().padding(18.dp)) {
        if(detail.tagline.isNotBlank()) {
            Text(
                "“"+detail.tagline+"”",
                color=FqGoldSoft,
                fontSize=13.sp,
                lineHeight=21.sp,
                modifier=Modifier.padding(bottom=12.dp)
            )
        }

        Text("داستان",fontSize=19.sp,fontWeight=FontWeight.Bold)
        Text(
            detail.media.overview.ifBlank {
                platform?.overview.orEmpty().ifBlank {
                    "توضیحی برای این عنوان ثبت نشده است."
                }
            },
            color=Color.White.copy(alpha=.82f),
            fontSize=11.sp,
            lineHeight=20.sp,
            modifier=Modifier.padding(top=8.dp)
        )

        val pills=buildList {
            if(platform?.versions.orEmpty().any { it.quality.contains("2160") || it.quality.contains("4K",true) }) {
                add(Icons.Default.HighQuality to "4K")
            }
            if(platform?.versions.orEmpty().any { it.hdr.isNotBlank() }) {
                add(Icons.Default.HdrOn to "HDR")
            }
            if(platform?.versions.orEmpty().any { it.codec.isNotBlank() }) {
                add(Icons.Default.Memory to "Multi Codec")
            }
            add(Icons.Default.Subtitles to "زیرنویس")
            add(Icons.Default.SurroundSound to "چندصدا")
        }

        LazyRow(
            horizontalArrangement=Arrangement.spacedBy(7.dp),
            modifier=Modifier.padding(top=16.dp)
        ) {
            items(pills) { p ->
                MetricPill(p.first,p.second)
            }
        }
    }
}

@Composable
private fun PremiumCastRow(
    cast: List<CastMember>,
    repository: TmdbRepository,
    onPerson: (CastMember) -> Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(13.dp)
    ) {
        items(cast.take(18),key={it.id}) { actor ->
            Column(
                Modifier.width(92.dp).clickable { onPerson(actor) },
                horizontalAlignment=Alignment.CenterHorizontally
            ) {
                RemoteImage(
                    repository.profile(actor.profilePath),
                    Modifier.size(84.dp).clip(CircleShape)
                )
                Text(
                    actor.name,
                    fontSize=11.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=6.dp)
                )
                Text(
                    actor.character,
                    color=FqMuted,
                    fontSize=11.sp,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PremiumCastListItem(
    actor: CastMember,
    repository: TmdbRepository,
    onPerson: (CastMember) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)
            .clip(RoundedCornerShape(17.dp)).background(FqSurface)
            .clickable { onPerson(actor) }.padding(10.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        RemoteImage(
            repository.profile(actor.profilePath),
            Modifier.size(62.dp).clip(CircleShape)
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(actor.name,fontSize=12.sp,fontWeight=FontWeight.Bold)
            Text(actor.character,color=FqMuted,fontSize=11.sp,modifier=Modifier.padding(top=3.dp))
        }
        Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
    }
}

@Composable
private fun PremiumRecommendationRow(
    list: List<MediaItem>,
    repository: TmdbRepository,
    onMedia: (MediaItem) -> Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(11.dp)
    ) {
        items(list.take(18),key={it.key}) { media ->
            Column(
                Modifier.width(138.dp).clickable { onMedia(media) }
            ) {
                RemoteImage(
                    repository.poster(media.posterPath),
                    Modifier.fillMaxWidth().height(202.dp).clip(RoundedCornerShape(18.dp)),
                    ContentScale.Crop
                )
                Text(
                    media.title,
                    fontSize=11.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=6.dp)
                )
                Text(
                    listOf(media.year,if(media.vote>0)"★ "+formatVote(media.vote) else "")
                        .filter(String::isNotBlank).joinToString(" • "),
                    color=FqMuted,
                    fontSize=11.sp
                )
            }
        }
    }
}

@Composable
private fun FranchiseWatchOrderRow(
    franchise:FranchiseInfo,
    current:MediaItem,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(11.dp)
    ) {
        items(
            franchise.parts,
            key={it.key}
        ) { media ->
            val index=franchise.parts.indexOfFirst { it.key==media.key }+1
            val active=media.id==current.id && media.type==current.type
            Column(
                Modifier.width(142.dp).clickable(enabled=!active) {
                    onMedia(media)
                }
            ) {
                Box(
                    Modifier.fillMaxWidth().height(207.dp)
                        .clip(RoundedCornerShape(18.dp))
                ) {
                    RemoteImage(
                        repository.poster(media.posterPath),
                        Modifier.fillMaxSize(),
                        ContentScale.Crop
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha=.06f),
                                    Color.Black.copy(alpha=.72f)
                                )
                            )
                        )
                    )
                    Surface(
                        color=if(active)FqGold else Color.Black.copy(alpha=.72f),
                        contentColor=if(active)Color.Black else Color.White,
                        shape=RoundedCornerShape(10.dp),
                        modifier=Modifier.align(Alignment.TopStart).padding(7.dp)
                    ) {
                        Text(
                            "#"+index,
                            fontSize=11.sp,
                            fontWeight=FontWeight.Black,
                            modifier=Modifier.padding(horizontal=7.dp,vertical=5.dp)
                        )
                    }
                    if(active) {
                        Surface(
                            color=FqGold,
                            contentColor=Color.Black,
                            shape=RoundedCornerShape(9.dp),
                            modifier=Modifier.align(Alignment.BottomCenter).padding(7.dp)
                        ) {
                            Text(
                                "در حال مشاهده",
                                fontSize=11.sp,
                                fontWeight=FontWeight.Bold,
                                modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp)
                            )
                        }
                    }
                }

                Text(
                    media.title,
                    fontSize=11.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=6.dp)
                )
                Text(
                    listOf(
                        media.year,
                        if(media.vote>0)"★ "+formatVote(media.vote) else ""
                    ).filter(String::isNotBlank).joinToString(" • "),
                    color=FqMuted,
                    fontSize=11.sp
                )
            }
        }
    }
}

@Composable
private fun PremiumSeriesPanel(
    title: String,
    seasons: List<PlatformSeason>,
    backend: BackendRepository,
    mediaTitleId: String?,
    loggedIn: Boolean,
    onRequireAuth: () -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
    onDownload: (PlatformEpisode) -> Unit,
    onDownloadSeason: (PlatformSeason) -> Unit
) {
    val seasonsKey=remember(seasons) { seasons.joinToString("|") { it.id } }
    var selectedSeason by rememberSaveable(seasonsKey) {
        mutableIntStateOf(
            seasons.firstOrNull { it.number>0 }?.number
                ?: seasons.firstOrNull()?.number
                ?: 0
        )
    }
    var episodeFilterName by rememberSaveable(seasonsKey) {
        mutableStateOf(SeriesEpisodeFilter.ALL.name)
    }
    val episodeFilter=runCatching {
        SeriesEpisodeFilter.valueOf(episodeFilterName)
    }.getOrDefault(SeriesEpisodeFilter.ALL)
    val scope=rememberCoroutineScope()
    val progressRepo=remember { SeriesProgressRepository(backend) }
    var progressRefresh by remember { mutableIntStateOf(0) }
    var watchProgress by remember { mutableStateOf<SeriesWatchProgress?>(null) }
    var progressLoading by remember { mutableStateOf(false) }
    var progressError by remember { mutableStateOf<String?>(null) }
    var busyEpisodeId by remember { mutableStateOf<String?>(null) }
    var busySeasonId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mediaTitleId,loggedIn,progressRefresh) {
        if(!loggedIn || mediaTitleId.isNullOrBlank()) {
            watchProgress=null
            return@LaunchedEffect
        }
        progressLoading=true
        runCatching { progressRepo.load(mediaTitleId) }
            .onSuccess {
                watchProgress=it
                progressError=null
            }
            .onFailure { progressError=it.message }
        progressLoading=false
    }

    val season=seasons.firstOrNull { it.number==selectedSeason } ?: seasons.firstOrNull()

    Column(Modifier.fillMaxWidth()) {
        PremiumSectionHeader(
            title="فصل‌ها و قسمت‌ها",
            subtitle=watchProgress?.let {
                it.watchedCount.toString()+" از "+it.totalCount+" قسمت دیده شده"
            } ?: seasons.size.toString()+" فصل",
            icon=Icons.Default.VideoLibrary
        )

        watchProgress?.let { progress ->
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(18.dp),
                modifier=Modifier.fillMaxWidth()
                    .padding(horizontal=16.dp,vertical=6.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.Insights,null,tint=FqGold)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "پیشرفت کل سریال",
                            fontSize=12.sp,
                            fontWeight=FontWeight.Bold,
                            modifier=Modifier.weight(1f)
                        )
                        Text(
                            ((progress.progress*100).toInt()).toString()+"٪",
                            color=FqGold,
                            fontSize=11.sp,
                            fontWeight=FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress={progress.progress},
                        color=FqGold,
                        trackColor=FqSurface3,
                        modifier=Modifier.fillMaxWidth()
                            .height(5.dp)
                            .padding(top=5.dp)
                    )
                }
            }
        }

        if(progressLoading) {
            LinearProgressIndicator(
                color=FqGold,
                modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp)
            )
        }

        progressError?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=11.sp,
                modifier=Modifier.padding(horizontal=16.dp,vertical=4.dp)
            )
        }

        LazyRow(
            contentPadding=PaddingValues(horizontal=16.dp),
            horizontalArrangement=Arrangement.spacedBy(8.dp)
        ) {
            items(seasons,key={it.id}) { s ->
                PremiumChip(
                    label=if(s.number==0)"ویژه" else "فصل "+s.number,
                    active=s.number==selectedSeason,
                    onClick={selectedSeason=s.number}
                )
            }
        }

        season?.let { selected ->
            val readyCount=selected.episodes.count {
                it.streamReady && !it.mediaVersionId.isNullOrBlank()
            }
            val seasonStates=selected.episodes.mapNotNull {
                watchProgress?.episodes?.get(it.id)
            }
            val watchedCount=seasonStates.count { it.completed }
            val seasonTotal=selected.episodes.size
            val allWatched=seasonTotal>0 && watchedCount>=seasonTotal

            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(17.dp),
                modifier=Modifier.fillMaxWidth()
                    .padding(horizontal=16.dp,vertical=9.dp)
            ) {
                Column(Modifier.padding(11.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if(selected.number==0)"قسمت‌های ویژه" else "فصل "+selected.number,
                                fontSize=11.sp,
                                fontWeight=FontWeight.Bold
                            )
                            Text(
                                watchedCount.toString()+" از "+seasonTotal+" قسمت دیده شده",
                                color=FqMuted,
                                fontSize=11.sp,
                                modifier=Modifier.padding(top=2.dp)
                            )
                        }
                        if(loggedIn) {
                            OutlinedButton(
                                enabled=busySeasonId!=selected.id,
                                onClick={
                                    busySeasonId=selected.id
                                    scope.launch {
                                        runCatching {
                                            progressRepo.setSeasonWatched(
                                                selected.id,
                                                watched=!allWatched
                                            )
                                        }.onSuccess {
                                            progressRefresh++
                                        }.onFailure {
                                            progressError=it.message
                                        }
                                        busySeasonId=null
                                    }
                                },
                                contentPadding=PaddingValues(horizontal=9.dp,vertical=4.dp)
                            ) {
                                if(busySeasonId==selected.id) {
                                    CircularProgressIndicator(
                                        color=FqGold,
                                        strokeWidth=2.dp,
                                        modifier=Modifier.size(15.dp)
                                    )
                                } else {
                                    Icon(
                                        if(allWatched)Icons.Default.RemoveDone else Icons.Default.DoneAll,
                                        null,
                                        modifier=Modifier.size(16.dp)
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if(allWatched)"نادیده" else "همه دیده‌شده",
                                    fontSize=11.sp
                                )
                            }
                        } else {
                            TextButton(onClick=onRequireAuth) {
                                Text("ورود برای Progress",fontSize=11.sp)
                            }
                        }
                    }

                    if(seasonTotal>0) {
                        LinearProgressIndicator(
                            progress={if(seasonTotal>0) watchedCount.toFloat()/seasonTotal else 0f},
                            color=FqGold,
                            trackColor=FqSurface3,
                            modifier=Modifier.fillMaxWidth()
                                .height(4.dp)
                                .padding(top=6.dp)
                        )
                    }

                    if(readyCount>0) {
                        OutlinedButton(
                            onClick={onDownloadSeason(selected)},
                            shape=RoundedCornerShape(13.dp),
                            modifier=Modifier.fillMaxWidth().padding(top=9.dp)
                        ) {
                            Icon(Icons.Default.DownloadForOffline,null,tint=FqGold)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "دانلود فصل "+selected.number+" • "+readyCount+" قسمت",
                                color=FqGold,
                                fontSize=11.sp
                            )
                        }
                    }
                }
            }

            LazyRow(
                contentPadding=PaddingValues(horizontal=16.dp),
                horizontalArrangement=Arrangement.spacedBy(7.dp)
            ) {
                listOf(
                    SeriesEpisodeFilter.ALL to "همه",
                    SeriesEpisodeFilter.UNWATCHED to "ندیده",
                    SeriesEpisodeFilter.READY to "آماده پخش"
                ).forEach { (item,label) ->
                    item {
                        FilterChip(
                            selected=episodeFilter==item,
                            onClick={episodeFilterName=item.name},
                            label={Text(label,fontSize=10.sp)}
                        )
                    }
                }
            }
        }

        val allEpisodes=seasons
            .sortedBy { it.number }
            .flatMap { currentSeason ->
                currentSeason.episodes.sortedBy { it.number }.map { ep ->
                    currentSeason.number to ep
                }
            }

        val visibleEpisodes=season?.episodes
            ?.sortedBy { it.number }
            ?.filter { ep ->
                when(episodeFilter) {
                    SeriesEpisodeFilter.ALL -> true
                    SeriesEpisodeFilter.UNWATCHED ->
                        watchProgress?.episodes?.get(ep.id)?.completed != true
                    SeriesEpisodeFilter.READY ->
                        ep.streamReady && !ep.mediaVersionId.isNullOrBlank()
                }
            }
            .orEmpty()

        if(season!=null && visibleEpisodes.isEmpty()) {
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(18.dp),
                modifier=Modifier.fillMaxWidth()
                    .padding(horizontal=16.dp,vertical=8.dp)
            ) {
                Row(
                    Modifier.padding(14.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(
                        if(episodeFilter==SeriesEpisodeFilter.UNWATCHED)
                            Icons.Default.DoneAll
                        else
                            Icons.Default.FilterAltOff,
                        contentDescription=null,
                        tint=FqMuted
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if(episodeFilter==SeriesEpisodeFilter.UNWATCHED)
                            "همه قسمت‌های این فصل دیده شده"
                        else
                            "قسمتی با این فیلتر پیدا نشد",
                        color=FqMuted,
                        fontSize=11.sp
                    )
                }
            }
        }

        visibleEpisodes.forEach { ep ->
            val currentIndex=allEpisodes.indexOfFirst { it.second.id==ep.id }
            val next=if(currentIndex>=0) {
                allEpisodes.drop(currentIndex+1).firstOrNull {
                    it.second.streamReady && !it.second.mediaVersionId.isNullOrBlank()
                }
            } else null

            EpisodeCard(
                title=title,
                mediaTitleId=mediaTitleId,
                seasonNumber=season?.number ?: 0,
                episode=ep,
                nextSeasonNumber=next?.first,
                nextEpisode=next?.second,
                watchState=watchProgress?.episodes?.get(ep.id),
                watchBusy=busyEpisodeId==ep.id,
                onToggleWatched={
                    if(!loggedIn) {
                        onRequireAuth()
                    } else {
                        busyEpisodeId=ep.id
                        scope.launch {
                            val watched=watchProgress?.episodes?.get(ep.id)?.completed==true
                            runCatching {
                                progressRepo.setEpisodeWatched(ep.id,!watched)
                            }.onSuccess {
                                progressRefresh++
                            }.onFailure {
                                progressError=it.message
                            }
                            busyEpisodeId=null
                        }
                    }
                },
                onPlay=onPlay,
                onDownload=onDownload
            )
        }
    }
}

@Composable
private fun EpisodeCard(
    title: String,
    mediaTitleId: String?,
    seasonNumber: Int,
    episode: PlatformEpisode,
    nextSeasonNumber: Int?,
    nextEpisode: PlatformEpisode?,
    watchState: EpisodeWatchState?,
    watchBusy: Boolean,
    onToggleWatched: () -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
    onDownload: (PlatformEpisode) -> Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(20.dp),
        modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(180.dp)) {
                RemoteImage(
                    episode.stillUrl.takeIf(String::isNotBlank),
                    Modifier.fillMaxSize(),
                    ContentScale.Crop
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent,Color.Black.copy(alpha=.75f))
                        )
                    )
                )

                Surface(
                    color=Color.Black.copy(alpha=.65f),
                    shape=RoundedCornerShape(9.dp),
                    modifier=Modifier.align(Alignment.TopStart).padding(9.dp)
                ) {
                    Text(
                        "S"+seasonNumber.toString().padStart(2,'0')+
                            "E"+episode.number.toString().padStart(2,'0'),
                        fontSize=11.sp,
                        modifier=Modifier.padding(horizontal=7.dp,vertical=4.dp)
                    )
                }

                if(watchState?.completed==true) {
                    Surface(
                        color=FqGreen.copy(alpha=.92f),
                        shape=CircleShape,
                        modifier=Modifier.align(Alignment.TopEnd).padding(9.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            null,
                            tint=Color.Black,
                            modifier=Modifier.padding(6.dp).size(17.dp)
                        )
                    }
                }

                if(watchState!=null && !watchState.completed && watchState.progress>0f) {
                    LinearProgressIndicator(
                        progress={watchState.progress},
                        color=FqGold,
                        trackColor=Color.White.copy(alpha=.22f),
                        modifier=Modifier.fillMaxWidth()
                            .height(5.dp)
                            .align(Alignment.BottomCenter)
                    )
                }

                if(episode.streamReady && !episode.mediaVersionId.isNullOrBlank()) {
                    Box(
                        Modifier.size(52.dp).align(Alignment.Center).clip(CircleShape)
                            .background(FqGold).clickable {
                                onPlay(
                                    PlaybackTarget(
                                        mediaVersionId=episode.mediaVersionId,
                                        title=episode.name.ifBlank { title+" • قسمت "+episode.number },
                                        mediaTitleId=mediaTitleId,
                                        subtitle="S"+seasonNumber.toString().padStart(2,'0')+
                                            "E"+episode.number.toString().padStart(2,'0')+
                                            if(episode.quality.isNullOrBlank())"" else " • "+episode.quality,
                                        introEndMs=episode.introEndMs,
                                        recapEndMs=episode.recapEndMs,
                                        creditsStartMs=episode.creditsStartMs,
                                        nextMediaVersionId=nextEpisode?.mediaVersionId,
                                        nextTitle=nextEpisode?.name?.ifBlank {
                                            title+" • قسمت "+nextEpisode.number
                                        },
                                        nextSubtitle=if(nextEpisode!=null && nextSeasonNumber!=null) {
                                            "S"+nextSeasonNumber.toString().padStart(2,'0')+
                                                "E"+nextEpisode.number.toString().padStart(2,'0')+
                                                if(nextEpisode.quality.isNullOrBlank())"" else " • "+nextEpisode.quality
                                        } else null
                                    )
                                )
                            },
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow,null,tint=Color.Black,modifier=Modifier.size(31.dp))
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        episode.name.ifBlank { "قسمت "+episode.number },
                        fontSize=12.sp,
                        fontWeight=FontWeight.Bold,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis
                    )
                    Text(
                        listOf(
                            episode.runtimeMinutes.takeIf { it>0 }?.let { it.toString()+" دقیقه" }.orEmpty(),
                            episode.quality.orEmpty()
                        ).filter(String::isNotBlank).joinToString(" • "),
                        color=FqMuted,
                        fontSize=11.sp,
                        modifier=Modifier.padding(top=3.dp)
                    )
                    if(episode.overview.isNotBlank()) {
                        Text(
                            episode.overview,
                            color=Color.White.copy(alpha=.7f),
                            fontSize=11.sp,
                            lineHeight=14.sp,
                            maxLines=2,
                            overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=5.dp)
                        )
                    }
                }

                IconButton(
                    onClick=onToggleWatched,
                    enabled=!watchBusy
                ) {
                    if(watchBusy) {
                        CircularProgressIndicator(
                            color=FqGold,
                            strokeWidth=2.dp,
                            modifier=Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            if(watchState?.completed==true)Icons.Default.CheckCircle
                            else Icons.Default.RadioButtonUnchecked,
                            null,
                            tint=if(watchState?.completed==true)FqGreen else FqMuted
                        )
                    }
                }

                if(episode.streamReady && !episode.mediaVersionId.isNullOrBlank()) {
                    IconButton(onClick={onDownload(episode)}) {
                        Icon(Icons.Default.Download,null,tint=FqGold)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewSeasonCard(
    season: SeasonInfo,
    repository: TmdbRepository
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)
            .clip(RoundedCornerShape(19.dp)).background(FqSurface).padding(10.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        RemoteImage(
            repository.poster(season.posterPath),
            Modifier.width(78.dp).height(110.dp).clip(RoundedCornerShape(13.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(season.name,fontSize=14.sp,fontWeight=FontWeight.Bold)
            Text(
                season.episodes.toString()+" قسمت",
                color=FqGold,
                fontSize=11.sp,
                modifier=Modifier.padding(top=4.dp)
            )
            if(season.airDate.isNotBlank()) {
                Text(season.airDate,color=FqMuted,fontSize=11.sp,modifier=Modifier.padding(top=3.dp))
            }
        }
        Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
    }
}

@Composable
private fun PremiumCommunityPanel(
    media: MediaItem,
    backend: BackendRepository,
    loggedIn: Boolean,
    relatedClips: List<ReelFeedItem>,
    onRequireAuth: () -> Unit,
    onChat: (MediaItem) -> Unit,
    onWatchParty: (MediaItem) -> Unit,
    onClip: (String) -> Unit
) {
    val scope=rememberCoroutineScope()
    val reviews=remember { ReviewsRepository(backend) }
    val mediaId=media.backendId

    var refresh by remember(media.key) { mutableIntStateOf(0) }
    var bundle by remember(media.key) { mutableStateOf<MediaReviewsBundle?>(null) }
    var reviewError by remember { mutableStateOf<String?>(null) }
    var showReview by remember { mutableStateOf(false) }

    LaunchedEffect(mediaId,refresh) {
        if(mediaId.isNullOrBlank()) return@LaunchedEffect
        runCatching { reviews.load(mediaId) }
            .onSuccess { bundle=it;reviewError=null }
            .onFailure { reviewError=it.message }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Surface(
            color=FqSurface,
            shape=RoundedCornerShape(24.dp),
            modifier=Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(17.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Box(
                        Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                            .background(FqGold.copy(alpha=.14f)),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(Icons.Default.Forum,null,tint=FqGold)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("کلاب "+media.title,fontSize=15.sp,fontWeight=FontWeight.Bold)
                        Text(
                            "امتیاز کاربران، Review، بحث و تماشای گروهی",
                            color=FqMuted,
                            fontSize=11.sp,
                            modifier=Modifier.padding(top=3.dp)
                        )
                    }
                }

                Text(
                    "بحث این عنوان به Catalog متصل است؛ Reviewها و پیام‌های اسپویلر با Spoiler Shield محافظت می‌شن.",
                    color=Color.White.copy(alpha=.75f),
                    fontSize=11.sp,
                    lineHeight=16.sp,
                    modifier=Modifier.padding(top=13.dp)
                )

                Row(Modifier.padding(top=14.dp)) {
                    Button(
                        onClick={onChat(media)},
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                        modifier=Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Forum,null)
                        Spacer(Modifier.width(5.dp))
                        Text("ورود به بحث")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick={onWatchParty(media)},
                        modifier=Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Groups,null)
                        Spacer(Modifier.width(5.dp))
                        Text("تماشای گروهی")
                    }
                }
            }
        }

        if(relatedClips.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top=18.dp,bottom=8.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Text(
                    "Clipهای این عنوان",
                    fontSize=15.sp,
                    fontWeight=FontWeight.Black
                )
                Spacer(Modifier.weight(1f))
                Text(
                    relatedClips.size.toString()+" Clip",
                    color=FqMuted,
                    fontSize=10.sp
                )
            }
            LazyRow(
                horizontalArrangement=Arrangement.spacedBy(10.dp),
                contentPadding=PaddingValues(end=4.dp)
            ) {
                items(relatedClips,key={it.id}) { clip ->
                    RelatedTitleClipCard(
                        clip=clip,
                        onClick={onClip(clip.id)}
                    )
                }
            }
        }

        if(mediaId.isNullOrBlank()) {
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(18.dp),
                modifier=Modifier.fillMaxWidth().padding(top=12.dp)
            ) {
                Text(
                    "امتیاز Club وقتی این عنوان وارد Catalog واقعی Filmiqoo بشه فعال می‌شه.",
                    color=FqMuted,
                    fontSize=11.sp,
                    modifier=Modifier.padding(14.dp)
                )
            }
            return@Column
        }

        val ratings=bundle
        Surface(
            color=FqSurface,
            shape=RoundedCornerShape(22.dp),
            modifier=Modifier.fillMaxWidth().padding(top=12.dp)
        ) {
            Column(Modifier.padding(15.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Column {
                        Text(
                            if(ratings==null || ratings.count==0L)"—"
                            else String.format(Locale.US,"%.1f",ratings.average),
                            fontSize=34.sp,
                            fontWeight=FontWeight.Black,
                            color=FqGold
                        )
                        Text("از 10",color=FqMuted,fontSize=11.sp)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if(ratings==null)"در حال دریافت امتیازها..."
                            else ratings.count.toString()+" امتیاز Club",
                            fontSize=12.sp,
                            fontWeight=FontWeight.Bold
                        )
                        if(ratings!=null && ratings.count>0) {
                            LinearProgressIndicator(
                                progress={(ratings.average/10.0).toFloat().coerceIn(0f,1f)},
                                color=FqGold,
                                trackColor=FqSurface3,
                                modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick={
                            if(!loggedIn) onRequireAuth() else showReview=true
                        },
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                        shape=RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.StarRate,null,tint=Color.Black,modifier=Modifier.size(17.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("امتیاز",color=Color.Black,fontSize=11.sp)
                    }
                }

                if(ratings!=null && ratings.count>0) {
                    Column(Modifier.padding(top=12.dp)) {
                        (10 downTo 6).forEach { rating ->
                            val count=ratings.distribution[rating] ?: 0L
                            val fraction=(count.toFloat()/ratings.count.toFloat()).coerceIn(0f,1f)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical=2.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Text(rating.toString(),color=FqMuted,fontSize=11.sp,modifier=Modifier.width(18.dp))
                                LinearProgressIndicator(
                                    progress={fraction},
                                    color=FqGold,
                                    trackColor=FqSurface3,
                                    modifier=Modifier.weight(1f).height(4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        reviewError?.let {
            Text(it,color=FqDanger,fontSize=11.sp,modifier=Modifier.padding(top=8.dp))
        }

        if(ratings!=null) {
            Row(
                Modifier.fillMaxWidth().padding(top=16.dp,bottom=7.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Text("Reviewهای کاربران",fontSize=14.sp,fontWeight=FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(ratings.items.size.toString(),color=FqMuted,fontSize=11.sp)
            }

            if(ratings.items.isEmpty()) {
                Surface(
                    color=FqSurface,
                    shape=RoundedCornerShape(18.dp),
                    modifier=Modifier.fillMaxWidth()
                ) {
                    Text(
                        "اولین Review این عنوان رو ثبت کن.",
                        color=FqMuted,
                        fontSize=11.sp,
                        modifier=Modifier.padding(14.dp)
                    )
                }
            } else {
                ratings.items.take(8).forEach { review ->
                    MediaReviewCard(
                        review=review,
                        onLike={
                            if(!loggedIn) {
                                onRequireAuth()
                            } else {
                                scope.launch {
                                    runCatching { reviews.toggleLike(review.id) }
                                        .onSuccess { refresh++ }
                                        .onFailure { reviewError=it.message }
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    if(showReview) {
        ReviewComposerDialog(
            mediaTitle=media.title,
            onDismiss={showReview=false},
            onPublish={rating,body,spoiler->
                scope.launch {
                    val targetId=mediaId ?: return@launch
                    runCatching {
                        reviews.save(targetId,rating,body,spoiler)
                    }.onSuccess {
                        showReview=false
                        refresh++
                    }.onFailure {
                        reviewError=it.message
                    }
                }
            }
        )
    }
}

@Composable
private fun RelatedTitleClipCard(
    clip:ReelFeedItem,
    onClick:()->Unit
) {
    Box(
        Modifier.width(142.dp)
            .height(220.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(FqSurface2)
            .clickable { onClick() }
    ) {
        RemoteImage(
            clip.coverUrl.takeIf(String::isNotBlank)
                ?: clip.media?.backdropUrl
                ?: clip.media?.posterUrl,
            Modifier.fillMaxSize(),
            ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha=.10f),
                        Color.Black.copy(alpha=.90f)
                    )
                )
            )
        )
        Surface(
            color=Color.Black.copy(alpha=.52f),
            shape=CircleShape,
            modifier=Modifier.align(Alignment.Center)
        ) {
            Icon(
                Icons.Default.PlayArrow,
                null,
                modifier=Modifier.padding(8.dp).size(20.dp)
            )
        }
        Column(
            Modifier.align(Alignment.BottomStart).padding(10.dp)
        ) {
            Text(
                clip.caption.ifBlank { clip.author.displayName },
                fontSize=10.sp,
                fontWeight=FontWeight.Bold,
                maxLines=2,
                overflow=TextOverflow.Ellipsis
            )
            Text(
                compactPulseCount(clip.views)+" بازدید",
                color=Color.White.copy(alpha=.66f),
                fontSize=8.sp,
                modifier=Modifier.padding(top=4.dp)
            )
        }
    }
}

@Composable
private fun MediaReviewCard(
    review:MediaReview,
    onLike:()->Unit
) {
    var revealed by remember(review.id) { mutableStateOf(!review.spoiler) }

    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth().padding(top=7.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                RemoteImage(
                    review.author.avatarUrl.takeIf(String::isNotBlank),
                    Modifier.size(38.dp).clip(CircleShape),
                    ContentScale.Crop
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(review.author.displayName,fontSize=11.sp,fontWeight=FontWeight.Bold)
                        if(review.author.verified) {
                            Spacer(Modifier.width(3.dp))
                            Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(12.dp))
                        }
                    }
                    Text("@"+review.author.username,color=FqMuted,fontSize=11.sp)
                }
                Surface(
                    color=FqGold.copy(alpha=.13f),
                    shape=RoundedCornerShape(9.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal=8.dp,vertical=5.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Star,null,tint=FqGold,modifier=Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(review.rating.toString()+"/10",color=FqGold,fontSize=11.sp,fontWeight=FontWeight.Bold)
                    }
                }
            }

            if(review.spoiler && !revealed) {
                Surface(
                    color=FqDanger.copy(alpha=.1f),
                    shape=RoundedCornerShape(12.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=9.dp).clickable { revealed=true }
                ) {
                    Row(Modifier.padding(11.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.VisibilityOff,null,tint=FqDanger,modifier=Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Spoiler Shield • برای نمایش Review لمس کن",color=FqDanger,fontSize=11.sp)
                    }
                }
            } else if(review.body.isNotBlank()) {
                Text(
                    review.body,
                    fontSize=11.sp,
                    lineHeight=16.sp,
                    modifier=Modifier.padding(top=9.dp)
                )
            }

            TextButton(
                onClick=onLike,
                contentPadding=PaddingValues(horizontal=3.dp,vertical=2.dp),
                modifier=Modifier.padding(top=4.dp)
            ) {
                Icon(Icons.Default.ThumbUpOffAlt,null,modifier=Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(review.likes.toString(),fontSize=11.sp)
            }
        }
    }
}

@Composable
private fun ReviewComposerDialog(
    mediaTitle:String,
    onDismiss:()->Unit,
    onPublish:(Int,String,Boolean)->Unit
) {
    var rating by remember { mutableIntStateOf(8) }
    var body by remember { mutableStateOf("") }
    var spoiler by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("امتیاز به "+mediaTitle)},
        text={
            Column {
                Text("امتیاز از ۱ تا ۱۰",color=FqMuted,fontSize=11.sp)
                LazyRow(
                    horizontalArrangement=Arrangement.spacedBy(5.dp),
                    modifier=Modifier.padding(top=7.dp)
                ) {
                    items((1..10).toList()) { value ->
                        FilterChip(
                            selected=rating==value,
                            onClick={rating=value},
                            label={Text(value.toString())}
                        )
                    }
                }

                OutlinedTextField(
                    value=body,
                    onValueChange={body=it.take(5000)},
                    label={Text("Review (اختیاری)")},
                    minLines=4,
                    maxLines=8,
                    shape=RoundedCornerShape(14.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                )

                FilterChip(
                    selected=spoiler,
                    onClick={spoiler=!spoiler},
                    label={Text("این Review اسپویل دارد")},
                    leadingIcon={Icon(Icons.Default.VisibilityOff,null)},
                    modifier=Modifier.padding(top=8.dp)
                )
            }
        },
        confirmButton={
            Button(
                onClick={onPublish(rating,body.trim(),spoiler)},
                colors=ButtonDefaults.buttonColors(containerColor=FqGold)
            ) {
                Text("ثبت",color=Color.Black)
            }
        },
        dismissButton={TextButton(onClick=onDismiss){Text("لغو")}}
    )
}
@Composable
private fun TechnicalInfoSection(
    media: MediaItem,
    detail: MediaDetail,
    versions: List<PlatformVersion>
) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        PremiumSectionHeader(
            title="اطلاعات فنی",
            subtitle="نسخه‌ها و مشخصات قابل پخش",
            icon=Icons.Default.Tune
        )

        InfoRow("نوع",if(media.type==MediaType.MOVIE)"فیلم" else "سریال")
        InfoRow("سال",media.year.ifBlank{"—"})
        if(detail.runtime>0) InfoRow("مدت",detail.runtime.toString()+" دقیقه")
        InfoRow("وضعیت",detail.status.ifBlank{"—"})

        if(versions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("نسخه‌ها",fontSize=13.sp,fontWeight=FontWeight.Bold)
            versions.forEach { version ->
                Surface(
                    color=FqSurface,
                    shape=RoundedCornerShape(16.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=7.dp)
                ) {
                    Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                                .background(FqGold.copy(alpha=.12f)),
                            contentAlignment=Alignment.Center
                        ) {
                            Icon(Icons.Default.HighQuality,null,tint=FqGold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(version.quality.ifBlank{"Auto"},fontSize=11.sp,fontWeight=FontWeight.Bold)
                            Text(
                                listOf(version.codec,version.hdr)
                                    .filter(String::isNotBlank).joinToString(" • "),
                                color=FqMuted,
                                fontSize=11.sp,
                                modifier=Modifier.padding(top=2.dp)
                            )
                        }
                        if(version.fileSizeBytes>0) {
                            Text(formatBytes(version.fileSizeBytes),color=FqMuted,fontSize=11.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String,value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical=7.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Text(label,color=FqMuted,fontSize=11.sp,modifier=Modifier.weight(1f))
        Text(value,fontSize=12.sp,fontWeight=FontWeight.Bold)
    }
    HorizontalDivider(color=FqSurface3)
}

private fun isCastTab(type: MediaType,tab: Int): Boolean =
    (type==MediaType.TV && tab==2) || (type==MediaType.MOVIE && tab==1)

private fun isCommunityTab(type: MediaType,tab: Int): Boolean =
    (type==MediaType.TV && tab==3) || (type==MediaType.MOVIE && tab==2)

private fun formatBytes(bytes: Long): String {
    if(bytes<=0) return "—"
    val gb=bytes/1024.0/1024.0/1024.0
    return if(gb>=1) {
        String.format(Locale.US,"%.1f GB",gb)
    } else {
        String.format(Locale.US,"%.0f MB",bytes/1024.0/1024.0)
    }
}


@Composable
private fun FilmiqooPulseCard(
    state:PulseState?,
    busy:Boolean,
    loggedIn:Boolean,
    onRequireAuth:()->Unit,
    onReact:(String)->Unit
) {
    val reactions=listOf("🔥","😱","😂","❤️","👀")
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(22.dp),
        border=androidx.compose.foundation.BorderStroke(
            1.dp,
            if(state?.live==true) FqGold.copy(alpha=.45f) else FqBorder
        ),
        modifier=Modifier.fillMaxWidth()
            .padding(horizontal=FqDimens.Screen,vertical=10.dp)
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(
                verticalAlignment=Alignment.CenterVertically,
                modifier=Modifier.fillMaxWidth()
            ) {
                Box(
                    Modifier.size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if(state?.live==true) Color(0xFFFF4D67) else FqMuted
                        )
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Pulse",
                        fontWeight=FontWeight.Black,
                        fontSize=15.sp
                    )
                    Text(
                        when {
                            state==null -> "واکنش زنده بین فیلم‌بازها"
                            state.watchingNow>0 ->
                                state.watchingNow.toString()+" نفر الان دارن می‌بینن"
                            state.recent>0 ->
                                state.recent.toString()+" واکنش در چند ساعت اخیر"
                            else -> "اولین واکنش این عنوان رو تو بفرست"
                        },
                        color=FqMuted,
                        fontSize=11.sp,
                        modifier=Modifier.padding(top=2.dp)
                    )
                }
                if(state?.live==true) {
                    Surface(
                        color=Color(0xFFFF4D67).copy(alpha=.14f),
                        shape=CircleShape
                    ) {
                        Text(
                            "LIVE",
                            color=Color(0xFFFF6A7D),
                            fontSize=10.sp,
                            fontWeight=FontWeight.Bold,
                            modifier=Modifier.padding(horizontal=9.dp,vertical=4.dp)
                        )
                    }
                }
            }

            LazyRow(
                horizontalArrangement=Arrangement.spacedBy(8.dp),
                modifier=Modifier.padding(top=13.dp)
            ) {
                items(reactions) { emoji ->
                    val count=state?.reactions?.get(emoji) ?: 0L
                    Surface(
                        color=FqSurface2,
                        shape=RoundedCornerShape(16.dp),
                        border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                        modifier=Modifier.clickable(enabled=!busy) {
                            if(loggedIn) onReact(emoji) else onRequireAuth()
                        }
                    ) {
                        Row(
                            Modifier.padding(horizontal=12.dp,vertical=9.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Text(emoji,fontSize=18.sp)
                            if(count>0) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    compactPulseCount(count),
                                    color=FqMuted,
                                    fontSize=11.sp,
                                    fontWeight=FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            state?.moments?.takeIf { it.isNotEmpty() }?.let { moments ->
                Surface(
                    color=FqGold.copy(alpha=.08f),
                    shape=RoundedCornerShape(14.dp),
                    border=androidx.compose.foundation.BorderStroke(
                        1.dp,
                        FqGold.copy(alpha=.14f)
                    ),
                    modifier=Modifier.fillMaxWidth().padding(top=12.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal=11.dp,vertical=9.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Text(
                            moments.take(3).joinToString("") { it.emoji },
                            fontSize=13.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                moments.size.toString()+" لحظه داغ ثبت شده",
                                color=Color.White.copy(alpha=.88f),
                                fontSize=9.sp,
                                fontWeight=FontWeight.Bold
                            )
                            Text(
                                "زمان دقیق بعد از رسیدن به هر لحظه داخل Player آشکار می‌شه",
                                color=FqMuted,
                                fontSize=8.sp,
                                modifier=Modifier.padding(top=2.dp)
                            )
                        }
                        Icon(
                            Icons.Default.VisibilityOff,
                            null,
                            tint=FqGold.copy(alpha=.72f),
                            modifier=Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun compactPulseCount(value:Long):String = when {
    value>=1_000_000 -> String.format(Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
