package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay

import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumSearchScreen(repository: TmdbRepository, backend: BackendRepository, onBack: () -> Unit, onMedia: (MediaItem) -> Unit,
    onCreator: (Creator) -> Unit, onOpenPost: (String) -> Unit, onOpenClip: (String) -> Unit, onDiscover: (() -> Unit)? = null,
    onPerson: (Int, String) -> Unit = { _, _ -> }, onWorld: (() -> Unit)? = null) {
    val context=LocalContext.current
    val search=remember(backend){UniversalSearchRepository(context.applicationContext,backend)}
    val discovery=remember(backend){DiscoveryRepository(context.applicationContext,backend)}
    val keyboard=LocalSoftwareKeyboardController.current
    val window=LocalConfiguration.current
    val compact=window.screenHeightDp<480||window.fontScale>=1.6f
    var query by rememberSaveable{mutableStateOf("")};var filter by rememberSaveable{mutableIntStateOf(0)}
    var playableOnly by rememberSaveable{mutableStateOf(false)};var orderName by rememberSaveable{mutableStateOf(CinematicSearchOrder.RELEVANCE.name)}
    var facets by rememberSaveable(stateSaver=SearchFacetsSaver) { mutableStateOf(CinematicSearchFacets()) }
    var showFilters by rememberSaveable{mutableStateOf(false)}
    var browseCountry by rememberSaveable{mutableStateOf("")};var browseGenre by rememberSaveable{mutableIntStateOf(0)}
    var browseSeries by rememberSaveable{mutableStateOf(false)};var browseTitle by rememberSaveable{mutableStateOf("")}
    val order=CinematicSearchOrder.entries.firstOrNull{it.name==orderName}?:CinematicSearchOrder.RELEVANCE
    var catalogRetry by remember{mutableIntStateOf(0)};var metadataRetry by remember{mutableIntStateOf(0)};var starterRetry by remember{mutableIntStateOf(0)}
    var catalog by remember{mutableStateOf<List<MediaItem>>(emptyList())};var metadata by remember{mutableStateOf<List<DiscoveryTitle>>(emptyList())}
    var people by remember{mutableStateOf<List<DiscoveryPerson>>(emptyList())}
    var movieStarters by remember{mutableStateOf<List<DiscoveryTitle>>(emptyList())};var seriesStarters by remember{mutableStateOf<List<DiscoveryTitle>>(emptyList())}
    var movieGenres by remember{mutableStateOf<List<DiscoveryGenre>>(emptyList())};var seriesGenres by remember{mutableStateOf<List<DiscoveryGenre>>(emptyList())}
    var history by remember{mutableStateOf(search.history())}
    var catalogLoading by remember{mutableStateOf(false)};var metadataLoading by remember{mutableStateOf(false)};var startersLoading by remember{mutableStateOf(true)}
    var catalogError by remember{mutableStateOf(false)};var metadataError by remember{mutableStateOf(false)};var startersError by remember{mutableStateOf(false)}
    var requestedPage by remember{mutableIntStateOf(1)};var lastPage by remember{mutableIntStateOf(1)};var hasMore by remember{mutableStateOf(false)}
    val normalized=remember(query){cinemaSearchKey(query)}
    val merged=remember(catalog,metadata){mergeDiscoveryTitles(catalog,metadata)}
    val visible=remember(merged,filter,playableOnly,order,facets){cinematicSearchDiscoveryTitles(merged,filter,playableOnly,order,facets)}
    val visiblePeople=if(filter==0||filter==3)people else emptyList()
    val starters=remember(movieStarters,seriesStarters){(movieStarters+seriesStarters).distinctBy{cinemaMediaKey(it.media)}}
    val artwork=remember(starters){cinematicSearchArtwork(starters.map{it.media})}
    val hasFilters=filter!=0||playableOnly||order!=CinematicSearchOrder.RELEVANCE||facets.active
    val loading=catalogLoading||metadataLoading;val gridState=rememberLazyGridState()
    fun changeQuery(value:String){requestedPage=1;query=value.take(120)}
    fun resetFilters(){filter=0;playableOnly=false;orderName=CinematicSearchOrder.RELEVANCE.name;facets=CinematicSearchFacets()}
    fun submitHistory(){if(normalized.length>=2){search.recordHistory(query.trim());history=search.history()};keyboard?.hide()}
    fun openTitle(media:MediaItem){submitHistory();onMedia(media)}
    if(browseCountry.isNotBlank()||browseGenre>0){
        DiscoveryFullListScreen(repository,backend,if(browseSeries)MediaType.TV else MediaType.MOVIE,DiscoverySection.POPULAR,
            DiscoveryFilters(country=browseCountry.takeIf(String::isNotBlank),genreId=browseGenre.takeIf{it>0}),browseTitle,{browseCountry="";browseGenre=0},onMedia)
        return
    }
    BackHandler(onBack=onBack)
    val listKey=listOf(normalized,filter,playableOnly,order,facets).joinToString("|")
    var lastListKey by rememberSaveable { mutableStateOf(listKey) }
    LaunchedEffect(listKey){if(lastListKey!=listKey){gridState.scrollToItem(0);lastListKey=listKey}}
    LaunchedEffect(discovery){
        kotlinx.coroutines.supervisorScope{
            launch{try{movieGenres=discovery.genres(MediaType.MOVIE)}catch(e:CancellationException){throw e}catch(_:Exception){}}
            launch{try{seriesGenres=discovery.genres(MediaType.TV)}catch(e:CancellationException){throw e}catch(_:Exception){}}
        }
    }
    LaunchedEffect(discovery,starterRetry){
        startersLoading=true;startersError=false
        kotlinx.coroutines.supervisorScope{
            launch{try{movieStarters=discovery.popular(MediaType.MOVIE).items}catch(e:CancellationException){throw e}catch(_:Exception){startersError=true}}
            launch{try{seriesStarters=discovery.popular(MediaType.TV).items}catch(e:CancellationException){throw e}catch(_:Exception){startersError=true}}
        };currentCoroutineContext().ensureActive();startersLoading=false
    }
    LaunchedEffect(normalized,catalogRetry){
        catalog=emptyList();catalogError=false;catalogLoading=normalized.length>=2
        if(normalized.length<2)return@LaunchedEffect
        delay(350)
        try{catalog=search.search(normalized).media}catch(e:CancellationException){throw e}catch(_:Exception){catalogError=true}
        finally{currentCoroutineContext().ensureActive();catalogLoading=false}
    }
    LaunchedEffect(normalized,metadataRetry,requestedPage){
        if(requestedPage==1){metadata=emptyList();people=emptyList();hasMore=false}
        metadataError=false;metadataLoading=normalized.length>=2
        if(normalized.length<2)return@LaunchedEffect
        if(requestedPage==1)delay(350)
        try{
            val result=discovery.searchMetadata(normalized,requestedPage);currentCoroutineContext().ensureActive()
            metadata=(if(requestedPage==1)result.titles else metadata+result.titles).distinctBy{cinemaMediaKey(it.media)}
            people=(if(requestedPage==1)result.people else people+result.people).distinctBy{it.id}
            lastPage=result.page;hasMore=result.hasMore
        }catch(e:CancellationException){throw e}catch(_:Exception){metadataError=true}
        finally{currentCoroutineContext().ensureActive();metadataLoading=false}
    }
    DiscoveryActionScope(backend){Box(Modifier.fillMaxSize().background(CinemaInk).imePadding().testTag("cinema-search")){
        SearchPosterAtmosphere(artwork,compact,normalized.isNotEmpty())
        Column(Modifier.fillMaxSize()){
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=12.dp,vertical=if(compact)4.dp else 10.dp),verticalAlignment=Alignment.CenterVertically){
                IconButton(onBack){Icon(Icons.Outlined.ArrowForward,"بازگشت",tint=CinemaPaper)}
                Column(Modifier.weight(1f).padding(horizontal=8.dp)){
                    if(!compact)Text("FILMIQOO / DISCOVERY",color=CinemaGold,fontSize=10.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp)
                    Text("جست‌وجوی سینما",fontSize=if(compact)22.sp else 26.sp,lineHeight=34.sp,fontWeight=FontWeight.Bold,color=CinemaPaper)
                }
                IconButton({keyboard?.hide();showFilters=true},Modifier.testTag("search-options")){
                    BadgedBox(badge={if(hasFilters)Badge(containerColor=CinemaAccent)}){Icon(Icons.Outlined.Tune,"تنظیم نتیجه‌ها",tint=CinemaPaper)}
                }
            }
            OutlinedTextField(query,::changeQuery,singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp).testTag("search-input"),
                label={Text("فیلم، سریال یا یک نام")},placeholder={Text("فارسی یا English",color=CinemaSoft)},leadingIcon={Icon(Icons.Outlined.Search,null,tint=CinemaAccent)},
                trailingIcon={if(query.isNotEmpty())IconButton({changeQuery("")},Modifier.testTag("search-clear")){Icon(Icons.Outlined.Close,"پاک‌کردن جست‌وجو")}},
                shape=RoundedCornerShape(18.dp),colors=OutlinedTextFieldDefaults.colors(focusedContainerColor=CinemaSurface.copy(alpha=.96f),unfocusedContainerColor=CinemaSurface.copy(alpha=.94f),focusedBorderColor=CinemaAccent,unfocusedBorderColor=Color.White.copy(alpha=.19f),focusedTextColor=CinemaPaper,unfocusedTextColor=CinemaPaper,cursorColor=CinemaAccent),
                keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={submitHistory()}))
            LazyRow(contentPadding=PaddingValues(horizontal=20.dp,vertical=10.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.testTag("search-type-filters")){
                listOf("همه","فیلم","سریال","هنرمندان").forEachIndexed{index,label->item(key=index){FilterChip(filter==index,{filter=index},label={Text(label,fontWeight=FontWeight.Bold)},modifier=Modifier.heightIn(min=48.dp).testTag("search-type-"+index),colors=FilterChipDefaults.filterChipColors(containerColor=CinemaInk.copy(alpha=.8f),labelColor=CinemaSoft,selectedContainerColor=CinemaAccent.copy(alpha=.23f),selectedLabelColor=CinemaPaper))}}
                if(playableOnly)item{InputChip(true,{playableOnly=false},label={Text("آمادهٔ پخش")},trailingIcon={Icon(Icons.Outlined.Close,"برداشتن فیلتر پخش",Modifier.size(16.dp))},modifier=Modifier.heightIn(min=48.dp).testTag("search-playable-active"))}
            }
            if(loading)LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).testTag("search-loading"),color=CinemaAccent,trackColor=CinemaLine)
            LazyVerticalGrid(GridCells.Adaptive(if(window.fontScale>=1.6f)160.dp else 144.dp),state=gridState,modifier=Modifier.weight(1f).testTag("search-results"),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=8.dp,bottom=28.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalArrangement=Arrangement.spacedBy(20.dp)){
                if(normalized.isEmpty()){
                    item(span={GridItemSpan(maxLineSpan)},key="welcome"){Column{
                        Text("داستان بعدی‌ات از اینجا شروع می‌شود",color=CinemaPaper,fontSize=if(compact)20.sp else 27.sp,lineHeight=if(compact)30.sp else 39.sp,fontWeight=FontWeight.Bold)
                        Text("عنوان‌ها و هنرمندان را پیدا کن؛ یا از جهان سینما شروع کن.",color=CinemaSoft,fontSize=13.sp,lineHeight=22.sp,modifier=Modifier.padding(top=6.dp))
                        if(onWorld!=null)OutlinedButton({keyboard?.hide();onWorld()},Modifier.padding(top=12.dp).heightIn(min=48.dp).testTag("search-world")){Icon(Icons.Outlined.Public,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("جهان سینما")}
                        else if(onDiscover!=null)OutlinedButton({keyboard?.hide();onDiscover()},Modifier.padding(top=12.dp).heightIn(min=48.dp).testTag("search-discover")){Text("مرور کشور، ژانر و سال")}
                    }}
                    if(history.isNotEmpty())item(span={GridItemSpan(maxLineSpan)},key="history"){SearchHistory(history,::changeQuery){search.clearHistory();history=emptyList()}}
                    item(span={GridItemSpan(maxLineSpan)},key="presets"){Column{
                        Text("یک مسیر برای کشف",color=CinemaPaper,fontWeight=FontWeight.Bold,fontSize=16.sp)
                        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(top=8.dp)){
                            item{AssistChip({keyboard?.hide();browseCountry="TR";browseSeries=false;browseTitle="فیلم‌های ترکی"},label={Text("فیلم ترکی")},modifier=Modifier.heightIn(min=48.dp).testTag("search-preset-tr"))}
                            item{AssistChip({keyboard?.hide();browseCountry="KR";browseSeries=true;browseTitle="سریال‌های کره‌ای"},label={Text("سریال کره‌ای")},modifier=Modifier.heightIn(min=48.dp).testTag("search-preset-kr"))}
                            item{AssistChip({keyboard?.hide();browseCountry="IR";browseGenre=35;browseSeries=false;browseTitle="کمدی ایرانی"},label={Text("کمدی ایرانی")},modifier=Modifier.heightIn(min=48.dp).testTag("search-preset-ir-comedy"))}
                        }
                    }}
                    val browseGenres=if(filter==2)seriesGenres else movieGenres
                    if(browseGenres.isNotEmpty())item(span={GridItemSpan(maxLineSpan)},key="genres"){DiscoveryGenreExplorer(browseGenres.take(8),if(filter==2)seriesStarters else movieStarters){selected->keyboard?.hide();browseGenre=selected;browseSeries=filter==2;browseTitle=browseGenres.firstOrNull{it.id==selected}?.name?:"مرور ژانر"}}
                    if(startersLoading&&starters.isEmpty())item(span={GridItemSpan(maxLineSpan)},key="starters-loading"){Text("در حال دریافت عنوان‌ها…",color=CinemaSoft)}
                    listOf(MediaType.MOVIE to movieStarters,MediaType.TV to seriesStarters).forEach{(kind,source)->
                        val subset=cinematicSearchDiscoveryTitles(source,filter,playableOnly,order,facets).take(6)
                        if(subset.isNotEmpty()){
                            item(span={GridItemSpan(maxLineSpan)},key="starter-heading-"+kind){SearchGroupHeading(if(kind==MediaType.MOVIE)"فیلم‌های پرطرفدار"else"سریال‌های پرطرفدار","عنوان‌های دریافت‌شده از TMDB")}
                            items(subset,key={"starter-"+cinemaMediaKey(it.media)}){title->DiscoveryPosterCard(title,backend,::openTitle,Modifier.fillMaxWidth())}
                        }
                    }
                    if(!startersLoading&&starters.isEmpty())item(span={GridItemSpan(maxLineSpan)},key="starters-empty"){CinemaNotice(if(startersError)"پوسترها دریافت نشدند"else"پیشنهادها هنوز آماده نیستند","جست‌وجو همچنان در دسترس است؛ دریافت پیشنهادهای این صفحه را دوباره امتحان کن.",Icons.Outlined.Movie,"دریافت پیشنهادها",{starterRetry++})}
                    if(!startersLoading&&starters.isNotEmpty()&&cinematicSearchDiscoveryTitles(starters,filter,playableOnly,order,facets).isEmpty())item(span={GridItemSpan(maxLineSpan)},key="starters-filter-empty"){CinemaNotice("با این انتخاب عنوانی دیده نمی‌شود","فیلترهای عنوان‌ها را بردار یا یک نام را جست‌وجو کن.",Icons.Outlined.FilterAltOff,"برداشتن فیلترها",::resetFilters)}
                    if(!startersLoading&&startersError&&starters.isNotEmpty())item(span={GridItemSpan(maxLineSpan)},key="starters-partial-error"){CinemaNotice("بخشی از پیشنهادها دریافت نشد","عنوان‌های موجود قابل مشاهده‌اند.",Icons.Outlined.CloudOff,"دریافت دوباره",{starterRetry++})}
                }else if(normalized.length<2){item(span={GridItemSpan(maxLineSpan)},key="short-query"){Text("حداقل دو حرف بنویس.",color=CinemaSoft,modifier=Modifier.padding(vertical=12.dp))}}
                else{
                    item(span={GridItemSpan(maxLineSpan)},key="result-heading"){Column(Modifier.testTag("search-result-summary")){SearchGroupHeading("نتیجه‌های جست‌وجو",visible.size.toString()+" عنوان · "+order.label);Text("فیلتر و ترتیب روی عنوان‌های دریافت‌شده اعمال می‌شود.",color=CinemaSoft,fontSize=11.sp,lineHeight=18.sp,modifier=Modifier.padding(top=4.dp))}}
                    if(catalogError)item(span={GridItemSpan(maxLineSpan)},key="catalog-error"){Column(Modifier.testTag("search-catalog-error")){CinemaNotice("ارتباط برقرار نشد","فهرست برنامه دریافت نشد؛ اطلاعات سینماییِ دریافت‌شده همچنان قابل مشاهده است.",Icons.Outlined.CloudOff,"تلاش دوباره",{catalogRetry++})}}
                    if(metadataError)item(span={GridItemSpan(maxLineSpan)},key="metadata-error"){Column(Modifier.testTag("search-metadata-error")){CinemaNotice("اطلاعات سینمایی دریافت نشد","عنوان‌های فهرست برنامه حفظ شده‌اند. برای دریافت این صفحه دوباره تلاش کن.",Icons.Outlined.CloudOff,"تلاش دوباره برای اطلاعات",{metadataRetry++})}}
                    listOf(MediaType.MOVIE,MediaType.TV).forEach{kind->val group=visible.filter{it.media.type==kind};if(group.isNotEmpty()){
                        item(span={GridItemSpan(maxLineSpan)},key="group-"+kind){Column(Modifier.testTag(if(kind==MediaType.MOVIE)"search-movies-heading"else"search-series-heading")){SearchGroupHeading(if(kind==MediaType.MOVIE)"فیلم‌ها"else"سریال‌ها",group.size.toString()+" عنوان دریافت‌شده")}}
                        items(group,key={cinemaMediaKey(it.media)}){title->DiscoveryPosterCard(title,backend,::openTitle,Modifier.fillMaxWidth())}
                    }}
                    if(visiblePeople.isNotEmpty()){
                        item(span={GridItemSpan(maxLineSpan)},key="people-heading"){Column(Modifier.testTag("search-people-heading")){SearchGroupHeading("هنرمندان",visiblePeople.size.toString()+" نام دریافت‌شده")}}
                        items(visiblePeople,key={"person-"+it.id},span={GridItemSpan(maxLineSpan)}){person->SearchPersonResult(person){submitHistory();onPerson(person.id,person.name)}}
                    }
                    if(!loading&&!catalogError&&!metadataError&&visible.isEmpty()&&visiblePeople.isEmpty())item(span={GridItemSpan(maxLineSpan)},key="empty"){Column(Modifier.testTag("search-empty")){CinemaNotice(if(merged.isNotEmpty()&&hasFilters)"با این فیلتر، عنوانی پیدا نشد"else"نتیجه‌ای پیدا نشد","نام کوتاه‌تر یا نام انگلیسی را امتحان کن. فیلترها فقط اطلاعات موجود را بررسی می‌کنند.",Icons.Outlined.SearchOff,if(hasFilters)"برداشتن فیلترها"else null,if(hasFilters)(::resetFilters)else null)}}
                    if(hasMore||metadataLoading&&requestedPage>1)item(span={GridItemSpan(maxLineSpan)},key="more"){OutlinedButton({if(metadataError)metadataRetry++ else requestedPage=lastPage+1},enabled=!metadataLoading,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("search-more")){Text(if(metadataLoading)"در حال دریافت…"else"ادامهٔ نتایج سینمایی")}}
                }
            }
        }
    }}
    if(showFilters)ModalBottomSheet(onDismissRequest={showFilters=false},containerColor=CinemaSurface,contentColor=CinemaPaper){
        SearchResultOptions(order,playableOnly,{orderName=it.name},{playableOnly=it},::resetFilters,{showFilters=false},facets,
            (movieGenres+seriesGenres).distinctBy{it.id}.filter{genre->(merged+starters).any{genre.id in it.genreIds}},
            (merged+starters).map{it.originalLanguage}.filter(String::isNotBlank).distinct().sorted()){facets=it}
    }
}
@Composable private fun SearchGroupHeading(title:String,subtitle:String){Column{Text(title,color=CinemaPaper,fontSize=18.sp,fontWeight=FontWeight.Bold);Text(subtitle,color=CinemaSoft,fontSize=12.sp,modifier=Modifier.padding(top=4.dp))}}
@Composable private fun SearchPersonResult(person:DiscoveryPerson,onClick:()->Unit){
    Surface(color=CinemaSurface.copy(alpha=.96f),shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,CinemaLine),modifier=Modifier.fillMaxWidth().clickable(role=Role.Button,onClick=onClick).testTag("search-person-"+person.id)){
        Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){
            AsyncImage(model=cinemaImage(person.profilePath),contentDescription=person.name,contentScale=ContentScale.Crop,modifier=Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(CinemaLine))
            Column(Modifier.weight(1f).padding(horizontal=14.dp)){Text(person.name,color=CinemaPaper,fontSize=17.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis);val known=person.knownFor.take(3).joinToString(" · "){it.media.title};if(known.isNotBlank())Text(known,color=CinemaSoft,fontSize=12.sp,lineHeight=19.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))}
            Icon(Icons.Outlined.ChevronLeft,"اطلاعات هنرمند",tint=CinemaSoft,modifier=Modifier.size(20.dp))
        }
    }
}
@Composable
private fun SearchPosterAtmosphere(artwork: List<String>, compact: Boolean, searching: Boolean) {
    val context = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(if (compact) 280.dp else 460.dp)
        .drawWithContent { clipRect { this@drawWithContent.drawContent() } }
        .testTag(if (artwork.isEmpty()) "search-poster-empty" else "search-poster-atmosphere")) {
        // Fixed tiles draw directly into this clipped viewport. No transformed or translucent
        // parent graphics layer is needed, including on short landscape windows.
        val wallWidth = maxWidth.coerceAtMost(760.dp)
        val columns = if (wallWidth < 480.dp) 3 else 4
        val gap = 7.dp
        val tileWidth = ((wallWidth - gap * (columns - 1)) / columns).coerceAtLeast(1.dp)
        val tileHeight = (tileWidth * 1.5f).coerceAtMost(280.dp)
        val rows = if ((tileHeight + gap) * 2 >= maxHeight) 2 else 3
        if (artwork.isNotEmpty()) Box(Modifier.width(wallWidth).height(maxHeight).align(Alignment.TopCenter)
            .clearAndSetSemantics {}) {
            repeat(columns * rows) { index ->
                val column = index % columns
                val row = index / columns
                val url = artwork[index % artwork.size]
                val request = remember(context, url) { ImageRequest.Builder(context).data(url).size(240, 360).crossfade(false).build() }
                AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.offset(x = (tileWidth + gap) * column,
                        y = (tileHeight + gap) * row - if (column % 2 == 0) 18.dp else 0.dp)
                        .size(tileWidth, tileHeight).clip(RoundedCornerShape(12.dp)).background(CinemaSurface))
            }
        }
        // Preserve the previous dimming with an ordinary foreground scrim instead of group alpha.
        val scrim = remember(searching) {
            val opacity = if (searching) .24f else .72f
            listOf(.66f, .4f, .88f, 1f).map { alpha -> CinemaInk.copy(alpha = 1f - (1f - alpha) * opacity) }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(scrim)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(CinemaInk.copy(alpha = .3f), Color.Transparent, CinemaInk.copy(alpha = .3f)))))
    }
}

