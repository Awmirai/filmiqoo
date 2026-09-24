package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import kotlinx.coroutines.launch

@Composable
fun SocialCollectionsScreen(
    backend:BackendRepository,
    repository:TmdbRepository,
    loggedIn:Boolean,
    initialCollectionId:String?=null,
    onBack:()->Unit,
    onMedia:(MediaItem)->Unit,
    onCreator:(Creator)->Unit,
    onRequireAuth:()->Unit
) {
    val repo=remember { SocialCollectionsRepository(backend) }
    val scope=rememberCoroutineScope()

    var tab by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var discover by remember { mutableStateOf<List<SocialCollection>>(emptyList()) }
    var following by remember { mutableStateOf<List<SocialCollection>>(emptyList()) }
    var followingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var detail by remember { mutableStateOf<SocialCollectionDetail?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var followBusy by remember { mutableStateOf<Set<String>>(emptySet()) }

    BackHandler {
        if(detail!=null) detail=null else onBack()
    }

    LaunchedEffect(refresh,loggedIn) {
        loading=true
        error=null
        runCatching { repo.discover() }
            .onSuccess { discover=it }
            .onFailure { error=it.message }

        if(loggedIn) {
            runCatching { repo.following() }
                .onSuccess {
                    following=it
                    followingIds=it.map { c->c.id }.toSet()
                }
                .onFailure { if(error==null) error=it.message }
        } else {
            following=emptyList()
            followingIds=emptySet()
        }
        loading=false
    }

    LaunchedEffect(initialCollectionId) {
        val id=initialCollectionId ?: return@LaunchedEffect
        loading=true
        runCatching { repo.detail(id) }
            .onSuccess { detail=it }
            .onFailure { error=it.message }
        loading=false
    }

    fun toggleFollow(collection:SocialCollection) {
        if(!loggedIn) {
            onRequireAuth()
            return
        }
        if(collection.id in followBusy) return
        followBusy=followBusy+collection.id
        scope.launch {
            runCatching { repo.toggleFollow(collection.id) }
                .onSuccess { nowFollowing->
                    followingIds=if(nowFollowing)
                        followingIds+collection.id
                    else
                        followingIds-collection.id

                    detail=detail?.takeIf { it.summary.id==collection.id }?.let {
                        it.copy(
                            summary=it.summary.copy(
                                following=nowFollowing,
                                followers=(it.summary.followers + if(nowFollowing)1 else -1)
                                    .coerceAtLeast(0)
                            )
                        )
                    } ?: detail
                    refresh++
                }
                .onFailure { error=it.message }
            followBusy=followBusy-collection.id
        }
    }

    val selected=detail
    if(selected!=null) {
        SocialCollectionDetailScreen(
            detail=selected.copy(
                summary=selected.summary.copy(
                    following=selected.summary.id in followingIds
                )
            ),
            repository=repository,
            followBusy=selected.summary.id in followBusy,
            onBack={detail=null},
            onFollow={toggleFollow(selected.summary)},
            onCreator={onCreator(selected.summary.owner.asCreator())},
            onMedia=onMedia
        )
        return
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.linearGradient(
                    listOf(Color(0xFF172238),Color(0xFF362708),FqBg)
                )
            )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=8.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,null) }
                Column(Modifier.weight(1f)) {
                    Text("Community Lists",fontSize=23.sp,fontWeight=FontWeight.Black)
                    Text("Collectionهای عمومی فیلم‌بازها و Creatorها",color=FqMuted,fontSize=8.sp)
                }
                IconButton(onClick={refresh++}) { Icon(Icons.Default.Refresh,null) }
            }
        }

        TabRow(
            selectedTabIndex=tab,
            containerColor=FqBg,
            contentColor=FqGold
        ) {
            Tab(
                selected=tab==0,
                onClick={tab=0},
                text={Text("کشف",fontSize=9.sp)}
            )
            Tab(
                selected=tab==1,
                onClick={
                    if(!loggedIn) onRequireAuth() else tab=1
                },
                text={Text("دنبال‌شده‌ها",fontSize=9.sp)}
            )
        }

        if(loading) {
            LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        }

        error?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=8.sp,
                modifier=Modifier.fillMaxWidth()
                    .background(FqDanger.copy(alpha=.08f))
                    .padding(10.dp)
            )
        }

        val items=if(tab==0)discover else following
        if(!loading && items.isEmpty()) {
            PremiumEmptyState(
                icon=Icons.Default.CollectionsBookmark,
                title=if(tab==0)"Collection عمومی هنوز کمه" else "Collectionی رو دنبال نکردی",
                body=if(tab==0)
                    "Collectionهای Public کاربران و Creatorها اینجا ظاهر می‌شن."
                else
                    "از تب کشف، لیست‌های خوب رو Follow کن تا آپدیت‌هاشون رو بگیری."
            )
        } else {
            LazyColumn(
                contentPadding=PaddingValues(12.dp),
                verticalArrangement=Arrangement.spacedBy(9.dp),
                modifier=Modifier.fillMaxSize()
            ) {
                items(items,key={it.id}) { collection ->
                    SocialCollectionCard(
                        collection=collection.copy(
                            following=collection.id in followingIds
                        ),
                        busy=collection.id in followBusy,
                        onClick={
                            scope.launch {
                                loading=true
                                runCatching { repo.detail(collection.id) }
                                    .onSuccess {
                                        detail=it.copy(
                                            summary=it.summary.copy(
                                                following=collection.id in followingIds
                                            )
                                        )
                                    }
                                    .onFailure { error=it.message }
                                loading=false
                            }
                        },
                        onFollow={toggleFollow(collection)},
                        onCreator={onCreator(collection.owner.asCreator())}
                    )
                }
            }
        }
    }
}

