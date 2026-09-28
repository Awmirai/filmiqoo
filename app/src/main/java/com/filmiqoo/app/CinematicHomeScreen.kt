package com.filmiqoo.app

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
    val hero=personalized?.forYou?.firstOrNull()
        ?: data.trending.firstOrNull()
        ?: data.popularMovies.firstOrNull()
        ?: data.popularTv.firstOrNull()

    LazyColumn(
        modifier=Modifier.fillMaxSize().background(FqBg),
        contentPadding=PaddingValues(bottom=26.dp)
    ) {
        item {
            CinematicHero(
                media=hero,
                repository=repository,
                unreadNotifications=unreadNotifications,
                onMedia=onMedia,
                onPlay=onPlay,
                onSearch=onSearch,
                onNotifications=onNotifications,
                onRefresh=onRefresh
            )
        }

        item {
            StreamingActionRail(
                onSearch=onSearch,
                onReleases=onReleases,
                onPulse=onPulse,
                onWatchParty={onWatchParty(null)},
                kidsMode=kidsMode
            )
        }

        if(continueItems.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    title="ادامه تماشا",
                    subtitle="دقیقاً از همان‌جایی که رها کردی",
                    icon=Icons.Default.PlayCircleFilled
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

        personalized?.forYou?.drop(1)?.takeIf { it.isNotEmpty() }?.let { items ->
            item {
                StreamingSectionTitle(
                    title="برای تو",
                    subtitle=cinematicPersonalizationSubtitle(personalized),
                    icon=Icons.Default.AutoAwesome
                )
            }
            item { CinematicPosterRail(items,repository,onMedia) }
        }

        if(!kidsMode && friendsWatching.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    title="الان بین دوستات",
                    subtitle="ببین بقیه همین لحظه مشغول تماشای چی هستن",
                    icon=Icons.Default.Groups
                )
            }
            item {
                FriendsWatchingRail(
                    items=friendsWatching,
                    repository=repository,
                    onMedia=onMedia
                )
            }
        }

        if(data.trending.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    title="داغ این روزها",
                    subtitle="فیلم و سریال‌هایی که همه درباره‌شون حرف می‌زنن",
                    icon=Icons.Default.LocalFireDepartment
                )
            }
            item { CinematicLandscapeRail(data.trending.take(12),repository,onMedia) }
        }

        personalized?.newForYou?.takeIf { it.isNotEmpty() }?.let { items ->
            item {
                StreamingSectionTitle(
                    title="تازه برای تو",
                    subtitle="تازه‌ترین چیزهایی که به سلیقه‌ات می‌خورن",
                    icon=Icons.Default.NewReleases
                )
            }
            item { CinematicPosterRail(items,repository,onMedia) }
        }

        if(data.iranian.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    title="ایرانی",
                    subtitle="فیلم و سریال‌های فارسی‌زبان برای امشب",
                    icon=Icons.Default.Movie
                )
            }
            item { CinematicPosterRail(data.iranian,repository,onMedia) }
        }

        if(data.korean.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    title="کره‌ای",
                    subtitle="سریال‌های محبوب و تازه",
                    icon=Icons.Default.LiveTv
                )
            }
            item { CinematicPosterRail(data.korean,repository,onMedia) }
        }

        if(data.anime.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    title="انیمه",
                    subtitle="از محبوب‌ها تا کشف‌های تازه",
                    icon=Icons.Default.Animation
                )
            }
            item { CinematicPosterRail(data.anime,repository,onMedia) }
        }

        if(data.bollywood.isNotEmpty()) {
            item {
                StreamingSectionTitle(
                    title="هندی",
                    subtitle="بالیوود و سینمای هند",
                    icon=Icons.Default.Theaters
                )
            }
            item { CinematicPosterRail(data.bollywood,repository,onMedia) }
        }

        if(!loggedIn) {
            item {
                Surface(
                    color=FqSurface,
                    shape=RoundedCornerShape(26.dp),
                    border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.08f)),
                    modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=26.dp)
                ) {
                    Row(
                        Modifier.padding(18.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(50.dp).background(FqGold.copy(alpha=.14f),CircleShape),
                            contentAlignment=Alignment.Center
                        ) {
                            Icon(Icons.Default.PersonAdd,null,tint=FqGold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Filmiqoo با حساب تو بهتر می‌شه",fontWeight=FontWeight.Bold,fontSize=14.sp)
                            Text(
                                "ادامه تماشا، پیشنهاد شخصی، دانلود و همگام‌سازی دستگاه‌ها بعد از ورود فعال می‌شن.",
                                color=FqMuted,
                                fontSize=11.sp,
                                lineHeight=17.sp,
                                modifier=Modifier.padding(top=3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CinematicHero(
    media:MediaItem?,
    repository:TmdbRepository,
    unreadNotifications:Long,
    onMedia:(MediaItem)->Unit,
    onPlay:(PlaybackTarget)->Unit,
    onSearch:()->Unit,
    onNotifications:()->Unit,
    onRefresh:()->Unit
) {
    Box(
        Modifier.fillMaxWidth().height(560.dp)
    ) {
        if(media!=null) {
            RemoteImage(
                repository.backdrop(media.backdropPath ?: media.posterPath),
                Modifier.fillMaxSize(),
                ContentScale.Crop
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color(0xFF171717),FqBg))
                )
            )
        }

        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha=.16f),
                    .48f to Color.Black.copy(alpha=.18f),
                    .78f to Color.Black.copy(alpha=.76f),
                    1f to FqBg
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha=.78f),
                    .62f to Color.Transparent,
                    1f to Color.Black.copy(alpha=.12f)
                )
            )
        )

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=16.dp,vertical=10.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            FilmiqooBrandMark(size=42.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "FILMIQOO",
                color=Color.White,
                fontSize=18.sp,
                fontWeight=FontWeight.Black,
                letterSpacing=1.sp
            )
            Spacer(Modifier.weight(1f))
            HeroRoundButton(Icons.Default.Search,"جستجو",onSearch)
            Spacer(Modifier.width(6.dp))
            BadgedBox(
                badge={
                    if(unreadNotifications>0) {
                        Badge(containerColor=FqGold) {
                            Text(if(unreadNotifications>99)"99+" else unreadNotifications.toString())
                        }
                    }
                }
            ) {
                HeroRoundButton(Icons.Default.NotificationsNone,"اعلان‌ها",onNotifications)
            }
        }

        media?.let { hero ->
            Column(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal=18.dp)
                    .padding(bottom=34.dp)
            ) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Surface(
                        color=FqGold,
                        contentColor=Color.White,
                        shape=RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            if(hero.type==MediaType.MOVIE)"فیلم" else "سریال",
                            fontSize=9.sp,
                            fontWeight=FontWeight.Black,
                            modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp)
                        )
                    }
                    if(hero.vote>0) {
                        Spacer(Modifier.width(8.dp))
                        Text("★ "+formatVote(hero.vote),color=Color.White,fontSize=11.sp,fontWeight=FontWeight.Bold)
                    }
                    if(hero.year.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(hero.year,color=Color.White.copy(alpha=.72f),fontSize=11.sp)
                    }
                    if(hero.quality.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color=Color.White.copy(alpha=.14f),
                            shape=RoundedCornerShape(7.dp)
                        ) {
                            Text(hero.quality,color=Color.White,fontSize=8.sp,modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp))
                        }
                    }
                }

                Text(
                    hero.title,
                    color=Color.White,
                    fontSize=32.sp,
                    lineHeight=37.sp,
                    fontWeight=FontWeight.Black,
                    maxLines=2,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=12.dp)
                )

                if(hero.overview.isNotBlank()) {
                    Text(
                        hero.overview,
                        color=Color.White.copy(alpha=.78f),
                        fontSize=12.sp,
                        lineHeight=19.sp,
                        maxLines=3,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=8.dp).fillMaxWidth(.9f)
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(top=18.dp),
                    horizontalArrangement=Arrangement.spacedBy(9.dp)
                ) {
                    Button(
                        onClick={
                            val version=hero.mediaVersionId
                            if(hero.streamReady && !version.isNullOrBlank()) {
                                onPlay(
                                    PlaybackTarget(
                                        mediaVersionId=version,
                                        mediaTitleId=hero.backendId,
                                        title=hero.title,
                                        subtitle=listOf(hero.year,hero.quality).filter(String::isNotBlank).joinToString(" • "),
                                        posterUrl=repository.poster(hero.posterPath)
                                    )
                                )
                            } else onMedia(hero)
                        },
                        colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=Color.Black),
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.height(50.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow,null)
                        Spacer(Modifier.width(5.dp))
                        Text(if(hero.streamReady)"تماشا" else "جزئیات",fontWeight=FontWeight.Black)
                    }

                    FilledTonalButton(
                        onClick={onMedia(hero)},
                        colors=ButtonDefaults.filledTonalButtonColors(
                            containerColor=Color.Black.copy(alpha=.48f),
                            contentColor=Color.White
                        ),
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.height(50.dp)
                    ) {
                        Icon(Icons.Default.Add,null)
                        Spacer(Modifier.width(5.dp))
                        Text("اطلاعات")
                    }

                    Spacer(Modifier.weight(1f))
                    HeroRoundButton(Icons.Default.Refresh,"تازه‌سازی",onRefresh)
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
        border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.12f)),
        modifier=Modifier.size(44.dp).clickable(onClick=onClick)
    ) {
        Box(contentAlignment=Alignment.Center) {
            Icon(icon,description,modifier=Modifier.size(21.dp))
        }
    }
}

