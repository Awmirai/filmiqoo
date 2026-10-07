package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.Locale

internal val CountryDiscoveryFilterSaver = listSaver<DiscoveryFilters, Any>(
    save={listOf(it.genreId ?: 0,it.yearFrom ?: 0,it.yearTo ?: 0,it.minRating,it.language.orEmpty(),it.country.orEmpty(),it.runtimeMax ?: 0,it.status.orEmpty(),it.sort.name,it.persianDubbedOnly,it.persianSubtitleOnly)},
    restore={DiscoveryFilters((it[0] as Int).takeIf{n->n>0},(it[1] as Int).takeIf{n->n>0},(it[2] as Int).takeIf{n->n>0},it[3] as Double,
        (it[4] as String).takeIf(String::isNotBlank),(it[5] as String).takeIf(String::isNotBlank),(it[6] as Int).takeIf{n->n>0},
        (it[7] as String).takeIf(String::isNotBlank),DiscoverySort.valueOf(it[8] as String),it[9] as Boolean,it[10] as Boolean)})
private data class CinemaCountryDestination(val code:String,val name:String,val introduction:String)
private val FeaturedCinemaCountries=listOf(
    CinemaCountryDestination("TR","ترکیه","سینما و داستان‌های دنباله‌دار"),CinemaCountryDestination("KR","کرهٔ جنوبی","K-Movies & K-Dramas"),
    CinemaCountryDestination("IN","هند","زبان‌های گوناگون، داستان‌های گوناگون"),CinemaCountryDestination("IR","ایران","از آثار تازه تا خاطره‌های سینما"),
    CinemaCountryDestination("US","آمریکا","دنیای آثار آمریکایی"),CinemaCountryDestination("JP","ژاپن","سینما، سریال و انیمیشن"),
    CinemaCountryDestination("GB","بریتانیا","داستان‌های سینما و تلویزیون"),CinemaCountryDestination("FR","فرانسه","جهانی دیگر برای کشف"),
    CinemaCountryDestination("CN","چین","سینما و داستان‌های تلویزیونی"),CinemaCountryDestination("ES","اسپانیا","از پردهٔ سینما تا سریال"),
    CinemaCountryDestination("DE","آلمان","سینما و تلویزیون"),CinemaCountryDestination("EG","مصر","بخشی از جهان عرب‌زبان"))

