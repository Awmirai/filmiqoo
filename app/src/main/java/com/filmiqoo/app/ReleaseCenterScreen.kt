package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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

@Composable
fun ReleaseCenterScreen(
    backend: BackendRepository,
    repository: TmdbRepository,
    onBack: () -> Unit,
    onMedia: (MediaItem) -> Unit
) {
    val repo=remember { ReleaseCenterRepository(backend) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var releases by remember { mutableStateOf<List<ReleaseCenterItem>>(emptyList()) }
    var filter by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        releases=runCatching { repo.releases() }
            .onFailure { error=it.message }
            .getOrDefault(emptyList())
        loading=false
    }

    val visible=releases.filter {
        when(filter) {
            1 -> it.media.type==MediaType.MOVIE
            2 -> it.media.type==MediaType.TV
            3 -> it.media.streamReady
            else -> true
        }
    }
    val featured=visible.firstOrNull { (it.daysAway ?: Int.MAX_VALUE)>=0 } ?: visible.firstOrNull()

    LazyColumn(
        Modifier.fillMaxSize().background(FqBg),
        contentPadding=PaddingValues(bottom=30.dp)
    ) {
        item {
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                if(featured!=null) {
                    RemoteImage(
                        repository.backdrop(featured.media.backdropPath ?: featured.media.posterPath),
                        Modifier.fillMaxSize(),
                        ContentScale.Crop
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha=.35f),Color.Transparent,Color.Black.copy(alpha=.75f),FqBg)
                        )
                    )
                )
                Row(
                    Modifier.fillMaxWidth().padding(9.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
                    Column(Modifier.weight(1f)) {
                        Text("Release Center",fontSize=21.sp,fontWeight=FontWeight.Black)
                        Text("تقویم انتشار فیلم و سریال",color=Color.White.copy(alpha=.65f),fontSize=8.sp)
                    }
                    IconButton(onClick={refresh++}){Icon(Icons.Default.Refresh,null)}
                }

                featured?.let { item ->
                    Column(
                        Modifier.align(Alignment.BottomStart).fillMaxWidth()
                            .padding(start=18.dp,end=18.dp,bottom=18.dp)
                    ) {
                        Surface(
                            color=FqGold,
                            contentColor=Color.Black,
                            shape=RoundedCornerShape(9.dp)
                        ) {
                            Text(
                                releaseTimingLabel(item.daysAway),
                                fontSize=8.sp,
                                fontWeight=FontWeight.Black,
                                modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp)
                            )
                        }
                        Text(
                            item.media.title,
                            fontSize=27.sp,
                            fontWeight=FontWeight.Black,
                            maxLines=2,
                            overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=8.dp)
                        )
                        Text(
                            listOf(
                                item.releaseDate,
                                if(item.media.type==MediaType.MOVIE)"فیلم" else "سریال",
                                if(item.media.vote>0)"★ "+formatVote(item.media.vote) else ""
                            ).filter(String::isNotBlank).joinToString(" • "),
                            color=FqMuted,
                            fontSize=8.sp,
                            modifier=Modifier.padding(top=5.dp)
                        )
                        Button(
                            onClick={onMedia(item.media)},
                            colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                            shape=RoundedCornerShape(13.dp),
                            modifier=Modifier.padding(top=12.dp)
                        ) {
                            Icon(Icons.Default.Info,null,tint=Color.Black)
                            Spacer(Modifier.width(5.dp))
                            Text("جزئیات",color=Color.Black)
                        }
                    }
                }
            }
        }

        item {
            LazyRow(
                contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                item { PremiumChip(Icons.Default.AllInclusive,"همه",filter==0){filter=0} }
                item { PremiumChip(Icons.Default.Movie,"فیلم",filter==1){filter=1} }
                item { PremiumChip(Icons.Default.Tv,"سریال",filter==2){filter=2} }
                item { PremiumChip(Icons.Default.PlayCircle,"قابل پخش",filter==3){filter=3} }
            }
        }

        if(loading) {
            item { LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth()) }
        }

        error?.let {
            item {
                Text(
                    it,
                    color=FqDanger,
                    fontSize=9.sp,
                    modifier=Modifier.fillMaxWidth().padding(14.dp)
                )
            }
        }

        if(!loading && visible.isEmpty()) {
            item {
                PremiumEmptyState(
                    Icons.Default.EventBusy,
                    "انتشاری پیدا نشد",
                    "Release Center از TMDB Backend تغذیه می‌شود؛ اتصال سرور را بررسی کن."
                )
            }
        } else {
            val soon=visible.filter { (it.daysAway ?: 999) in 0..7 }
            val later=visible.filter { (it.daysAway ?: 999) > 7 }
            val recent=visible.filter { (it.daysAway ?: -999) < 0 }

            if(soon.isNotEmpty()) {
                item { PremiumSectionHeader("این هفته","انتشارهای نزدیک",Icons.Default.Event) }
                items(soon,key={it.media.key+"_"+it.releaseDate}) { item ->
                    ReleaseRow(item,repository,onMedia)
                }
            }

            if(later.isNotEmpty()) {
                item { PremiumSectionHeader("به‌زودی","بعد از این هفته",Icons.Default.CalendarMonth) }
                items(later,key={it.media.key+"_"+it.releaseDate}) { item ->
                    ReleaseRow(item,repository,onMedia)
                }
            }

            if(recent.isNotEmpty()) {
                item { PremiumSectionHeader("تازه منتشرشده","روزهای اخیر",Icons.Default.NewReleases) }
                items(recent,key={it.media.key+"_"+it.releaseDate}) { item ->
                    ReleaseRow(item,repository,onMedia)
                }
            }
        }
    }
}

