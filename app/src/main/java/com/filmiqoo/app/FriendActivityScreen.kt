package com.filmiqoo.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class FriendActivityFilter { ALL, WATCHING, POSTS, CLIPS, REVIEWS }

@Composable
fun FriendActivityScreen(
    backend:BackendRepository,
    repository:TmdbRepository,
    onBack:()->Unit,
    onMedia:(MediaItem)->Unit,
    onCreator:(Creator)->Unit
) {
    val repo=remember { FriendActivityRepository(backend) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<FriendActivityItem>>(emptyList()) }
    var filterName by rememberSaveable { mutableStateOf(FriendActivityFilter.ALL.name) }
    val filter=runCatching { FriendActivityFilter.valueOf(filterName) }
        .getOrDefault(FriendActivityFilter.ALL)

    val visibleItems=remember(items,filter) {
        when(filter) {
            FriendActivityFilter.ALL -> items
            FriendActivityFilter.WATCHING -> items.filter { it.type=="watching" }
            FriendActivityFilter.POSTS -> items.filter { it.type=="post" }
            FriendActivityFilter.CLIPS -> items.filter { it.type=="reel" }
            FriendActivityFilter.REVIEWS -> items.filter { it.type=="review" }
        }
    }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        runCatching { repo.feed() }
            .onSuccess { items=it }
            .onFailure { error=it.message }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=7.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
            Column(Modifier.weight(1f)) {
                Text("فعالیت دوستان",fontSize=22.sp,fontWeight=FontWeight.Black)
                Text(
                    "تماشا، پست، کلیپ و ریویوی افرادی که دنبال کردی",
                    color=FqMuted,fontSize=11.sp
                )
            }
            IconButton(onClick={refresh++}){Icon(Icons.Default.Refresh,null)}
        }

        LazyRow(
            contentPadding=PaddingValues(horizontal=12.dp),
            horizontalArrangement=Arrangement.spacedBy(7.dp)
        ) {
            listOf(
                FriendActivityFilter.ALL to "همه",
                FriendActivityFilter.WATCHING to "تماشا",
                FriendActivityFilter.POSTS to "پست",
                FriendActivityFilter.CLIPS to "کلیپ",
                FriendActivityFilter.REVIEWS to "ریویو"
            ).forEach { (item,label) ->
                item {
                    FilterChip(
                        selected=filter==item,
                        onClick={filterName=item.name},
                        label={Text(label,fontSize=10.sp)}
                    )
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
                fontSize=11.sp,
                modifier=Modifier.fillMaxWidth().padding(12.dp)
            )
        }

        if(!loading && visibleItems.isEmpty()) {
            PremiumEmptyState(
                Icons.Default.Diversity3,
                if(filter==FriendActivityFilter.ALL)"فعلاً فعالیتی نیست" else "در این فیلتر فعالیتی نیست",
                if(filter==FriendActivityFilter.ALL)
                    "وقتی افرادی که دنبال کردی چیزی ببینن یا محتوا منتشر کنن، اینجا ظاهر می‌شه."
                else
                    "فیلتر دیگه‌ای رو انتخاب کن یا بعداً دوباره بررسی کن."
            )
        } else {
            LazyColumn(
                contentPadding=PaddingValues(horizontal=12.dp,vertical=8.dp),
                verticalArrangement=Arrangement.spacedBy(9.dp),
                modifier=Modifier.fillMaxSize()
            ) {
                items(visibleItems,key={it.type+":"+it.entityId+":"+it.actor.id}) { item ->
                    FriendActivityCard(
                        item=item,
                        repository=repository,
                        onMedia={item.media?.let(onMedia)},
                        onCreator={
                            onCreator(
                                Creator(
                                    name=item.actor.displayName,
                                    handle="@"+item.actor.username,
                                    followers="",
                                    bio="",
                                    verified=item.actor.verified,
                                    id=item.actor.id,
                                    entityType="user",
                                    avatarUrl=item.actor.avatarUrl
                                )
                            )
                        }
                    )
                }
                item { Spacer(Modifier.height(22.dp)) }
            }
        }
    }
}

