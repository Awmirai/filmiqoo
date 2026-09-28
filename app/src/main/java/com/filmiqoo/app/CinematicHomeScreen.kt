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
import androidx.compose.runtime.Composable
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

@Composable
fun CinematicHomeContent(
    data:HomeBundle,
    continueItems:List<ContinueWatchingItem>,
    personalized:PersonalizedHomeBundle?,
    friendsWatching:List<FriendWatchingNow>,
    unreadNotifications:Long,
    repository:TmdbRepository,
    loggedIn:Boolean,
    kidsMode:Boolean,
    onMedia:(MediaItem)->Unit,
    onPlay:(PlaybackTarget)->Unit,
    onSearch:()->Unit,
    onNotifications:()->Unit,
    onReleases:()->Unit,
    onPulse:()->Unit,
    onWatchParty:(MediaItem?)->Unit,
    onRefresh:()->Unit
) {
    val personalizedItems=(
        personalized?.forYou.orEmpty() +
        personalized?.newForYou.orEmpty()
    ).distinctBy { it.key }

    val heroItems=if(kidsMode) {
        personalizedItems.take(6)
    } else {
        (
            personalized?.forYou.orEmpty().take(2) +
            data.trending +
            data.popularMovies +
            data.popularTv
        ).distinctBy { it.key }.take(6)
    }

    val tonight=(
        personalized?.forYou.orEmpty() +
        data.trending +
        data.popularMovies +
        data.popularTv
    ).distinctBy { it.key }.take(8)

    val topTen=(
        data.trending +
        data.popularMovies +
        data.popularTv
    ).distinctBy { it.key }.take(10)

    LazyColumn(
        modifier=Modifier.fillMaxSize().background(FqBg),
        contentPadding=PaddingValues(bottom=34.dp)
    ) {
        item {
            CinematicHeroPager(
                items=heroItems,
                repository=repository,
                unreadNotifications=unreadNotifications,
                kidsMode=kidsMode,
                onMedia=onMedia,
                onPlay=onPlay,
                onSearch=onSearch,
                onNotifications=onNotifications,
                onWatchParty=onWatchParty
            )
        }

        if(continueItems.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    eyebrow="ادامه بده",
                    title="ادامه تماشا",
                    subtitle="از دقیقاً همان لحظه‌ای که رها کردی",
                    onMore=null
                )
            }
            item {
                ContinueWatchingRail(
                    items=continueItems,
                    repository=repository,
                    onPlay=onPlay,
                    onMedia=onMedia
                )
            }
        }

        item {
            StreamingQuickActions(
                onSearch=onSearch,
                onReleases=onReleases,
                onWatchParty={onWatchParty(heroItems.firstOrNull())},
                kidsMode=kidsMode
            )
        }

        if(tonight.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    eyebrow="انتخاب سریع",
                    title="امشب چی ببینم؟",
                    subtitle="چند انتخاب آماده برای وقتی نمی‌خوای زیاد بگردی",
                    onMore=onSearch
                )
            }
            item {
                TonightRail(
                    items=tonight,
                    repository=repository,
                    onMedia=onMedia,
                    onPlay=onPlay
                )
            }
        }

        personalized?.forYou?.takeIf { it.isNotEmpty() }?.let { items ->
            item {
                StreamingSectionTitle(
                    eyebrow="برای تو",
                    title="پیشنهاد مخصوص تو",
                    subtitle=cinematicPersonalizationSubtitle(personalized),
                    onMore=null
                )
            }
            item { CinematicPosterRail(items,repository,onMedia) }
        }

        if(topTen.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    eyebrow="محبوب",
                    title="۱۰ تای برتر امروز",
                    subtitle="عنوان‌هایی که امروز بیشتر دیده می‌شن",
                    onMore=null
                )
            }
            item {
                CinematicTopTenRail(
                    items=topTen,
                    repository=repository,
                    onMedia=onMedia
                )
            }
        }

        personalized?.newForYou?.takeIf { it.isNotEmpty() }?.let { items ->
            item {
                StreamingSectionTitle(
                    eyebrow="تازه رسیده",
                    title="جدید برای تو",
                    subtitle="عنوان‌های تازه‌ای که به سلیقه‌ات نزدیکن",
                    onMore=onReleases
                )
            }
            item { CinematicLandscapeRail(items.take(14),repository,onMedia) }
        }

        if(data.popularMovies.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    eyebrow="فیلم",
                    title="فیلم‌های پیشنهادی",
                    subtitle="از انتخاب‌های داغ تا فیلم‌هایی که شاید ندیده باشی",
                    onMore=onSearch
                )
            }
            item { CinematicPosterRail(data.popularMovies,repository,onMedia) }
        }

        if(data.popularTv.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    eyebrow="سریال",
                    title="سریال‌هایی که ارزش شروع دارن",
                    subtitle="برای یک قسمت یا یک شب کامل",
                    onMore=onSearch
                )
            }
            item { CinematicLandscapeRail(data.popularTv.take(14),repository,onMedia) }
        }

        if(data.iranian.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    eyebrow="ایران",
                    title="سینمای ایران",
                    subtitle="فیلم و سریال فارسی‌زبان",
                    onMore=onSearch
                )
            }
            item { CinematicPosterRail(data.iranian,repository,onMedia) }
        }

        if(data.korean.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    eyebrow="K-Drama",
                    title="کره‌ای‌های محبوب",
                    subtitle="سریال‌ها و فیلم‌های محبوب کره‌ای",
                    onMore=onSearch
                )
            }
            item { CinematicLandscapeRail(data.korean.take(14),repository,onMedia) }
        }

        if(data.anime.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    eyebrow="Anime",
                    title="دنیای انیمه",
                    subtitle="از محبوب‌ها تا کشف‌های تازه",
                    onMore=onSearch
                )
            }
            item { CinematicPosterRail(data.anime,repository,onMedia) }
        }

        if(data.bollywood.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    eyebrow="India",
                    title="سینمای هند",
                    subtitle="بالیوود و انتخاب‌های محبوب هندی",
                    onMore=onSearch
                )
            }
            item { CinematicLandscapeRail(data.bollywood.take(14),repository,onMedia) }
        }

        if(!kidsMode && (friendsWatching.isNotEmpty() || loggedIn)) {
            item {
                PulseHomeBridge(
                    friendsWatching=friendsWatching,
                    repository=repository,
                    onMedia=onMedia,
                    onPulse=onPulse
                )
            }
        }

        if(!loggedIn) {
            item {
                GuestStreamingCard()
            }
        }

        item {
            TextButton(
                onClick=onRefresh,
                modifier=Modifier.fillMaxWidth().padding(top=12.dp,bottom=10.dp)
            ) {
                Icon(Icons.Default.Refresh,null,modifier=Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("تازه‌سازی پیشنهادها",fontSize=11.sp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CinematicHeroPager(
    items:List<MediaItem>,
    repository:TmdbRepository,
    unreadNotifications:Long,
    kidsMode:Boolean,
    onMedia:(MediaItem)->Unit,
    onPlay:(PlaybackTarget)->Unit,
    onSearch:()->Unit,
    onNotifications:()->Unit,
    onWatchParty:(MediaItem?)->Unit
) {
    val pageCount=items.size.coerceAtLeast(1)
    val pager=rememberPagerState(pageCount={pageCount})

    Column {
        Box(
            Modifier.fillMaxWidth().height(610.dp)
        ) {
            if(items.isNotEmpty()) {
                HorizontalPager(
                    state=pager,
                    modifier=Modifier.fillMaxSize()
                ) { page ->
                    val media=items[page.coerceIn(0,items.lastIndex)]
                    CinematicHeroPage(
                        media=media,
                        repository=repository,
                        kidsMode=kidsMode,
                        onMedia=onMedia,
                        onPlay=onPlay,
                        onWatchParty=onWatchParty
                    )
                }
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF161616),FqBg)
                        )
                    )
                )
            }

            Row(
                Modifier.fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal=16.dp,vertical=10.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                FilmiqooBrandMark(size=42.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "FILMIQOO",
                    color=Color.White,
                    fontSize=18.sp,
                    fontWeight=FontWeight.Black,
                    letterSpacing=1.4.sp
                )
                Spacer(Modifier.weight(1f))
                HeroRoundButton(Icons.Default.Search,"جستجو",onSearch)
                Spacer(Modifier.width(7.dp))
                BadgedBox(
                    badge={
                        if(unreadNotifications>0) {
                            Badge(containerColor=FqGold) {
                                Text(
                                    if(unreadNotifications>99)"99+"
                                    else unreadNotifications.toString(),
                                    color=Color.White,
                                    fontSize=8.sp
                                )
                            }
                        }
                    }
                ) {
                    HeroRoundButton(
                        Icons.Default.NotificationsNone,
                        "اعلان‌ها",
                        onNotifications
                    )
                }
            }
        }

        if(items.size>1) {
            Row(
                Modifier.fillMaxWidth().padding(top=10.dp),
                horizontalArrangement=Arrangement.Center
            ) {
                repeat(items.size) { index ->
                    Box(
                        Modifier.padding(horizontal=3.dp)
                            .width(if(index==pager.currentPage)24.dp else 6.dp)
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(
                                if(index==pager.currentPage) FqGold
                                else Color.White.copy(alpha=.18f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun CinematicHeroPage(
    media:MediaItem,
    repository:TmdbRepository,
    kidsMode:Boolean,
    onMedia:(MediaItem)->Unit,
    onPlay:(PlaybackTarget)->Unit,
    onWatchParty:(MediaItem?)->Unit
) {
    Box(Modifier.fillMaxSize()) {
        RemoteImage(
            repository.backdrop(media.backdropPath ?: media.posterPath),
            Modifier.fillMaxSize(),
            ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha=.08f),
                    .36f to Color.Transparent,
                    .67f to Color.Black.copy(alpha=.42f),
                    .88f to Color.Black.copy(alpha=.90f),
                    1f to FqBg
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha=.70f),
                    .58f to Color.Transparent,
                    1f to Color.Black.copy(alpha=.12f)
                )
            )
        )

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
                        if(media.type==MediaType.MOVIE)"فیلم" else "سریال",
                        fontSize=8.sp,
                        fontWeight=FontWeight.Black,
                        modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp)
                    )
                }
                if(media.streamReady) {
                    Surface(
                        color=Color.White.copy(alpha=.14f),
                        contentColor=Color.White,
                        shape=RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            media.quality.ifBlank { "آماده پخش" },
                            fontSize=8.sp,
                            fontWeight=FontWeight.Bold,
                            modifier=Modifier.padding(horizontal=7.dp,vertical=4.dp)
                        )
                    }
                }
                if(media.vote>0) {
                    Text(
                        "★ "+formatVote(media.vote),
                        color=Color.White,
                        fontSize=10.sp,
                        fontWeight=FontWeight.Bold
                    )
                }
                if(media.year.isNotBlank()) {
                    Text(
                        media.year,
                        color=Color.White.copy(alpha=.70f),
                        fontSize=10.sp
                    )
                }
            }

            Text(
                media.title,
                color=Color.White,
                fontSize=34.sp,
                lineHeight=39.sp,
                fontWeight=FontWeight.Black,
                maxLines=2,
                overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=12.dp)
            )

            if(
                media.originalTitle.isNotBlank() &&
                media.originalTitle!=media.title
            ) {
                Text(
                    media.originalTitle,
                    color=Color.White.copy(alpha=.60f),
                    fontSize=11.sp,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=2.dp)
                )
            }

            if(media.overview.isNotBlank()) {
                Text(
                    media.overview,
                    color=Color.White.copy(alpha=.78f),
                    fontSize=12.sp,
                    lineHeight=18.sp,
                    maxLines=3,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=9.dp).fillMaxWidth(.92f)
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top=18.dp),
                verticalAlignment=Alignment.CenterVertically,
                horizontalArrangement=Arrangement.spacedBy(9.dp)
            ) {
                Button(
                    onClick={
                        val version=media.mediaVersionId
                        if(media.streamReady && !version.isNullOrBlank()) {
                            onPlay(
                                PlaybackTarget(
                                    mediaVersionId=version,
                                    mediaTitleId=media.backendId,
                                    title=media.title,
                                    subtitle=listOf(media.year,media.quality)
                                        .filter(String::isNotBlank)
                                        .joinToString(" • "),
                                    posterUrl=repository.poster(media.posterPath)
                                )
                            )
                        } else {
                            onMedia(media)
                        }
                    },
                    colors=ButtonDefaults.buttonColors(
                        containerColor=Color.White,
                        contentColor=Color.Black
                    ),
                    shape=RoundedCornerShape(14.dp),
                    contentPadding=PaddingValues(horizontal=18.dp,vertical=13.dp),
                    modifier=Modifier.heightIn(min=50.dp)
                ) {
                    Icon(
                        if(media.streamReady) Icons.Default.PlayArrow
                        else Icons.Default.Info,
                        null
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        if(media.streamReady)"پخش" else "جزئیات",
                        fontWeight=FontWeight.Black
                    )
                }

                FilledTonalButton(
                    onClick={onMedia(media)},
                    colors=ButtonDefaults.filledTonalButtonColors(
                        containerColor=Color.Black.copy(alpha=.50f),
                        contentColor=Color.White
                    ),
                    shape=RoundedCornerShape(14.dp),
                    modifier=Modifier.heightIn(min=50.dp)
                ) {
                    Icon(Icons.Default.InfoOutline,null)
                    Spacer(Modifier.width(5.dp))
                    Text("اطلاعات")
                }

                if(!kidsMode) {
                    FilledTonalIconButton(
                        onClick={onWatchParty(media)},
                        colors=IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor=Color.Black.copy(alpha=.50f),
                            contentColor=Color.White
                        ),
                        modifier=Modifier.size(50.dp)
                    ) {
                        Icon(Icons.Default.Groups,null)
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroRoundButton(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    description:String,
    onClick:()->Unit
) {
    Surface(
        color=Color.Black.copy(alpha=.38f),
        contentColor=Color.White,
        shape=CircleShape,
        border=androidx.compose.foundation.BorderStroke(
            1.dp,
            Color.White.copy(alpha=.13f)
        ),
        modifier=Modifier.size(44.dp).clickable(onClick=onClick)
    ) {
        Box(contentAlignment=Alignment.Center) {
            Icon(icon,description,modifier=Modifier.size(21.dp))
        }
    }
}

@Composable
private fun StreamingQuickActions(
    onSearch:()->Unit,
    onReleases:()->Unit,
    onWatchParty:()->Unit,
    kidsMode:Boolean
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal=16.dp)
            .padding(top=18.dp),
        horizontalArrangement=Arrangement.spacedBy(9.dp)
    ) {
        StreamingQuickAction(
            icon=Icons.Default.Search,
            title="کشف",
            subtitle="چی ببینم؟",
            onClick=onSearch,
            modifier=Modifier.weight(1f)
        )
        StreamingQuickAction(
            icon=Icons.Default.NewReleases,
            title="تازه‌ها",
            subtitle="انتشارهای جدید",
            onClick=onReleases,
            modifier=Modifier.weight(1f)
        )
        if(!kidsMode) {
            StreamingQuickAction(
                icon=Icons.Default.Groups,
                title="باهم ببین",
                subtitle="Watch Party",
                onClick=onWatchParty,
                modifier=Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StreamingQuickAction(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    title:String,
    subtitle:String,
    onClick:()->Unit,
    modifier:Modifier=Modifier
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(18.dp),
        border=androidx.compose.foundation.BorderStroke(
            1.dp,
            Color.White.copy(alpha=.07f)
        ),
        modifier=modifier.clickable(onClick=onClick)
    ) {
        Column(
            Modifier.padding(horizontal=12.dp,vertical=13.dp)
        ) {
            Icon(icon,null,tint=FqGold,modifier=Modifier.size(19.dp))
            Text(
                title,
                fontSize=11.sp,
                fontWeight=FontWeight.Black,
                modifier=Modifier.padding(top=8.dp)
            )
            Text(
                subtitle,
                color=FqMuted,
                fontSize=8.sp,
                maxLines=1,
                overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=1.dp)
            )
        }
    }
}

@Composable
private fun StreamingSectionTitle(
    eyebrow:String,
    title:String,
    subtitle:String,
    onMore:(()->Unit)?
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(start=16.dp,end=16.dp,top=28.dp,bottom=11.dp),
        verticalAlignment=Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                eyebrow.uppercase(),
                color=FqGold,
                fontSize=9.sp,
                fontWeight=FontWeight.Black,
                letterSpacing=.8.sp
            )
            Text(
                title,
                color=FqText,
                fontSize=20.sp,
                fontWeight=FontWeight.Black,
                modifier=Modifier.padding(top=2.dp)
            )
            Text(
                subtitle,
                color=FqMuted,
                fontSize=10.sp,
                maxLines=1,
                overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=2.dp)
            )
        }
        if(onMore!=null) {
            TextButton(onClick=onMore) {
                Text("بیشتر",fontSize=10.sp)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Default.ChevronLeft,null,modifier=Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ContinueWatchingRail(
    items:List<ContinueWatchingItem>,
    repository:TmdbRepository,
    onPlay:(PlaybackTarget)->Unit,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp)
    ) {
        items(items,key={it.target.mediaVersionId}) { item ->
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(20.dp),
                border=androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Color.White.copy(alpha=.07f)
                ),
                modifier=Modifier.width(292.dp)
                    .clickable { onPlay(item.target) }
            ) {
                Column {
                    Box(Modifier.fillMaxWidth().height(158.dp)) {
                        RemoteImage(
                            repository.backdrop(
                                item.media.backdropPath ?: item.media.posterPath
                            ),
                            Modifier.fillMaxSize(),
                            ContentScale.Crop
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha=.76f)
                                    )
                                )
                            )
                        )
                        Box(
                            Modifier.size(50.dp)
                                .align(Alignment.Center)
                                .background(
                                    Color.White.copy(alpha=.94f),
                                    CircleShape
                                ),
                            contentAlignment=Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                null,
                                tint=Color.Black,
                                modifier=Modifier.size(29.dp)
                            )
                        }
                        Text(
                            item.episodeLabel.ifBlank { "ادامه تماشا" },
                            color=Color.White,
                            fontSize=10.sp,
                            fontWeight=FontWeight.Bold,
                            modifier=Modifier.align(Alignment.BottomStart)
                                .padding(horizontal=11.dp,vertical=9.dp)
                        )
                    }
                    LinearProgressIndicator(
                        progress={item.progress.coerceIn(0f,1f)},
                        color=FqGold,
                        trackColor=Color.White.copy(alpha=.08f),
                        modifier=Modifier.fillMaxWidth().height(3.dp)
                    )
                    Row(
                        Modifier.padding(horizontal=12.dp,vertical=10.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.media.title,
                                fontWeight=FontWeight.Black,
                                fontSize=12.sp,
                                maxLines=1,
                                overflow=TextOverflow.Ellipsis
                            )
                            Text(
                                ((item.progress*100).toInt()).toString()+"٪ دیده شده",
                                color=FqMuted,
                                fontSize=8.sp,
                                modifier=Modifier.padding(top=2.dp)
                            )
                        }
                        IconButton(
                            onClick={onMedia(item.media)},
                            modifier=Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreHoriz,
                                null,
                                tint=FqMuted
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TonightRail(
    items:List<MediaItem>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit,
    onPlay:(PlaybackTarget)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp)
    ) {
        items(items.take(8),key={it.key}) { media ->
            Box(
                Modifier.width(305.dp)
                    .height(178.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .clickable { onMedia(media) }
            ) {
                RemoteImage(
                    repository.backdrop(media.backdropPath ?: media.posterPath),
                    Modifier.fillMaxSize(),
                    ContentScale.Crop
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha=.82f),
                                Color.Black.copy(alpha=.20f)
                            )
                        )
                    )
                )
                Column(
                    Modifier.align(Alignment.BottomStart)
                        .padding(14.dp)
                        .fillMaxWidth(.78f)
                ) {
                    Text(
                        media.title,
                        color=Color.White,
                        fontSize=16.sp,
                        fontWeight=FontWeight.Black,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis
                    )
                    Text(
                        listOf(
                            media.year,
                            if(media.type==MediaType.MOVIE)"فیلم" else "سریال",
                            if(media.vote>0)"★ "+formatVote(media.vote) else ""
                        ).filter(String::isNotBlank).joinToString(" • "),
                        color=Color.White.copy(alpha=.72f),
                        fontSize=9.sp,
                        modifier=Modifier.padding(top=3.dp)
                    )
                }
                if(media.streamReady && !media.mediaVersionId.isNullOrBlank()) {
                    Surface(
                        color=Color.White,
                        contentColor=Color.Black,
                        shape=CircleShape,
                        modifier=Modifier.align(Alignment.BottomEnd)
                            .padding(12.dp)
                            .size(42.dp)
                            .clickable {
                                onPlay(
                                    PlaybackTarget(
                                        mediaVersionId=media.mediaVersionId!!,
                                        mediaTitleId=media.backendId,
                                        title=media.title,
                                        subtitle=listOf(media.year,media.quality)
                                            .filter(String::isNotBlank)
                                            .joinToString(" • "),
                                        posterUrl=repository.poster(media.posterPath)
                                    )
                                )
                            }
                    ) {
                        Box(contentAlignment=Alignment.Center) {
                            Icon(
                                Icons.Default.PlayArrow,
                                null,
                                modifier=Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CinematicTopTenRail(
    items:List<MediaItem>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(2.dp)
    ) {
        items(items.size,key={items[it].key}) { index ->
            val media=items[index]
            Box(
                Modifier.width(174.dp)
                    .height(236.dp)
                    .clickable { onMedia(media) }
            ) {
                Text(
                    (index+1).toString(),
                    color=Color.White.copy(alpha=.14f),
                    fontSize=94.sp,
                    lineHeight=94.sp,
                    fontWeight=FontWeight.Black,
                    modifier=Modifier.align(Alignment.BottomStart)
                )
                RemoteImage(
                    repository.poster(media.posterPath),
                    Modifier.width(128.dp)
                        .height(208.dp)
                        .align(Alignment.TopEnd)
                        .clip(RoundedCornerShape(17.dp)),
                    ContentScale.Crop
                )
                if(media.streamReady) {
                    Surface(
                        color=FqGold,
                        shape=RoundedCornerShape(7.dp),
                        modifier=Modifier.align(Alignment.TopEnd)
                            .padding(top=7.dp,end=7.dp)
                    ) {
                        Text(
                            media.quality.ifBlank { "PLAY" },
                            color=Color.White,
                            fontSize=7.sp,
                            fontWeight=FontWeight.Black,
                            modifier=Modifier.padding(horizontal=5.dp,vertical=3.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CinematicPosterRail(
    items:List<MediaItem>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(11.dp)
    ) {
        items(items.take(20),key={it.key}) { media ->
            Column(
                Modifier.width(136.dp).clickable { onMedia(media) }
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .height(202.dp)
                        .clip(RoundedCornerShape(17.dp))
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
                                    Color.Black.copy(alpha=.48f)
                                )
                            )
                        )
                    )
                    if(media.streamReady) {
                        Surface(
                            color=FqGold,
                            shape=RoundedCornerShape(7.dp),
                            modifier=Modifier.align(Alignment.TopStart).padding(7.dp)
                        ) {
                            Text(
                                media.quality.ifBlank{"PLAY"},
                                color=Color.White,
                                fontSize=7.sp,
                                fontWeight=FontWeight.Black,
                                modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp)
                            )
                        }
                    }
                    if(media.vote>0) {
                        Surface(
                            color=Color.Black.copy(alpha=.62f),
                            shape=RoundedCornerShape(8.dp),
                            modifier=Modifier.align(Alignment.BottomStart).padding(7.dp)
                        ) {
                            Text(
                                "★ "+formatVote(media.vote),
                                color=Color.White,
                                fontSize=8.sp,
                                modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp)
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
                    modifier=Modifier.padding(top=7.dp)
                )
                Text(
                    listOf(
                        media.year,
                        if(media.type==MediaType.MOVIE)"فیلم" else "سریال"
                    ).filter(String::isNotBlank).joinToString(" • "),
                    color=FqMuted,
                    fontSize=8.sp,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=2.dp)
                )
            }
        }
    }
}

@Composable
private fun CinematicLandscapeRail(
    items:List<MediaItem>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp)
    ) {
        items(items,key={it.key}) { media ->
            Box(
                Modifier.width(270.dp)
                    .height(154.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .clickable { onMedia(media) }
            ) {
                RemoteImage(
                    repository.backdrop(media.backdropPath ?: media.posterPath),
                    Modifier.fillMaxSize(),
                    ContentScale.Crop
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Black.copy(alpha=.80f),
                                Color.Transparent
                            )
                        )
                    )
                )
                Column(
                    Modifier.align(Alignment.BottomStart)
                        .padding(13.dp)
                        .fillMaxWidth(.82f)
                ) {
                    Text(
                        media.title,
                        color=Color.White,
                        fontWeight=FontWeight.Black,
                        fontSize=14.sp,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis
                    )
                    Text(
                        listOf(
                            media.year,
                            if(media.vote>0)"★ "+formatVote(media.vote) else ""
                        ).filter(String::isNotBlank).joinToString(" • "),
                        color=Color.White.copy(alpha=.70f),
                        fontSize=9.sp,
                        modifier=Modifier.padding(top=3.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PulseHomeBridge(
    friendsWatching:List<FriendWatchingNow>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit,
    onPulse:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(24.dp),
        border=androidx.compose.foundation.BorderStroke(
            1.dp,
            Color.White.copy(alpha=.07f)
        ),
        modifier=Modifier.fillMaxWidth()
            .padding(horizontal=16.dp)
            .padding(top=30.dp)
    ) {
        Column(Modifier.padding(vertical=16.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal=16.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(40.dp)
                        .background(FqGold.copy(alpha=.14f),CircleShape),
                    contentAlignment=Alignment.Center
                ) {
                    Icon(Icons.Default.Whatshot,null,tint=FqGold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Pulse",
                        fontSize=15.sp,
                        fontWeight=FontWeight.Black
                    )
                    Text(
                        if(friendsWatching.isNotEmpty())
                            "ببین فیلم‌بازها الان چی می‌بینن و درباره چی حرف می‌زنن"
                        else
                            "واکنش، نقد و بحثی که مستقیم به فیلم و سریال وصله",
                        color=FqMuted,
                        fontSize=9.sp,
                        lineHeight=14.sp,
                        modifier=Modifier.padding(top=2.dp)
                    )
                }
                TextButton(onClick=onPulse) {
                    Text("باز کردن",fontSize=10.sp)
                    Icon(Icons.Default.ChevronLeft,null,modifier=Modifier.size(16.dp))
                }
            }

            if(friendsWatching.isNotEmpty()) {
                LazyRow(
                    contentPadding=PaddingValues(horizontal=16.dp),
                    horizontalArrangement=Arrangement.spacedBy(9.dp),
                    modifier=Modifier.padding(top=12.dp)
                ) {
                    items(
                        friendsWatching.take(8),
                        key={it.user.id+":"+it.media.key}
                    ) { item ->
                        Surface(
                            color=FqSurface2,
                            shape=RoundedCornerShape(16.dp),
                            modifier=Modifier.width(180.dp)
                                .clickable { onMedia(item.media) }
                        ) {
                            Row(
                                Modifier.padding(9.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                RemoteImage(
                                    item.user.avatarUrl.takeIf(String::isNotBlank),
                                    Modifier.size(34.dp).clip(CircleShape)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        item.user.displayName,
                                        fontSize=9.sp,
                                        fontWeight=FontWeight.Bold,
                                        maxLines=1,
                                        overflow=TextOverflow.Ellipsis
                                    )
                                    Text(
                                        item.media.title,
                                        color=FqMuted,
                                        fontSize=8.sp,
                                        maxLines=1,
                                        overflow=TextOverflow.Ellipsis,
                                        modifier=Modifier.padding(top=2.dp)
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
private fun GuestStreamingCard() {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(24.dp),
        border=androidx.compose.foundation.BorderStroke(
            1.dp,
            Color.White.copy(alpha=.07f)
        ),
        modifier=Modifier.fillMaxWidth()
            .padding(horizontal=16.dp)
            .padding(top=30.dp)
    ) {
        Row(
            Modifier.padding(18.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(48.dp)
                    .background(FqGold.copy(alpha=.14f),CircleShape),
                contentAlignment=Alignment.Center
            ) {
                Icon(Icons.Default.PersonAdd,null,tint=FqGold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "با حساب خودت بهتر می‌شه",
                    fontWeight=FontWeight.Black,
                    fontSize=13.sp
                )
                Text(
                    "ادامه تماشا، پیشنهاد شخصی، دانلود و همگام‌سازی دستگاه‌ها بعد از ورود فعال می‌شن.",
                    color=FqMuted,
                    fontSize=10.sp,
                    lineHeight=15.sp,
                    modifier=Modifier.padding(top=3.dp)
                )
            }
        }
    }
}

private fun cinematicPersonalizationSubtitle(
    bundle:PersonalizedHomeBundle
):String {
    val signals=buildList {
        when(bundle.preferredKind) {
            "movie" -> add("فیلم")
            "series","tv" -> add("سریال")
            "anime" -> add("انیمه")
        }
        when(bundle.preferredLanguage) {
            "fa" -> add("فارسی")
            "ko" -> add("کره‌ای")
            "ja" -> add("ژاپنی")
            "hi" -> add("هندی")
            "en" -> add("انگلیسی")
        }
    }
    return if(signals.isEmpty()) {
        "بر اساس چیزهایی که تماشا کردی و برای بعد نگه داشتی"
    } else {
        "بر اساس علاقه‌ات به "+signals.joinToString(" و ")
    }
}
