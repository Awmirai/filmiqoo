package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private enum class SearchTab { ALL, MEDIA, USERS, CHANNELS, REELS }

private sealed interface UniversalSearchLoad {
    data object Loading : UniversalSearchLoad
    data class Ready(val result: UniversalSearchResult) : UniversalSearchLoad
    data class Error(val message: String) : UniversalSearchLoad
}

@Composable
fun PremiumSearchScreen(
    repository: TmdbRepository,
    backend: BackendRepository,
    onBack: () -> Unit,
    onMedia: (MediaItem) -> Unit,
    onCreator: (Creator) -> Unit,
    onOpenReels: () -> Unit
) {
    val context=LocalContext.current
    val searchRepo=remember { UniversalSearchRepository(context.applicationContext,backend) }

    var query by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(SearchTab.ALL) }
    var state by remember { mutableStateOf<UniversalSearchLoad>(UniversalSearchLoad.Loading) }
    var history by remember { mutableStateOf(searchRepo.history()) }
    var mediaFilter by remember { mutableStateOf<MediaType?>(null) }

    BackHandler { onBack() }

    LaunchedEffect(query) {
        delay(if(query.isBlank())100 else 350)
        state=UniversalSearchLoad.Loading
        val q=query.trim()
        state=runCatching {
            UniversalSearchLoad.Ready(searchRepo.search(q))
        }.getOrElse { backendError ->
            val fallbackMedia=runCatching {
                if(q.isBlank()) repository.trending() else repository.search(q)
            }.getOrDefault(emptyList())
            if(fallbackMedia.isNotEmpty()) {
                UniversalSearchLoad.Ready(
                    UniversalSearchResult(
                        media=fallbackMedia,
                        users=emptyList(),
                        channels=emptyList(),
                        reels=emptyList()
                    )
                )
            } else {
                UniversalSearchLoad.Error(backendError.message ?: "جستجو در دسترس نیست")
            }
        }
        history=searchRepo.history()
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        SearchHeader(
            query=query,
            onQuery={query=it},
            onBack=onBack
        )

        SearchTabs(
            selected=tab,
            onSelected={tab=it}
        )

        if(query.isBlank() && history.isNotEmpty()) {
            RecentSearches(
                history=history,
                onSelect={query=it},
                onClear={
                    searchRepo.clearHistory()
                    history=emptyList()
                }
            )
        }

        Box(Modifier.weight(1f)) {
            when(val s=state) {
                UniversalSearchLoad.Loading -> {
                    FqLoadingState(
                        if(query.isBlank())
                            "داریم Discover رو آماده می‌کنیم..."
                        else
                            "در حال جستجو..."
                    )
                }

                is UniversalSearchLoad.Error -> {
                    PremiumEmptyState(
                        icon=Icons.Default.SearchOff,
                        title="نتیجه‌ای پیدا نشد",
                        body=s.message
                    )
                }

                is UniversalSearchLoad.Ready -> {
                    when(tab) {
                        SearchTab.ALL -> SearchAllContent(
                            result=s.result,
                            query=query,
                            repository=repository,
                            onMedia=onMedia,
                            onCreator=onCreator,
                            onOpenReels=onOpenReels,
                            onTab={tab=it}
                        )
                        SearchTab.MEDIA -> MediaSearchGrid(
                            media=s.result.media,
                            repository=repository,
                            filter=mediaFilter,
                            onFilter={mediaFilter=it},
                            onMedia=onMedia
                        )
                        SearchTab.USERS -> UserSearchList(s.result.users,onCreator)
                        SearchTab.CHANNELS -> ChannelSearchList(s.result.channels,onCreator)
                        SearchTab.REELS -> ReelSearchGrid(s.result.reels,onOpenReels,onCreator)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHeader(
    query:String,
    onQuery:(String)->Unit,
    onBack:()->Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF111620),
                        FqBg
                    )
                )
            )
            .statusBarsPadding()
            .padding(horizontal=FqDimens.Screen)
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(top=8.dp,bottom=8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            FqIconButton(
                icon=Icons.Default.ArrowBack,
                contentDescription="بازگشت",
                onClick=onBack
            )
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "جستجو و Discover",
                    style=MaterialTheme.typography.headlineSmall,
                    fontWeight=FontWeight.Black
                )
                Text(
                    "فیلم، سریال، کاربر، کانال و Reel",
                    color=FqMuted,
                    style=MaterialTheme.typography.bodySmall,
                    modifier=Modifier.padding(top=2.dp)
                )
            }
        }

        OutlinedTextField(
            value=query,
            onValueChange=onQuery,
            placeholder={
                Text(
                    "اسم فیلم، سریال یا @کاربر...",
                    color=FqMuted
                )
            },
            leadingIcon={
                Icon(
                    Icons.Default.Search,
                    null,
                    tint=FqGold
                )
            },
            trailingIcon={
                if(query.isNotBlank()) {
                    IconButton(onClick={onQuery("")}) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription="پاک کردن جستجو"
                        )
                    }
                }
            },
            singleLine=true,
            shape=RoundedCornerShape(18.dp),
            colors=OutlinedTextFieldDefaults.colors(
                focusedBorderColor=FqGold,
                unfocusedBorderColor=FqBorder,
                focusedContainerColor=FqSurface,
                unfocusedContainerColor=FqSurface
            ),
            modifier=Modifier.fillMaxWidth()
                .padding(bottom=12.dp)
        )
    }
}

