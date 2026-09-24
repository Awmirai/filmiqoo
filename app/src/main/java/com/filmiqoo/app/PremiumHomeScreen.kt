package com.filmiqoo.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface PremiumHomeLoad {
    data object Loading : PremiumHomeLoad
    data class Ready(val data: HomeBundle) : PremiumHomeLoad
    data class Error(val message: String) : PremiumHomeLoad
}

@Composable
fun PremiumHomeScreen(
    repository: TmdbRepository,
    backend: BackendRepository,
    loggedIn: Boolean,
    onMedia: (MediaItem) -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
    onStory: (MediaItem, Int) -> Unit,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    onReleases: () -> Unit,
    onWatchParty: (MediaItem?) -> Unit
) {
    var reload by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<PremiumHomeLoad>(PremiumHomeLoad.Loading) }
    var continueItems by remember { mutableStateOf<List<ContinueWatchingItem>>(emptyList()) }
    var personalized by remember { mutableStateOf<PersonalizedHomeBundle?>(null) }
    var friendsWatching by remember { mutableStateOf<List<FriendWatchingNow>>(emptyList()) }
    val personalizationRepo=remember { HomePersonalizationRepository(backend) }
    val friendActivityRepo=remember { FriendActivityRepository(backend) }
    val activeViewer=if(loggedIn) backend.viewerProfiles.active() else null
    val kidsMode=activeViewer?.kidsMode==true

    LaunchedEffect(reload,loggedIn,activeViewer?.id,kidsMode) {
        if(loggedIn) {
            continueItems=runCatching { backend.continueWatching() }.getOrDefault(emptyList())
            personalized=runCatching { personalizationRepo.load() }.getOrNull()
            friendsWatching=if(!kidsMode) {
                runCatching { friendActivityRepo.followingWatching() }.getOrDefault(emptyList())
            } else emptyList()
        } else {
            continueItems=emptyList()
            personalized=null
            friendsWatching=emptyList()
        }
        state=PremiumHomeLoad.Loading
        state=if(kidsMode) {
            PremiumHomeLoad.Ready(HomeBundle())
        } else {
            runCatching { PremiumHomeLoad.Ready(repository.home()) }
                .getOrElse { PremiumHomeLoad.Error(it.message ?: "خطا در دریافت خانه") }
        }
    }

    when(val s=state) {
        PremiumHomeLoad.Loading -> LoadingPage("در حال چیدن صفحه شخصی تو...")
        is PremiumHomeLoad.Error -> ErrorPage(s.message) { reload++ }
        is PremiumHomeLoad.Ready -> PremiumHomeContent(
            data=s.data,
            continueItems=continueItems,
            personalized=personalized,
            friendsWatching=friendsWatching,
            repository=repository,
            loggedIn=loggedIn,
            kidsMode=kidsMode,
            onMedia=onMedia,
            onPlay=onPlay,
            onStory=onStory,
            onSearch=onSearch,
            onNotifications=onNotifications,
            onReleases=onReleases,
            onWatchParty=onWatchParty,
            onRefresh={reload++}
        )
    }
}

