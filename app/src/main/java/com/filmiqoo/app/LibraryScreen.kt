package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import kotlinx.coroutines.launch

private enum class LibraryTab { FAVORITES, WATCHLIST, COLLECTIONS, SCENES }

@Composable
fun LibraryScreen(
    backend: BackendRepository,
    repository: TmdbRepository,
    onBack: () -> Unit,
    onMedia: (MediaItem) -> Unit,
    onPlay: (PlaybackTarget) -> Unit
) {
    val lib=remember { LibraryRepository(backend) }
    val sceneRepo=remember { SceneBookmarksRepository(backend) }
    val scope=rememberCoroutineScope()

    var tab by remember { mutableStateOf(LibraryTab.FAVORITES) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var watchlist by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var collections by remember { mutableStateOf<List<MediaCollection>>(emptyList()) }
    var sceneBookmarks by remember { mutableStateOf<List<SceneBookmark>>(emptyList()) }
    var activeCollection by remember { mutableStateOf<MediaCollectionDetail?>(null) }
    var showCreate by remember { mutableStateOf(false) }

    BackHandler {
        if(activeCollection!=null) activeCollection=null else onBack()
    }

    LaunchedEffect(refresh) {
        loading=true
        error=null

        runCatching { lib.favorites() }
            .onSuccess { favorites=it }
            .onFailure { error=it.message ?: "خطا در دریافت Favoriteها" }

        runCatching { lib.watchlist() }
            .onSuccess { watchlist=it }
            .onFailure { error=error ?: it.message ?: "خطا در دریافت Watchlist" }

        runCatching { lib.collections() }
            .onSuccess { collections=it }
            .onFailure { error=error ?: it.message ?: "خطا در دریافت Collectionها" }

        runCatching { sceneRepo.all() }
            .onSuccess { sceneBookmarks=it }
            .onFailure { error=error ?: it.message ?: "خطا در دریافت Scene Bookmarkها" }

        loading=false
    }

    if(activeCollection!=null) {
        CollectionDetailScreen(
            detail=activeCollection!!,
            repository=repository,
            onBack={activeCollection=null},
            onMedia=onMedia,
            onRemove={media->
                val collection=activeCollection ?: return@CollectionDetailScreen
                val id=media.backendId ?: return@CollectionDetailScreen
                scope.launch {
                    runCatching { lib.toggleCollectionItem(collection.summary.id,id) }
                        .onSuccess {
                            activeCollection=collection.copy(
                                summary=collection.summary.copy(
                                    itemCount=(collection.summary.itemCount-1).coerceAtLeast(0)
                                ),
                                items=collection.items.filterNot { it.backendId==id }
                            )
                            refresh++
                        }
                        .onFailure { error=it.message }
                }
            },
            onDelete={
                val collection=activeCollection ?: return@CollectionDetailScreen
                scope.launch {
                    runCatching { lib.deleteCollection(collection.summary.id) }
                        .onSuccess {
                            activeCollection=null
                            refresh++
                        }
                        .onFailure { error=it.message }
                }
            }
        )
        return
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        LibraryHeader(
            favoriteCount=favorites.size,
            watchlistCount=watchlist.size,
            collectionCount=collections.size,
            sceneCount=sceneBookmarks.size,
            onBack=onBack,
            onRefresh={refresh++}
        )

        TabRow(
            selectedTabIndex=tab.ordinal,
            containerColor=FqBg,
            contentColor=FqGold
        ) {
            listOf(
                LibraryTab.FAVORITES to "موردعلاقه‌ها",
                LibraryTab.WATCHLIST to "Watchlist",
                LibraryTab.COLLECTIONS to "Collectionها",
                LibraryTab.SCENES to "Sceneها"
            ).forEach { item ->
                Tab(
                    selected=tab==item.first,
                    onClick={tab=item.first},
                    text={Text(item.second,fontSize=11.sp)}
                )
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
                modifier=Modifier.fillMaxWidth()
                    .background(FqDanger.copy(alpha=.08f))
                    .padding(9.dp)
            )
        }

        when(tab) {
            LibraryTab.FAVORITES -> LibraryMediaGrid(
                items=favorites,
                repository=repository,
                emptyTitle="موردعلاقه‌ای نداری",
                emptyBody="از صفحه فیلم یا سریال، Heart رو بزن تا اینجا ذخیره بشه.",
                emptyIcon=Icons.Default.FavoriteBorder,
                onMedia=onMedia
            )

            LibraryTab.WATCHLIST -> LibraryMediaGrid(
                items=watchlist,
                repository=repository,
                emptyTitle="Watchlist خالیه",
                emptyBody="عنوان‌هایی که می‌خوای بعداً ببینی رو به Watchlist اضافه کن.",
                emptyIcon=Icons.Default.BookmarkBorder,
                onMedia=onMedia
            )

            LibraryTab.COLLECTIONS -> {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Collectionهای شخصی",fontSize=14.sp,fontWeight=FontWeight.Bold)
                            Text("لیست‌های اختصاصی خودت رو بساز.",color=FqMuted,fontSize=11.sp)
                        }
                        Button(
                            onClick={showCreate=true},
                            colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                            shape=RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add,null,tint=Color.Black)
                            Spacer(Modifier.width(4.dp))
                            Text("جدید",color=Color.Black,fontSize=11.sp)
                        }
                    }

                    if(!loading && collections.isEmpty()) {
                        PremiumEmptyState(
                            icon=Icons.Default.CollectionsBookmark,
                            title="هنوز Collection نداری",
                            body="مثلاً «فیلم‌های آخر هفته»، «بهترین‌های 2026» یا «Anime Favorites» بساز.",
                            action="ساخت Collection",
                            onAction={showCreate=true}
                        )
                    } else {
                        LazyColumn(
                            contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp),
                            verticalArrangement=Arrangement.spacedBy(8.dp)
                        ) {
                            items(collections,key={it.id}) { collection ->
                                CollectionCard(
                                    collection=collection,
                                    onClick={
                                        scope.launch {
                                            loading=true
                                            runCatching { lib.collection(collection.id) }
                                                .onSuccess { activeCollection=it }
                                                .onFailure { error=it.message }
                                            loading=false
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            LibraryTab.SCENES -> SceneBookmarksLibrary(
                items=sceneBookmarks,
                onPlay=onPlay,
                onDelete={bookmark->
                    scope.launch {
                        runCatching { sceneRepo.delete(bookmark.id) }
                            .onSuccess {
                                sceneBookmarks=sceneBookmarks.filterNot { it.id==bookmark.id }
                            }
                            .onFailure { error=it.message }
                    }
                }
            )
        }
    }

    if(showCreate) {
        CreateCollectionDialog(
            onDismiss={showCreate=false},
            onCreate={name,description,emoji,visibility->
                scope.launch {
                    runCatching {
                        lib.createCollection(name,description,emoji,visibility)
                    }.onSuccess {
                        showCreate=false
                        refresh++
                    }.onFailure {
                        error=it.message
                    }
                }
            }
        )
    }
}

@Composable
private fun LibraryHeader(
    favoriteCount:Int,
    watchlistCount:Int,
    collectionCount:Int,
    sceneCount:Int,
    onBack:()->Unit,
    onRefresh:()->Unit
) {
    Box(
        Modifier.fillMaxWidth().height(215.dp).background(
            Brush.verticalGradient(
                listOf(Color(0xFF182033),Color(0xFF251C09),FqBg)
            )
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
            Spacer(Modifier.weight(1f))
            IconButton(onClick=onRefresh){Icon(Icons.Default.Refresh,null)}
        }

        Column(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start=18.dp,end=18.dp,bottom=18.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(Icons.Default.VideoLibrary,null,tint=FqGold,modifier=Modifier.size(34.dp))
                Spacer(Modifier.width(9.dp))
                Column {
                    Text("Library من",fontSize=27.sp,fontWeight=FontWeight.Black)
                    Text("همه چیزهایی که برای خودت نگه داشتی",color=FqMuted,fontSize=11.sp)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top=14.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                PremiumStat(favoriteCount.toString(),"Favorite",Modifier.weight(1f))
                PremiumStat(watchlistCount.toString(),"Watchlist",Modifier.weight(1f))
                PremiumStat(collectionCount.toString(),"Collection",Modifier.weight(1f))
                PremiumStat(sceneCount.toString(),"Scene",Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LibraryMediaGrid(
    items:List<MediaItem>,
    repository:TmdbRepository,
    emptyTitle:String,
    emptyBody:String,
    emptyIcon:androidx.compose.ui.graphics.vector.ImageVector,
    onMedia:(MediaItem)->Unit
) {
    if(items.isEmpty()) {
        PremiumEmptyState(emptyIcon,emptyTitle,emptyBody)
        return
    }

    LazyVerticalGrid(
        columns=GridCells.Fixed(3),
        contentPadding=PaddingValues(10.dp),
        horizontalArrangement=Arrangement.spacedBy(8.dp),
        verticalArrangement=Arrangement.spacedBy(13.dp),
        modifier=Modifier.fillMaxSize()
    ) {
        items(items,key={it.key}) { media ->
            Column(
                Modifier.fillMaxWidth().clickable { onMedia(media) }
            ) {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(.68f).clip(RoundedCornerShape(14.dp))
                ) {
                    RemoteImage(
                        repository.poster(media.posterPath),
                        Modifier.fillMaxSize(),
                        ContentScale.Crop
                    )
                    if(media.vote>0) {
                        Surface(
                            color=Color.Black.copy(alpha=.72f),
                            shape=RoundedCornerShape(7.dp),
                            modifier=Modifier.align(Alignment.TopEnd).padding(5.dp)
                        ) {
                            Text(
                                "★ "+String.format(java.util.Locale.US,"%.1f",media.vote),
                                color=FqGold,
                                fontSize=6.sp,
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
                        if(media.type==MediaType.MOVIE)"فیلم" else "سریال"
                    ).filter(String::isNotBlank).joinToString(" • "),
                    color=FqMuted,
                    fontSize=6.sp
                )
            }
        }
    }
}

@Composable
private fun CollectionCard(
    collection:MediaCollection,
    onClick:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(20.dp),
        modifier=Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Row(Modifier.padding(11.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(
                Modifier.size(74.dp).clip(RoundedCornerShape(16.dp))
                    .background(FqSurface2),
                contentAlignment=Alignment.Center
            ) {
                if(collection.posterUrl!=null) {
                    RemoteImage(
                        collection.posterUrl,
                        Modifier.fillMaxSize(),
                        ContentScale.Crop
                    )
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.27f)))
                    Text(collection.emoji,fontSize=24.sp)
                } else {
                    Text(collection.emoji,fontSize=29.sp)
                }
            }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Text(collection.name,fontSize=12.sp,fontWeight=FontWeight.Bold)
                if(collection.description.isNotBlank()) {
                    Text(
                        collection.description,
                        color=FqMuted,
                        fontSize=11.sp,
                        maxLines=1,
                        overflow=TextOverflow.Ellipsis,
                        modifier=Modifier.padding(top=3.dp)
                    )
                }
                Row(Modifier.padding(top=6.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(
                        if(collection.visibility=="private")Icons.Default.Lock else Icons.Default.Public,
                        null,tint=FqMuted,modifier=Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        collection.itemCount.toString()+" عنوان • "+
                            when(collection.visibility) {
                                "public" -> "عمومی"
                                "unlisted" -> "Unlisted"
                                else -> "خصوصی"
                            },
                        color=FqMuted,
                        fontSize=11.sp
                    )
                }
            }
            Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
        }
    }
}

@Composable
private fun CollectionDetailScreen(
    detail:MediaCollectionDetail,
    repository:TmdbRepository,
    onBack:()->Unit,
    onMedia:(MediaItem)->Unit,
    onRemove:(MediaItem)->Unit,
    onDelete:()->Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Box(
            Modifier.fillMaxWidth().height(220.dp).background(
                Brush.linearGradient(
                    listOf(Color(0xFF181F30),Color(0xFF3A2A08),FqBg)
                )
            )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
                Spacer(Modifier.weight(1f))
                IconButton(onClick={confirmDelete=true}) {
                    Icon(Icons.Default.DeleteOutline,null,tint=FqDanger)
                }
            }

            Row(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start=18.dp,end=18.dp,bottom=18.dp),
                verticalAlignment=Alignment.Bottom
            ) {
                Text(detail.summary.emoji,fontSize=50.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(detail.summary.name,fontSize=25.sp,fontWeight=FontWeight.Black)
                    if(detail.summary.description.isNotBlank()) {
                        Text(
                            detail.summary.description,
                            color=FqMuted,
                            fontSize=11.sp,
                            maxLines=2,
                            overflow=TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        detail.items.size.toString()+" عنوان",
                        color=FqGold,
                        fontSize=11.sp,
                        modifier=Modifier.padding(top=5.dp)
                    )
                }
            }
        }

        if(detail.items.isEmpty()) {
            PremiumEmptyState(
                Icons.Default.PlaylistAdd,
                "Collection خالیه",
                "از صفحه هر فیلم یا سریال می‌تونی مستقیم به این Collection اضافه‌اش کنی."
            )
        } else {
            LazyColumn(
                contentPadding=PaddingValues(12.dp),
                verticalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                items(detail.items,key={it.key}) { media ->
                    Surface(
                        color=FqSurface,
                        shape=RoundedCornerShape(18.dp),
                        modifier=Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.clickable { onMedia(media) }.padding(10.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            RemoteImage(
                                repository.poster(media.posterPath),
                                Modifier.width(66.dp).height(94.dp).clip(RoundedCornerShape(12.dp)),
                                ContentScale.Crop
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(media.title,fontSize=11.sp,fontWeight=FontWeight.Bold)
                                Text(
                                    listOf(media.year,if(media.type==MediaType.MOVIE)"فیلم" else "سریال")
                                        .filter(String::isNotBlank).joinToString(" • "),
                                    color=FqMuted,fontSize=11.sp,modifier=Modifier.padding(top=3.dp)
                                )
                                if(media.overview.isNotBlank()) {
                                    Text(
                                        media.overview,
                                        color=Color.White.copy(alpha=.67f),
                                        fontSize=11.sp,
                                        maxLines=2,
                                        overflow=TextOverflow.Ellipsis,
                                        modifier=Modifier.padding(top=5.dp)
                                    )
                                }
                            }
                            IconButton(onClick={onRemove(media)}) {
                                Icon(Icons.Default.RemoveCircleOutline,null,tint=FqDanger)
                            }
                        }
                    }
                }
            }
        }
    }

    if(confirmDelete) {
        AlertDialog(
            onDismissRequest={confirmDelete=false},
            icon={Icon(Icons.Default.DeleteForever,null,tint=FqDanger)},
            title={Text("حذف Collection؟")},
            text={Text("خود Collection حذف می‌شه؛ فیلم‌ها و سریال‌ها از Library اصلی حذف نمی‌شن.")},
            confirmButton={
                TextButton(onClick={
                    confirmDelete=false
                    onDelete()
                }) { Text("حذف",color=FqDanger) }
            },
            dismissButton={
                TextButton(onClick={confirmDelete=false}){Text("لغو")}
            }
        )
    }
}

@Composable
private fun CreateCollectionDialog(
    onDismiss:()->Unit,
    onCreate:(String,String,String,String)->Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("🎬") }
    var visibility by remember { mutableStateOf("private") }

    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("Collection جدید")},
        text={
            Column {
                OutlinedTextField(
                    value=name,
                    onValueChange={name=it.take(80)},
                    label={Text("نام")},
                    singleLine=true,
                    modifier=Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value=description,
                    onValueChange={description=it.take(500)},
                    label={Text("توضیح")},
                    minLines=2,
                    maxLines=4,
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                )
                OutlinedTextField(
                    value=emoji,
                    onValueChange={emoji=it.take(8)},
                    label={Text("Emoji")},
                    singleLine=true,
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                )
                Row(
                    Modifier.fillMaxWidth().padding(top=10.dp),
                    horizontalArrangement=Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected=visibility=="private",
                        onClick={visibility="private"},
                        label={Text("خصوصی",fontSize=11.sp)}
                    )
                    FilterChip(
                        selected=visibility=="public",
                        onClick={visibility="public"},
                        label={Text("عمومی",fontSize=11.sp)}
                    )
                    FilterChip(
                        selected=visibility=="unlisted",
                        onClick={visibility="unlisted"},
                        label={Text("Unlisted",fontSize=11.sp)}
                    )
                }
            }
        },
        confirmButton={
            Button(
                onClick={onCreate(name.trim(),description.trim(),emoji.ifBlank{"🎬"},visibility)},
                enabled=name.trim().isNotEmpty(),
                colors=ButtonDefaults.buttonColors(containerColor=FqGold)
            ) { Text("ساخت",color=Color.Black) }
        },
        dismissButton={TextButton(onClick=onDismiss){Text("لغو")}}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionPickerSheet(
    backend:BackendRepository,
    media:MediaItem,
    onDismiss:()->Unit,
    onMessage:(String)->Unit
) {
    val lib=remember { LibraryRepository(backend) }
    val scope=rememberCoroutineScope()
    var collections by remember { mutableStateOf<List<MediaCollection>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { lib.collections() }
            .onSuccess { collections=it }
            .onFailure { onMessage(it.message ?: "خطا در دریافت Collectionها") }
        loading=false
    }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom=26.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal=18.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("افزودن به Collection",fontSize=18.sp,fontWeight=FontWeight.Bold)
                    Text(media.title,color=FqMuted,fontSize=11.sp)
                }
                IconButton(onClick={showCreate=true}) {
                    Icon(Icons.Default.CreateNewFolder,null,tint=FqGold)
                }
            }

            if(loading) {
                LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth().padding(top=10.dp))
            } else if(collections.isEmpty()) {
                PremiumEmptyState(
                    Icons.Default.CollectionsBookmark,
                    "Collection نداری",
                    "اول یک Collection بساز.",
                    "ساخت Collection"
                ){showCreate=true}
            } else {
                LazyColumn(
                    contentPadding=PaddingValues(horizontal=12.dp,vertical=10.dp),
                    verticalArrangement=Arrangement.spacedBy(7.dp),
                    modifier=Modifier.heightIn(max=430.dp)
                ) {
                    items(collections,key={it.id}) { collection ->
                        Surface(
                            color=FqSurface2,
                            shape=RoundedCornerShape(16.dp),
                            modifier=Modifier.fillMaxWidth().clickable {
                                val mediaId=media.backendId
                                if(mediaId.isNullOrBlank()) {
                                    onMessage("این عنوان هنوز به Catalog واقعی Filmiqoo متصل نیست.")
                                } else {
                                    scope.launch {
                                        runCatching {
                                            lib.toggleCollectionItem(collection.id,mediaId)
                                        }.onSuccess { included->
                                            onMessage(
                                                if(included)
                                                    "به «"+collection.name+"» اضافه شد."
                                                else
                                                    "از «"+collection.name+"» حذف شد."
                                            )
                                        }.onFailure {
                                            onMessage(it.message ?: "خطا")
                                        }
                                    }
                                }
                            }
                        ) {
                            Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(collection.emoji,fontSize=25.sp)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(collection.name,fontSize=12.sp,fontWeight=FontWeight.Bold)
                                    Text(
                                        collection.itemCount.toString()+" عنوان",
                                        color=FqMuted,fontSize=11.sp
                                    )
                                }
                                Icon(Icons.Default.AddCircleOutline,null,tint=FqGold)
                            }
                        }
                    }
                }
            }
        }
    }

    if(showCreate) {
        CreateCollectionDialog(
            onDismiss={showCreate=false},
            onCreate={name,description,emoji,visibility->
                scope.launch {
                    runCatching {
                        lib.createCollection(name,description,emoji,visibility)
                    }.onSuccess {
                        collections=listOf(it)+collections
                        showCreate=false
                    }.onFailure {
                        onMessage(it.message ?: "خطا در ساخت Collection")
                    }
                }
            }
        )
    }
}


