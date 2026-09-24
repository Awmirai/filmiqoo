package com.filmiqoo.app

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchPartyQueueSheet(
    partyId:String,
    backend:BackendRepository,
    partyRepo:WatchPartyRepository,
    repository:TmdbRepository,
    canHostControl:Boolean,
    myUserId:String?,
    onDismiss:()->Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val searchRepo=remember { UniversalSearchRepository(context.applicationContext,backend) }

    var queue by remember { mutableStateOf<List<WatchPartyQueueItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busyItem by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }

    suspend fun refreshQueue() {
        runCatching { partyRepo.queue(partyId) }
            .onSuccess {
                queue=it
                error=null
            }
            .onFailure { error=it.message }
    }

    LaunchedEffect(partyId) {
        loading=true
        refreshQueue()
        loading=false
    }

    LaunchedEffect(query,showSearch) {
        if(!showSearch || query.trim().length<2) {
            results=emptyList()
            return@LaunchedEffect
        }
        delay(300)
        searching=true
        runCatching { searchRepo.search(query.trim()).media }
            .onSuccess { results=it.take(20) }
            .onFailure { error=it.message }
        searching=false
    }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start=14.dp,end=14.dp,bottom=28.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("بعدی‌ها",fontSize=21.sp,fontWeight=FontWeight.Black)
                    Text(
                        "پیشنهاد بده، رأی بده و با Host عنوان بعدی رو انتخاب کن.",
                        color=FqMuted,fontSize=8.sp
                    )
                }
                FilledTonalIconButton(onClick={showSearch=!showSearch}) {
                    Icon(
                        if(showSearch)Icons.Default.Close else Icons.Default.PlaylistAdd,
                        null
                    )
                }
            }

            if(showSearch) {
                OutlinedTextField(
                    value=query,
                    onValueChange={query=it},
                    placeholder={Text("فیلم یا سریال برای Queue...")},
                    leadingIcon={Icon(Icons.Default.Search,null)},
                    singleLine=true,
                    shape=RoundedCornerShape(16.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                )

                if(searching) {
                    LinearProgressIndicator(
                        color=FqGold,
                        modifier=Modifier.fillMaxWidth().padding(top=6.dp)
                    )
                }

                if(results.isNotEmpty()) {
                    LazyColumn(
                        modifier=Modifier.heightIn(max=250.dp).padding(top=7.dp),
                        verticalArrangement=Arrangement.spacedBy(6.dp)
                    ) {
                        items(results,key={it.key}) { media ->
                            Surface(
                                color=FqSurface2,
                                shape=RoundedCornerShape(14.dp),
                                modifier=Modifier.fillMaxWidth().clickable {
                                    val mediaId=media.backendId
                                    if(mediaId.isNullOrBlank()) {
                                        error="این عنوان هنوز به Catalog واقعی Filmiqoo متصل نیست."
                                    } else {
                                        busyItem="add:"+mediaId
                                        scope.launch {
                                            runCatching { partyRepo.addQueueItem(partyId,mediaId) }
                                                .onSuccess {
                                                    query=""
                                                    results=emptyList()
                                                    showSearch=false
                                                    refreshQueue()
                                                }
                                                .onFailure { error=it.message }
                                            busyItem=null
                                        }
                                    }
                                }
                            ) {
                                Row(
                                    Modifier.padding(8.dp),
                                    verticalAlignment=Alignment.CenterVertically
                                ) {
                                    RemoteImage(
                                        repository.poster(media.posterPath),
                                        Modifier.width(42.dp).height(60.dp).clip(RoundedCornerShape(8.dp)),
                                        ContentScale.Crop
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            media.title,
                                            fontSize=9.sp,
                                            fontWeight=FontWeight.Bold,
                                            maxLines=1,
                                            overflow=TextOverflow.Ellipsis
                                        )
                                        Text(
                                            listOf(
                                                media.year,
                                                if(media.type==MediaType.MOVIE)"فیلم" else "سریال"
                                            ).filter(String::isNotBlank).joinToString(" • "),
                                            color=FqMuted,fontSize=7.sp
                                        )
                                    }
                                    Icon(Icons.Default.AddCircle,null,tint=FqGold)
                                }
                            }
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

            if(!loading && queue.isEmpty()) {
                PremiumEmptyState(
                    Icons.Default.PlaylistPlay,
                    "Queue خالیه",
                    "اولین فیلم یا سریال بعدی رو پیشنهاد بده.",
                    "پیشنهاد عنوان"
                ) { showSearch=true }
            } else {
                LazyColumn(
                    modifier=Modifier.heightIn(max=480.dp).padding(top=10.dp),
                    verticalArrangement=Arrangement.spacedBy(8.dp)
                ) {
                    items(queue,key={it.id}) { item ->
                        WatchPartyQueueRow(
                            item=item,
                            repository=repository,
                            busy=busyItem==item.id,
                            canHostControl=canHostControl,
                            canRemove=canHostControl || item.suggestedBy.id==myUserId,
                            onVote={
                                busyItem=item.id
                                scope.launch {
                                    runCatching { partyRepo.voteQueueItem(partyId,item.id) }
                                        .onSuccess { refreshQueue() }
                                        .onFailure { error=it.message }
                                    busyItem=null
                                }
                            },
                            onPlay={
                                busyItem=item.id
                                scope.launch {
                                    runCatching { partyRepo.playQueueItem(partyId,item.id) }
                                        .onSuccess {
                                            refreshQueue()
                                            onDismiss()
                                        }
                                        .onFailure { error=it.message }
                                    busyItem=null
                                }
                            },
                            onRemove={
                                busyItem=item.id
                                scope.launch {
                                    runCatching { partyRepo.removeQueueItem(partyId,item.id) }
                                        .onSuccess { refreshQueue() }
                                        .onFailure { error=it.message }
                                    busyItem=null
                                }
                            }
                        )
                    }
                }
            }

            error?.let {
                Text(
                    it,
                    color=FqDanger,
                    fontSize=8.sp,
                    modifier=Modifier.fillMaxWidth().padding(top=9.dp)
                )
            }
        }
    }
}

@Composable
private fun WatchPartyQueueRow(
    item:WatchPartyQueueItem,
    repository:TmdbRepository,
    busy:Boolean,
    canHostControl:Boolean,
    canRemove:Boolean,
    onVote:()->Unit,
    onPlay:()->Unit,
    onRemove:()->Unit
) {
    Surface(
        color=if(item.status=="playing")FqGold.copy(alpha=.08f) else FqSurface2,
        shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(10.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            RemoteImage(
                repository.poster(item.media.posterPath),
                Modifier.width(58.dp).height(82.dp).clip(RoundedCornerShape(11.dp)),
                ContentScale.Crop
            )
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(
                        item.media.title,
                        fontSize=10.sp,
                        fontWeight=FontWeight.Bold,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.weight(1f)
                    )
                    if(item.status=="playing") {
                        Surface(
                            color=FqDanger,
                            shape=RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "NOW",
                                fontSize=6.sp,
                                fontWeight=FontWeight.Black,
                                modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp)
                            )
                        }
                    }
                }
                Text(
                    "پیشنهاد @"+item.suggestedBy.username,
                    color=FqMuted,fontSize=7.sp,modifier=Modifier.padding(top=3.dp)
                )
                Row(
                    Modifier.padding(top=7.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        enabled=!busy && item.status=="queued",
                        onClick=onVote,
                        contentPadding=PaddingValues(horizontal=9.dp,vertical=4.dp)
                    ) {
                        Icon(
                            if(item.voted)Icons.Default.ThumbUp else Icons.Default.ThumbUpOffAlt,
                            null,
                            tint=if(item.voted)FqGold else FqMuted,
                            modifier=Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(item.votes.toString(),fontSize=7.sp)
                    }

                    if(canHostControl && item.status=="queued") {
                        Spacer(Modifier.width(6.dp))
                        Button(
                            enabled=!busy,
                            onClick=onPlay,
                            colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                            contentPadding=PaddingValues(horizontal=9.dp,vertical=4.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow,null,tint=Color.Black,modifier=Modifier.size(15.dp))
                            Spacer(Modifier.width(3.dp))
                            Text("پخش بعدی",color=Color.Black,fontSize=7.sp)
                        }
                    }
                }
            }

            if(canRemove && item.status=="queued") {
                IconButton(enabled=!busy,onClick=onRemove) {
                    Icon(Icons.Default.RemoveCircleOutline,null,tint=FqDanger)
                }
            } else if(busy) {
                CircularProgressIndicator(
                    color=FqGold,
                    strokeWidth=2.dp,
                    modifier=Modifier.size(18.dp)
                )
            }
        }
    }
}