@Composable
private fun PremiumHomeContent(
    data: HomeBundle,
    continueItems: List<ContinueWatchingItem>,
    personalized: PersonalizedHomeBundle?,
    friendsWatching: List<FriendWatchingNow>,
    repository: TmdbRepository,
    loggedIn: Boolean,
    kidsMode: Boolean,
    onMedia: (MediaItem) -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
    onStory: (MediaItem, Int) -> Unit,
    onSearch: () -> Unit,
    onNotifications: () -> Unit,
    onReleases: () -> Unit,
    onWatchParty: (MediaItem?) -> Unit,
    onRefresh: () -> Unit
) {
    val safePersonalized=(
        personalized?.forYou.orEmpty() +
        personalized?.watchlist.orEmpty() +
        personalized?.newForYou.orEmpty()
    ).distinctBy { it.key }
    val hero=if(kidsMode) {
        safePersonalized.take(6)
    } else {
        data.trending.ifEmpty { data.popularMovies + data.popularTv }.distinctBy { it.key }.take(6)
    }
    val top10=if(kidsMode) {
        safePersonalized.take(10)
    } else {
        (data.trending + data.popularMovies + data.popularTv).distinctBy { it.key }.take(10)
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            PremiumTopBar(
                subtitle=when {
                    kidsMode -> "فضای امن Kids • فقط محتوای متناسب با پروفایل"
                    loggedIn -> "پیشنهادهای امروز بر اساس تماشای تو"
                    else -> "فیلم، سریال و Community در یک جا"
                },
                onSearch=onSearch,
                onNotifications=onNotifications
            )
        }

        if(!kidsMode && data.trending.isNotEmpty()) {
            item {
                LazyRow(
                    contentPadding=PaddingValues(horizontal=FqDimens.Screen),
                    horizontalArrangement=Arrangement.spacedBy(10.dp)
                ) {
                    items(data.trending.take(10),key={it.key}) { media ->
                        PremiumStoryBubble(media,repository) {
                            onStory(media,data.trending.indexOf(media))
                        }
                    }
                }
            }
        }

        if(hero.isNotEmpty()) {
            item {
                PremiumHeroPager(hero,repository,onMedia,onPlay,onWatchParty)
            }
        }

        item {
            LazyRow(
                contentPadding=PaddingValues(horizontal=FqDimens.Screen),
                horizontalArrangement=Arrangement.spacedBy(8.dp),
                modifier=Modifier.padding(top=14.dp)
            ) {
                if(kidsMode) {
                    item { PremiumChip(Icons.Default.ChildCare,"Kids",true){} }
                    item { PremiumChip(Icons.Default.Movie,"فیلم"){} }
                    item { PremiumChip(Icons.Default.Tv,"سریال"){} }
                    item { PremiumChip(Icons.Default.Animation,"انیمیشن"){} }
                } else {
                    item { PremiumChip(Icons.Default.LocalFireDepartment,"ترند",true){} }
                    item { PremiumChip(Icons.Default.Movie,"فیلم"){} }
                    item { PremiumChip(Icons.Default.Tv,"سریال"){} }
                    item { PremiumChip(Icons.Default.Animation,"انیمه"){} }
                    item { PremiumChip(Icons.Default.Language,"ایرانی"){} }
                    item { PremiumChip(Icons.Default.CalendarMonth,"انتشارها",false,onReleases) }
                }
            }
        }

        if(continueItems.isNotEmpty()) {
            item {
                PremiumSectionHeader(
                    title="ادامه تماشا",
                    subtitle="دقیقاً از همان‌جایی که رها کردی",
                    icon=Icons.Default.PlayCircle
                )
            }
            item {
                PremiumContinueRow(continueItems,repository,onPlay,onMedia)
            }
        } else if(loggedIn) {
            item {
                PremiumSectionHeader("ادامه تماشا","هنوز چیزی نیمه‌کاره نداری",Icons.Default.PlayCircle)
            }
            item {
                PremiumEmptyState(
                    icon=Icons.Default.PlayCircleOutline,
                    title="صف تماشات خالیه",
                    body="وقتی یک فیلم یا قسمت رو شروع کنی، اینجا با زمان دقیق ادامه نمایش داده می‌شه."
                )
            }
        }

        if(!kidsMode && friendsWatching.isNotEmpty()) {
            item {
                PremiumSectionHeader(
                    title="دوستان الان دارن می‌بینن",
                    subtitle="فعالیت زنده افرادی که Follow کردی",
                    icon=Icons.Default.Groups
                )
            }
            item {
                FriendsWatchingRow(
                    items=friendsWatching,
                    repository=repository,
                    onMedia=onMedia
                )
            }
        }

        personalized?.forYou?.takeIf { it.isNotEmpty() }?.let { items ->
            item {
                PremiumSectionHeader(
                    title="برای تو",
                    subtitle=personalizationSubtitle(personalized),
                    icon=Icons.Default.AutoAwesome
                )
            }
            item { PremiumPosterRow(items,repository,onMedia) }
        }

        personalized?.watchlist?.takeIf { it.isNotEmpty() }?.let { items ->
            item {
                PremiumSectionHeader(
                    title="از Watchlist تو",
                    subtitle="چیزهایی که برای بعد ذخیره کردی",
                    icon=Icons.Default.Bookmark
                )
            }
            item { PremiumWideRow(items,repository,onMedia) }
        }

        if(!kidsMode) personalized?.communityHot?.takeIf { it.isNotEmpty() }?.let { items ->
            item {
                PremiumSectionHeader(
                    title="داغ در Community",
                    subtitle="بر اساس Post، Reel، Like، Save و Share",
                    icon=Icons.Default.LocalFireDepartment
                )
            }
            item { PremiumPosterRow(items,repository,onMedia) }
        }

        personalized?.newForYou?.takeIf { it.isNotEmpty() }?.let { items ->
            item {
                PremiumSectionHeader(
                    title="تازه برای تو",
                    subtitle="جدیدترین عنوان‌های Catalog متناسب با سلیقه‌ات",
                    icon=Icons.Default.NewReleases
                )
            }
            item { PremiumWideRow(items,repository,onMedia) }
        }

        if(top10.isNotEmpty()) {
            item {
                PremiumSectionHeader(
                    title="Top 10 امروز",
                    subtitle="محبوب‌ترین‌ها در Filmiqoo",
                    icon=Icons.Default.EmojiEvents
                )
            }
            item { PremiumTop10Row(top10,repository,onMedia) }
        }

        if(!kidsMode && data.popularMovies.isNotEmpty()) {
            item { PremiumSectionHeader("فیلم‌های منتخب","برای امشب",Icons.Default.Movie) }
            item { PremiumPosterRow(data.popularMovies,repository,onMedia) }
        }

        if(!kidsMode && data.popularTv.isNotEmpty()) {
            item { PremiumSectionHeader("سریال‌های داغ","قسمت بعدی منتظرته",Icons.Default.LiveTv) }
            item { PremiumWideRow(data.popularTv,repository,onMedia) }
        }

        if(!kidsMode && data.iranian.isNotEmpty()) {
            item { PremiumSectionHeader("سینمای ایران","فیلم و سریال ایرانی",Icons.Default.Language) }
            item { PremiumPosterRow(data.iranian,repository,onMedia) }
        }

        if(!kidsMode && data.korean.isNotEmpty()) {
            item { PremiumSectionHeader("K-Drama","انتخاب‌های محبوب کره‌ای",Icons.Default.Favorite) }
            item { PremiumPosterRow(data.korean,repository,onMedia) }
        }

        if(!kidsMode && data.anime.isNotEmpty()) {
            item { PremiumSectionHeader("Anime","دنیای انیمه",Icons.Default.Animation) }
            item { PremiumWideRow(data.anime,repository,onMedia) }
        }

        if(!kidsMode) {
            item {
                Surface(
                    color=FqSurface,
                    shape=RoundedCornerShape(24.dp),
                    modifier=Modifier.fillMaxWidth().padding(16.dp)
                ) {
                    Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp))
                                .background(FqGold.copy(alpha=.13f)),
                            contentAlignment=Alignment.Center
                        ) {
                            Icon(Icons.Default.Groups,null,tint=FqGold,modifier=Modifier.size(28.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Watch Party",fontSize=15.sp,fontWeight=FontWeight.Bold)
                            Text("فیلم رو همزمان با بقیه ببین، چت کن و واکنش بده.",color=FqMuted,fontSize=9.sp,lineHeight=15.sp)
                        }
                        Button(
                            onClick={onWatchParty(hero.firstOrNull())},
                            colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                            contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp)
                        ) { Text("شروع",fontSize=9.sp) }
                    }
                }
            }
        }

        item {
            TextButton(
                onClick=onRefresh,
                modifier=Modifier.fillMaxWidth().padding(bottom=22.dp)
            ) {
                Icon(Icons.Default.Refresh,null,modifier=Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("به‌روزرسانی پیشنهادها",fontSize=9.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PremiumHeroPager(
    items: List<MediaItem>,
    repository: TmdbRepository,
    onMedia: (MediaItem) -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
    onWatchParty: (MediaItem?) -> Unit
) {
    val pager=rememberPagerState(pageCount={items.size})
    Column(Modifier.padding(top=14.dp)) {
        HorizontalPager(
            state=pager,
            contentPadding=PaddingValues(horizontal=FqDimens.Screen),
            pageSpacing=10.dp,
            modifier=Modifier.height(430.dp)
        ) { page ->
            val media=items[page]
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(30.dp))
                    .background(FqSurface)
            ) {
                RemoteImage(
                    repository.backdrop(media.backdropPath ?: media.posterPath),
                    Modifier.fillMaxSize(),
                    ContentScale.Crop
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha=.08f),
                                Color.Transparent,
                                Color.Black.copy(alpha=.35f),
                                Color.Black.copy(alpha=.94f)
                            )
                        )
                    )
                )

                Row(
                    Modifier.align(Alignment.TopStart).padding(14.dp),
                    horizontalArrangement=Arrangement.spacedBy(6.dp)
                ) {
                    if(media.vote>0) HeroBadge(Icons.Default.Star,formatVote(media.vote))
                    if(media.streamReady) HeroBadge(Icons.Default.HighQuality,media.quality.ifBlank{"Ready"})
                }

                Column(
                    Modifier.align(Alignment.BottomStart).padding(18.dp)
                ) {
                    Text(
                        media.title,
                        fontSize=29.sp,
                        fontWeight=FontWeight.Black,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis
                    )
                    val meta=listOf(
                        media.year,
                        if(media.type==MediaType.MOVIE)"فیلم" else "سریال",
                        media.originalTitle.takeIf { it.isNotBlank() && it!=media.title }.orEmpty()
                    ).filter(String::isNotBlank).joinToString(" • ")
                    Text(meta,color=Color.White.copy(alpha=.72f),fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))

                    if(media.overview.isNotBlank()) {
                        Text(
                            media.overview,
                            color=Color.White.copy(alpha=.82f),
                            fontSize=10.sp,
                            lineHeight=16.sp,
                            maxLines=3,
                            overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=9.dp)
                        )
                    }

                    Row(Modifier.padding(top=14.dp),verticalAlignment=Alignment.CenterVertically) {
                        Button(
                            onClick={
                                if(media.streamReady && !media.mediaVersionId.isNullOrBlank()) {
                                    onPlay(
                                        PlaybackTarget(
                                            mediaVersionId=media.mediaVersionId,
                                            title=media.title,
                                            subtitle=listOf(media.year,media.quality).filter(String::isNotBlank).joinToString(" • "),
                                            posterUrl=repository.poster(media.posterPath)
                                        )
                                    )
                                } else onMedia(media)
                            },
                            colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                            shape=RoundedCornerShape(14.dp),
                            modifier=Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.PlayArrow,null,tint=Color.Black)
                            Spacer(Modifier.width(5.dp))
                            Text(if(media.streamReady)"تماشا" else "جزئیات",color=Color.Black,fontWeight=FontWeight.Bold)
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalIconButton(
                            onClick={onMedia(media)},
                            colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=Color.White.copy(alpha=.14f))
                        ) { Icon(Icons.Default.Info,null) }
                        Spacer(Modifier.width(6.dp))
                        FilledTonalIconButton(
                            onClick={onWatchParty(media)},
                            colors=IconButtonDefaults.filledTonalIconButtonColors(containerColor=Color.White.copy(alpha=.14f))
                        ) { Icon(Icons.Default.Groups,null) }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top=9.dp),
            horizontalArrangement=Arrangement.Center
        ) {
            repeat(items.size) { i ->
                Box(
                    Modifier.padding(horizontal=3.dp)
                        .width(if(i==pager.currentPage)22.dp else 6.dp)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(if(i==pager.currentPage)FqGold else FqSurface3)
                )
            }
        }
    }
}