@Composable
private fun StreamingActionRail(
    onSearch:()->Unit,
    onReleases:()->Unit,
    onPulse:()->Unit,
    onWatchParty:()->Unit,
    kidsMode:Boolean
) {
    val actions=buildList {
        add(Triple(Icons.Default.Search,"کشف",onSearch))
        add(Triple(Icons.Default.CalendarMonth,"انتشارها",onReleases))
        if(!kidsMode) {
            add(Triple(Icons.Default.Whatshot,"Pulse",onPulse))
            add(Triple(Icons.Default.Groups,"تماشای گروهی",onWatchParty))
        }
    }
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp),
        horizontalArrangement=Arrangement.spacedBy(9.dp)
    ) {
        items(actions) { (icon,label,action) ->
            Surface(
                color=FqSurface,
                contentColor=FqText,
                shape=RoundedCornerShape(18.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.07f)),
                modifier=Modifier.clickable(onClick=action)
            ) {
                Row(
                    Modifier.padding(horizontal=14.dp,vertical=12.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(icon,null,tint=FqGold,modifier=Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(label,fontSize=11.sp,fontWeight=FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StreamingSectionTitle(
    title:String,
    subtitle:String,
    icon:androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=24.dp,bottom=10.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).background(FqGold.copy(alpha=.12f),RoundedCornerShape(11.dp)),
            contentAlignment=Alignment.Center
        ) {
            Icon(icon,null,tint=FqGold,modifier=Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title,fontSize=18.sp,fontWeight=FontWeight.Black)
            Text(subtitle,color=FqMuted,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
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
                shape=RoundedCornerShape(18.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.07f)),
                modifier=Modifier.width(270.dp).clickable { onPlay(item.target) }
            ) {
                Column {
                    Box(Modifier.fillMaxWidth().height(145.dp)) {
                        RemoteImage(
                            repository.backdrop(item.media.backdropPath ?: item.media.posterPath),
                            Modifier.fillMaxSize(),
                            ContentScale.Crop
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.72f)))
                            )
                        )
                        Box(
                            Modifier.size(48.dp).align(Alignment.Center)
                                .background(Color.White.copy(alpha=.92f),CircleShape),
                            contentAlignment=Alignment.Center
                        ) {
                            Icon(Icons.Default.PlayArrow,null,tint=Color.Black,modifier=Modifier.size(27.dp))
                        }
                    }
                    LinearProgressIndicator(
                        progress={item.progress.coerceIn(0f,1f)},
                        color=FqGold,
                        trackColor=Color.White.copy(alpha=.08f),
                        modifier=Modifier.fillMaxWidth().height(3.dp)
                    )
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.media.title,fontWeight=FontWeight.Bold,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                            Text(
                                item.episodeLabel.ifBlank{"ادامه تماشا"},
                                color=FqMuted,
                                fontSize=9.sp,
                                maxLines=1,
                                overflow=TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick={onMedia(item.media)}) {
                            Icon(Icons.Default.MoreHoriz,null,tint=FqMuted)
                        }
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
                Modifier.width(132.dp).clickable { onMedia(media) }
            ) {
                Box(
                    Modifier.fillMaxWidth().height(196.dp)
                        .clip(RoundedCornerShape(17.dp))
                ) {
                    RemoteImage(repository.poster(media.posterPath),Modifier.fillMaxSize(),ContentScale.Crop)
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.62f)))
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
                }
                Text(media.title,fontSize=11.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=7.dp))
                Text(
                    listOf(media.year,if(media.vote>0)"★ "+formatVote(media.vote) else "").filter(String::isNotBlank).joinToString(" • "),
                    color=FqMuted,
                    fontSize=9.sp,
                    maxLines=1
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
                Modifier.width(265.dp).height(150.dp)
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
                        Brush.horizontalGradient(listOf(Color.Black.copy(alpha=.78f),Color.Transparent))
                    )
                )
                Column(
                    Modifier.align(Alignment.BottomStart).padding(13.dp)
                ) {
                    Text(media.title,color=Color.White,fontWeight=FontWeight.Black,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(
                        listOf(media.year,if(media.vote>0)"★ "+formatVote(media.vote) else "").filter(String::isNotBlank).joinToString(" • "),
                        color=Color.White.copy(alpha=.72f),
                        fontSize=9.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendsWatchingRail(
    items:List<FriendWatchingNow>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        items(items.take(12),key={it.user.id+":"+it.media.key}) { item ->
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(18.dp),
                border=androidx.compose.foundation.BorderStroke(1.dp,Color.White.copy(alpha=.07f)),
                modifier=Modifier.width(210.dp).clickable { onMedia(item.media) }
            ) {
                Column {
                    Box(Modifier.fillMaxWidth().height(110.dp)) {
                        RemoteImage(
                            repository.backdrop(item.media.backdropPath ?: item.media.posterPath),
                            Modifier.fillMaxSize(),
                            ContentScale.Crop
                        )
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.7f)))))
                        Row(
                            Modifier.align(Alignment.BottomStart).padding(9.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            RemoteImage(
                                item.user.avatarUrl.takeIf(String::isNotBlank),
                                Modifier.size(30.dp).clip(CircleShape)
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(item.user.displayName,color=Color.White,fontSize=10.sp,fontWeight=FontWeight.Bold,maxLines=1)
                        }
                    }
                    Column(Modifier.padding(10.dp)) {
                        Text(item.media.title,fontSize=11.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
                        Text(item.episodeLabel.ifBlank{"در حال تماشا"},color=FqMuted,fontSize=8.sp)
                    }
                }
            }
        }
    }
}

private fun cinematicPersonalizationSubtitle(bundle:PersonalizedHomeBundle):String {
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
        "بر اساس تماشا، علاقه‌مندی‌ها و چیزهایی که برای بعد نگه داشتی"
    } else {
        "بر اساس علاقه‌ات به "+signals.joinToString(" و ")
    }
}
