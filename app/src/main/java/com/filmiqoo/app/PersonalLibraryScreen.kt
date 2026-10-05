package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

@Composable
fun CinemaLibraryScreen(backend:BackendRepository,repository:TmdbRepository,onBack:()->Unit,onMedia:(MediaItem)->Unit,onPlay:(PlaybackTarget)->Unit,
    onDownloads:()->Unit={},onHistory:()->Unit={},onFilmDna:()->Unit={},onRequireAuth:()->Unit={},showBack:Boolean=true,onAccount:()->Unit={}) {
    val context=LocalContext.current
    val profile=backend.viewerProfiles.activeId()
    val personal=remember(profile){CinemaPersonalStore(context,profile)}
    var selected by rememberSaveable{mutableIntStateOf(0)}
    var query by rememberSaveable{mutableStateOf("")}
    var legacy by rememberSaveable{mutableStateOf(false)}
    var serverSaved by remember{mutableStateOf<List<MediaItem>>(emptyList())}
    var serverFavorites by remember{mutableStateOf<List<MediaItem>>(emptyList())}
    var error by remember{mutableStateOf(false)}
    var loading by remember{mutableStateOf(false)}
    var refresh by remember{mutableIntStateOf(0)}
    LaunchedEffect(profile,refresh,backend.session.isLoggedIn){
        if(!backend.session.isLoggedIn)return@LaunchedEffect
        loading=true;error=false
        try{val repo=LibraryRepository(backend);serverSaved=repo.watchlist();serverFavorites=repo.favorites()}
        catch(e:CancellationException){throw e}catch(_:Exception){error=true}finally{loading=false}
    }
    BackHandler(enabled=legacy||showBack){if(legacy)legacy=false else onBack()}
    if(legacy){
        LibraryScreen(backend,repository,{legacy=false},onMedia,onPlay,onDownloads,onHistory,onFilmDna,onRequireAuth,showBack=true)
        return
    }
    val entries=when(selected){1->personal.seenItems();2->serverFavorites+personal.favorites();else->serverSaved+personal.saved()}
        .distinctBy(::cinemaMediaKey).filter{cinemaSearchKey(it.title+" "+it.originalTitle).contains(cinemaSearchKey(query))}
    Column(Modifier.fillMaxSize().background(CinemaInk).imePadding().testTag("cinema-library")){
        CinemaPageHeader("کتابخانه","فهرست تماشا و آثار دیده‌شده",if(showBack)onBack else null){IconButton(onAccount){Icon(Icons.Outlined.AccountCircle,"حساب و تنظیمات")}}
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            CinemaAction(Icons.Outlined.Download,"دانلودها",onDownloads,Modifier.weight(1f))
            CinemaAction(Icons.Outlined.History,"سابقهٔ پخش",onHistory,Modifier.weight(1f))
        }
        LazyRow(contentPadding=PaddingValues(20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            listOf("برای تماشا","دیده‌شده","پسندیده‌ها").forEachIndexed{i,label->item{CinemaTag(label,selected==i){selected=i}}}
        }
        OutlinedTextField(query,{query=it.take(120)},modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp),singleLine=true,
            label={Text("جست‌وجو در کتابخانه")},leadingIcon={Icon(Icons.Outlined.Search,null)},shape=MaterialTheme.shapes.large)
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp),color=CinemaAccent)
        LazyVerticalGrid(GridCells.Adaptive(136.dp),modifier=Modifier.weight(1f),contentPadding=PaddingValues(20.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
            item(span={GridItemSpan(maxLineSpan)}){Text(if(selected==1)"آثاری که خودت «دیده‌ام» علامت زده‌ای؛ سابقهٔ پخش مسیر جدا دارد." else "فهرست حساب و ذخیره‌های این دستگاه، کنار هم. عنوان بدون فایل هم قابل ذخیره است.",color=CinemaSoft,style=MaterialTheme.typography.bodySmall)}
            if(error)item(span={GridItemSpan(maxLineSpan)}){CinemaNotice("فهرست حساب به‌روز نشد","ذخیره‌های دستگاه باقی مانده‌اند؛ برای دریافت فهرست حساب دوباره تلاش کن.",Icons.Outlined.CloudOff,"تلاش دوباره",{refresh++})}
            if(entries.isEmpty()&&!loading)item(span={GridItemSpan(maxLineSpan)}){CinemaNotice(if(query.isBlank())"این فهرست هنوز خالی است"else "نتیجه‌ای پیدا نشد",if(query.isBlank())"در صفحهٔ اثر، فهرست تماشا یا دیده‌ام را انتخاب کن؛ اینجا پیدایش می‌کنی."else "نام کوتاه‌تر یا نام انگلیسی را امتحان کن.",Icons.Outlined.Bookmarks)}
            items(entries,key=::cinemaMediaKey){media->CinemaPoster(media,{onMedia(media)},Modifier.fillMaxWidth())}
            item(span={GridItemSpan(maxLineSpan)}){TextButton({legacy=true}){Text("مجموعه‌ها و نشانک‌های حساب")}}
        }
    }
}