@Composable
private fun FriendActivityCard(
    item:FriendActivityItem,
    repository:TmdbRepository,
    onMedia:()->Unit,
    onCreator:()->Unit
) {
    var revealed by remember(item.entityId) { mutableStateOf(!item.spoiler) }

    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(20.dp),
        modifier=Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                RemoteImage(
                    item.actor.avatarUrl.takeIf(String::isNotBlank),
                    Modifier.size(46.dp).clip(CircleShape).clickable { onCreator() }
                )
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Text(
                            item.actor.displayName,
                            fontSize=12.sp,
                            fontWeight=FontWeight.Bold,
                            modifier=Modifier.clickable { onCreator() }
                        )
                        if(item.actor.verified) {
                            Spacer(Modifier.width(3.dp))
                            Icon(
                                Icons.Default.Verified,
                                null,
                                tint=Color(0xFF4AB7FF),
                                modifier=Modifier.size(13.dp)
                            )
                        }
                    }
                    Text(
                        activitySentence(item),
                        color=activityColor(item.type),
                        fontSize=11.sp,
                        modifier=Modifier.padding(top=2.dp)
                    )
                }
                Surface(
                    color=activityColor(item.type).copy(alpha=.12f),
                    shape=RoundedCornerShape(10.dp)
                ) {
                    Icon(
                        activityIcon(item.type),
                        null,
                        tint=activityColor(item.type),
                        modifier=Modifier.padding(7.dp).size(17.dp)
                    )
                }
            }

            if(item.spoiler && !revealed) {
                Surface(
                    color=FqDanger.copy(alpha=.09f),
                    shape=RoundedCornerShape(13.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                        .clickable { revealed=true }
                ) {
                    Row(Modifier.padding(11.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.VisibilityOff,null,tint=FqDanger)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "اسپویلر مخفی شده • برای نمایش لمس کن",
                            color=FqDanger,fontSize=11.sp
                        )
                    }
                }
            } else if(item.body.isNotBlank()) {
                Text(
                    item.body,
                    fontSize=11.sp,
                    lineHeight=15.sp,
                    maxLines=4,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=10.dp)
                )
            }

            item.media?.let { media ->
                Surface(
                    color=FqSurface2,
                    shape=RoundedCornerShape(15.dp),
                    modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                        .clickable { onMedia() }
                ) {
                    Row(
                        Modifier.padding(8.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        RemoteImage(
                            repository.poster(media.posterPath),
                            Modifier.width(46.dp).height(66.dp).clip(RoundedCornerShape(9.dp)),
                            ContentScale.Crop
                        )
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                media.title,
                                fontSize=11.sp,
                                fontWeight=FontWeight.Bold,
                                maxLines=1,
                                overflow=TextOverflow.Ellipsis
                            )
                            Text(
                                listOf(
                                    media.year,
                                    if(media.type==MediaType.MOVIE)"فیلم" else "سریال"
                                ).filter(String::isNotBlank).joinToString(" • "),
                                color=FqMuted,fontSize=11.sp,modifier=Modifier.padding(top=3.dp)
                            )
                        }
                        Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
                    }
                }
            }
        }
    }
}

private fun activitySentence(item:FriendActivityItem):String=when(item.type) {
    "watching" -> "الان داره «"+(item.media?.title ?: "یک عنوان")+"» رو می‌بینه"
    "reel" -> "یک کلیپ جدید منتشر کرد"
    "review" -> "یک ریویوی جدید نوشت"
    "post" -> "یک پست جدید منتشر کرد"
    else -> "فعالیت جدید"
}

private fun activityIcon(type:String)=when(type) {
    "watching" -> Icons.Default.PlayCircle
    "reel" -> Icons.Default.VideoLibrary
    "review" -> Icons.Default.StarRate
    "post" -> Icons.Default.DynamicFeed
    else -> Icons.Default.Bolt
}

private fun activityColor(type:String)=when(type) {
    "watching" -> FqGreen
    "reel" -> FqGold
    "review" -> Color(0xFFFFC857)
    "post" -> Color(0xFF6CA8FF)
    else -> FqMuted
}
