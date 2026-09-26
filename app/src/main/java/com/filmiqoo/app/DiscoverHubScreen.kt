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

private enum class DiscoverFilter { ALL, MOVIES, SERIES }

@Composable
fun DiscoverHubScreen(
    repository:TmdbRepository,
    social:SocialRepository,
    onSearch:()->Unit,
    onMedia:(MediaItem)->Unit,
    onCreator:(Creator)->Unit
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf(HomeBundle()) }
    var creators by remember { mutableStateOf<List<SocialChannel>>(emptyList()) }
    var filter by remember { mutableStateOf(DiscoverFilter.ALL) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        runCatching { repository.home() }
            .onSuccess { data=it }
            .onFailure { error=it.message ?: "Discover در دسترس نیست" }
        creators=runCatching { social.channels() }.getOrDefault(emptyList())
        loading=false
    }

    val filtered=remember(data,filter) {
        when(filter) {
            DiscoverFilter.ALL -> data.trending
            DiscoverFilter.MOVIES ->
                (data.popularMovies+data.trending.filter { it.type==MediaType.MOVIE })
                    .distinctBy { it.key }
            DiscoverFilter.SERIES ->
                (data.popularTv+data.trending.filter { it.type==MediaType.TV })
                    .distinctBy { it.key }
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(FqBg),
        contentPadding=PaddingValues(bottom=28.dp)
    ) {
        item {
            Column(
                Modifier.fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF111722),FqBg)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal=16.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(top=10.dp,bottom=14.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Discover",
                            fontSize=30.sp,
                            fontWeight=FontWeight.Black,
                            letterSpacing=(-0.5).sp
                        )
                        Text(
                            "چیزی پیدا کن که واقعاً ارزش دیدن داشته باشه",
                            color=FqMuted,
                            fontSize=11.sp,
                            modifier=Modifier.padding(top=2.dp)
                        )
                    }
                    IconButton(onClick={refresh++}) {
                        Icon(Icons.Default.Refresh,null,tint=FqMuted)
                    }
                }

                Surface(
                    color=FqSurface,
                    shape=RoundedCornerShape(18.dp),
                    border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                    modifier=Modifier.fillMaxWidth()
                        .height(52.dp)
                        .clickable { onSearch() }
                ) {
                    Row(
                        Modifier.fillMaxSize().padding(horizontal=14.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Search,null,tint=FqMuted)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "فیلم، سریال، آدم یا موضوع...",
                            color=FqMuted,
                            fontSize=12.sp,
                            modifier=Modifier.weight(1f)
                        )
                        Text(
                            "جستجو",
                            color=Color.White,
                            fontSize=10.sp,
                            fontWeight=FontWeight.Bold
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top=12.dp,bottom=10.dp),
                    horizontalArrangement=Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        DiscoverFilter.ALL to "همه",
                        DiscoverFilter.MOVIES to "فیلم",
                        DiscoverFilter.SERIES to "سریال"
                    ).forEach { item ->
                        val active=filter==item.first
                        Surface(
                            color=if(active)Color.White else FqSurface,
                            contentColor=if(active)Color.Black else FqMuted,
                            shape=RoundedCornerShape(14.dp),
                            modifier=Modifier.clickable { filter=item.first }
                        ) {
                            Text(
                                item.second,
                                fontSize=10.sp,
                                fontWeight=if(active)FontWeight.Bold else FontWeight.Medium,
                                modifier=Modifier.padding(horizontal=14.dp,vertical=8.dp)
                            )
                        }
                    }
                }
            }
        }

        if(loading) {
            item {
                LinearProgressIndicator(
                    color=FqGold,
                    trackColor=Color.Transparent,
                    modifier=Modifier.fillMaxWidth().height(2.dp)
                )
            }
        }

        error?.let {
            item {
                Row(
                    Modifier.fillMaxWidth()
                        .background(Color(0xFFFF4D67).copy(alpha=.08f))
                        .padding(horizontal=16.dp,vertical=9.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Text(
                        it,
                        color=Color(0xFFFF8A9A),
                        fontSize=10.sp,
                        modifier=Modifier.weight(1f)
                    )
                    TextButton(onClick={refresh++}) {
                        Text("دوباره",fontSize=10.sp,color=Color.White)
                    }
                }
            }
        }

        if(filtered.isNotEmpty()) {
            item {
                DiscoverTitle(
                    "ترند الان",
                    "چیزهایی که امروز بیشتر دیده می‌شن"
                )
            }
            item {
                DiscoverHeroRow(
                    items=filtered.take(10),
                    repository=repository,
                    onMedia=onMedia
                )
            }
        }

        if(data.popularMovies.isNotEmpty()) {
            item { DiscoverTitle("فیلم‌ها","انتخاب‌های قوی برای امشب") }
            item {
                DiscoverPosterRow(
                    items=data.popularMovies.take(18),
                    repository=repository,
                    onMedia=onMedia
                )
            }
        }

        if(data.popularTv.isNotEmpty()) {
            item { DiscoverTitle("سریال‌ها","چیزهایی که ارزش ادامه دادن دارن") }
            item {
                DiscoverPosterRow(
                    items=data.popularTv.take(18),
                    repository=repository,
                    onMedia=onMedia
                )
            }
        }

        if(creators.isNotEmpty()) {
            item {
                DiscoverTitle(
                    "Creatorهای پیشنهادی",
                    "آدم‌ها و رسانه‌هایی که محتوای خوبی می‌سازن"
                )
            }
            item {
                LazyRow(
                    contentPadding=PaddingValues(horizontal=16.dp),
                    horizontalArrangement=Arrangement.spacedBy(10.dp)
                ) {
                    items(creators.take(12),key={it.id}) { channel ->
                        Surface(
                            color=FqSurface,
                            shape=RoundedCornerShape(18.dp),
                            border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
                            modifier=Modifier.width(190.dp)
                                .clickable {
                                    onCreator(
                                        Creator(
                                            name=channel.name,
                                            handle="@"+channel.slug,
                                            followers=channel.followers.toString(),
                                            bio=channel.bio,
                                            verified=channel.verified,
                                            id=channel.id,
                                            entityType="channel",
                                            avatarUrl=channel.avatarUrl,
                                            coverUrl=channel.coverUrl
                                        )
                                    )
                                }
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                RemoteImage(
                                    channel.avatarUrl.takeIf(String::isNotBlank),
                                    Modifier.size(46.dp).clip(CircleShape)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment=Alignment.CenterVertically) {
                                        Text(
                                            channel.name,
                                            fontSize=11.sp,
                                            fontWeight=FontWeight.Bold,
                                            maxLines=1,
                                            overflow=TextOverflow.Ellipsis
                                        )
                                        if(channel.verified) {
                                            Spacer(Modifier.width(3.dp))
                                            Icon(
                                                Icons.Default.Verified,
                                                null,
                                                tint=Color(0xFF4AB7FF),
                                                modifier=Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        compactDiscoverCount(channel.followers)+" دنبال‌کننده",
                                        color=FqMuted,
                                        fontSize=8.sp,
                                        modifier=Modifier.padding(top=3.dp)
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
private fun DiscoverTitle(
    title:String,
    subtitle:String
) {
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal=16.dp)
            .padding(top=20.dp,bottom=9.dp)
    ) {
        Text(title,fontSize=19.sp,fontWeight=FontWeight.Black)
        Text(
            subtitle,
            color=FqMuted,
            fontSize=10.sp,
            modifier=Modifier.padding(top=2.dp)
        )
    }
}

@Composable
private fun DiscoverHeroRow(
    items:List<MediaItem>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(11.dp)
    ) {
        items(items,key={it.key}) { media ->
            Box(
                Modifier.width(286.dp)
                    .height(170.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(FqSurface)
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
                                Color.Black.copy(alpha=.22f)
                            )
                        )
                    )
                )
                Column(
                    Modifier.align(Alignment.BottomStart).padding(14.dp)
                ) {
                    Text(
                        media.title,
                        fontSize=18.sp,
                        fontWeight=FontWeight.Black,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment=Alignment.CenterVertically,
                        modifier=Modifier.padding(top=6.dp)
                    ) {
                        if(media.vote>0) {
                            Icon(
                                Icons.Default.Star,
                                null,
                                tint=FqGold,
                                modifier=Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(formatVote(media.vote),fontSize=9.sp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            listOf(
                                media.year,
                                if(media.type==MediaType.MOVIE)"فیلم" else "سریال"
                            ).filter(String::isNotBlank).joinToString(" • "),
                            color=Color.White.copy(alpha=.72f),
                            fontSize=9.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverPosterRow(
    items:List<MediaItem>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=16.dp),
        horizontalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        items(items,key={it.key}) { media ->
            Column(
                Modifier.width(122.dp)
                    .clickable { onMedia(media) }
            ) {
                Box(
                    Modifier.fillMaxWidth()
                        .height(178.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(FqSurface2)
                ) {
                    RemoteImage(
                        repository.poster(media.posterPath),
                        Modifier.fillMaxSize(),
                        ContentScale.Crop
                    )
                }
                Text(
                    media.title,
                    fontSize=10.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=7.dp)
                )
                Text(
                    media.year,
                    color=FqMuted,
                    fontSize=8.sp,
                    modifier=Modifier.padding(top=2.dp)
                )
            }
        }
    }
}

private fun compactDiscoverCount(value:Long):String = when {
    value>=1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