@Composable
private fun HeroBadge(icon: androidx.compose.ui.graphics.vector.ImageVector,text: String) {
    Surface(color=Color.Black.copy(alpha=.62f),shape=RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(horizontal=8.dp,vertical=5.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(icon,null,tint=FqGold,modifier=Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(text,fontSize=9.sp,fontWeight=FontWeight.Bold)
        }
    }
}

@Composable
private fun PremiumStoryBubble(
    media: MediaItem,
    repository: TmdbRepository,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment=Alignment.CenterHorizontally,
        modifier=Modifier.width(78.dp).clickable { onClick() }
    ) {
        Box(
            Modifier.size(68.dp)
                .background(
                    Brush.sweepGradient(listOf(FqGold,FqDanger,FqBlue,FqGold)),
                    CircleShape
                ).padding(2.dp)
        ) {
            Box(Modifier.fillMaxSize().background(FqBg,CircleShape).padding(2.dp)) {
                RemoteImage(repository.poster(media.posterPath),Modifier.fillMaxSize().clip(CircleShape))
            }
        }
        Text(media.title,fontSize=8.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))
    }
}

@Composable
private fun PremiumContinueRow(
    items: List<ContinueWatchingItem>,
    repository: TmdbRepository,
    onPlay: (PlaybackTarget) -> Unit,
    onMedia: (MediaItem) -> Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=FqDimens.Screen),
        horizontalArrangement=Arrangement.spacedBy(11.dp)
    ) {
        items(items,key={it.target.mediaVersionId}) { item ->
            Column(
                Modifier.width(232.dp).clip(RoundedCornerShape(20.dp)).background(FqSurface)
                    .clickable { onPlay(item.target) }
            ) {
                Box(Modifier.fillMaxWidth().height(130.dp)) {
                    RemoteImage(
                        repository.backdrop(item.media.backdropPath ?: item.media.posterPath),
                        Modifier.fillMaxSize(),
                        ContentScale.Crop
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.78f)))
                        )
                    )
                    Box(
                        Modifier.size(44.dp).align(Alignment.Center).clip(CircleShape)
                            .background(FqGold),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(Icons.Default.PlayArrow,null,tint=Color.Black,modifier=Modifier.size(28.dp))
                    }
                    Text(
                        item.episodeLabel,
                        color=Color.White,
                        fontSize=8.sp,
                        modifier=Modifier.align(Alignment.BottomStart).padding(9.dp)
                    )
                }
                LinearProgressIndicator(
                    progress={item.progress},
                    color=FqGold,
                    trackColor=FqSurface3,
                    modifier=Modifier.fillMaxWidth().height(3.dp)
                )
                Row(Modifier.fillMaxWidth().padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.media.title,fontSize=11.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(((item.progress*100).toInt()).toString()+"٪ دیده شده",color=FqMuted,fontSize=8.sp,modifier=Modifier.padding(top=3.dp))
                    }
                    IconButton(onClick={onMedia(item.media)},modifier=Modifier.size(32.dp)) {
                        Icon(Icons.Default.MoreVert,null,modifier=Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PremiumTop10Row(
    list: List<MediaItem>,
    repository: TmdbRepository,
    onMedia: (MediaItem) -> Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=FqDimens.Screen),
        horizontalArrangement=Arrangement.spacedBy(6.dp)
    ) {
        items(list.size,key={list[it].key}) { index ->
            val media=list[index]
            Box(
                Modifier.width(168.dp).height(230.dp).clickable { onMedia(media) }
            ) {
                Text(
                    (index+1).toString(),
                    fontSize=86.sp,
                    fontWeight=FontWeight.Black,
                    color=FqSurface3,
                    modifier=Modifier.align(Alignment.BottomStart)
                )
                RemoteImage(
                    repository.poster(media.posterPath),
                    Modifier.width(126.dp).height(198.dp).align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(18.dp)),
                    ContentScale.Crop
                )
                Surface(
                    color=Color.Black.copy(alpha=.72f),
                    shape=RoundedCornerShape(8.dp),
                    modifier=Modifier.align(Alignment.BottomEnd).padding(bottom=4.dp)
                ) {
                    Text(media.title,fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.widthIn(max=122.dp).padding(7.dp))
                }
            }
        }
    }
}