@Composable
private fun SceneBookmarksLibrary(
    items:List<SceneBookmark>,
    onPlay:(PlaybackTarget)->Unit,
    onDelete:(SceneBookmark)->Unit
) {
    if(items.isEmpty()) {
        PremiumEmptyState(
            icon=Icons.Default.Bookmarks,
            title="Scene Bookmark نداری",
            body="وسط پخش روی Bookmark بزن تا هر صحنه را خصوصی با زمان دقیق ذخیره کنی."
        )
        return
    }

    LazyColumn(
        contentPadding=PaddingValues(horizontal=12.dp,vertical=10.dp),
        verticalArrangement=Arrangement.spacedBy(8.dp),
        modifier=Modifier.fillMaxSize()
    ) {
        items(items,key={it.id}) { bookmark ->
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(18.dp),
                modifier=Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onPlay(bookmark.asPlaybackTarget()) }
                        .padding(10.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.width(74.dp).height(92.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(FqSurface2)
                    ) {
                        RemoteImage(
                            bookmark.posterUrl,
                            Modifier.fillMaxSize(),
                            ContentScale.Crop
                        )
                        Surface(
                            color=Color.Black.copy(alpha=.76f),
                            shape=RoundedCornerShape(7.dp),
                            modifier=Modifier.align(Alignment.BottomStart).padding(5.dp)
                        ) {
                            Text(
                                formatSceneTime(bookmark.positionMs),
                                color=FqGold,
                                fontSize=11.sp,
                                fontWeight=FontWeight.Bold,
                                modifier=Modifier.padding(horizontal=6.dp,vertical=3.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(10.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            bookmark.title,
                            fontSize=12.sp,
                            fontWeight=FontWeight.Bold,
                            maxLines=1,
                            overflow=TextOverflow.Ellipsis
                        )
                        if(bookmark.subtitle.isNotBlank()) {
                            Text(
                                bookmark.subtitle,
                                color=FqMuted,
                                fontSize=11.sp,
                                modifier=Modifier.padding(top=2.dp)
                            )
                        }
                        Text(
                            bookmark.note.ifBlank { "Scene Bookmark" },
                            color=Color.White.copy(alpha=.72f),
                            fontSize=11.sp,
                            maxLines=2,
                            overflow=TextOverflow.Ellipsis,
                            modifier=Modifier.padding(top=6.dp)
                        )
                        if(bookmark.tag.isNotBlank()) {
                            Text(
                                "#"+bookmark.tag,
                                color=FqGold,
                                fontSize=11.sp,
                                modifier=Modifier.padding(top=4.dp)
                            )
                        }
                    }

                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PlayCircle,
                            null,
                            tint=FqGold,
                            modifier=Modifier.size(27.dp)
                        )
                        IconButton(onClick={onDelete(bookmark)}) {
                            Icon(Icons.Default.DeleteOutline,null,tint=FqDanger)
                        }
                    }
                }
            }
        }
    }
}
