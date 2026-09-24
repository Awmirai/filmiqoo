package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
fun SeriesCalendarScreen(
    backend:BackendRepository,
    repository:TmdbRepository,
    onBack:()->Unit,
    onMedia:(MediaItem)->Unit
) {
    val alerts=remember { SeriesAlertsRepository(backend) }
    var days by remember { mutableIntStateOf(60) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<SeriesCalendarItem>>(emptyList()) }

    BackHandler { onBack() }

    LaunchedEffect(days,refresh) {
        loading=true
        error=null
        runCatching { alerts.calendar(days) }
            .onSuccess { items=it }
            .onFailure { error=it.message ?: "خطا در دریافت تقویم سریال‌ها" }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(
                    listOf(Color(0xFF111A2B),Color(0xFF2D220A),FqBg)
                )
            )
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom=15.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
                    Column(Modifier.weight(1f)) {
                        Text("تقویم سریال‌های من",fontSize=22.sp,fontWeight=FontWeight.Black)
                        Text(
                            "قسمت‌های جدید و نسخه‌های آماده تماشا",
                            color=FqMuted,fontSize=8.sp
                        )
                    }
                    IconButton(onClick={refresh++}){Icon(Icons.Default.Refresh,null)}
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal=14.dp),
                    horizontalArrangement=Arrangement.spacedBy(7.dp)
                ) {
                    listOf(30,60,90).forEach { value ->
                        FilterChip(
                            selected=days==value,
                            onClick={days=value},
                            label={Text(value.toString()+" روز",fontSize=8.sp)}
                        )
                    }
                }
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
                modifier=Modifier.fillMaxWidth().padding(12.dp)
            )
        }

        if(!loading && items.isEmpty()) {
            PremiumEmptyState(
                icon=Icons.Default.EventAvailable,
                title="تقویم هنوز خالیه",
                body="از صفحه سریال‌ها دکمه «دنبال‌کردن سریال» رو بزن تا قسمت‌های آینده اینجا بیاد."
            )
        } else {
            val grouped=items.groupBy { it.airDate }
            LazyColumn(
                contentPadding=PaddingValues(horizontal=12.dp,vertical=10.dp),
                verticalArrangement=Arrangement.spacedBy(9.dp),
                modifier=Modifier.fillMaxSize()
            ) {
                grouped.forEach { (date,episodes) ->
                    item(key="date_"+date) {
                        Row(
                            Modifier.fillMaxWidth().padding(top=5.dp,bottom=1.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CalendarMonth,null,tint=FqGold,modifier=Modifier.size(17.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                formatSeriesCalendarDate(date),
                                fontSize=12.sp,
                                fontWeight=FontWeight.Bold
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                episodes.size.toString()+" قسمت",
                                color=FqMuted,
                                fontSize=7.sp
                            )
                        }
                    }

                    items(episodes,key={it.episodeId}) { episode ->
                        SeriesCalendarCard(
                            item=episode,
                            repository=repository,
                            onClick={onMedia(episode.media)}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesCalendarCard(
    item:SeriesCalendarItem,
    repository:TmdbRepository,
    onClick:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(20.dp),
        modifier=Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Box(
                Modifier.width(116.dp).height(72.dp).clip(RoundedCornerShape(13.dp))
            ) {
                RemoteImage(
                    item.stillUrl ?: repository.backdrop(item.media.backdropPath),
                    Modifier.fillMaxSize(),
                    ContentScale.Crop
                )
                Surface(
                    color=Color.Black.copy(alpha=.72f),
                    shape=RoundedCornerShape(7.dp),
                    modifier=Modifier.align(Alignment.TopStart).padding(5.dp)
                ) {
                    Text(
                        item.episodeLabel,
                        color=FqGold,
                        fontSize=7.sp,
                        fontWeight=FontWeight.Bold,
                        modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp)
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    item.media.title,
                    fontSize=11.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis
                )
                Text(
                    item.episodeName.ifBlank{"قسمت "+item.episodeNumber},
                    color=Color.White.copy(alpha=.82f),
                    fontSize=8.sp,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=3.dp)
                )

                Row(
                    Modifier.padding(top=6.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Surface(
                        color=if(item.streamReady)FqGreen.copy(alpha=.12f) else FqSurface2,
                        shape=RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal=7.dp,vertical=4.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Icon(
                                if(item.streamReady)Icons.Default.PlayCircle else Icons.Default.Schedule,
                                null,
                                tint=if(item.streamReady)FqGreen else FqMuted,
                                modifier=Modifier.size(13.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if(item.streamReady)"آماده تماشا" else "در انتظار انتشار",
                                color=if(item.streamReady)FqGreen else FqMuted,
                                fontSize=6.sp
                            )
                        }
                    }
                    if(item.runtimeMinutes>0) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            item.runtimeMinutes.toString()+" دقیقه",
                            color=FqMuted,fontSize=7.sp
                        )
                    }
                }
            }

            Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
        }
    }
}

private fun formatSeriesCalendarDate(value:String):String {
    if(value.isBlank()) return "—"
    return value
}
