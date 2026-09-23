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
import kotlinx.coroutines.launch

@Composable
fun WatchHistoryScreen(
    backend: BackendRepository,
    repository: TmdbRepository,
    onBack: () -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
    onMedia: (MediaItem) -> Unit
) {
    val repo=remember { HistoryRepository(backend) }
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<WatchHistoryItem>>(emptyList()) }
    var filter by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        runCatching { repo.history() }
            .onSuccess { items=it }
            .onFailure { error=it.message }
        loading=false
    }

    val visible=items.filter {
        when(filter) {
            1 -> !it.completed
            2 -> it.completed
            else -> true
        }
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=7.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
            Column(Modifier.weight(1f)) {
                Text("تاریخچه تماشا",fontSize=22.sp,fontWeight=FontWeight.Black)
                Text(items.size.toString()+" مورد اخیر",color=FqMuted,fontSize=8.sp)
            }
            if(items.isNotEmpty()) {
                TextButton(onClick={confirmClear=true}) {
                    Text("پاک کردن همه",color=FqDanger,fontSize=8.sp)
                }
            }
            IconButton(onClick={refresh++}){Icon(Icons.Default.Refresh,null)}
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp),
            horizontalArrangement=Arrangement.spacedBy(7.dp)
        ) {
            PremiumChip(Icons.Default.AllInclusive,"همه",filter==0){filter=0}
            PremiumChip(Icons.Default.PlayCircle,"نیمه‌کاره",filter==1){filter=1}
            PremiumChip(Icons.Default.CheckCircle,"تمام‌شده",filter==2){filter=2}
        }

        if(loading) LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        error?.let {
            Text(it,color=FqDanger,fontSize=9.sp,modifier=Modifier.padding(12.dp))
        }

        if(!loading && visible.isEmpty()) {
            PremiumEmptyState(
                Icons.Default.History,
                "تاریخچه خالیه",
                "هر چیزی که تماشا کنی با موقعیت دقیق اینجا ذخیره می‌شه."
            )
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding=PaddingValues(12.dp),
                verticalArrangement=Arrangement.spacedBy(9.dp)
            ) {
                items(visible,key={it.target.mediaVersionId}) { item ->
                    HistoryCard(
                        item=item,
                        repository=repository,
                        onPlay={onPlay(item.target)},
                        onMedia={onMedia(item.media)},
                        onRemove={
                            scope.launch {
                                runCatching { repo.remove(item.target.mediaVersionId) }
                                    .onSuccess { refresh++ }
                                    .onFailure { error=it.message }
                            }
                        }
                    )
                }
            }
        }
    }

    if(confirmClear) {
        AlertDialog(
            onDismissRequest={confirmClear=false},
            icon={Icon(Icons.Default.DeleteSweep,null,tint=FqDanger)},
            title={Text("پاک‌کردن تاریخچه؟")},
            text={Text("هم تاریخچه و هم Continue Watching فعلی پاک می‌شن.")},
            confirmButton={
                TextButton(onClick={
                    confirmClear=false
                    scope.launch {
                        runCatching { repo.clear() }
                            .onSuccess { refresh++ }
                            .onFailure { error=it.message }
                    }
                }) { Text("پاک کن",color=FqDanger) }
            },
            dismissButton={
                TextButton(onClick={confirmClear=false}){Text("لغو")}
            }
        )
    }
}

@Composable
private fun HistoryCard(
    item: WatchHistoryItem,
    repository: TmdbRepository,
    onPlay: () -> Unit,
    onMedia: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(20.dp),
        modifier=Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Box(
                    Modifier.width(132.dp).height(82.dp).clip(RoundedCornerShape(14.dp))
                        .clickable { onPlay() }
                ) {
                    RemoteImage(
                        repository.backdrop(item.media.backdropPath ?: item.media.posterPath),
                        Modifier.fillMaxSize(),
                        ContentScale.Crop
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.7f)))
                        )
                    )
                    Icon(
                        if(item.completed)Icons.Default.Replay else Icons.Default.PlayCircle,
                        null,
                        tint=Color.White,
                        modifier=Modifier.size(38.dp).align(Alignment.Center)
                    )
                    if(item.completed) {
                        Surface(
                            color=FqGreen.copy(alpha=.9f),
                            contentColor=Color.Black,
                            shape=RoundedCornerShape(7.dp),
                            modifier=Modifier.align(Alignment.TopStart).padding(6.dp)
                        ) {
                            Text("کامل",fontSize=6.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(horizontal=5.dp,vertical=3.dp))
                        }
                    }
                }

                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f).clickable { onMedia() }) {
                    Text(
                        item.media.title,
                        fontSize=12.sp,
                        fontWeight=FontWeight.Bold,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis
                    )
                    if(item.episodeLabel.isNotBlank()) {
                        Text(item.episodeLabel,color=FqGold,fontSize=8.sp,modifier=Modifier.padding(top=3.dp))
                    }
                    Text(
                        if(item.completed)"تماشا کامل شده" else ((item.progress*100).toInt()).toString()+"٪ دیده شده",
                        color=FqMuted,
                        fontSize=7.sp,
                        modifier=Modifier.padding(top=4.dp)
                    )
                    if(!item.completed) {
                        LinearProgressIndicator(
                            progress={item.progress},
                            color=FqGold,
                            trackColor=FqSurface3,
                            modifier=Modifier.fillMaxWidth().padding(top=6.dp).height(4.dp)
                        )
                    }
                }

                IconButton(onClick=onRemove) {
                    Icon(Icons.Default.DeleteOutline,null,tint=FqMuted)
                }
            }

            if(!item.completed) {
                Button(
                    onClick=onPlay,
                    colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                    shape=RoundedCornerShape(12.dp),
                    modifier=Modifier.fillMaxWidth().padding(start=10.dp,end=10.dp,bottom=10.dp)
                ) {
                    Icon(Icons.Default.PlayArrow,null,tint=Color.Black)
                    Spacer(Modifier.width(5.dp))
                    Text("ادامه از همان‌جا",color=Color.Black)
                }
            }
        }
    }
}