@Composable
private fun PremiumPosterRow(
    list: List<MediaItem>,
    repository: TmdbRepository,
    onMedia: (MediaItem) -> Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=FqDimens.Screen),
        horizontalArrangement=Arrangement.spacedBy(11.dp)
    ) {
        items(list.take(20),key={it.key}) { media ->
            Column(Modifier.width(140.dp).clickable { onMedia(media) }) {
                Box(Modifier.fillMaxWidth().height(205.dp).clip(RoundedCornerShape(20.dp))) {
                    RemoteImage(repository.poster(media.posterPath),Modifier.fillMaxSize(),ContentScale.Crop)
                    if(media.streamReady) {
                        Surface(
                            color=FqGreen.copy(alpha=.9f),
                            contentColor=Color.Black,
                            shape=RoundedCornerShape(8.dp),
                            modifier=Modifier.align(Alignment.TopStart).padding(7.dp)
                        ) {
                            Text(media.quality.ifBlank{"PLAY"},fontSize=7.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp))
                        }
                    }
                    if(media.vote>0) {
                        Surface(
                            color=Color.Black.copy(alpha=.68f),
                            shape=RoundedCornerShape(8.dp),
                            modifier=Modifier.align(Alignment.BottomStart).padding(7.dp)
                        ) {
                            Row(Modifier.padding(horizontal=6.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically) {
                                Icon(Icons.Default.Star,null,tint=FqGold,modifier=Modifier.size(11.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(formatVote(media.vote),fontSize=8.sp)
                            }
                        }
                    }
                }
                Text(media.title,fontSize=10.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=7.dp))
                Text(listOf(media.year,if(media.type==MediaType.MOVIE)"فیلم" else "سریال").filter(String::isNotBlank).joinToString(" • "),color=FqMuted,fontSize=8.sp,modifier=Modifier.padding(top=2.dp))
            }
        }
    }
}