@Composable
private fun SearchHistory(history: List<String>, onQuery: (String) -> Unit, onClear: () -> Unit) {
    Surface(color = CinemaSurface.copy(alpha = .94f), shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, CinemaLine)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("جست‌وجوهای اخیر", color = CinemaPaper, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClear, modifier = Modifier.heightIn(min = 48.dp).testTag("search-history-clear")) { Text("پاک‌کردن", color = CinemaSoft, fontSize = 12.sp) }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 6.dp)) {
                items(history.take(6), key = { it }) { previous ->
                    AssistChip({ onQuery(previous) }, label = { Text(previous, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Outlined.History, null, Modifier.size(18.dp)) }, modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 260.dp))
                }
            }
        }
    }
}


@Composable
private fun SearchResultOptions(order:CinematicSearchOrder,playableOnly:Boolean,onOrder:(CinematicSearchOrder)->Unit,onPlayable:(Boolean)->Unit,onReset:()->Unit,onDone:()->Unit,
    facets:CinematicSearchFacets,genres:List<DiscoveryGenre>,languages:List<String>,onFacets:(CinematicSearchFacets)->Unit){
    val large=LocalConfiguration.current.fontScale>=1.6f
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).navigationBarsPadding().testTag("search-options-sheet")){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("تنظیم نتیجه‌ها",fontSize=20.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));IconButton(onDone){Icon(Icons.Outlined.Close,"بستن تنظیم نتیجه‌ها")}}
        Text("فیلترها فقط عنوان‌های دریافت‌شده را بررسی می‌کنند؛ اطلاعات نامشخص با فیلتر فعال تطبیق داده نمی‌شود. هنرمندان جدا هستند.",color=CinemaSoft,fontSize=12.sp,lineHeight=21.sp,modifier=Modifier.padding(vertical=8.dp))
        Column(Modifier.selectableGroup()){CinematicSearchOrder.entries.forEach{choice->Row(Modifier.fillMaxWidth().heightIn(min=56.dp).selectable(order==choice,role=Role.RadioButton,onClick={onOrder(choice)}).testTag("search-order-"+choice.name),verticalAlignment=Alignment.CenterVertically){RadioButton(order==choice,onClick=null);Text(choice.label,Modifier.padding(start=10.dp),fontSize=14.sp)}}}
        HorizontalDivider(Modifier.padding(vertical=12.dp),color=CinemaLine)
        SearchFacetChoiceRow("سال انتشار",listOf("all" to "همهٔ سال‌ها","2020" to "از ۲۰۲۰","2010" to "۲۰۱۰ تا ۲۰۱۹","classic" to "پیش از ۲۰۱۰"),when{facets.firstYear==2020->"2020";facets.firstYear==2010&&facets.lastYear==2019->"2010";facets.lastYear==2009->"classic";else->"all"},"year"){value->onFacets(facets.copy(firstYear=when(value){"2020"->2020;"2010"->2010;else->null},lastYear=when(value){"2010"->2019;"classic"->2009;else->null}))}
        SearchFacetChoiceRow("حداقل امتیاز ثبت‌شده",listOf("0" to "بدون محدودیت","6" to "۶+","7" to "۷+","8" to "۸+"),(facets.minimumRating?.toInt()?:0).toString(),"rating"){onFacets(facets.copy(minimumRating=it.toDoubleOrNull()?.takeIf{value->value>0}))}
        if(genres.isNotEmpty())SearchFacetChoiceRow("ژانر موجود در نتیجه‌ها",listOf("0" to "همهٔ ژانرها")+genres.map{it.id.toString() to it.name},(facets.genreId?:0).toString(),"genre"){onFacets(facets.copy(genreId=it.toIntOrNull()?.takeIf{value->value>0}))}
        if(languages.isNotEmpty())SearchFacetChoiceRow("زبان اصلیِ ثبت‌شده",listOf("" to "همهٔ زبان‌ها")+languages.map{it to when(it){"fa"->"فارسی";"en"->"English";"ko"->"Korean";"hi"->"Hindi";"tr"->"Türkçe";"ar"->"العربية";"ja"->"日本語";else->it}},facets.originalLanguage.orEmpty(),"language"){onFacets(facets.copy(originalLanguage=it.takeIf(String::isNotBlank)))}
        HorizontalDivider(Modifier.padding(vertical=12.dp),color=CinemaLine)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f).padding(end=12.dp)){Text("فقط آمادهٔ پخش",fontSize=14.sp,fontWeight=FontWeight.Bold);Text("نیازمند نسخهٔ قابل پخش و شناسهٔ واقعی سرور",fontSize=12.sp,lineHeight=20.sp,color=CinemaSoft,modifier=Modifier.padding(top=4.dp))};Switch(playableOnly,onPlayable,Modifier.testTag("search-playable-switch").semantics{contentDescription="فقط عنوان‌های آمادهٔ پخش"})}
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){Text("دوبلهٔ فارسی تأییدشده",fontSize=14.sp,modifier=Modifier.weight(1f).padding(end=12.dp));Switch(facets.persianDubbedOnly,{onFacets(facets.copy(persianDubbedOnly=it))},Modifier.testTag("search-dubbed-switch").semantics{contentDescription="فقط دوبلهٔ فارسی تأییدشده"})}
        Row(Modifier.fillMaxWidth().heightIn(min=56.dp),verticalAlignment=Alignment.CenterVertically){Text("زیرنویس فارسی تأییدشده",fontSize=14.sp,modifier=Modifier.weight(1f).padding(end=12.dp));Switch(facets.persianSubtitleOnly,{onFacets(facets.copy(persianSubtitleOnly=it))},Modifier.testTag("search-subtitle-switch").semantics{contentDescription="فقط زیرنویس فارسی تأییدشده"})}
        Text("زبان اصلی نشانهٔ دوبله یا زیرنویس نیست؛ این دو فیلتر فقط اطلاعات تأییدشدهٔ فایل را می‌پذیرند.",color=CinemaSoft,fontSize=12.sp,lineHeight=20.sp)
        if(large)Column(Modifier.fillMaxWidth().padding(top=16.dp,bottom=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Button(onDone,Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("اعمال")};OutlinedButton(onReset,Modifier.fillMaxWidth().heightIn(min=48.dp)){Text("بازنشانی")}}
        else Row(Modifier.fillMaxWidth().padding(top=16.dp,bottom=20.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(onReset,Modifier.weight(1f).heightIn(min=48.dp)){Text("بازنشانی")};Button(onDone,Modifier.weight(1f).heightIn(min=48.dp)){Text("اعمال")}}
    }
}
@Composable private fun SearchFacetChoiceRow(label:String,choices:List<Pair<String,String>>,selected:String,tag:String,onSelect:(String)->Unit){
    Text(label,fontSize=14.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=10.dp));LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(top=6.dp,bottom=10.dp)){items(choices,key={it.first}){choice->FilterChip(choice.first==selected,{onSelect(choice.first)},label={Text(choice.second)},modifier=Modifier.heightIn(min=48.dp).testTag("search-facet-"+tag+"-"+choice.first))}}
}

private val SearchFacetsSaver=listSaver<CinematicSearchFacets,Any>(save={listOf(it.firstYear?:0,it.lastYear?:0,it.minimumRating?:0.0,it.genreId?:0,it.originalLanguage.orEmpty(),it.persianDubbedOnly,it.persianSubtitleOnly)},restore={CinematicSearchFacets((it[0] as Int).takeIf{v->v>0},(it[1] as Int).takeIf{v->v>0},(it[2] as Double).takeIf{v->v>0},(it[3] as Int).takeIf{v->v>0},(it[4] as String).takeIf(String::isNotBlank),it[5] as Boolean,it[6] as Boolean)})
