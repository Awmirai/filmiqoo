package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import kotlinx.coroutines.launch
import java.util.Locale

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
    onPlay: (PlaybackTarget) -> Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val library=remember { LibraryRepository(backend) }

    var reload by remember(media.key) { mutableIntStateOf(0) }
    var state by remember(media.key) { mutableStateOf<PremiumDetailLoad>(PremiumDetailLoad.Loading) }
    var favorite by remember(media.key) { mutableStateOf(store.contains("favorites",media.key)) }
    var favoriteBusy by remember { mutableStateOf(false) }
    var watchlist by remember(media.key) { mutableStateOf(false) }
    var watchlistBusy by remember { mutableStateOf(false) }
    var showCollections by remember { mutableStateOf(false) }
    var downloadBusy by remember { mutableStateOf(false) }
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
            }
            PremiumDetailLoad.Ready(tmdb,platform)
        }.getOrElse { PremiumDetailLoad.Error(it.message ?: "خطا در دریافت اطلاعات") }
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
                    listOf("معرفی","قسمت‌ها","بازیگران","Community","اطلاعات")
                } else {
                    listOf("معرفی","بازیگران","Community","اطلاعات")
                }
            }
            var tab by remember(d.media.key) { mutableIntStateOf(0) }

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
                                shareText(
                                    context,
                                    d.media.title + if(d.media.year.isBlank()) "" else " ("+d.media.year+")"
                                )
                            }
                        )
                    }

                    item {
                        PremiumDetailActions(
                            favorite=favorite,
                            favoriteBusy=favoriteBusy,
                            watchlist=watchlist,
                            watchlistBusy=watchlistBusy,
                            downloadBusy=downloadBusy,
                            canDownload=canPlay,
                            onFavorite={
                                if(favoriteBusy) return@PremiumDetailActions
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
                                        message=it.message
                                    }
                                    favoriteBusy=false
                                }
                            },
                            onWatchlist={
                                val id=d.media.backendId
                                if(id.isNullOrBlank() || !backend.session.isLoggedIn) {
                                    message="برای Watchlist باید وارد حساب Filmiqoo شوی."
                                    return@PremiumDetailActions
                                }
                                if(watchlistBusy) return@PremiumDetailActions
                                watchlistBusy=true
                                scope.launch {
                                    runCatching { library.toggleWatchlist(id) }
                                        .onSuccess { watchlist=it }
                                        .onFailure { message=it.message }
                                    watchlistBusy=false
                                }
                            },
                            onCollections={
                                if(!backend.session.isLoggedIn) {
                                    message="برای Collectionها باید وارد حساب Filmiqoo شوی."
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
                                    message="برای دانلود باید وارد حساب Filmiqoo شوی."
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
                        ScrollableTabRow(
                            selectedTabIndex=tab,
                            containerColor=FqBg,
                            contentColor=FqGold,
                            edgePadding=12.dp,
                            divider={}
                        ) {
                            tabs.forEachIndexed { index,label ->
                                Tab(
                                    selected=tab==index,
                                    onClick={tab=index},
                                    text={Text(label,fontSize=10.sp)}
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
                            if(d.cast.isNotEmpty()) {
                                item {
                                    PremiumSectionHeader(
                                        title="بازیگران",
                                        subtitle="چهره‌های اصلی",
                                        icon=Icons.Default.Groups
                                    )
                                }
                                item { PremiumCastRow(d.cast,repository) }
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
                                        onPlay=onPlay,
                                        onDownload={ ep ->
                                            val id=ep.mediaVersionId ?: return@PremiumSeriesPanel
                                            if(!backend.session.isLoggedIn) {
                                                message="برای دانلود باید وارد حساب Filmiqoo شوی."
                                            } else {
                                                scope.launch {
                                                    runCatching {
                                                        backend.enqueueDownload(
                                                            context,
                                                            PlaybackTarget(
                                                                mediaVersionId=id,
                                                                title=ep.name.ifBlank { d.media.title+" • قسمت "+ep.number },
                                                                subtitle=ep.quality.orEmpty()
                                                            )
                                                        )
                                                    }.onSuccess {
                                                        message="دانلود قسمت به صف اضافه شد."
                                                    }.onFailure {
                                                        message=it.message
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
                            items(d.cast,key={it.id}) { actor ->
                                PremiumCastListItem(actor,repository)
                            }
                        }

                        isCommunityTab(d.media.type,tab) -> {
                            item {
                                PremiumCommunityPanel(
                                    media=d.media,
                                    onChat=onChat,
                                    onWatchParty=onWatchParty
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
    Box(Modifier.fillMaxWidth().height(470.dp)) {
        RemoteImage(
            repository.backdrop(detail.media.backdropPath ?: detail.media.posterPath),
            Modifier.fillMaxSize(),
            ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha=.25f),
                        Color.Transparent,
                        Color.Black.copy(alpha=.5f),
                        FqBg
                    )
                )
            )
        )

        Row(
            Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            FilledTonalIconButton(
                onClick=onBack,
                colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=Color.Black.copy(alpha=.52f))
            ) { Icon(Icons.Default.ArrowBack,null) }
            Spacer(Modifier.weight(1f))
            FilledTonalIconButton(
                onClick=onShare,
                colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=Color.Black.copy(alpha=.52f))
            ) { Icon(Icons.Default.Share,null) }
        }

        Column(
            Modifier.align(Alignment.BottomStart).padding(start=18.dp,end=18.dp,bottom=18.dp)
        ) {
            Row(verticalAlignment=Alignment.Bottom) {
                RemoteImage(
                    repository.poster(detail.media.posterPath),
                    Modifier.width(106.dp).height(154.dp).clip(RoundedCornerShape(17.dp)),
                    ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
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
                        fontSize=28.sp,
                        fontWeight=FontWeight.Black,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=9.dp)
                    )

                    if(detail.media.originalTitle.isNotBlank() &&
                        detail.media.originalTitle!=detail.media.title) {
                        Text(
                            detail.media.originalTitle,
                            color=Color.White.copy(alpha=.6f),
                            fontSize=10.sp,
                            maxLines=1,
                            overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=2.dp)
                        )
                    }

                    val meta=listOf(
                        detail.media.year,
                        detail.runtime.takeIf { it>0 }?.let { it.toString()+" دقیقه" }.orEmpty(),
                        if(detail.media.type==MediaType.MOVIE)"فیلم" else "سریال",
                        detail.status
                    ).filter(String::isNotBlank).joinToString(" • ")
                    Text(
                        meta,
                        color=FqMuted,
                        fontSize=9.sp,
                        modifier=Modifier.padding(top=6.dp)
                    )
                }
            }

            if(detail.genres.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement=Arrangement.spacedBy(6.dp),
                    modifier=Modifier.padding(top=12.dp)
                ) {
                    items(detail.genres.take(5)) { genre ->
                        Surface(
                            color=Color.White.copy(alpha=.08f),
                            shape=RoundedCornerShape(999.dp)
                        ) {
                            Text(
                                genre,
                                fontSize=8.sp,
                                modifier=Modifier.padding(horizontal=10.dp,vertical=5.dp)
                            )
                        }
                    }
                }
            }

            Row(Modifier.padding(top=14.dp),verticalAlignment=Alignment.CenterVertically) {
                Button(
                    onClick=onPlay,
                    enabled=canPlay,
                    colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                    shape=RoundedCornerShape(14.dp),
                    modifier=Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow,null,tint=Color.Black)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if(canPlay)"تماشا" else "نسخه پخش موجود نیست",
                        color=Color.Black,
                        fontWeight=FontWeight.Bold
                    )
                }
                if(detail.trailerKey!=null) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick=onTrailer,
                        shape=RoundedCornerShape(14.dp)
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
            Text(text,fontSize=8.sp)
        }
    }
}