@Composable
private fun ReleaseRow(
    item: ReleaseCenterItem,
    repository: TmdbRepository,
    onMedia: (MediaItem) -> Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(20.dp),
        modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=5.dp)
            .clickable { onMedia(item.media) }
    ) {
        Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
            RemoteImage(
                repository.poster(item.media.posterPath),
                Modifier.width(72.dp).height(104.dp).clip(RoundedCornerShape(14.dp)),
                ContentScale.Crop
            )
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(
                        item.media.title,
                        fontSize=12.sp,
                        fontWeight=FontWeight.Bold,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.weight(1f)
                    )
                    if(item.media.streamReady) {
                        Surface(color=FqGreen.copy(alpha=.15f),shape=RoundedCornerShape(7.dp)) {
                            Text(
                                item.media.quality.ifBlank{"Ready"},
                                color=FqGreen,
                                fontSize=6.sp,
                                modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp)
                            )
                        }
                    }
                }
                if(item.media.originalTitle.isNotBlank() && item.media.originalTitle!=item.media.title) {
                    Text(
                        item.media.originalTitle,
                        color=FqMuted,
                        fontSize=7.sp,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=2.dp)
                    )
                }
                Text(
                    item.releaseDate.ifBlank{"تاریخ نامشخص"},
                    color=FqGold,
                    fontSize=8.sp,
                    modifier=Modifier.padding(top=6.dp)
                )
                Text(
                    releaseTimingLabel(item.daysAway),
                    color=FqMuted,
                    fontSize=7.sp,
                    modifier=Modifier.padding(top=2.dp)
                )
                if(item.media.overview.isNotBlank()) {
                    Text(
                        item.media.overview,
                        color=Color.White.copy(alpha=.67f),
                        fontSize=7.sp,
                        lineHeight=12.sp,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=5.dp)
                    )
                }
            }
            Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
        }
    }
}

private fun releaseTimingLabel(days:Int?):String=when {
    days==null -> "به‌زودی"
    days==0 -> "امروز"
    days==1 -> "فردا"
    days in 2..7 -> days.toString()+" روز دیگه"
    days>7 -> days.toString()+" روز دیگه"
    days==-1 -> "دیروز منتشر شد"
    else -> kotlin.math.abs(days).toString()+" روز پیش منتشر شد"
}
