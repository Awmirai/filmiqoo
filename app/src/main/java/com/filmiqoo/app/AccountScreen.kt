package com.filmiqoo.app
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun MeScreen(
    backend:BackendRepository,
    repository:TmdbRepository,
    kidsMode:Boolean=false,
    onMedia:(MediaItem)->Unit,
    onPlay:(PlaybackTarget)->Unit,
    onClips:()->Unit,
    onCommunity:()->Unit,
    onOpenPost:(String)->Unit,
    onOpenClip:(String)->Unit,
    onDownloads:()->Unit,
    onLibrary:()->Unit,
    onSocialSaves:()->Unit,
    onHistory:()->Unit,
    onCreatorStudio:()->Unit,
    onInbox:()->Unit,
    onSettings:()->Unit,
    onViewerProfiles:()->Unit,
    onParentalControls:()->Unit,
    onSecurity:()->Unit,
    onSafety:()->Unit,
    onFollowRequests:()->Unit,
    onCloseFriends:()->Unit,
    onEditProfile:()->Unit,
    onFilmDna:()->Unit,
    onReputation:(String)->Unit,
    onSeriesCalendar:()->Unit,
    onSocialCollections:()->Unit,
    onLoggedOut:()->Unit,
    onBack:()->Unit={}
) {
    var legacy by rememberSaveable { mutableStateOf(false) }
    var profile by remember { mutableStateOf<AccountProfile?>(null) }
    var error by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var logout by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    BackHandler { if(legacy)legacy=false else onBack() }
    LaunchedEffect(retry) { try{profile=backend.me();error=false}catch(e:CancellationException){throw e}catch(_:Exception){error=true} }
    if(legacy) {
        Column(Modifier.fillMaxSize()) { TextButton({legacy=false},Modifier.statusBarsPadding()){Text("بازگشت به حساب")}
            Box(Modifier.weight(1f)){LegacyMeScreen(backend,repository,kidsMode,onMedia,onPlay,onClips,onCommunity,onOpenPost,onOpenClip,onDownloads,onLibrary,onSocialSaves,onHistory,onCreatorStudio,onInbox,onSettings,onViewerProfiles,onParentalControls,onSecurity,onSafety,onFollowRequests,onCloseFriends,onEditProfile,onFilmDna,onReputation,onSeriesCalendar,onSocialCollections,onLoggedOut)}
        };return
    }
    LazyColumn(Modifier.fillMaxSize().background(CinemaInk),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item{CinemaPageHeader("حساب و تنظیمات",profile?.displayName,onBack)}
        if(error)item{Box(Modifier.padding(horizontal=20.dp)){CinemaNotice("مشخصات حساب دریافت نشد","تنظیمات و پیام‌ها همچنان از مسیرهای زیر در دسترس‌اند.",Icons.Outlined.CloudOff,"تلاش دوباره",{retry++})}}
        item{CinemaCard(Modifier.padding(horizontal=20.dp).fillMaxWidth()){
            Text(profile?.displayName?:"حساب من",style=MaterialTheme.typography.titleLarge)
            TextButton(onEditProfile){Text("ویرایش مشخصات")}
            TextButton(onViewerProfiles){Text("پروفایل‌های تماشا")}
        }}
        if(!kidsMode)item{CinemaCard(Modifier.padding(horizontal=20.dp).fillMaxWidth()){
            Text("ارتباط با دیگران",style=MaterialTheme.typography.titleMedium)
            CinemaAction(Icons.Outlined.ChatBubbleOutline,"پیام‌های خصوصی",onInbox,Modifier.fillMaxWidth().padding(top=12.dp))
            Text("گفت‌وگوهای شخصی؛ جدا از دیدگاه آثار و اعلان‌های برنامه.",color=CinemaSoft,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=8.dp))
            TextButton(onSafety){Text("مسدودسازی و ایمنی")}
        }}
        item{CinemaCard(Modifier.padding(horizontal=20.dp).fillMaxWidth()){
            Text("تنظیمات من",style=MaterialTheme.typography.titleMedium)
            listOf("پخش، اینترنت و اعلان‌ها" to onSettings,"امنیت حساب" to onSecurity,"کنترل والدین" to onParentalControls).forEach{(label,action)->TextButton(action,Modifier.fillMaxWidth()){Text(label,modifier=Modifier.weight(1f));Icon(Icons.Outlined.ChevronLeft,null)}}
        }}
        if(!kidsMode)item{Box(Modifier.padding(horizontal=20.dp)){CinemaAction(Icons.Outlined.AccountCircle,"پروفایل و فعالیت‌های قبلی",{legacy=true},Modifier.fillMaxWidth())}}
        item{TextButton({logout=true},Modifier.padding(horizontal=20.dp)){Text("خروج از حساب")}}
    }
    if(logout)AlertDialog(onDismissRequest={if(!busy)logout=false},title={Text("از حساب خارج می‌شوی؟")},text={Text("پیام‌ها و فهرست‌های حساب پاک نمی‌شوند.")},
        confirmButton={TextButton({if(!busy){busy=true;scope.launch{try{backend.logout();onLoggedOut()}finally{busy=false;logout=false}}}},enabled=!busy){Text("خروج")}},
        dismissButton={TextButton({logout=false},enabled=!busy){Text("انصراف")}})
}