@Composable
private fun PremiumDetailActions(
    favorite: Boolean,
    favoriteBusy: Boolean,
    watchlist: Boolean,
    watchlistBusy: Boolean,
    downloadBusy: Boolean,
    canDownload: Boolean,
    onFavorite: () -> Unit,
    onWatchlist: () -> Unit,
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
                label=if(favorite)"موردعلاقه" else "Favorite",
                active=favorite,
                loading=favoriteBusy,
                onClick=onFavorite
            )
        }
        item {
            ActionTile(
                icon=if(watchlist)Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                label=if(watchlist)"در Watchlist" else "Watchlist",
                active=watchlist,
                loading=watchlistBusy,
                onClick=onWatchlist
            )
        }
        item {
            ActionTile(
                icon=Icons.Default.CollectionsBookmark,
                label="Collection",
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
                label="Watch Party",
                onClick=onWatchParty
            )
        }
        item {
            ActionTile(
                icon=Icons.Default.Forum,
                label="Community",
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
    Surface(
        color=if(active)FqGold.copy(alpha=.13f) else FqSurface,
        shape=RoundedCornerShape(16.dp),
        modifier=Modifier.width(112.dp).clickable(enabled=enabled && !loading) { onClick() }
    ) {
        Column(
            Modifier.padding(horizontal=10.dp,vertical=11.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            if(loading) {
                CircularProgressIndicator(
                    color=FqGold,
                    strokeWidth=2.dp,
                    modifier=Modifier.size(21.dp)
                )
            } else {
                Icon(
                    icon,
                    null,
                    tint=if(active)FqGold else if(enabled)Color.White else FqMuted,
                    modifier=Modifier.size(22.dp)
                )
            }
            Text(
                label,
                color=if(enabled)Color.White else FqMuted,
                fontSize=8.sp,
                maxLines=1,
                modifier=Modifier.padding(top=5.dp)
            )
        }
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
                            fontSize=7.sp,
                            color=if(selected)Color.Black.copy(alpha=.7f) else FqMuted,
                            modifier=Modifier.padding(top=2.dp)
                        )
                    }
                    if(version.fileSizeBytes>0) {
                        Text(
                            formatBytes(version.fileSizeBytes),
                            fontSize=7.sp,
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
    repository: TmdbRepository
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(13.dp)
    ) {
        items(cast.take(18),key={it.id}) { actor ->
            Column(
                Modifier.width(92.dp),
                horizontalAlignment=Alignment.CenterHorizontally
            ) {
                RemoteImage(
                    repository.profile(actor.profilePath),
                    Modifier.size(84.dp).clip(CircleShape)
                )
                Text(
                    actor.name,
                    fontSize=9.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=6.dp)
                )
                Text(
                    actor.character,
                    color=FqMuted,
                    fontSize=7.sp,
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
    repository: TmdbRepository
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp)
            .clip(RoundedCornerShape(17.dp)).background(FqSurface).padding(10.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        RemoteImage(
            repository.profile(actor.profilePath),
            Modifier.size(62.dp).clip(CircleShape)
        )
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(actor.name,fontSize=12.sp,fontWeight=FontWeight.Bold)
            Text(actor.character,color=FqMuted,fontSize=9.sp,modifier=Modifier.padding(top=3.dp))
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
                    fontSize=9.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=6.dp)
                )
                Text(
                    listOf(media.year,if(media.vote>0)"★ "+formatVote(media.vote) else "")
                        .filter(String::isNotBlank).joinToString(" • "),
                    color=FqMuted,
                    fontSize=7.sp
                )
            }
        }
    }
}

