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
import kotlinx.coroutines.launch

@Composable
fun ReleaseCenterScreen(
    backend: BackendRepository,
    repository: TmdbRepository,
    onBack: () -> Unit,
    onMedia: (MediaItem) -> Unit,
    onRequireAuth: () -> Unit
) {
    val repo=remember { ReleaseCenterRepository(backend) }
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var releases by remember { mutableStateOf<List<ReleaseCenterItem>>(emptyList()) }
    var reminderKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var reminderBusy by remember { mutableStateOf<Set<String>>(emptySet()) }
    var filter by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        releases=runCatching { repo.releases() }
            .onFailure { error=it.message }
            .getOrDefault(emptyList())
        reminderKeys=if(backend.session.isLoggedIn) {
            runCatching { repo.reminders().map { it.key }.toSet() }.getOrDefault(emptySet())
        } else emptySet()
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
                        Text("تقویم انتشار فیلم و سریال",color=Color.White.copy(alpha=.65f),fontSize=11.sp)
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
                                fontSize=11.sp,
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
                            fontSize=11.sp,
                            modifier=Modifier.padding(top=5.dp)
                        )
                        Row(
                            Modifier.padding(top=12.dp),
                            horizontalArrangement=Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick={onMedia(item.media)},
                                colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                                shape=RoundedCornerShape(13.dp)
                            ) {
                                Icon(Icons.Default.Info,null,tint=Color.Black)
                                Spacer(Modifier.width(5.dp))
                                Text("جزئیات",color=Color.Black)
                            }
                            if((item.daysAway ?: -1) >= 0) {
                                val reminderKey=repo.reminderKey(item)
                                val reminded=reminderKey in reminderKeys
                                OutlinedButton(
                                    enabled=reminderKey !in reminderBusy,
                                    onClick={
                                        if(!backend.session.isLoggedIn) {
                                            onRequireAuth()
                                        } else {
                                            reminderBusy=reminderBusy+reminderKey
                                            scope.launch {
                                                runCatching { repo.toggleReminder(item) }
                                                    .onSuccess { enabled->
                                                        reminderKeys=if(enabled)
                                                            reminderKeys+reminderKey
                                                        else
                                                            reminderKeys-reminderKey
                                                    }
                                                    .onFailure { error=it.message }
                                                reminderBusy=reminderBusy-reminderKey
                                            }
                                        }
                                    },
                                    shape=RoundedCornerShape(13.dp)
                                ) {
                                    if(reminderKey in reminderBusy) {
                                        CircularProgressIndicator(
                                            color=FqGold,
                                            strokeWidth=2.dp,
                                            modifier=Modifier.size(16.dp)
                                        )
                                    } else {
                                        Icon(
                                            if(reminded)Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                                            null,
                                            tint=if(reminded)FqGold else Color.White
                                        )
                                    }
                                    Spacer(Modifier.width(5.dp))
                                    Text(if(reminded)"یادم هست" else "یادم بنداز")
                                }
                            }
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
                    fontSize=11.sp,
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
                    ReleaseRow(
                        item=item,
                        repository=repository,
                        reminded=repo.reminderKey(item) in reminderKeys,
                        reminderBusy=repo.reminderKey(item) in reminderBusy,
                        onReminder={
                            val key=repo.reminderKey(item)
                            if(!backend.session.isLoggedIn) {
                                onRequireAuth()
                            } else {
                                reminderBusy=reminderBusy+key
                                scope.launch {
                                    runCatching { repo.toggleReminder(item) }
                                        .onSuccess { enabled->
                                            reminderKeys=if(enabled) reminderKeys+key else reminderKeys-key
                                        }
                                        .onFailure { error=it.message }
                                    reminderBusy=reminderBusy-key
                                }
                            }
                        },
                        onMedia=onMedia
                    )
                }
            }

            if(later.isNotEmpty()) {
                item { PremiumSectionHeader("به‌زودی","بعد از این هفته",Icons.Default.CalendarMonth) }
                items(later,key={it.media.key+"_"+it.releaseDate}) { item ->
                    ReleaseRow(
                        item=item,
                        repository=repository,
                        reminded=repo.reminderKey(item) in reminderKeys,
                        reminderBusy=repo.reminderKey(item) in reminderBusy,
                        onReminder={
                            val key=repo.reminderKey(item)
                            if(!backend.session.isLoggedIn) {
                                onRequireAuth()
                            } else {
                                reminderBusy=reminderBusy+key
                                scope.launch {
                                    runCatching { repo.toggleReminder(item) }
                                        .onSuccess { enabled->
                                            reminderKeys=if(enabled) reminderKeys+key else reminderKeys-key
                                        }
                                        .onFailure { error=it.message }
                                    reminderBusy=reminderBusy-key
                                }
                            }
                        },
                        onMedia=onMedia
                    )
                }
            }

            if(recent.isNotEmpty()) {
                item { PremiumSectionHeader("تازه منتشرشده","روزهای اخیر",Icons.Default.NewReleases) }
                items(recent,key={it.media.key+"_"+it.releaseDate}) { item ->
                    ReleaseRow(
                        item=item,
                        repository=repository,
                        reminded=repo.reminderKey(item) in reminderKeys,
                        reminderBusy=repo.reminderKey(item) in reminderBusy,
                        onReminder={
                            val key=repo.reminderKey(item)
                            if(!backend.session.isLoggedIn) {
                                onRequireAuth()
                            } else {
                                reminderBusy=reminderBusy+key
                                scope.launch {
                                    runCatching { repo.toggleReminder(item) }
                                        .onSuccess { enabled->
                                            reminderKeys=if(enabled) reminderKeys+key else reminderKeys-key
                                        }
                                        .onFailure { error=it.message }
                                    reminderBusy=reminderBusy-key
                                }
                            }
                        },
                        onMedia=onMedia
                    )
                }
            }
        }
    }
}

@Composable
private fun ReleaseRow(
    item: ReleaseCenterItem,
    repository: TmdbRepository,
    reminded:Boolean,
    reminderBusy:Boolean,
    onReminder:()->Unit,
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
                        fontSize=11.sp,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=2.dp)
                    )
                }
                Text(
                    item.releaseDate.ifBlank{"تاریخ نامشخص"},
                    color=FqGold,
                    fontSize=11.sp,
                    modifier=Modifier.padding(top=6.dp)
                )
                Text(
                    releaseTimingLabel(item.daysAway),
                    color=FqMuted,
                    fontSize=11.sp,
                    modifier=Modifier.padding(top=2.dp)
                )
                if(item.media.overview.isNotBlank()) {
                    Text(
                        item.media.overview,
                        color=Color.White.copy(alpha=.67f),
                        fontSize=11.sp,
                        lineHeight=12.sp,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=5.dp)
                    )
                }
            }
            if((item.daysAway ?: -1) >= 0) {
                IconButton(
                    onClick=onReminder,
                    enabled=!reminderBusy
                ) {
                    if(reminderBusy) {
                        CircularProgressIndicator(
                            color=FqGold,
                            strokeWidth=2.dp,
                            modifier=Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            if(reminded)Icons.Default.NotificationsActive else Icons.Default.NotificationsNone,
                            null,
                            tint=if(reminded)FqGold else FqMuted
                        )
                    }
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