@Composable
private fun SocialCollectionCard(
    collection:SocialCollection,
    busy:Boolean,
    onClick:()->Unit,
    onFollow:()->Unit,
    onCreator:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(22.dp),
        modifier=Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(11.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(
                Modifier.size(86.dp).clip(RoundedCornerShape(18.dp))
                    .background(FqSurface2),
                contentAlignment=Alignment.Center
            ) {
                if(collection.posterUrl!=null) {
                    RemoteImage(
                        collection.posterUrl,
                        Modifier.fillMaxSize(),
                        ContentScale.Crop
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.25f)))
                }
                Text(collection.emoji,fontSize=29.sp)
            }

            Spacer(Modifier.width(11.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    collection.name,
                    fontSize=12.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis
                )
                if(collection.description.isNotBlank()) {
                    Text(
                        collection.description,
                        color=FqMuted,
                        fontSize=8.sp,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=3.dp)
                    )
                }

                Row(
                    Modifier.padding(top=7.dp).clickable { onCreator() },
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    RemoteImage(
                        collection.owner.avatarUrl.takeIf(String::isNotBlank),
                        Modifier.size(21.dp).clip(CircleShape)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        collection.owner.displayName,
                        fontSize=7.sp,
                        color=Color.White.copy(alpha=.8f)
                    )
                    if(collection.owner.verified) {
                        Spacer(Modifier.width(3.dp))
                        Icon(
                            Icons.Default.Verified,
                            null,
                            tint=Color(0xFF4AB7FF),
                            modifier=Modifier.size(11.dp)
                        )
                    }
                }

                Text(
                    collection.itemCount.toString()+" عنوان • "+
                        compactSocialCollectionCount(collection.followers)+" دنبال‌کننده",
                    color=FqMuted,
                    fontSize=7.sp,
                    modifier=Modifier.padding(top=5.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            FilledTonalIconButton(
                onClick=onFollow,
                enabled=!busy
            ) {
                if(busy) {
                    CircularProgressIndicator(
                        strokeWidth=2.dp,
                        color=FqGold,
                        modifier=Modifier.size(17.dp)
                    )
                } else {
                    Icon(
                        if(collection.following)Icons.Default.Check else Icons.Default.Add,
                        null,
                        tint=if(collection.following)FqGreen else FqGold
                    )
                }
            }
        }
    }
}