@Composable
private fun PremiumSeriesPanel(
    title: String,
    seasons: List<PlatformSeason>,
    onPlay: (PlaybackTarget) -> Unit,
    onDownload: (PlatformEpisode) -> Unit
) {
    var selectedSeason by remember(seasons) {
        mutableIntStateOf(seasons.firstOrNull()?.number ?: 0)
    }
    val season=seasons.firstOrNull { it.number==selectedSeason } ?: seasons.firstOrNull()

    Column(Modifier.fillMaxWidth()) {
        PremiumSectionHeader(
            title="فصل‌ها و قسمت‌ها",
            subtitle=seasons.size.toString()+" فصل",
            icon=Icons.Default.VideoLibrary
        )

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

        val allEpisodes=seasons
            .sortedBy { it.number }
            .flatMap { currentSeason ->
                currentSeason.episodes.sortedBy { it.number }.map { ep ->
                    currentSeason.number to ep
                }
            }

        season?.episodes?.sortedBy { it.number }?.forEach { ep ->
            val currentIndex=allEpisodes.indexOfFirst { it.second.id==ep.id }
            val next=if(currentIndex>=0) {
                allEpisodes.drop(currentIndex+1).firstOrNull {
                    it.second.streamReady && !it.second.mediaVersionId.isNullOrBlank()
                }
            } else null

            EpisodeCard(
                title=title,
                seasonNumber=season.number,
                episode=ep,
                nextSeasonNumber=next?.first,
                nextEpisode=next?.second,
                onPlay=onPlay,
                onDownload=onDownload
            )
        }
    }
}

