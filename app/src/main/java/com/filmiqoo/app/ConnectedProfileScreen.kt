package com.filmiqoo.app
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

@Composable
fun ConnectedProfileScreen(
    backend:BackendRepository,repository:TmdbRepository,kidsMode:Boolean=false,
    onMedia:(MediaItem)->Unit,onPlay:(PlaybackTarget)->Unit,onCommunity:()->Unit,onDownloads:()->Unit,
    onLibrary:()->Unit,onSocialSaves:()->Unit,onHistory:()->Unit,onCreatorStudio:()->Unit,onInbox:()->Unit,
    onSettings:()->Unit,onViewerProfiles:()->Unit,onParentalControls:()->Unit,onSecurity:()->Unit,onSafety:()->Unit,
    onFollowRequests:()->Unit,onCloseFriends:()->Unit,onEditProfile:()->Unit,onFilmDna:()->Unit,
    onReputation:(String)->Unit,onSeriesCalendar:()->Unit,onSocialCollections:()->Unit,onLoggedOut:()->Unit,
    loggedIn:Boolean=true,onRequireAuth:()->Unit={},onClips:()->Unit={},onCreate:()->Unit={},
    onNotifications:()->Unit={},onWatchParty:()->Unit={},onOpenPost:(String)->Unit={},onOpenClip:(String)->Unit={},onMovies:()->Unit={},onSeries:()->Unit={}
) {

    val context=LocalContext.current
    val activeProfile=backend.viewerProfiles.activeId();val accountScope=backend.session.localAccountScope
    val scope=key(activeProfile,accountScope){rememberCoroutineScope()}
    val viewer=backend.viewerProfiles.active()
    val personal=remember(activeProfile,accountScope){CinemaPersonalStore(context,activeProfile)}
    val library=remember(backend){LibraryRepository(backend)};val historyRepository=remember(backend){HistoryRepository(backend)}
    val statsRepository=remember(backend){UserViewingStatsRepository(backend)}
    var tab by rememberSaveable(activeProfile,accountScope){mutableIntStateOf(0)};var listTab by rememberSaveable(activeProfile,accountScope){mutableIntStateOf(0)}
    var typeFilter by rememberSaveable{mutableIntStateOf(0)};var listOrder by rememberSaveable{mutableIntStateOf(0)}
    var refresh by remember{mutableIntStateOf(0)};var revision by remember{mutableIntStateOf(0)}
    var account by remember(activeProfile,accountScope){mutableStateOf<AccountProfile?>(null)}
    var stats by remember(activeProfile,accountScope){mutableStateOf<UserViewingStats?>(null)}
    var continuing by remember(activeProfile,accountScope){mutableStateOf<List<ContinueWatchingItem>>(emptyList())}
    var cloudSaved by remember(activeProfile,accountScope){mutableStateOf<List<MediaItem>>(emptyList())}
    var cloudFavorites by remember(activeProfile,accountScope){mutableStateOf<List<MediaItem>>(emptyList())}
    var savedLimited by remember(activeProfile,accountScope){mutableStateOf(false)}
    var favoriteLimited by remember(activeProfile,accountScope){mutableStateOf(false)}
    var history by remember(activeProfile,accountScope){mutableStateOf<List<WatchHistoryItem>>(emptyList())}
    var historyPage by remember(activeProfile,accountScope){mutableIntStateOf(1)};var historyHasMore by remember(activeProfile,accountScope){mutableStateOf(false)}
    var loading by remember(activeProfile,accountScope){mutableStateOf(loggedIn)};var historyLoading by remember(activeProfile,accountScope){mutableStateOf(false)}
    var accountError by remember{mutableStateOf(false)};var statsError by remember{mutableStateOf(false)};var continueError by remember{mutableStateOf(false)}
    var savedError by remember{mutableStateOf(false)};var favoriteError by remember{mutableStateOf(false)};var historyError by remember(activeProfile,accountScope){mutableStateOf(false)}
    var historyRetry by remember(activeProfile,accountScope){mutableIntStateOf(0)};var mutationBusy by remember(activeProfile,accountScope){mutableStateOf(false)}
    var pendingRemoval by remember(activeProfile,accountScope){mutableStateOf<WatchHistoryItem?>(null)};var clearHistory by remember(activeProfile,accountScope){mutableStateOf(false)};var logout by remember(activeProfile,accountScope){mutableStateOf(false)}
    var message by remember(activeProfile,accountScope){mutableStateOf<String?>(null)}
    val saved=remember(cloudSaved,personal,revision,refresh){(cloudSaved+personal.saved()).distinctBy(::cinemaMediaKey)}
    val favorites=remember(cloudFavorites,personal,revision,refresh){(cloudFavorites+personal.favorites()).distinctBy(::cinemaMediaKey)}
    val ratings=remember(personal,revision,refresh){personal.ratings()}
    val artwork=(continuing.map{it.media}+favorites+saved).firstOrNull{!it.backdropPath.isNullOrBlank()||!it.posterPath.isNullOrBlank()}
    val listState=rememberLazyListState();val large=LocalConfiguration.current.fontScale>=1.6f
    LaunchedEffect(tab,listTab){listState.scrollToItem(0);typeFilter=0}
    LaunchedEffect(backend,loggedIn,refresh,activeProfile,accountScope){
        accountError=false;statsError=false;continueError=false;savedError=false;favoriteError=false
        if(!loggedIn){account=null;stats=null;continuing=emptyList();cloudSaved=emptyList();cloudFavorites=emptyList();history=emptyList();historyPage=1;historyHasMore=false;historyError=false;loading=false;return@LaunchedEffect}
        loading=true
        supervisorScope{
            launch{try{account=backend.me()}catch(e:CancellationException){throw e}catch(_:Exception){accountError=true}}
            launch{try{stats=statsRepository.load()}catch(e:CancellationException){throw e}catch(_:Exception){statsError=true}}
            launch{try{continuing=backend.continueWatching()}catch(e:CancellationException){throw e}catch(_:Exception){continueError=true}}
            launch{try{cloudSaved=library.watchlist();savedLimited=library.watchlistLimited}catch(e:CancellationException){throw e}catch(_:Exception){savedError=true}}
            launch{try{cloudFavorites=library.favorites();favoriteLimited=library.favoritesLimited}catch(e:CancellationException){throw e}catch(_:Exception){favoriteError=true}}
        };loading=false
    }
    LaunchedEffect(loggedIn,activeProfile,accountScope,historyPage,historyRetry,refresh){
        if(!loggedIn)return@LaunchedEffect
        historyLoading=true;historyError=false
        try{val page=historyRepository.historyPage(historyPage);history=(if(historyPage==1)page.items else history+page.items).distinctBy{it.target.mediaVersionId};historyHasMore=page.hasMore}
        catch(e:CancellationException){throw e}catch(_:Exception){historyError=true}finally{historyLoading=false}
    }
    fun accountAction(action:()->Unit){if(loggedIn)action()else onRequireAuth()}
    fun removeSaved(media:MediaItem,favorite:Boolean){if(mutationBusy)return;scope.launch{
        mutationBusy=true;message=null
        try{val cloud=if(favorite)cloudFavorites else cloudSaved
            if(loggedIn&&!media.backendId.isNullOrBlank()&&cloud.any{cinemaMediaKey(it)==cinemaMediaKey(media)}){
                if(favorite)backend.toggleFavorite(media.backendId!!)else library.toggleWatchlist(media.backendId!!)
                if(favorite)cloudFavorites=cloudFavorites.filterNot{cinemaMediaKey(it)==cinemaMediaKey(media)}else cloudSaved=cloudSaved.filterNot{cinemaMediaKey(it)==cinemaMediaKey(media)}
            }
            personal.setSaved(if(favorite)"favorites"else"watchlist",media,false);revision++;message="فهرست به‌روز شد."
        }catch(e:CancellationException){throw e}catch(_:Exception){message="تغییر ذخیره نشد. دوباره تلاش کن."}finally{mutationBusy=false}
    }}
    Box(Modifier.fillMaxSize().background(CinemaInk).testTag("cinema-profile-hub")){
        if(artwork!=null)Box(Modifier.fillMaxWidth().height(440.dp)){
            CinemaImage(artwork.backdropPath?:artwork.posterPath,Modifier.fillMaxSize(),true)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CinemaInk.copy(alpha=.72f),CinemaInk.copy(alpha=.9f),CinemaInk))))
        }
        LazyColumn(state=listState,modifier=Modifier.fillMaxSize().testTag("profile-scroll"),contentPadding=PaddingValues(start=20.dp,end=20.dp,top=18.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
            item("identity"){Column(Modifier.statusBarsPadding()){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                    Box(Modifier.size(64.dp).clip(CircleShape).background(CinemaSurface),contentAlignment=Alignment.Center){
                        if(!(if(kidsMode)viewer?.avatarUrl else account?.avatarUrl).isNullOrBlank())CinemaImage(if(kidsMode)viewer?.avatarUrl else account?.avatarUrl,Modifier.fillMaxSize())else Icon(Icons.Outlined.PersonOutline,null,Modifier.size(30.dp),tint=CinemaSoft)
                    }
                    Column(Modifier.weight(1f).padding(horizontal=14.dp)){
                        Text(if(kidsMode)"سینمای کودک"else"سینمای من",color=CinemaGold,fontSize=12.sp,fontWeight=FontWeight.Bold)
                        Text(if(kidsMode)viewer?.name?:"تماشاگر کودک"else account?.displayName?.takeIf(String::isNotBlank)?:account?.username?.takeIf(String::isNotBlank)?:if(!loggedIn)"تماشاگر مهمان"else if(accountError)"حساب کاربری"else"در حال دریافت حساب…",color=CinemaPaper,fontSize=21.sp,lineHeight=30.sp,maxLines=2,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Bold,modifier=Modifier.testTag("account-name"))
                        if(!kidsMode)account?.username?.takeIf(String::isNotBlank)?.let{Text("@"+it,color=CinemaSoft,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)}
                    }
                    IconButton({accountAction(if(kidsMode)onViewerProfiles else onSettings)},Modifier.testTag("profile-settings")){Icon(Icons.Outlined.Settings,"تنظیمات",tint=CinemaPaper)}
                }
                if(loggedIn&&!kidsMode)TextButton(onEditProfile,Modifier.heightIn(min=48.dp).testTag("profile-edit")){Text("ویرایش حساب")}
            }}
            item("tabs"){LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.testTag("profile-hub-tabs")){
                listOf("سینمای من","تاریخچه","آمار تماشا","فهرست‌ها").forEachIndexed{index,label->item(key=index){FilterChip(tab==index,{tab=index},label={Text(label,fontWeight=FontWeight.Bold)},modifier=Modifier.heightIn(min=48.dp).testTag("profile-hub-tab-"+index),colors=FilterChipDefaults.filterChipColors(containerColor=CinemaSurface,selectedContainerColor=CinemaAccent.copy(alpha=.2f),selectedLabelColor=CinemaPaper,labelColor=CinemaSoft))}}
            }}
            message?.let{value->item("operation"){Text(value,color=CinemaSoft,fontSize=13.sp,lineHeight=21.sp,modifier=Modifier.testTag("profile-operation-message"))}}
            if(!loggedIn)item("auth"){CinemaNotice("سینمای شخصی‌ات را بساز","آمار و ادامهٔ تماشا با حساب دریافت می‌شود. فهرست‌های محلی همین دستگاه در دسترس‌اند.",Icons.Outlined.PersonOutline,"ورود به حساب",onRequireAuth)}
            if(accountError&&loggedIn)item("account-error"){CinemaNotice("حساب دریافت نشد","بخش‌های دریافت‌شده همچنان قابل استفاده‌اند.",Icons.Outlined.CloudOff,"دریافت دوباره",{refresh++})}
            if(tab==0||tab==2){
                if(loading&&stats==null)item("stats-loading"){LinearProgressIndicator(Modifier.fillMaxWidth(),color=CinemaAccent,trackColor=CinemaLine)}
                if(statsError)item("stats-error"){Column(Modifier.testTag("profile-stats-error")){CinemaNotice("آمار دریافت نشد","دریافت آمار این پروفایل را دوباره امتحان کن.",Icons.Outlined.CloudOff,"دریافت آمار",{refresh++})}}
                val observed=stats
                if(observed!=null&&observed.historyTitles>0){
                    item("time"){CinemaHubWatchTime(observed)}
                    item("metrics"){CinemaHubMetrics(observed,large,tab==2)}
                    if(tab==2){item("stats-rules"){CinemaCard{Text("آمار تماشای من",fontWeight=FontWeight.Bold,color=CinemaPaper);Text("زمان، مجموع پخش واقعی ثبت‌شده است؛ جلو بردن ویدیو زمان اضافه نمی‌کند. فیلم و قسمت با حداقل ۹۵٪ تکمیل، فقط یک بار شمرده می‌شوند. سریال کامل باید پایان‌یافته باشد و همهٔ قسمت‌های تأییدشده‌اش کامل شده باشند.",color=CinemaSoft,fontSize=13.sp,lineHeight=23.sp,modifier=Modifier.padding(top=8.dp));if(observed.legacyHistoryWithoutTime)Text("بخشی از تاریخچهٔ قدیمی زمان پخش ثبت‌شده ندارد و به ساعت تماشا اضافه نشده است.",color=CinemaGold,fontSize=12.sp,lineHeight=21.sp,modifier=Modifier.padding(top=8.dp));Text("تمام زمان ثبت‌شدهٔ این پروفایل نمایش داده می‌شود.",color=CinemaSoft,fontSize=12.sp,modifier=Modifier.padding(top=8.dp))}};item("taste"){CinemaHubTaste(observed)}}
                }else if(!loading&&!statsError)item("onboarding"){CinemaCard(Modifier.testTag("profile-new-viewer")){Text("داستان سینمایی تو از اینجا شروع می‌شه",color=CinemaPaper,fontSize=25.sp,lineHeight=36.sp,fontWeight=FontWeight.Bold);Text("فیلم و سریال پیدا کن، برای بعد ذخیره کن و با تماشا، سینمای شخصی‌ات را بساز.",color=CinemaSoft,fontSize=13.sp,lineHeight=22.sp,modifier=Modifier.padding(top=10.dp))}}
            }
            if(tab==0){
                if(!kidsMode)item("browse"){Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){OutlinedButton(onMovies,Modifier.weight(1f).heightIn(min=52.dp).testTag("profile-movies")){Text("فیلم‌ها")};OutlinedButton(onSeries,Modifier.weight(1f).heightIn(min=52.dp).testTag("profile-series")){Text("سریال‌ها")}}}
                if(continuing.isNotEmpty()){
                    item("continue-header"){CinemaHubHeading("ادامهٔ تماشا","مدیریت تاریخچه"){tab=1}}
                    items(continuing.take(3),key={"continue-"+it.target.mediaVersionId}){entry->CinemaHubContinueRow(entry,{onPlay(entry.target)},{onMedia(entry.media)})}
                }
                if(continueError)item("continue-error"){CinemaNotice("ادامهٔ تماشا به‌روز نشد","اتصال را بررسی کن.",Icons.Outlined.CloudOff,"تلاش دوباره",{refresh++})}
                if(saved.isNotEmpty()){
                    item("saved-header"){CinemaHubHeading("لیست تماشا","مشاهده همه"){tab=3;listTab=0}}
                    items(saved.take(2),key={"overview-saved-"+cinemaMediaKey(it)}){media->CinemaHubSavedRow(media,{onMedia(media)},{removeSaved(media,false)},!mutationBusy)}
                }
                if(history.isNotEmpty()){
                    item("recent-header"){CinemaHubHeading("اخیراً تماشا کردی","تاریخچه"){tab=1}}
                    items(history.take(2),key={"recent-"+it.target.mediaVersionId}){entry->CinemaHubHistoryRow(entry,{onPlay(entry.target)},{onMedia(entry.media)},{pendingRemoval=entry},!mutationBusy)}
                }
                stats?.takeIf{it.historyTitles>0}?.let{observed->item("taste-snapshot"){CinemaHubTaste(observed)}}
                item("shortcuts"){CinemaCard{
                    CinemaHubMenuRow("فهرست‌های من",cinemaHubNumber(saved.size.toLong())+" برای تماشا · "+cinemaHubNumber(favorites.size.toLong())+" علاقه‌مندی",{tab=3;listTab=0},"profile-watchlist")
                    CinemaHubMenuRow("علاقه‌مندی‌ها","عنوان‌هایی که دوست داری",{tab=3;listTab=1},"profile-favorites")
                    CinemaHubMenuRow("امتیازهای من","خصوصی و ذخیره‌شده در این دستگاه",{tab=3;listTab=2},"profile-ratings")
                    CinemaHubMenuRow("دانلودها","فایل‌های آمادهٔ تماشای آفلاین",onDownloads,"profile-downloads")
                }}
                item("settings-menu"){CinemaCard{
                    CinemaHubMenuRow("پروفایل‌های تماشا","انتخاب پروفایل و حالت کودک",{accountAction(onViewerProfiles)},"profile-viewers")
                    if(!kidsMode){CinemaHubMenuRow("تنظیمات برنامه","پخش، دانلود و اعلان‌ها",{accountAction(onSettings)},"profile-settings-menu");CinemaHubMenuRow("کنترل والدین","دسترسی کودک",{accountAction(onParentalControls)},"profile-parental");CinemaHubMenuRow("امنیت حساب","دستگاه‌ها و نشست‌ها",{accountAction(onSecurity)},"profile-security")}
                }}
                if(loggedIn&&!kidsMode)item("logout"){TextButton({logout=true},Modifier.heightIn(min=48.dp).testTag("profile-logout")){Text("خروج از حساب",color=CinemaSoft)}}
            }
            if(tab==1){
                item("history-header"){CinemaHubHeading("تاریخچهٔ تماشا",if(loggedIn&&history.isNotEmpty())"پاک‌کردن"else null){clearHistory=true}}
                item("history-filter"){CinemaHubTypeFilter(typeFilter,{typeFilter=it},true)}
                if(historyError)item("history-error"){CinemaNotice("تاریخچه دریافت نشد","ردیف‌های دریافت‌شده حفظ شده‌اند.",Icons.Outlined.CloudOff,"تلاش دوباره",{historyRetry++})}
                val rows=history.filter{typeFilter==0||(typeFilter==1&&it.media.type==MediaType.MOVIE)||(typeFilter==2&&it.media.type==MediaType.TV)||(typeFilter==3&&it.episodeId!=null)}
                items(rows,key={"history-"+it.target.mediaVersionId}){entry->CinemaHubHistoryRow(entry,{onPlay(entry.target)},{onMedia(entry.media)},{pendingRemoval=entry},!mutationBusy)}
                if(historyLoading)item("history-loading"){LinearProgressIndicator(Modifier.fillMaxWidth(),color=CinemaAccent)}
                if(loggedIn&&!historyLoading&&!historyError&&rows.isEmpty())item("history-empty"){CinemaNotice("تاریخچه‌ای در این بخش ثبت نشده","با تماشای عنوان، جای ادامه و سابقهٔ آن اینجا دیده می‌شود.",Icons.Outlined.History)}
                if(historyHasMore)item("history-more"){OutlinedButton({historyPage++},enabled=!historyLoading&&!historyError,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).testTag("profile-history-more")){Text("تاریخچهٔ بیشتر")}}
            }
            if(tab==3){
                if((listTab==0&&savedLimited)||(listTab==1&&favoriteLimited))item("limited-library"){
                    CinemaNotice("همهٔ فهرست دریافت نشده","بخش اول فهرست در دسترس است. دریافت همهٔ موارد به نسخهٔ جدید سرویس نیاز دارد.",Icons.Outlined.Info)
                }
                item("list-tabs"){Column{
                    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("برای تماشا","علاقه‌مندی‌ها","امتیازها").forEachIndexed{index,label->item(key=index){FilterChip(listTab==index,{listTab=index},label={Text(label)},modifier=Modifier.heightIn(min=48.dp).testTag("profile-list-tab-"+index))}}}
                    CinemaHubTypeFilter(typeFilter,{typeFilter=it},false)
                    if(listTab<2)LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("جدیدترین ذخیره","نام عنوان","امتیاز TMDB").forEachIndexed{index,label->item{FilterChip(listOrder==index,{listOrder=index},label={Text(label)},modifier=Modifier.heightIn(min=48.dp).testTag("profile-list-order-"+index))}}}
                    Text(if(listTab==2)"امتیازهای شخصی فقط در همین دستگاه و پروفایل ذخیره می‌شوند."else"ذخیره‌کردن عنوان به معنی وجود فایل قابل پخش نیست.",color=CinemaSoft,fontSize=12.sp,lineHeight=21.sp,modifier=Modifier.padding(top=8.dp))
                }}
                if(savedError||favoriteError)item("lists-error"){CinemaNotice("بخشی از فهرست آنلاین به‌روز نشد","ذخیره‌های محلی و عنوان‌های قبلاً دریافت‌شده حفظ شده‌اند.",Icons.Outlined.CloudOff,"دریافت فهرست‌ها",{refresh++})}
                val source=if(listTab==0)saved else favorites
                val filtered=source.filter{typeFilter==0||it.type==if(typeFilter==1)MediaType.MOVIE else MediaType.TV}
                val entries=when(listOrder){1->filtered.sortedBy{it.title};2->filtered.sortedByDescending{it.vote};else->filtered}
                if(listTab<2){items(entries,key={"list-"+cinemaMediaKey(it)}){entry->CinemaHubSavedRow(entry,{onMedia(entry)},{removeSaved(entry,listTab==1)},!mutationBusy)}
                    if(!loading&&entries.isEmpty()&&!(if(listTab==0)savedError else favoriteError))item("lists-empty"){CinemaNotice("این فهرست هنوز خالی است","از صفحهٔ عنوان آن را برای تماشا یا در علاقه‌مندی‌ها ذخیره کن.",Icons.Outlined.BookmarkBorder)}
                }else{
                    val visibleRatings=ratings.filter{typeFilter==0||it.media.type==if(typeFilter==1)MediaType.MOVIE else MediaType.TV}
                    items(visibleRatings,key={"rating-"+cinemaMediaKey(it.media)}){rating->CinemaHubSavedRow(rating.media,{onMedia(rating.media)},{personal.setRating(rating.media,null);revision++},true,"امتیاز شخصی: "+cinemaHubNumber(rating.value.toLong())+" از ۱۰ · محلی")}
                    if(visibleRatings.isEmpty())item("ratings-empty"){CinemaNotice("هنوز امتیازی در این بخش نداده‌ای","از صفحهٔ عنوان امتیاز بده. امتیاز شخصی با TMDB متفاوت است.",Icons.Outlined.StarOutline)}
                }
            }
        }
    }
    val removal=pendingRemoval
    if(removal!=null||clearHistory)AlertDialog(onDismissRequest={if(!mutationBusy){pendingRemoval=null;clearHistory=false}},title={Text(if(clearHistory)"پاک‌کردن تاریخچه؟"else"حذف این سابقه؟")},text={Text("سابقه، زمان ثبت‌شده و جای ادامهٔ این تاریخچه پاک می‌شود و آمار به‌روز خواهد شد. این کار قابل بازگشت نیست.")},
        confirmButton={TextButton({scope.launch{mutationBusy=true
            try{if(clearHistory)historyRepository.clear()else removal?.let{historyRepository.remove(it.target.mediaVersionId)};history=emptyList();historyPage=1;historyHasMore=false;historyRetry++;refresh++;pendingRemoval=null;clearHistory=false;message="تاریخچه به‌روز شد."}
            catch(e:CancellationException){throw e}catch(_:Exception){message="پاک‌کردن انجام نشد. دوباره تلاش کن."}finally{mutationBusy=false}
        }},enabled=!mutationBusy,modifier=Modifier.heightIn(min=48.dp).testTag("profile-history-confirm")){Text("پاک‌کردن")}},dismissButton={TextButton({pendingRemoval=null;clearHistory=false},enabled=!mutationBusy){Text("انصراف")}})
    if(logout)AlertDialog(onDismissRequest={if(!mutationBusy)logout=false},title={Text("خروج از حساب؟")},text={Text("برای دریافت آمار و ادامهٔ تماشا دوباره باید وارد شوی.")},confirmButton={TextButton({scope.launch{mutationBusy=true;try{backend.logout();logout=false;onLoggedOut()}catch(e:CancellationException){throw e}catch(_:Exception){logout=false;message="خروج انجام نشد. دوباره تلاش کن."}finally{mutationBusy=false}}},enabled=!mutationBusy){Text("خروج")}},dismissButton={TextButton({logout=false},enabled=!mutationBusy){Text("انصراف")}})
}