@Composable
private fun SocialCollectionDetailScreen(
    detail:SocialCollectionDetail,
    repository:TmdbRepository,
    followBusy:Boolean,
    onBack:()->Unit,
    onFollow:()->Unit,
    onCreator:()->Unit,
    onMedia:(MediaItem)->Unit
) {
    BackHandler { onBack() }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Box(
            Modifier.fillMaxWidth().height(300.dp).background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1A2944),Color(0xFF3A2908),FqBg)
                )
            )
        ) {
            detail.summary.posterUrl?.let {
                RemoteImage(
                    it,
                    Modifier.fillMaxWidth().height(230.dp),
                    ContentScale.Crop
                )
                Box(
                    Modifier.fillMaxWidth().height(230.dp).background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha=.18f),FqBg.copy(alpha=.9f))
                        )
                    )
                )
            }

            IconButton(
                onClick=onBack,
                modifier=Modifier.padding(8.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha=.38f))
            ) {
                Icon(Icons.Default.ArrowBack,null)
            }

            Column(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal=18.dp,bottom=18.dp)
            ) {
                Text(detail.summary.emoji,fontSize=38.sp)
                Text(
                    detail.summary.name,
                    fontSize=25.sp,
                    fontWeight=FontWeight.Black,
                    modifier=Modifier.padding(top=4.dp)
                )
                if(detail.summary.description.isNotBlank()) {
                    Text(
                        detail.summary.description,
                        color=Color.White.copy(alpha=.72f),
                        fontSize=9.sp,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=5.dp)
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(top=12.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Row(
                        Modifier.weight(1f).clickable { onCreator() },
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        RemoteImage(
                            detail.summary.owner.avatarUrl.takeIf(String::isNotBlank),
                            Modifier.size(34.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.width(7.dp))
                        Column {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(detail.summary.owner.displayName,fontSize=9.sp,fontWeight=FontWeight.Bold)
                                if(detail.summary.owner.verified) {
                                    Spacer(Modifier.width(3.dp))
                                    Icon(
                                        Icons.Default.Verified,
                                        null,
                                        tint=Color(0xFF4AB7FF),
                                        modifier=Modifier.size(12.dp)
                                    )
                                }
                            }
                            Text("@"+detail.summary.owner.username,color=FqMuted,fontSize=7.sp)
                        }
                    }

                    Button(
                        onClick=onFollow,
                        enabled=!followBusy,
                        colors=ButtonDefaults.buttonColors(
                            containerColor=if(detail.summary.following)FqSurface2 else FqGold,
                            contentColor=if(detail.summary.following)Color.White else Color.Black
                        ),
                        shape=RoundedCornerShape(13.dp)
                    ) {
                        if(followBusy) {
                            CircularProgressIndicator(
                                strokeWidth=2.dp,
                                modifier=Modifier.size(16.dp)
                            )
                        } else {
                            Icon(
                                if(detail.summary.following)Icons.Default.Check else Icons.Default.Add,
                                null,
                                modifier=Modifier.size(17.dp)
                            )
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(if(detail.summary.following)"دنبال می‌کنی" else "دنبال کردن",fontSize=8.sp)
                    }
                }

                Text(
                    detail.summary.itemCount.toString()+" عنوان • "+
                        compactSocialCollectionCount(detail.summary.followers)+" دنبال‌کننده",
                    color=FqGold,
                    fontSize=8.sp,
                    modifier=Modifier.padding(top=9.dp)
                )
            }
        }

        if(detail.items.isEmpty()) {
            PremiumEmptyState(
                Icons.Default.PlaylistAdd,
                "این Collection خالیه",
                "Curator هنوز عنوانی اضافه نکرده."
            )
        } else {
            LazyVerticalGrid(
                columns=GridCells.Fixed(3),
                contentPadding=PaddingValues(10.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp),
                modifier=Modifier.fillMaxSize()
            ) {
                gridItems(detail.items,key={it.key}) { media ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onMedia(media) }
                    ) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(.68f)
                                .clip(RoundedCornerShape(14.dp))
                        ) {
                            RemoteImage(
                                repository.poster(media.posterPath),
                                Modifier.fillMaxSize(),
                                ContentScale.Crop
                            )
                        }
                        Text(
                            media.title,
                            fontSize=8.sp,
                            fontWeight=FontWeight.Bold,
                            maxLines=1,
                            overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=5.dp)
                        )
                        Text(
                            listOf(
                                media.year,
                                if(media.type==MediaType.MOVIE)"فیلم" else "سریال"
                            ).filter(String::isNotBlank).joinToString(" • "),
                            color=FqMuted,
                            fontSize=6.sp
                        )
                    }
                }
            }
        }
    }
}

private fun compactSocialCollectionCount(value:Long):String=when {
    value>=1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
