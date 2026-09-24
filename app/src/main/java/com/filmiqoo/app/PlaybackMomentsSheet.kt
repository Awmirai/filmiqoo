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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackMomentsSheet(
    backend:BackendRepository,
    mediaVersionId:String,
    positionMs:Long,
    onSeekTo:(Long)->Unit,
    onDismiss:()->Unit
) {
    val repo=remember { PlaybackMomentsRepository(backend) }
    val scope=rememberCoroutineScope()
    val anchor=remember(mediaVersionId,positionMs) { positionMs }

    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var moments by remember { mutableStateOf<List<PlaybackMoment>>(emptyList()) }
    var body by remember { mutableStateOf("") }
    var spoiler by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mediaVersionId,anchor,refresh) {
        loading=true
        error=null
        runCatching { repo.around(mediaVersionId,anchor) }
            .onSuccess { moments=it }
            .onFailure { error=it.message }
        loading=false
    }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth()
                .fillMaxHeight(.78f)
                .padding(bottom=18.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal=16.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(44.dp)
                        .background(FqGold.copy(alpha=.13f),RoundedCornerShape(13.dp)),
                    contentAlignment=Alignment.Center
                ) {
                    Icon(Icons.Default.Forum,null,tint=FqGold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Moments",fontSize=20.sp,fontWeight=FontWeight.Black)
                    Text(
                        "واکنش‌ها و کامنت‌های نزدیک "+formatMomentTime(anchor),
                        color=FqMuted,
                        fontSize=8.sp
                    )
                }
                Surface(
                    color=FqSurface2,
                    shape=RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "±15s",
                        color=FqGold,
                        fontSize=7.sp,
                        modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp)
                    )
                }
            }

            LazyRow(
                contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                items(listOf("🔥","😱","😂","❤️","👏","🤯")) { reaction ->
                    Surface(
                        color=FqSurface2,
                        shape=CircleShape,
                        modifier=Modifier.size(44.dp)
                            .clickable(enabled=!sending) {
                                sending=true
                                scope.launch {
                                    runCatching {
                                        repo.create(
                                            mediaVersionId=mediaVersionId,
                                            positionMs=anchor,
                                            reaction=reaction
                                        )
                                    }.onSuccess {
                                        refresh++
                                    }.onFailure {
                                        error=it.message
                                    }
                                    sending=false
                                }
                            }
                    ) {
                        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                            Text(reaction,fontSize=20.sp)
                        }
                    }
                }
            }

            Surface(
                color=FqSurface2,
                shape=RoundedCornerShape(18.dp),
                modifier=Modifier.fillMaxWidth()
                    .padding(horizontal=14.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    OutlinedTextField(
                        value=body,
                        onValueChange={body=it.take(1200)},
                        placeholder={Text("نظرت درباره همین لحظه...")},
                        minLines=2,
                        maxLines=4,
                        modifier=Modifier.fillMaxWidth()
                    )

                    Row(
                        Modifier.fillMaxWidth().padding(top=7.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected=spoiler,
                            onClick={spoiler=!spoiler},
                            label={Text("Spoiler",fontSize=7.sp)},
                            leadingIcon={
                                Icon(
                                    Icons.Default.VisibilityOff,
                                    null,
                                    modifier=Modifier.size(14.dp)
                                )
                            }
                        )
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled=body.trim().isNotEmpty() && !sending,
                            onClick={
                                sending=true
                                scope.launch {
                                    runCatching {
                                        repo.create(
                                            mediaVersionId=mediaVersionId,
                                            positionMs=anchor,
                                            body=body,
                                            spoiler=spoiler
                                        )
                                    }.onSuccess {
                                        body=""
                                        spoiler=false
                                        refresh++
                                    }.onFailure {
                                        error=it.message
                                    }
                                    sending=false
                                }
                            },
                            colors=ButtonDefaults.buttonColors(containerColor=FqGold)
                        ) {
                            if(sending) {
                                CircularProgressIndicator(
                                    color=Color.Black,
                                    strokeWidth=2.dp,
                                    modifier=Modifier.size(16.dp)
                                )
                            } else {
                                Icon(Icons.Default.Send,null,tint=Color.Black)
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("ارسال",color=Color.Black,fontSize=8.sp)
                        }
                    }
                }
            }

            if(loading) {
                LinearProgressIndicator(
                    color=FqGold,
                    modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                )
            }

            error?.let {
                Text(
                    it,
                    color=FqDanger,
                    fontSize=8.sp,
                    modifier=Modifier.padding(horizontal=16.dp,vertical=8.dp)
                )
            }

            if(!loading && moments.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment=Alignment.Center
                ) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.ModeComment,
                            null,
                            tint=FqMuted,
                            modifier=Modifier.size(38.dp)
                        )
                        Text(
                            "هنوز کسی روی این لحظه چیزی نگفته.",
                            color=FqMuted,
                            fontSize=9.sp,
                            modifier=Modifier.padding(top=8.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding=PaddingValues(horizontal=14.dp,vertical=10.dp),
                    verticalArrangement=Arrangement.spacedBy(7.dp),
                    modifier=Modifier.weight(1f)
                ) {
                    items(moments,key={it.id}) { moment ->
                        PlaybackMomentCard(
                            moment=moment,
                            anchor=anchor,
                            onSeek={
                                onSeekTo(moment.positionMs)
                                onDismiss()
                            },
                            onLike={
                                scope.launch {
                                    runCatching { repo.toggleLike(moment.id) }
                                        .onSuccess { refresh++ }
                                        .onFailure { error=it.message }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackMomentCard(
    moment:PlaybackMoment,
    anchor:Long,
    onSeek:()->Unit,
    onLike:()->Unit
) {
    var revealed by remember(moment.id) { mutableStateOf(!moment.spoiler) }

    Surface(
        color=FqSurface2,
        shape=RoundedCornerShape(17.dp),
        modifier=Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(11.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                RemoteImage(
                    moment.author.avatarUrl.takeIf(String::isNotBlank),
                    Modifier.size(38.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(
                            moment.author.displayName,
                            fontSize=9.sp,
                            fontWeight=FontWeight.Bold,
                            maxLines=1,
                            overflow=TextOverflow.Ellipsis
                        )
                        if(moment.author.verified) {
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
                        "@"+moment.author.username,
                        color=FqMuted,
                        fontSize=7.sp
                    )
                }

                Surface(
                    color=FqBg,
                    shape=RoundedCornerShape(9.dp),
                    modifier=Modifier.clickable { onSeek() }
                ) {
                    Text(
                        formatMomentTime(moment.positionMs),
                        color=FqGold,
                        fontSize=7.sp,
                        fontWeight=FontWeight.Bold,
                        modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp)
                    )
                }
            }

            if(moment.reaction.isNotBlank()) {
                Text(
                    moment.reaction,
                    fontSize=28.sp,
                    modifier=Modifier.padding(top=7.dp)
                )
            }

            if(moment.spoiler && !revealed) {
                Surface(
                    color=FqDanger.copy(alpha=.09f),
                    shape=RoundedCornerShape(11.dp),
                    modifier=Modifier.fillMaxWidth()
                        .padding(top=7.dp)
                        .clickable { revealed=true }
                ) {
                    Row(
                        Modifier.padding(9.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.VisibilityOff,
                            null,
                            tint=FqDanger,
                            modifier=Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Spoiler Shield • برای نمایش لمس کن",
                            color=FqDanger,
                            fontSize=7.sp
                        )
                    }
                }
            } else if(moment.body.isNotBlank()) {
                Text(
                    moment.body,
                    fontSize=9.sp,
                    lineHeight=15.sp,
                    modifier=Modifier.padding(top=7.dp)
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top=4.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                TextButton(
                    onClick=onLike,
                    contentPadding=PaddingValues(horizontal=3.dp,vertical=2.dp)
                ) {
                    Icon(
                        if(moment.liked)Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        null,
                        tint=if(moment.liked)FqDanger else FqMuted,
                        modifier=Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(moment.likes.toString(),fontSize=7.sp)
                }
                Spacer(Modifier.weight(1f))
                val delta=abs(moment.positionMs-anchor)
                if(delta>0) {
                    Text(
                        "فاصله "+formatMomentDelta(delta),
                        color=FqMuted,
                        fontSize=6.sp
                    )
                }
            }
        }
    }
}

private fun formatMomentTime(ms:Long):String {
    val total=(ms/1000L).coerceAtLeast(0L)
    val h=total/3600L
    val m=(total%3600L)/60L
    val s=total%60L
    return if(h>0) {
        "%02d:%02d:%02d".format(h,m,s)
    } else {
        "%02d:%02d".format(m,s)
    }
}

private fun formatMomentDelta(ms:Long):String {
    val sec=(ms/1000L).coerceAtLeast(0L)
    return if(sec<60) sec.toString()+" ثانیه"
    else (sec/60).toString()+" دقیقه"
}