@Composable
private fun PremiumWideRow(
    list: List<MediaItem>,
    repository: TmdbRepository,
    onMedia: (MediaItem) -> Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=FqDimens.Screen),
        horizontalArrangement=Arrangement.spacedBy(11.dp)
    ) {
        items(list.take(16),key={it.key}) { media ->
            Box(
                Modifier.width(248.dp).height(142.dp).clip(RoundedCornerShape(20.dp))
                    .clickable { onMedia(media) }
            ) {
                RemoteImage(repository.backdrop(media.backdropPath ?: media.posterPath),Modifier.fillMaxSize(),ContentScale.Crop)
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.88f)))
                    )
                )
                Column(Modifier.align(Alignment.BottomStart).padding(11.dp)) {
                    Text(media.title,fontSize=13.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(listOf(media.year,if(media.vote>0)"★ "+formatVote(media.vote) else "").filter(String::isNotBlank).joinToString(" • "),color=FqMuted,fontSize=8.sp)
                }
            }
        }
    }
}


private fun personalizationSubtitle(bundle: PersonalizedHomeBundle?):String {
    if(bundle==null) return "پیشنهاد شخصی Filmiqoo"
    val parts=buildList {
        when(bundle.preferredKind) {
            "movie" -> add("فیلم")
            "series","tv" -> add("سریال")
            "anime" -> add("انیمه")
        }
        if(bundle.preferredLanguage.isNotBlank()) {
            add(
                when(bundle.preferredLanguage) {
                    "fa" -> "فارسی"
                    "ko" -> "کره‌ای"
                    "ja" -> "ژاپنی"
                    "hi" -> "هندی"
                    "en" -> "انگلیسی"
                    else -> bundle.preferredLanguage.uppercase()
                }
            )
        }
    }
    return if(parts.isEmpty()) "بر اساس تماشا، Favorite و Watchlist تو"
    else "بر اساس علاقه‌ات به "+parts.joinToString(" و ")
}