@Composable
private fun SearchTabs(
    selected:SearchTab,
    onSelected:(SearchTab)->Unit
) {
    val items=listOf(
        SearchTab.ALL to "همه",
        SearchTab.MEDIA to "فیلم و سریال",
        SearchTab.USERS to "کاربران",
        SearchTab.CHANNELS to "کانال‌ها",
        SearchTab.REELS to "Reels"
    )
    LazyRow(
        contentPadding=PaddingValues(
            horizontal=FqDimens.Screen,
            vertical=8.dp
        ),
        horizontalArrangement=Arrangement.spacedBy(8.dp),
        modifier=Modifier.fillMaxWidth()
            .background(FqBg)
    ) {
        items(items,key={it.first.name}) { item ->
            PremiumChip(
                label=item.second,
                active=selected==item.first,
                onClick={onSelected(item.first)}
            )
        }
    }
}

@Composable
private fun RecentSearches(
    history: List<String>,
    onSelect: (String) -> Unit,
    onClear: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top=7.dp,bottom=4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=16.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Text("جستجوهای اخیر",fontSize=11.sp,fontWeight=FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick=onClear) {
                Text("پاک کردن",fontSize=11.sp,color=FqMuted)
            }
        }
        LazyRow(
            contentPadding=PaddingValues(horizontal=16.dp),
            horizontalArrangement=Arrangement.spacedBy(7.dp)
        ) {
            items(history) { value ->
                AssistChip(
                    onClick={onSelect(value)},
                    label={Text(value,fontSize=11.sp)},
                    leadingIcon={
                        Icon(Icons.Default.History,null,modifier=Modifier.size(14.dp))
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchAllContent(
    result: UniversalSearchResult,
    query: String,
    repository: TmdbRepository,
    onMedia: (MediaItem) -> Unit,
    onCreator: (Creator) -> Unit,
    onOpenReels: () -> Unit,
    onTab: (SearchTab) -> Unit
) {
    val empty=result.media.isEmpty() && result.users.isEmpty() &&
        result.channels.isEmpty() && result.reels.isEmpty()

    if(empty) {
        PremiumEmptyState(
            icon=if(query.isBlank())Icons.Default.Explore else Icons.Default.SearchOff,
            title=if(query.isBlank())"Discover هنوز خالیه" else "چیزی پیدا نشد",
            body=if(query.isBlank())
                "با اضافه‌شدن محتوا و Creatorها، پیشنهادهای ترند اینجا ظاهر می‌شن."
            else
                "عبارت دیگه‌ای امتحان کن یا اسم اصلی فیلم رو بنویس."
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding=PaddingValues(bottom=26.dp)
    ) {
        if(result.media.isNotEmpty()) {
            item {
                SearchSectionTitle(
                    title=if(query.isBlank())"ترند فیلم و سریال" else "فیلم و سریال",
                    count=result.media.size,
                    onMore={onTab(SearchTab.MEDIA)}
                )
            }
            item {
                LazyRow(
                    contentPadding=PaddingValues(horizontal=16.dp),
                    horizontalArrangement=Arrangement.spacedBy(10.dp)
                ) {
                    items(result.media.take(12),key={it.key}) { media ->
                        SearchPosterCard(media,repository) { onMedia(media) }
                    }
                }
            }
        }

        if(result.users.isNotEmpty()) {
            item {
                SearchSectionTitle("Creatorها و کاربران",result.users.size) {
                    onTab(SearchTab.USERS)
                }
            }
            item {
                LazyRow(
                    contentPadding=PaddingValues(horizontal=16.dp),
                    horizontalArrangement=Arrangement.spacedBy(12.dp)
                ) {
                    items(result.users.take(12),key={it.id}) { user ->
                        UserBubble(user) { onCreator(user.asCreator()) }
                    }
                }
            }
        }

        if(result.channels.isNotEmpty()) {
            item {
                SearchSectionTitle("کانال‌ها",result.channels.size) {
                    onTab(SearchTab.CHANNELS)
                }
            }
            items(result.channels.take(4),key={it.id}) { channel ->
                ChannelRow(channel) { onCreator(channel.asCreator()) }
            }
        }

        if(result.reels.isNotEmpty()) {
            item {
                SearchSectionTitle("Reels",result.reels.size) {
                    onTab(SearchTab.REELS)
                }
            }
            item {
                LazyRow(
                    contentPadding=PaddingValues(horizontal=16.dp),
                    horizontalArrangement=Arrangement.spacedBy(9.dp)
                ) {
                    items(result.reels.take(8),key={it.id}) { reel ->
                        ReelSearchCard(reel,onOpenReels) {
                            onCreator(reel.author.asCreator())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchSectionTitle(
    title: String,
    count: Int,
    onMore: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=20.dp,bottom=9.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Text(title,fontSize=16.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Surface(color=FqSurface2,shape=CircleShape) {
            Text(count.toString(),fontSize=11.sp,modifier=Modifier.padding(horizontal=7.dp,vertical=3.dp))
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick=onMore) {
            Text("همه",fontSize=11.sp)
            Icon(Icons.Default.ChevronLeft,null,modifier=Modifier.size(15.dp))
        }
    }
}

@Composable
private fun SearchPosterCard(
    media: MediaItem,
    repository: TmdbRepository,
    onClick: () -> Unit
) {
    Column(
        Modifier.width(132.dp).clickable { onClick() }
    ) {
        Box(
            Modifier.fillMaxWidth().height(194.dp).clip(RoundedCornerShape(18.dp))
        ) {
            RemoteImage(
                repository.poster(media.posterPath),
                Modifier.fillMaxSize(),
                ContentScale.Crop
            )
            if(media.streamReady) {
                Surface(
                    color=FqGreen.copy(alpha=.9f),
                    contentColor=Color.Black,
                    shape=RoundedCornerShape(7.dp),
                    modifier=Modifier.align(Alignment.TopStart).padding(6.dp)
                ) {
                    Text(
                        media.quality.ifBlank{"PLAY"},
                        fontSize=6.sp,
                        fontWeight=FontWeight.Black,
                        modifier=Modifier.padding(horizontal=5.dp,vertical=3.dp)
                    )
                }
            }
        }
        Text(
            media.title,
            fontSize=11.sp,
            fontWeight=FontWeight.Bold,
            maxLines=1,
            overflow=TextOverflow.Ellipsis,
            modifier=Modifier.padding(top=5.dp)
        )
        Text(
            listOf(
                media.year,
                if(media.type==MediaType.MOVIE)"فیلم" else "سریال",
                if(media.vote>0)"★ "+formatVote(media.vote) else ""
            ).filter(String::isNotBlank).joinToString(" • "),
            color=FqMuted,
            fontSize=11.sp,
            maxLines=1
        )
    }
}

@Composable
private fun MediaSearchGrid(
    media: List<MediaItem>,
    repository: TmdbRepository,
    filter: MediaType?,
    onFilter: (MediaType?) -> Unit,
    onMedia: (MediaItem) -> Unit
) {
    val visible=if(filter==null)media else media.filter { it.type==filter }

    Column(Modifier.fillMaxSize()) {
        LazyRow(
            contentPadding=PaddingValues(horizontal=16.dp,vertical=10.dp),
            horizontalArrangement=Arrangement.spacedBy(7.dp)
        ) {
            item { PremiumChip(label="همه",active=filter==null){onFilter(null)} }
            item { PremiumChip(Icons.Default.Movie,"فیلم",filter==MediaType.MOVIE){onFilter(MediaType.MOVIE)} }
            item { PremiumChip(Icons.Default.Tv,"سریال",filter==MediaType.TV){onFilter(MediaType.TV)} }
        }

        if(visible.isEmpty()) {
            PremiumEmptyState(
                icon=Icons.Default.MovieFilter,
                title="نتیجه‌ای در این دسته نیست",
                body="فیلتر رو عوض کن یا عبارت جستجو رو دقیق‌تر بنویس."
            )
        } else {
            LazyVerticalGrid(
                columns=GridCells.Fixed(3),
                contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=26.dp),
                horizontalArrangement=Arrangement.spacedBy(9.dp),
                verticalArrangement=Arrangement.spacedBy(14.dp),
                modifier=Modifier.fillMaxSize()
            ) {
                items(visible,key={it.key}) { item ->
                    SearchPosterCard(item,repository){onMedia(item)}
                }
            }
        }
    }
}

@Composable
private fun UserSearchList(
    users: List<SearchUser>,
    onCreator: (Creator) -> Unit
) {
    if(users.isEmpty()) {
        PremiumEmptyState(Icons.Default.PersonSearch,"کاربری پیدا نشد","نام کاربری یا نام نمایشی دیگری جستجو کن.")
        return
    }

    LazyColumn(
        contentPadding=PaddingValues(start=14.dp,end=14.dp,top=10.dp,bottom=24.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)
    ) {
        items(users,key={it.id}) { user ->
            UserRow(user){onCreator(user.asCreator())}
        }
    }
}

@Composable
private fun ChannelSearchList(
    channels: List<SearchChannel>,
    onCreator: (Creator) -> Unit
) {
    if(channels.isEmpty()) {
        PremiumEmptyState(Icons.Default.Campaign,"کانالی پیدا نشد","اسم یا آیدی کانال رو امتحان کن.")
        return
    }

    LazyColumn(
        contentPadding=PaddingValues(start=14.dp,end=14.dp,top=10.dp,bottom=24.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp)
    ) {
        items(channels,key={it.id}) { channel ->
            ChannelRow(channel){onCreator(channel.asCreator())}
        }
    }
}

@Composable
private fun ReelSearchGrid(
    reels: List<SearchReel>,
    onOpenReels: () -> Unit,
    onCreator: (Creator) -> Unit
) {
    if(reels.isEmpty()) {
        PremiumEmptyState(Icons.Default.VideoLibrary,"Reel پیدا نشد","Caption، Creator یا اسم فیلم رو جستجو کن.")
        return
    }

    LazyVerticalGrid(
        columns=GridCells.Fixed(2),
        contentPadding=PaddingValues(12.dp),
        horizontalArrangement=Arrangement.spacedBy(9.dp),
        verticalArrangement=Arrangement.spacedBy(9.dp)
    ) {
        items(reels,key={it.id}) { reel ->
            ReelSearchCard(reel,onOpenReels){onCreator(reel.author.asCreator())}
        }
    }
}

@Composable
private fun UserBubble(
    user: SearchUser,
    onClick: () -> Unit
) {
    Column(
        Modifier.width(82.dp).clickable { onClick() },
        horizontalAlignment=Alignment.CenterHorizontally
    ) {
        Box {
            RemoteImage(
                user.avatarUrl.takeIf(String::isNotBlank),
                Modifier.size(68.dp).clip(CircleShape),
                ContentScale.Crop
            )
            if(user.verified) {
                Icon(
                    Icons.Default.Verified,
                    null,
                    tint=Color(0xFF4AB7FF),
                    modifier=Modifier.size(17.dp).align(Alignment.BottomEnd)
                        .background(FqBg,CircleShape)
                )
            }
        }
        Text(
            user.displayName,
            fontSize=11.sp,
            fontWeight=FontWeight.Bold,
            maxLines=1,
            overflow=TextOverflow.Ellipsis,
            modifier=Modifier.padding(top=5.dp)
        )
        Text(
            "@"+user.username,
            color=FqMuted,
            fontSize=11.sp,
            maxLines=1,
            overflow=TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun UserRow(
    user: SearchUser,
    onClick: () -> Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            RemoteImage(
                user.avatarUrl.takeIf(String::isNotBlank),
                Modifier.size(54.dp).clip(CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(user.displayName,fontSize=12.sp,fontWeight=FontWeight.Bold)
                    if(user.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(14.dp))
                    }
                }
                Text("@"+user.username,color=FqMuted,fontSize=11.sp)
                if(user.bio.isNotBlank()) {
                    Text(
                        user.bio,
                        color=Color.White.copy(alpha=.72f),
                        fontSize=11.sp,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=3.dp)
                    )
                }
            }
            Column(horizontalAlignment=Alignment.End) {
                Text(compactSearchCount(user.followers),fontSize=12.sp,fontWeight=FontWeight.Bold)
                Text("دنبال‌کننده",color=FqMuted,fontSize=6.sp)
            }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: SearchChannel,
    onClick: () -> Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(18.dp),
        modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp)
            .clickable { onClick() }
    ) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            RemoteImage(
                channel.avatarUrl.takeIf(String::isNotBlank),
                Modifier.size(54.dp).clip(RoundedCornerShape(15.dp))
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(channel.name,fontSize=12.sp,fontWeight=FontWeight.Bold)
                    if(channel.verified) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(14.dp))
                    }
                }
                Text("@"+channel.slug,color=FqMuted,fontSize=11.sp)
                Text(
                    listOf(
                        compactSearchCount(channel.followers)+" دنبال‌کننده",
                        channel.posts.toString()+" پست",
                        channel.reels.toString()+" Reel"
                    ).joinToString(" • "),
                    color=FqMuted,
                    fontSize=11.sp,
                    modifier=Modifier.padding(top=3.dp)
                )
            }
            Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
        }
    }
}

@Composable
private fun ReelSearchCard(
    reel: SearchReel,
    onOpen: () -> Unit,
    onCreator: () -> Unit
) {
    Column(
        Modifier.width(180.dp).clip(RoundedCornerShape(18.dp))
            .background(FqSurface).clickable { onOpen() }
    ) {
        Box(Modifier.fillMaxWidth().height(235.dp)) {
            RemoteImage(
                reel.coverUrl.takeIf(String::isNotBlank),
                Modifier.fillMaxSize(),
                ContentScale.Crop
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent,Color.Black.copy(alpha=.78f))
                    )
                )
            )
            if(reel.spoiler) {
                Surface(
                    color=FqDanger.copy(alpha=.88f),
                    shape=RoundedCornerShape(8.dp),
                    modifier=Modifier.align(Alignment.TopStart).padding(7.dp)
                ) {
                    Text("Spoiler",fontSize=6.sp,modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp))
                }
            }
            Row(
                Modifier.align(Alignment.BottomStart).padding(9.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Icon(Icons.Default.PlayArrow,null,tint=Color.White,modifier=Modifier.size(17.dp))
                Text(
                    compactSearchCount(reel.views),
                    color=Color.White,
                    fontSize=11.sp
                )
            }
        }

        Column(Modifier.padding(9.dp)) {
            Text(
                reel.caption.ifBlank { reel.mediaTitle ?: "Reel" },
                fontSize=11.sp,
                maxLines=2,
                overflow=TextOverflow.Ellipsis
            )
            Row(
                Modifier.padding(top=6.dp).clickable { onCreator() },
                verticalAlignment=Alignment.CenterVertically
            ) {
                RemoteImage(
                    reel.author.avatarUrl.takeIf(String::isNotBlank),
                    Modifier.size(22.dp).clip(CircleShape)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    "@"+reel.author.username,
                    color=FqMuted,
                    fontSize=11.sp,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis
                )
            }
        }
    }
}