/** Artwork is used only when its actual origin metadata matches the destination. */
@Composable
fun WorldDiscoveryHub(repository:TmdbRepository,backend:BackendRepository,type:MediaType,onCountry:(String)->Unit,modifier:Modifier=Modifier){
    val context=LocalContext.current;val loader=remember(backend){DiscoveryRepository(context,backend)}
    var titles by remember(loader,type){mutableStateOf<List<DiscoveryTitle>>(emptyList())};var picker by rememberSaveable{mutableStateOf(false)}
    LaunchedEffect(loader,type){try{val values=loader.recentCatalog(type);currentCoroutineContext().ensureActive();titles=values}
        catch(e:CancellationException){throw e}catch(_:Exception){titles=emptyList()}}
    val large=LocalDensity.current.fontScale>=1.6f
    Column(modifier.testTag("world-discovery-${type.name.lowercase(Locale.ROOT)}")){
        CinemaHeading("سینمای جهان","یک مقصد تازه، یک جهان تازه؛ کشور را انتخاب کن","همهٔ کشورها",{picker=true})
        LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
            items(FeaturedCinemaCountries.chunked(2),key={it.first().code}){pair->Column(Modifier.width(if(large)228.dp else 192.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                pair.forEach{destination->
                    val art=titles.firstOrNull{destination.code in it.originCountries&&it.media.type==type&&(!it.media.backdropPath.isNullOrBlank()||!it.media.posterPath.isNullOrBlank())}
                    Surface(shape=RoundedCornerShape(20.dp),color=CinemaSurface,border=BorderStroke(1.dp,CinemaLine),
                        modifier=Modifier.fillMaxWidth().clickable(role=Role.Button,onClickLabel="کشف ${destination.name}",onClick={onCountry(destination.code)}).testTag("world-country-${destination.code}")){
                        Box(Modifier.heightIn(min=if(large)158.dp else 126.dp)){
                            art?.let{DiscoveryArtwork(it.media.backdropPath ?: it.media.posterPath,true,Modifier.matchParentSize())}
                            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0xFF111D2C).copy(alpha=.65f),CinemaInk.copy(alpha=.96f)))))
                            Column(Modifier.fillMaxWidth().padding(16.dp)){
                                Text(destination.code,style=MaterialTheme.typography.labelLarge,color=CinemaGold)
                                Text(destination.name,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold,color=CinemaPaper,modifier=Modifier.padding(top=10.dp))
                                Text(destination.introduction,color=CinemaSoft,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=6.dp))
                            }
                        }
                    }
                }
            }}
        }
    };if(picker)WorldCountryPicker(loader,{picker=false;onCountry(it)},{picker=false})
}
@Composable
private fun WorldCountryPicker(loader:DiscoveryRepository,onSelected:(String)->Unit,onDismiss:()->Unit){
    var values by remember(loader){mutableStateOf<List<Pair<String,String>>>(emptyList())};var query by rememberSaveable{mutableStateOf("")}
    var loading by remember{mutableStateOf(true)};var error by remember{mutableStateOf<String?>(null)};var retry by remember{mutableIntStateOf(0)}
    LaunchedEffect(loader,retry){loading=true;error=null
        try{val loaded=loader.countries().map{it.code to it.name.ifBlank{it.englishName}}.distinctBy{it.first}.sortedBy{it.second};currentCoroutineContext().ensureActive();values=loaded}
        catch(e:CancellationException){throw e}catch(e:Exception){error=e.message ?: "کشورها دریافت نشدند."}finally{currentCoroutineContext().ensureActive();loading=false}}
    AlertDialog(onDismissRequest=onDismiss,modifier=Modifier.testTag("world-country-picker"),title={Text("تمام جهان، یک انتخاب")},
        text={Column(Modifier.heightIn(max=460.dp)){
            OutlinedTextField(query,{query=it},label={Text("نام کشور یا کد")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("world-country-query"))
            if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let{Text(it,color=CinemaAccent);TextButton({retry++}){Text("تلاش دوباره")}}
            val matches=values.filter{cinemaSearchKey(it.first+" "+it.second).contains(cinemaSearchKey(query))}
            if(!loading&&error==null&&matches.isEmpty())Text("کشوری با این نام پیدا نشد.",color=CinemaSoft)
            LazyColumn(Modifier.testTag("world-country-results")){items(matches,key={it.first}){(code,name)->TextButton({onSelected(code)},Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("world-registry-$code")){Text("$name · $code")}}}
        }},confirmButton={TextButton(onDismiss){Text("بستن")}})
}

/** One typed architecture for every origin; filters always carry the selected country. */
@Composable
fun CountryDiscoveryPage(repository:TmdbRepository,backend:BackendRepository,country:String,type:MediaType,onBack:()->Unit,onMedia:(MediaItem)->Unit,onSearch:()->Unit){
    val context=LocalContext.current;val loader=remember(backend){DiscoveryRepository(context,backend)}
    var activeCountry by rememberSaveable(country){mutableStateOf(country.uppercase(Locale.ROOT))}
    var filters by rememberSaveable(country,type,stateSaver=CountryDiscoveryFilterSaver){mutableStateOf(DiscoveryFilters(country=activeCountry))}
    var listSection by rememberSaveable(country,type){mutableStateOf<String?>(null)};var listTitle by rememberSaveable(country,type){mutableStateOf("")}
    var listFilters by rememberSaveable(country,type,stateSaver=CountryDiscoveryFilterSaver){mutableStateOf(DiscoveryFilters(country=activeCountry))}
    var showFilters by rememberSaveable(country,type){mutableStateOf(false)};var genres by remember(loader,type){mutableStateOf<List<DiscoveryGenre>>(emptyList())}
    var genreError by remember(loader,type){mutableStateOf<String?>(null)};var genreRetry by remember{mutableIntStateOf(0)}
    val hero=rememberDiscoveryModule(loader,type,DiscoverySection.POPULAR,filters)
    var registryLabel by remember(loader,activeCountry){mutableStateOf<String?>(null)}
    LaunchedEffect(loader,activeCountry){
        if(FeaturedCinemaCountries.none{it.code==activeCountry})try{
            registryLabel=loader.countries().firstOrNull{it.code==activeCountry}?.let{it.name.ifBlank{it.englishName}}
        }catch(e:CancellationException){throw e}catch(_:Exception){/* ISO fallback remains usable offline. */}
    }
    val label=FeaturedCinemaCountries.firstOrNull{it.code==activeCountry}?.name ?: registryLabel ?: cinemaCountryLabel(activeCountry);val kind=if(type==MediaType.TV)"سریال" else "فیلم"
    LaunchedEffect(loader,type,genreRetry){genreError=null;try{genres=loader.genres(type)}catch(e:CancellationException){throw e}catch(e:Exception){genreError=e.message ?: "ژانرها دریافت نشدند."}}
    fun open(section:DiscoverySection,title:String,value:DiscoveryFilters=filters){listFilters=value.copy(country=activeCountry);listTitle=title;listSection=section.name}
    listSection?.let{DiscoveryFullListScreen(repository,backend,type,DiscoverySection.valueOf(it),listFilters,listTitle,{listSection=null},onMedia);return}
    BackHandler(onBack=onBack)
    DiscoveryActionScope(backend){
        LazyColumn(Modifier.fillMaxSize().background(CinemaInk).testTag("country-discovery-${type.name.lowercase(Locale.ROOT)}-$activeCountry"),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            item("header"){DiscoveryDestinationHeader("$kind‌های $label","داستان‌های $label، در جهان FILMIQOO",onSearch,onBack)}
            item("hero"){
                hero.items.firstOrNull()?.let{CinematicDiscoveryHero(it,backend,onMedia,"$label / $kind",series=type==MediaType.TV)}
                DiscoveryModuleStatus(hero,"هنوز عنوانی با این ترکیب پیدا نشد.")
                if(!hero.loading&&hero.error==null&&hero.items.isEmpty())TextButton({filters=DiscoveryFilters(country=activeCountry)},Modifier.padding(horizontal=20.dp).testTag("country-clear-filters")){Text("کشف همهٔ $kind‌های $label")}
            }
            item("filters"){Column(Modifier.padding(horizontal=20.dp)){
                OutlinedButton({showFilters=true},Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("country-filters")){Icon(Icons.Outlined.Tune,null);Spacer(Modifier.width(8.dp));Text("انتخاب دقیق‌تر")}
                if(activeCountry=="IN")LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(top=8.dp)){
                    items(listOf("" to "همهٔ زبان‌ها","hi" to "هندی","ta" to "تامیل","te" to "تلوگو","ml" to "مالایالم","kn" to "Kannada","bn" to "بنگالی"),key={it.first}){(code,name)->
                        FilterChip(filters.language.orEmpty()==code,{filters=filters.copy(language=code.takeIf(String::isNotBlank))},label={Text(name)},modifier=Modifier.testTag("country-language-${code.ifBlank{"all"}}"))
                    }
                }
                if(type==MediaType.TV)LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    item{AssistChip({open(DiscoverySection.COMPLETED,"سریال‌های پایان‌یافتهٔ $label")},label={Text("پایان‌یافته‌ها")},modifier=Modifier.testTag("country-completed"))}
                    item{AssistChip({open(DiscoverySection.MINISERIES,"مینی‌سریال‌های $label")},label={Text("مینی‌سریال")},modifier=Modifier.testTag("country-miniseries"))}
                }
            }}
            item("popular"){CountryEditorialModule(loader,backend,type,DiscoverySection.POPULAR,filters,"محبوب‌های $label","بر اساس محبوبیت در TMDB",onMedia,{open(DiscoverySection.POPULAR,"محبوب‌های $label")})}
            item("new"){CountryEditorialModule(loader,backend,type,DiscoverySection.NEW,filters,"تازه‌های $label","ترتیب تاریخ انتشار ثبت‌شدهٔ عنوان‌ها",onMedia,{open(DiscoverySection.NEW,"تازه‌های $label")})}
            if(type==MediaType.TV)item("airing"){CountryEditorialModule(loader,backend,type,DiscoverySection.AIRING,filters,"هنوز ادامه دارد","سریال‌های $label با قسمت تازه یا پیشِ رو در TMDB",onMedia,{open(DiscoverySection.AIRING,"سریال‌های در حال پخش $label")})}
            item("rated"){
                val state=rememberDiscoveryModule(loader,type,DiscoverySection.TOP_RATED,filters)
                if(state.loading||state.error!=null||state.items.isNotEmpty())Column{
                    CinemaHeading("امتیازهای بالاترِ $label","امتیاز TMDB با حداقل ۲۰۰ رأی؛ در محدودهٔ همین انتخاب","همه",{open(DiscoverySection.TOP_RATED,"$kind‌های برتر $label بر اساس امتیاز TMDB")})
                    DiscoveryModuleStatus(state);state.items.take(3).forEachIndexed{index,title->RankedDiscoveryCard(title,index+1,backend,onMedia,Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=5.dp))}
                }
            }
            item("dubbed"){CountryEditorialModule(loader,backend,type,DiscoverySection.DUBBED,filters,"$label با دوبلهٔ فارسی","فایل‌های موجود با دوبلهٔ فارسی تأییدشده",onMedia,{open(DiscoverySection.DUBBED,"$kind‌های دوبلهٔ فارسی $label")})}
            item("subtitled"){CountryEditorialModule(loader,backend,type,DiscoverySection.SUBTITLED,filters,"$label با زیرنویس فارسی","وجود زیرنویس فارسی تأییدشده، جدا از دوبله",onMedia,{open(DiscoverySection.SUBTITLED,"$kind‌های زیرنویس فارسی $label")})}
            item("genres"){Column(Modifier.testTag("country-genres")){
                CinemaHeading("حال‌وهوای $label","ژانر دلخواهت را در همین کشور پیدا کن")
                genreError?.let{CinemaNotice("ژانرها دریافت نشدند",it,Icons.Outlined.CloudOff,"تلاش دوباره",{genreRetry++})}
                if(genres.isNotEmpty())DiscoveryGenreExplorer(genres,hero.items){id->open(DiscoverySection.POPULAR,genres.firstOrNull{it.id==id}?.name ?: "ژانر",filters.copy(genreId=id))}
            }}
        }
        if(showFilters)DiscoveryFilterSheet(type,filters,genres,onApply={value->activeCountry=value.country?.uppercase(Locale.ROOT) ?: activeCountry;filters=value.copy(country=activeCountry);showFilters=false},onDismiss={showFilters=false})
    }
}
/** Layout follows available artwork and result density, not a nationality stereotype. */
@Composable
private fun CountryEditorialModule(loader:DiscoveryRepository,backend:BackendRepository,type:MediaType,section:DiscoverySection,filters:DiscoveryFilters,title:String,subtitle:String,onMedia:(MediaItem)->Unit,onMore:()->Unit){
    val state=rememberDiscoveryModule(loader,type,section,filters);if(!state.loading&&state.error==null&&state.items.isEmpty())return
    Column(Modifier.testTag("country-module-${section.name.lowercase(Locale.ROOT)}")){
        CinemaHeading(title,subtitle,"همه",onMore);DiscoveryModuleStatus(state)
        when{
            state.items.size<=2||state.items.take(4).count{!it.media.backdropPath.isNullOrBlank()}>=3->CountryBackdropGrid(state.items.take(4),backend,onMedia)
            else->LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(14.dp)){items(state.items.take(6),key={cinemaMediaKey(it.media)}){DiscoveryPosterCard(it,backend,onMedia,Modifier.width(152.dp))}}
        }
    }
}
@Composable
internal fun CountryBackdropGrid(titles:List<DiscoveryTitle>,backend:BackendRepository,onMedia:(MediaItem)->Unit){
    val large=LocalDensity.current.fontScale>=1.6f
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal=20.dp)){
        val columns=if(large||maxWidth<330.dp)1 else 2
        Column(verticalArrangement=Arrangement.spacedBy(14.dp)){titles.chunked(columns).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(14.dp)){
            row.forEach{DiscoveryBackdropCard(it,backend,onMedia,Modifier.weight(1f))};repeat(columns-row.size){Spacer(Modifier.weight(1f))}
        }}}
    }
}
