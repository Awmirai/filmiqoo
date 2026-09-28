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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private enum class LibraryTab { WATCHLIST, FAVORITES, COLLECTIONS, SCENES }

@Composable
fun LibraryScreen(
    backend: BackendRepository,
    repository: TmdbRepository,
    onBack: () -> Unit,
    onMedia: (MediaItem) -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
    onDownloads: () -> Unit = {},
    onHistory: () -> Unit = {},
    onRequireAuth: () -> Unit = {},
    showBack: Boolean = true
) {
    val lib=remember { LibraryRepository(backend) }
    val sceneRepo=remember { SceneBookmarksRepository(backend) }
    val scope=rememberCoroutineScope()

    if(!backend.session.isLoggedIn) {
        BackHandler(enabled=showBack) { onBack() }
        Column(Modifier.fillMaxSize().background(FqBg)) {
            Box(
                Modifier.fillMaxWidth().height(190.dp).background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF24070A),Color(0xFF10090A),FqBg)
                    )
                )
            ) {
                if(showBack) {
                    IconButton(
                        onClick=onBack,
                        modifier=Modifier.padding(8.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack,null)
                    }
                }
                Column(
                    Modifier.align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Text("کتابخانه من",fontSize=27.sp,fontWeight=FontWeight.Black)
                    Text(
                        "ذخیره‌ها، دانلودها، تاریخچه و لیست‌های شخصی",
                        color=FqMuted,
                        fontSize=11.sp,
                        modifier=Modifier.padding(top=3.dp)
                    )
                }
            }
            PremiumEmptyState(
                icon=Icons.Default.Lock,
                title="برای دیدن کتابخانه وارد شو",
                body="بعد از ورود، علاقه‌مندی‌ها، «بعداً می‌بینم»، دانلودهای آفلاین، تاریخچه و لیست‌های شخصی‌ات اینجا همگام می‌شن.",
                action="ورود به حساب",
                onAction=onRequireAuth
            )
        }
        return
    }

    var tabName by rememberSaveable { mutableStateOf(LibraryTab.WATCHLIST.name) }
    val tab=runCatching { LibraryTab.valueOf(tabName) }
        .getOrDefault(LibraryTab.WATCHLIST)
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var favorites by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var watchlist by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var collections by remember { mutableStateOf<List<MediaCollection>>(emptyList()) }
    var sceneBookmarks by remember { mutableStateOf<List<SceneBookmark>>(emptyList()) }
    var activeCollection by remember { mutableStateOf<MediaCollectionDetail?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    BackHandler(enabled=activeCollection!=null || showBack) {
        if(activeCollection!=null) activeCollection=null else onBack()
    }

    LaunchedEffect(refresh) {
        loading=true
        error=null

        coroutineScope {
            val favoritesRequest=async { runCatching { lib.favorites() } }
            val watchlistRequest=async { runCatching { lib.watchlist() } }
            val collectionsRequest=async { runCatching { lib.collections() } }
            val scenesRequest=async { runCatching { sceneRepo.all() } }

            favoritesRequest.await()
                .onSuccess { favorites=it }
                .onFailure { error=it.message ?: "خطا در دریافت علاقه‌مندی‌ها" }

            watchlistRequest.await()
                .onSuccess { watchlist=it }
                .onFailure { error=error ?: it.message ?: "خطا در دریافت «بعداً می‌بینم»" }

            collectionsRequest.await()
                .onSuccess { collections=it }
                .onFailure { error=error ?: it.message ?: "خطا در دریافت لیست‌ها" }

            scenesRequest.await()
                .onSuccess { sceneBookmarks=it }
                .onFailure { error=error ?: it.message ?: "خطا در دریافت لحظه‌ها" }
        }

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

    val normalizedQuery=query.trim()
    val filteredFavorites=remember(favorites,normalizedQuery) {
        if(normalizedQuery.isBlank()) favorites
        else favorites.filter { media ->
            media.title.contains(normalizedQuery,ignoreCase=true) ||
                media.originalTitle.contains(normalizedQuery,ignoreCase=true) ||
                media.overview.contains(normalizedQuery,ignoreCase=true)
        }
    }
    val filteredWatchlist=remember(watchlist,normalizedQuery) {
        if(normalizedQuery.isBlank()) watchlist
        else watchlist.filter { media ->
            media.title.contains(normalizedQuery,ignoreCase=true) ||
                media.originalTitle.contains(normalizedQuery,ignoreCase=true) ||
                media.overview.contains(normalizedQuery,ignoreCase=true)
        }
    }
    val filteredCollections=remember(collections,normalizedQuery) {
        if(normalizedQuery.isBlank()) collections
        else collections.filter { collection ->
            collection.name.contains(normalizedQuery,ignoreCase=true) ||
                collection.description.contains(normalizedQuery,ignoreCase=true)
        }
    }
    val filteredScenes=remember(sceneBookmarks,normalizedQuery) {
        if(normalizedQuery.isBlank()) sceneBookmarks
        else sceneBookmarks.filter { scene ->
            scene.title.contains(normalizedQuery,ignoreCase=true) ||
                scene.subtitle.contains(normalizedQuery,ignoreCase=true) ||
                scene.note.contains(normalizedQuery,ignoreCase=true) ||
                scene.tag.contains(normalizedQuery,ignoreCase=true)
        }
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        LibraryHeader(
            favoriteCount=favorites.size,
            watchlistCount=watchlist.size,
            collectionCount=collections.size,
            sceneCount=sceneBookmarks.size,
            onBack=onBack,
            onRefresh={refresh++},
            showBack=showBack
        )

        LibraryQuickAccess(
            onDownloads=onDownloads,
            onHistory=onHistory
        )

        LibrarySearchField(
            query=query,
            onQuery={query=it}
        )

        TabRow(
            selectedTabIndex=tab.ordinal,
            containerColor=FqBg,
            contentColor=FqGold
        ) {
            listOf(
                LibraryTab.WATCHLIST to "بعداً",
                LibraryTab.FAVORITES to "علاقه‌مندی",
                LibraryTab.COLLECTIONS to "لیست‌ها",
                LibraryTab.SCENES to "لحظه‌ها"
            ).forEach { item ->
                Tab(
                    selected=tab==item.first,
                    onClick={tabName=item.first.name},
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
                items=filteredFavorites,
                repository=repository,
                emptyTitle=if(normalizedQuery.isBlank())
                    "هنوز چیزی به علاقه‌مندی‌ها اضافه نکردی"
                else
                    "در علاقه‌مندی‌ها پیدا نشد",
                emptyBody=if(normalizedQuery.isBlank())
                    "از صفحه فیلم یا سریال، قلب رو بزن تا اینجا نگهش داری."
                else
                    "عبارت جستجو رو تغییر بده یا پاکش کن.",
                emptyIcon=Icons.Default.FavoriteBorder,
                onMedia=onMedia
            )

            LibraryTab.WATCHLIST -> LibraryMediaGrid(
                items=filteredWatchlist,
                repository=repository,
                emptyTitle=if(normalizedQuery.isBlank())
                    "لیست «بعداً می‌بینم» خالیه"
                else
                    "در «بعداً می‌بینم» پیدا نشد",
                emptyBody=if(normalizedQuery.isBlank())
                    "فیلم‌ها و سریال‌هایی که برای بعد نگه می‌داری اینجا جمع می‌شن."
                else
                    "عبارت جستجو رو تغییر بده یا پاکش کن.",
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
                            Text("لیست‌های شخصی",fontSize=14.sp,fontWeight=FontWeight.Bold)
                            Text("مجموعه‌های اختصاصی برای هر حال‌وهوا یا موضوع بساز.",color=FqMuted,fontSize=11.sp)
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

                    if(!loading && filteredCollections.isEmpty()) {
                        if(normalizedQuery.isBlank()) {
                            PremiumEmptyState(
                                icon=Icons.Default.CollectionsBookmark,
                                title="هنوز لیست شخصی نداری",
                                body="مثلاً «فیلم‌های آخر هفته»، «بهترین‌های ۲۰۲۶» یا «انیمه‌های محبوب» بساز.",
                                action="ساخت لیست",
                                onAction={showCreate=true}
                            )
                        } else {
                            PremiumEmptyState(
                                icon=Icons.Default.SearchOff,
                                title="لیستی پیدا نشد",
                                body="عبارت جستجو رو تغییر بده یا پاکش کن."
                            )
                        }
                    } else {
                        LazyColumn(
                            contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp),
                            verticalArrangement=Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredCollections,key={it.id}) { collection ->
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
                items=filteredScenes,
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
    onRefresh:()->Unit,
    showBack:Boolean=true
) {
    Box(
        Modifier.fillMaxWidth().height(205.dp).background(
            Brush.verticalGradient(
                listOf(Color(0xFF1C0507),Color(0xFF0C090A),FqBg)
            )
        )
    ) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding()
                .padding(horizontal=10.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            if(showBack) {
                FqIconButton(
                    icon=Icons.Default.ArrowBack,
                    contentDescription="بازگشت",
                    onClick=onBack
                )
            }
            Spacer(Modifier.weight(1f))
            FqIconButton(
                icon=Icons.Default.Refresh,
                contentDescription="تازه‌سازی",
                onClick=onRefresh
            )
        }

        Column(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal=18.dp)
                .padding(bottom=18.dp)
        ) {
            Text(
                "کتابخانه من",
                fontSize=30.sp,
                fontWeight=FontWeight.Black
            )
            Text(
                "برای بعد، دانلود آفلاین، تاریخچه و چیزهایی که دوست داشتی",
                color=FqMuted,
                fontSize=10.sp,
                modifier=Modifier.padding(top=2.dp)
            )

            LazyRow(
                horizontalArrangement=Arrangement.spacedBy(7.dp),
                modifier=Modifier.padding(top=13.dp)
            ) {
                items(
                    listOf(
                        favoriteCount.toString()+" علاقه‌مندی",
                        watchlistCount.toString()+" برای بعد",
                        collectionCount.toString()+" لیست",
                        sceneCount.toString()+" لحظه"
                    )
                ) { label ->
                    Surface(
                        color=Color.White.copy(alpha=.06f),
                        shape=RoundedCornerShape(999.dp),
                        border=androidx.compose.foundation.BorderStroke(
                            1.dp,
                            Color.White.copy(alpha=.07f)
                        )
                    ) {
                        Text(
                            label,
                            color=FqMutedStrong,
                            fontSize=8.sp,
                            fontWeight=FontWeight.Bold,
                            modifier=Modifier.padding(horizontal=9.dp,vertical=6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySearchField(
    query:String,
    onQuery:(String)->Unit
) {
    OutlinedTextField(
        value=query,
        onValueChange=onQuery,
        singleLine=true,
        placeholder={Text("جستجو در کتابخانه...")},
        leadingIcon={
            Icon(
                Icons.Default.Search,
                contentDescription=null,
                modifier=Modifier.size(18.dp)
            )
        },
        trailingIcon={
            if(query.isNotBlank()) {
                IconButton(onClick={onQuery("")}) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription="پاک کردن جستجو",
                        modifier=Modifier.size(18.dp)
                    )
                }
            }
        },
        shape=RoundedCornerShape(16.dp),
        colors=OutlinedTextFieldDefaults.colors(
            focusedBorderColor=FqGold.copy(alpha=.6f),
            unfocusedBorderColor=FqBorder,
            focusedContainerColor=FqSurface,
            unfocusedContainerColor=FqSurface
        ),
        modifier=Modifier.fillMaxWidth()
            .padding(horizontal=12.dp)
            .padding(bottom=4.dp)
    )
}

@Composable
private fun LibraryQuickAccess(
    onDownloads:()->Unit,
    onHistory:()->Unit
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal=12.dp,vertical=8.dp),
        horizontalArrangement=Arrangement.spacedBy(8.dp)
    ) {
        LibraryQuickCard(
            icon=Icons.Default.DownloadForOffline,
            title="دانلود آفلاین",
            subtitle="بدون اینترنت تماشا کن",
            modifier=Modifier.weight(1f),
            onClick=onDownloads
        )
        LibraryQuickCard(
            icon=Icons.Default.History,
            title="ادامه و تاریخچه",
            subtitle="برگرد به چیزی که داشتی می‌دیدی",
            modifier=Modifier.weight(1f),
            onClick=onHistory
        )
    }
}

@Composable
private fun LibraryQuickCard(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    title:String,
    subtitle:String,
    modifier:Modifier=Modifier,
    onClick:()->Unit
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(18.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
        modifier=modifier.clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal=12.dp,vertical=11.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp)
                    .background(FqGold.copy(alpha=.11f),RoundedCornerShape(12.dp)),
                contentAlignment=Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription=null,
                    tint=FqGold,
                    modifier=Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize=11.sp,
                    fontWeight=FontWeight.Bold
                )
                Text(
                    subtitle,
                    color=FqMuted,
                    fontSize=8.sp,
                    lineHeight=11.sp,
                    maxLines=2,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=2.dp)
                )
            }
            Icon(
                Icons.Default.ChevronLeft,
                contentDescription=null,
                tint=FqMuted,
                modifier=Modifier.size(17.dp)
            )
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
                                "unlisted" -> "فهرست‌نشده"
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
                "این لیست خالیه",
                "از صفحه هر فیلم یا سریال می‌تونی مستقیم به این لیست اضافه‌اش کنی."
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
            title={Text("حذف لیست؟")},
            text={Text("خود لیست حذف می‌شه؛ فیلم‌ها و سریال‌ها از کتابخانه اصلی حذف نمی‌شن.")},
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
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var emoji by rememberSaveable { mutableStateOf("🎬") }
    var visibility by rememberSaveable { mutableStateOf("private") }

    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("لیست جدید")},
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
                    label={Text("ایموجی")},
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
                    Text("افزودن به لیست",fontSize=18.sp,fontWeight=FontWeight.Bold)
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
                    "هنوز لیستی نداری",
                    "اول یک لیست بساز.",
                    "ساخت لیست"
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
                                    onMessage("این عنوان هنوز به کاتالوگ Filmiqoo متصل نیست.")
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
                        onMessage(it.message ?: "خطا در ساخت لیست")
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
            title="نشانه صحنه‌ای نداری",
            body="وسط پخش روی «نشانه‌گذاری صحنه» بزن تا هر لحظه را خصوصی با زمان دقیق ذخیره کنی."
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