@Composable
private fun EpisodeCard(
    title: String,
    seasonNumber: Int,
    episode: PlatformEpisode,
    nextSeasonNumber: Int?,
    nextEpisode: PlatformEpisode?,
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
                        fontSize=8.sp,
                        modifier=Modifier.padding(horizontal=7.dp,vertical=4.dp)
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
                                        subtitle="S"+seasonNumber.toString().padStart(2,'0')+
                                            "E"+episode.number.toString().padStart(2,'0')+
                                            if(episode.quality.isNullOrBlank())"" else " • "+episode.quality,
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
                        fontSize=8.sp,
                        modifier=Modifier.padding(top=3.dp)
                    )
                    if(episode.overview.isNotBlank()) {
                        Text(
                            episode.overview,
                            color=Color.White.copy(alpha=.7f),
                            fontSize=8.sp,
                            lineHeight=14.sp,
                            maxLines=2,
                            overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=5.dp)
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
                fontSize=9.sp,
                modifier=Modifier.padding(top=4.dp)
            )
            if(season.airDate.isNotBlank()) {
                Text(season.airDate,color=FqMuted,fontSize=8.sp,modifier=Modifier.padding(top=3.dp))
            }
        }
        Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
    }
}

@Composable
private fun PremiumCommunityPanel(
    media: MediaItem,
    onChat: (MediaItem) -> Unit,
    onWatchParty: (MediaItem) -> Unit
) {
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
                        Text("Community "+media.title,fontSize=15.sp,fontWeight=FontWeight.Bold)
                        Text(
                            "بحث بدون اسپویل، نقد و Watch Party",
                            color=FqMuted,
                            fontSize=9.sp,
                            modifier=Modifier.padding(top=3.dp)
                        )
                    }
                }

                Text(
                    "روم این عنوان به Catalog متصل است و برای سریال‌ها می‌تونه به سطح هر قسمت جدا بشه؛ Spoiler Shield هم برای پیام‌ها و نقدها فعال می‌شه.",
                    color=Color.White.copy(alpha=.75f),
                    fontSize=9.sp,
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
                        Text("Watch Party")
                    }
                }
            }
        }
    }
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
                                fontSize=8.sp,
                                modifier=Modifier.padding(top=2.dp)
                            )
                        }
                        if(version.fileSizeBytes>0) {
                            Text(formatBytes(version.fileSizeBytes),color=FqMuted,fontSize=8.sp)
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
        Text(label,color=FqMuted,fontSize=9.sp,modifier=Modifier.weight(1f))
        Text(value,fontSize=10.sp,fontWeight=FontWeight.Bold)
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
