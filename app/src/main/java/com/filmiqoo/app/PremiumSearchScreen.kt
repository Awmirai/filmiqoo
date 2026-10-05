package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

@Composable
fun PremiumSearchScreen(repository:TmdbRepository,backend:BackendRepository,onBack:()->Unit,onMedia:(MediaItem)->Unit,
    onCreator:(Creator)->Unit,onOpenPost:(String)->Unit,onOpenClip:(String)->Unit,onDiscover:(()->Unit)?=null) {
    val context=LocalContext.current
    val search=remember(backend){UniversalSearchRepository(context.applicationContext,backend)}
    val keyboard=LocalSoftwareKeyboardController.current
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var history by remember { mutableStateOf(search.history()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack=onBack)
    LaunchedEffect(query,retry) {
        val input=cinemaSearchKey(query)
        items=emptyList(); error=null; loading=input.length>=2
        if(input.length<2)return@LaunchedEffect
        delay(350)
        try {
            val local=search.search(input).media
            val result=if(local.isNotEmpty())local else repository.search(input)
            currentCoroutineContext().ensureActive()
            items=result.distinctBy(::cinemaMediaKey)
        } catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){error="جست‌وجو انجام نشد. اتصال اینترنت را بررسی کن."}
        finally { currentCoroutineContext().ensureActive(); loading=false }
    }
    Column(Modifier.fillMaxSize().background(CinemaInk).imePadding().testTag("cinema-search")) {
        CinemaPageHeader("جست‌وجو","نام فارسی یا انگلیسی فیلم و سریال",onBack)
        OutlinedTextField(query,{query=it.take(120)},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp).testTag("search-input"),
            label={Text("چه چیزی می‌خواهی ببینی؟")},leadingIcon={Icon(Icons.Outlined.Search,null)},
            trailingIcon={if(query.isNotEmpty())IconButton({query=""}){Icon(Icons.Outlined.Close,"پاک‌کردن جست‌وجو")}},
            shape=MaterialTheme.shapes.large,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={keyboard?.hide()}))
        LazyRow(contentPadding=PaddingValues(20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("همه","فیلم","سریال").forEachIndexed { i,label->item{CinemaTag(label,filter==i){filter=i}} }
            if(onDiscover!=null)item{CinemaTag("کشور، ژانر و سال",onClick=onDiscover)}
        }
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth(),color=CinemaAccent)
        val visible=items.filter{filter==0||it.type==if(filter==1)MediaType.MOVIE else MediaType.TV}
        LazyVerticalGrid(GridCells.Adaptive(136.dp),modifier=Modifier.weight(1f).testTag("search-results"),
            contentPadding=PaddingValues(20.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
            if(query.isBlank()) {
                item(span={GridItemSpan(maxLineSpan)}){CinemaNotice("فیلم بعدی از اینجا شروع می‌شود","نام یک اثر را بنویس؛ برای مرور سینمای ایران، کره، هند و جهان، کشور و ژانر را انتخاب کن.",Icons.Outlined.Search)}
                if(history.isNotEmpty())item(span={GridItemSpan(maxLineSpan)}){
                    Column {
                        Row { Text("جست‌وجوهای اخیر",modifier=Modifier.weight(1f));TextButton({search.clearHistory();history=emptyList()}){Text("پاک‌کردن")}}
                        history.take(6).forEach { old->TextButton({query=old}){Icon(Icons.Outlined.History,null);Spacer(Modifier.width(8.dp));Text(old)} }
                    }
                }
            } else if(query.trim().length<2)item(span={GridItemSpan(maxLineSpan)}){Text("حداقل دو حرف بنویس.",color=CinemaSoft)}
            error?.let { message->item(span={GridItemSpan(maxLineSpan)}){CinemaNotice("ارتباط برقرار نشد",message,Icons.Outlined.CloudOff,"تلاش دوباره",{retry++})} }
            if(!loading&&error==null&&query.trim().length>=2&&visible.isEmpty())item(span={GridItemSpan(maxLineSpan)}) {
                CinemaNotice("عنوانی پیدا نشد","نام کوتاه‌تر یا نام انگلیسی را امتحان کن؛ فیلتر فعال را هم می‌توانی پاک کنی.",Icons.Outlined.SearchOff,if(filter!=0)"پاک‌کردن فیلتر"else null,if(filter!=0)({filter=0})else null)
            }
            items(visible,key=::cinemaMediaKey){m->CinemaPoster(m,{search.recordHistory(query.trim());history=search.history();keyboard?.hide();onMedia(m)},Modifier.fillMaxWidth())}
        }
    }
}