@Composable private fun CinemaHubHeading(title:String,action:String?,onAction:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,color=CinemaPaper,fontSize=18.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));if(action!=null)TextButton(onAction,Modifier.heightIn(min=48.dp)){Text(action)}}}
@Composable private fun CinemaHubTypeFilter(selected:Int,onSelect:(Int)->Unit,episodes:Boolean){LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){(if(episodes)listOf("همه","فیلم","سریال","قسمت‌ها")else listOf("همه","فیلم","سریال")).forEachIndexed{index,label->item{FilterChip(selected==index,{onSelect(index)},label={Text(label)},modifier=Modifier.heightIn(min=48.dp).testTag("profile-type-"+index))}}}}
@Composable private fun CinemaHubWatchTime(stats:UserViewingStats){
    Surface(shape=RoundedCornerShape(24.dp),color=CinemaSurface.copy(alpha=.9f),border=BorderStroke(1.dp,CinemaAccent.copy(alpha=.2f)),modifier=Modifier.fillMaxWidth().testTag("profile-stats-ready")){
        Column(Modifier.background(Brush.linearGradient(listOf(CinemaAccent.copy(alpha=.12f),Color.Transparent))).padding(22.dp)){
            Text("PERSONAL CINEMA",color=CinemaGold,fontSize=10.sp,letterSpacing=1.sp,fontWeight=FontWeight.Bold)
            Text("زمان تماشای ثبت‌شده",color=CinemaSoft,fontSize=13.sp,modifier=Modifier.padding(top=12.dp))
            Text(cinemaHubWatchDuration(stats.totalWatchMs),color=CinemaPaper,fontSize=30.sp,lineHeight=43.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=4.dp).testTag("profile-watch-time"))
            Text("مجموع پخش واقعی این پروفایل",color=CinemaSoft,fontSize=12.sp,lineHeight=20.sp,modifier=Modifier.padding(top=8.dp))
        }
    }
}
@Composable private fun CinemaHubMetrics(stats:UserViewingStats,large:Boolean,full:Boolean){
    val primary=listOf("فیلم کامل" to stats.moviesWatched,"قسمت کامل" to stats.episodesWatched,"سریال کامل" to stats.seriesWatched)
    Column(Modifier.testTag("profile-real-metrics"),verticalArrangement=Arrangement.spacedBy(10.dp)){
        primary.chunked(if(large)1 else 3).forEach{group->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){group.forEach{(label,count)->Column(Modifier.weight(1f).padding(vertical=8.dp)){Text(cinemaHubNumber(count.toLong()),color=CinemaPaper,fontSize=25.sp,fontWeight=FontWeight.Bold);Text(label,color=CinemaSoft,fontSize=12.sp,lineHeight=20.sp)}}}}
        if(full){HorizontalDivider(color=CinemaLine);listOf("سریال شروع‌شده" to stats.seriesStarted,"در حال تماشا" to stats.currentlyWatching,"عنوان در تاریخچه" to stats.historyTitles).forEach{(label,count)->Row(Modifier.fillMaxWidth()){Text(label,color=CinemaSoft,modifier=Modifier.weight(1f));Text(cinemaHubNumber(count.toLong()),color=CinemaPaper)}}}
    }
}
@Composable private fun CinemaHubTaste(stats:UserViewingStats){CinemaCard{
    Text("سلیقهٔ سینمایی تو",color=CinemaPaper,fontSize=18.sp,fontWeight=FontWeight.Bold)
    if(!cinemaHubTasteAvailable(stats)){Text("با ثبت سابقهٔ حداقل ۱۰ عنوان، الگوی ژانر و کشورها شکل می‌گیرد. هنوز دادهٔ کافی برای نتیجه‌گیری وجود ندارد.",color=CinemaSoft,fontSize=13.sp,lineHeight=23.sp,modifier=Modifier.padding(top=8.dp).testTag("profile-taste-insufficient"))}
    else{Text("براساس "+cinemaHubNumber(stats.tasteSampleSize.toLong())+" عنوان و اطلاعات ثبت‌شدهٔ آن‌ها",color=CinemaSoft,fontSize=12.sp,lineHeight=21.sp,modifier=Modifier.padding(top=6.dp))
        listOf("ژانرهای پررنگ‌تر" to cinemaHubTasteShares(stats.genres),"بیشتر چی می‌بینی؟" to cinemaHubTasteShares(stats.countries)).forEach{(label,shares)->if(shares.isNotEmpty()){
            Text(label,color=CinemaPaper,fontSize=13.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=16.dp));shares.take(5).forEach{share->Row(Modifier.fillMaxWidth().padding(top=10.dp)){Text(share.label,color=CinemaSoft,fontSize=13.sp,modifier=Modifier.weight(1f));Text(cinemaHubNumber((share.fraction*100).toLong())+"٪",color=CinemaGold,fontSize=12.sp)};LinearProgressIndicator(progress={share.fraction},modifier=Modifier.fillMaxWidth().padding(top=5.dp).height(4.dp),color=CinemaAccent,trackColor=CinemaLine)}
        }};Text("سهم عنوان‌های چندژانری یا چندکشوری تقسیم می‌شود. موجودبودن دوبله، سلیقهٔ زبان تماشا را ثابت نمی‌کند.",color=CinemaSoft,fontSize=11.sp,lineHeight=19.sp,modifier=Modifier.padding(top=14.dp))
    }
}}
@Composable private fun CinemaHubMenuRow(title:String,description:String,onClick:()->Unit,tag:String){Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable(role=Role.Button,onClick=onClick).testTag(tag).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f).padding(end=12.dp)){Text(title,color=CinemaPaper,fontSize=15.sp,fontWeight=FontWeight.Medium);Text(description,color=CinemaSoft,fontSize=12.sp,lineHeight=20.sp,modifier=Modifier.padding(top=3.dp))};Icon(Icons.Outlined.ChevronLeft,null,tint=CinemaSoft,modifier=Modifier.size(20.dp))}}
@Composable private fun CinemaHubPoster(media:MediaItem,modifier:Modifier){Box(modifier.clip(RoundedCornerShape(12.dp)).background(CinemaLine),contentAlignment=Alignment.Center){if(cinemaImage(media.posterPath)!=null)CinemaImage(media.posterPath,Modifier.fillMaxSize())else Icon(Icons.Outlined.Movie,null,tint=CinemaSoft)}}
@Composable private fun CinemaHubContinueRow(entry:ContinueWatchingItem,onPlay:()->Unit,onDetails:()->Unit){Surface(color=CinemaSurface,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,CinemaLine),modifier=Modifier.fillMaxWidth().testTag("profile-continue-"+entry.target.mediaVersionId)){
    Row(Modifier.clickable(role=Role.Button,onClick=onPlay).padding(14.dp),verticalAlignment=Alignment.CenterVertically){CinemaHubPoster(entry.media,Modifier.width(66.dp).height(94.dp));Column(Modifier.weight(1f).padding(horizontal=12.dp)){
        Text(entry.media.title,color=CinemaPaper,fontWeight=FontWeight.Bold,fontSize=15.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
        if(entry.episodeLabel.isNotBlank())Text(entry.episodeLabel,color=CinemaSoft,fontSize=12.sp,lineHeight=19.sp)
        val progress=entry.progress.takeIf(Float::isFinite)?.coerceIn(0f,1f)?:0f
        LinearProgressIndicator(progress={progress},Modifier.fillMaxWidth().padding(top=10.dp).height(3.dp),color=CinemaAccent,trackColor=CinemaLine)
        Text(if(entry.durationMs>entry.positionMs)cinemaHubWatchDuration(entry.durationMs-entry.positionMs)+" باقی‌مانده"else"ادامه از جای ثبت‌شده",color=CinemaSoft,fontSize=11.sp,lineHeight=18.sp,modifier=Modifier.padding(top=5.dp))
    };IconButton(onDetails){Icon(Icons.Outlined.Info,"اطلاعات عنوان",tint=CinemaSoft)}}
}}
@Composable private fun CinemaHubHistoryRow(entry:WatchHistoryItem,onPlay:()->Unit,onDetails:()->Unit,onRemove:()->Unit,enabled:Boolean){Surface(color=CinemaSurface,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,CinemaLine),modifier=Modifier.fillMaxWidth().testTag("profile-history-"+entry.target.mediaVersionId)){Column(Modifier.padding(14.dp)){
    Row(Modifier.fillMaxWidth().clickable(role=Role.Button,onClick=onDetails),verticalAlignment=Alignment.CenterVertically){CinemaHubPoster(entry.media,Modifier.width(54.dp).height(78.dp));Column(Modifier.weight(1f).padding(horizontal=12.dp)){
        Text(entry.media.title,color=CinemaPaper,fontWeight=FontWeight.Bold,fontSize=15.sp,maxLines=2,overflow=TextOverflow.Ellipsis);if(entry.episodeLabel.isNotBlank())Text(entry.episodeLabel,color=CinemaSoft,fontSize=12.sp,lineHeight=19.sp);Text(if(entry.completed)"این نوبت کامل شده"else"جای ادامه ثبت شده",color=if(entry.completed)CinemaGold else CinemaSoft,fontSize=11.sp,lineHeight=19.sp);if(entry.updatedAt.isNotBlank())Text(entry.updatedAt.take(10),color=CinemaSoft,fontSize=11.sp)
    };IconButton(onRemove,enabled=enabled,modifier=Modifier.testTag("profile-history-remove-"+entry.target.mediaVersionId)){Icon(Icons.Outlined.DeleteOutline,"حذف این سابقه",tint=CinemaSoft)}}
    OutlinedButton(onPlay,enabled=entry.media.streamReady,modifier=Modifier.fillMaxWidth().padding(top=10.dp).heightIn(min=48.dp)){Text(if(!entry.media.streamReady)"فایل فعلاً آماده نیست"else if(entry.completed)"تماشای دوباره"else"ادامهٔ تماشا")}
}}}
@Composable private fun CinemaHubSavedRow(media:MediaItem,onDetails:()->Unit,onRemove:()->Unit,enabled:Boolean,annotation:String?=null){Surface(color=CinemaSurface,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,CinemaLine),modifier=Modifier.fillMaxWidth().testTag("profile-saved-"+cinemaMediaKey(media))){Row(Modifier.clickable(role=Role.Button,onClick=onDetails).padding(14.dp),verticalAlignment=Alignment.CenterVertically){CinemaHubPoster(media,Modifier.width(54.dp).height(78.dp));Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(media.title,color=CinemaPaper,fontSize=15.sp,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis);Text((if(media.type==MediaType.MOVIE)"فیلم"else"سریال")+media.year.takeIf(String::isNotBlank)?.let{" · "+it}.orEmpty(),color=CinemaSoft,fontSize=12.sp,modifier=Modifier.padding(top=4.dp));if(annotation!=null)Text(annotation,color=CinemaGold,fontSize=12.sp,lineHeight=20.sp,modifier=Modifier.padding(top=5.dp))};IconButton(onRemove,enabled=enabled,modifier=Modifier.testTag("profile-list-remove-"+cinemaMediaKey(media))){Icon(Icons.Outlined.DeleteOutline,"برداشتن از این فهرست",tint=CinemaSoft)}}}}
