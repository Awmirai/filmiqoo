package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Account identity and cinema activity share a hub. Existing library/profile routes remain intact. */
@Composable
fun ConnectedProfileScreen(
    backend:BackendRepository,repository:TmdbRepository,kidsMode:Boolean=false,
    onMedia:(MediaItem)->Unit,onPlay:(PlaybackTarget)->Unit,onCommunity:()->Unit,onDownloads:()->Unit,
    onLibrary:()->Unit,onSocialSaves:()->Unit,onHistory:()->Unit,onCreatorStudio:()->Unit,onInbox:()->Unit,
    onSettings:()->Unit,onViewerProfiles:()->Unit,onParentalControls:()->Unit,onSecurity:()->Unit,onSafety:()->Unit,
    onFollowRequests:()->Unit,onCloseFriends:()->Unit,onEditProfile:()->Unit,onFilmDna:()->Unit,
    onReputation:(String)->Unit,onSeriesCalendar:()->Unit,onSocialCollections:()->Unit,onLoggedOut:()->Unit,
    loggedIn:Boolean=true,onRequireAuth:()->Unit={},onClips:()->Unit={},onCreate:()->Unit={},
    onNotifications:()->Unit={},onWatchParty:()->Unit={},onOpenPost:(String)->Unit={},onOpenClip:(String)->Unit={}
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val activeProfile=backend.viewerProfiles.activeId()
    val personal=remember(activeProfile){CinemaPersonalStore(context,activeProfile)}
    val creators=remember(backend){CreatorChannelRepository(backend)}
    var profile by remember{mutableStateOf<AccountProfile?>(null)}
    var stats by remember{mutableStateOf<LibraryStats?>(null)}
    var watching by remember{mutableStateOf<List<ContinueWatchingItem>>(emptyList())}
    var favorites by remember{mutableStateOf<List<MediaItem>>(emptyList())}
    var posts by remember{mutableStateOf<List<SocialPost>>(emptyList())}
    var clips by remember{mutableStateOf<List<ReelFeedItem>>(emptyList())}
    var loading by remember{mutableStateOf(false)}
    var activityLoading by remember{mutableStateOf(false)}
    var accountError by remember{mutableStateOf(false)}
    var activityError by remember{mutableStateOf(false)}
    var refresh by remember{mutableIntStateOf(0)}
    var tab by rememberSaveable{mutableIntStateOf(0)}
    var more by rememberSaveable{mutableStateOf(false)}
    var logout by remember{mutableStateOf(false)}
    var logoutBusy by remember{mutableStateOf(false)}
    val snackbar=remember{SnackbarHostState()}
    fun accountAction(action:()->Unit){if(loggedIn)action()else onRequireAuth()}
    LaunchedEffect(refresh,loggedIn,activeProfile){
        profile=null;stats=null;watching=emptyList();favorites=emptyList();accountError=false
        if(!loggedIn)return@LaunchedEffect
        loading=true
        try {
            coroutineScope {
                val identity=async { cinemaUiOptional{backend.me()} }
                val totals=async { cinemaUiOptional{backend.libraryStats()} }
                val progress=async { cinemaUiOptional{backend.continueWatching()} }
                val saved=async { cinemaUiOptional{backend.favorites()} }
                profile=identity.await();stats=totals.await();watching=progress.await().orEmpty();favorites=saved.await().orEmpty()
                accountError=profile==null || stats==null
            }
        }finally{loading=false}
    }
    LaunchedEffect(tab,profile?.id,refresh,loggedIn){
        posts=emptyList();clips=emptyList();activityError=false
        val id=profile?.id
        if(tab==0||!loggedIn||id==null)return@LaunchedEffect
        activityLoading=true
        try{if(tab==1)posts=creators.userPosts(id)else clips=creators.userReels(id)}
        catch(e:CancellationException){throw e}catch(_:Exception){activityError=true}finally{activityLoading=false}
    }
    Box(Modifier.fillMaxSize().background(CinemaInk).testTag("cinema-profile-hub")) {
        LazyColumn(modifier=Modifier.testTag("profile-scroll"),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item("header"){CinemaPageHeader(if(kidsMode)"پروفایل تماشا"else "فضای من","سلیقه‌ات، آدم‌هایت، سینمایت"){
                if(!kidsMode)IconButton({accountAction(onSettings)},Modifier.testTag("profile-settings")){Icon(Icons.Outlined.Settings,"تنظیمات حساب")}
                IconButton({refresh++},enabled=!loading){Icon(Icons.Outlined.Refresh,"تازه‌کردن پروفایل")}
            }}
            item("identity"){ProfileIdentityCard(profile,loggedIn,loading,onEdit={accountAction(if(kidsMode)onViewerProfiles else onEditProfile)},onLogin=onRequireAuth,kidsMode=kidsMode,viewer=backend.viewerProfiles.active())}
            if(accountError)item("error"){Box(Modifier.padding(horizontal=20.dp)){CinemaNotice("همگام‌سازی کامل نشد","بعضی اطلاعات حساب دریافت نشد. مسیر فهرست‌ها، دانلودها و تنظیمات همچنان باز است.",Icons.Outlined.CloudOff,"تلاش دوباره",{refresh++})}}
            if(!kidsMode)item("party"){CommunityWatchTogetherCard({accountAction(onWatchParty)})}
            item("tabs"){LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                items(if(kidsMode)listOf(0 to "تماشای من")else listOf(0 to "تماشای من",1 to "پست‌های من",2 to "کلیپ‌های من")){(id,label)->
                    FilterChip(tab==id,{tab=id},label={Text(label)},modifier=Modifier.testTag("profile-tab-$id"))
                }
            }}
            if(tab==0 || kidsMode) {
                if(watching.isNotEmpty()) {
                    item("continue-header"){CinemaHeading("از همان‌جا ادامه بده",action="تاریخچه",onAction={accountAction(onHistory)})}
                    item("continue"){LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        items(watching,key={it.target.mediaVersionId}){entry->CinemaCard(Modifier.width(250.dp).clickable{onPlay(entry.target)}){
                            Box(Modifier.fillMaxWidth().aspectRatio(16f/9f).clip(RoundedCornerShape(14.dp))){CinemaImage(entry.media.backdropPath?:entry.media.posterPath,Modifier.fillMaxSize(),true);Icon(Icons.Outlined.PlayCircleOutline,"ادامهٔ پخش",Modifier.align(Alignment.Center).size(40.dp))}
                            Text(entry.media.title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=10.dp))
                            if(entry.episodeLabel.isNotBlank())Text(entry.episodeLabel,color=CinemaSoft,style=MaterialTheme.typography.bodySmall)
                            LinearProgressIndicator(progress={entry.progress.coerceIn(0f,1f)},Modifier.fillMaxWidth().padding(top=10.dp),color=CinemaAccent,trackColor=CinemaLine)
                        }}
                    }}
                }
                item("library"){CinemaCard(Modifier.padding(horizontal=20.dp).fillMaxWidth()){
                    Text("سینمای شخصی تو",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    Text("فهرست تماشا، علاقه‌مندی‌ها و دیده‌شده‌ها؛ کتابخانه‌ات از اینجا در دسترس است.",color=CinemaSoft,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(vertical=10.dp))
                    CinemaAction(Icons.Outlined.Bookmarks,"بازکردن کتابخانهٔ من",onLibrary,Modifier.fillMaxWidth().testTag("profile-library"),primary=true)
                    ProfileHubAction(Icons.Outlined.Download,"دانلودهای من","صف دانلود و تماشای آفلاین",onDownloads,Modifier.testTag("profile-downloads"))
                    ProfileHubAction(Icons.Outlined.History,"تاریخچهٔ تماشا","پیشرفت واقعی تماشای حساب",{accountAction(onHistory)})
                    if(!kidsMode)ProfileHubAction(Icons.Outlined.Event,"تقویم سریال‌ها","قسمت‌های آثاری که دنبال می‌کنی",{accountAction(onSeriesCalendar)})
                }}
                val saved=(favorites+personal.favorites()).distinctBy{it.key}
                if(saved.isNotEmpty()) {
                    item("favorite-header"){CinemaHeading("علاقه‌مندی‌های من",action="همه",onAction=onLibrary)}
                    item("favorites"){LazyRow(contentPadding=PaddingValues(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                        items(saved.take(12),key={it.key}){media->Column(Modifier.width(112.dp).clickable{onMedia(media)}){
                            CinemaImage(media.posterPath,Modifier.fillMaxWidth().aspectRatio(2f/3f).clip(RoundedCornerShape(14.dp)))
                            Text(media.title,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(top=8.dp))
                        }}
                    }}
                }
                stats?.let { real->item("stats"){CinemaCard(Modifier.padding(horizontal=20.dp).fillMaxWidth()){
                    Text("رد پای تماشای تو",style=MaterialTheme.typography.titleMedium)
                    Text("${real.distinctTitles} عنوان در تاریخچه · ${real.completedVersions} نسخهٔ کامل‌شده",color=CinemaSoft,modifier=Modifier.padding(top=8.dp))
                    Text(profileWatchTime(real.watchTimeMinutes)+" تماشا",color=CinemaAccent,modifier=Modifier.padding(top=6.dp))
                }} }
            } else {
                if(!loggedIn)item("activity-auth"){Box(Modifier.padding(horizontal=20.dp)){CinemaNotice("صدای تو در سینماکلاب","برای انتشار پست و کلیپ و دیدن فعالیت حسابت وارد شو.",Icons.Outlined.PersonOutline,"ورود",onRequireAuth)}}
                else {
                    if(activityLoading)item("activity-loading"){LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal=20.dp),color=CinemaAccent)}
                    if(activityError || (profile==null&&accountError))item("activity-error"){Box(Modifier.padding(horizontal=20.dp)){CinemaNotice("فعالیت دریافت نشد","پست‌ها و کلیپ‌های قبلی حساب حذف نشده‌اند.",Icons.Outlined.CloudOff,"تلاش دوباره",{refresh++})}}
                    if(profile!=null&&!activityLoading&&!activityError&&(if(tab==1)posts.isEmpty()else clips.isEmpty()))item("activity-empty"){Box(Modifier.padding(horizontal=20.dp)){CinemaNotice(
                        if(tab==1)"هنوز پستی ننوشته‌ای"else "هنوز کلیپی منتشر نکرده‌ای",
                        if(tab==1)"از تجربهٔ تماشایت بنویس؛ پیشنهادت می‌تواند انتخاب فیلم نفر بعد باشد."else "یک کلیپ سینمایی منتشر کن. فایل‌ها پس از پردازش و انتشار اینجا دیده می‌شوند.",
                        Icons.Outlined.EditNote,"ساخت محتوا",onCreate)}}
                    if(tab==1)items(posts,key={it.id}){post->ProfilePostPreview(post,{onOpenPost(post.id)})}
                    else items(clips,key={it.id}){clip->CommunityClipCard(clip,{onOpenClip(clip.id)},{})}
                }
            }
            if(!kidsMode)item("connections"){CinemaCard(Modifier.padding(horizontal=20.dp).fillMaxWidth()){
                Text("آدم‌ها و محتوا",style=MaterialTheme.typography.titleLarge)
                ProfileHubAction(Icons.Outlined.Forum,"سینماکلاب","کشف آدم‌ها، نقدها و گفت‌وگوهای آثار",onCommunity)
                ProfileHubAction(Icons.Outlined.EditNote,"ساخت پست و کلیپ","نظر، نظرسنجی، استوری یا ویدیو",{accountAction(onCreate)},Modifier.testTag("profile-create"))
                ProfileHubAction(Icons.Outlined.BookmarkBorder,"پست‌ها و کلیپ‌های ذخیره‌شده","ذخیره‌های اجتماعی حساب",{accountAction(onSocialSaves)})
                ProfileHubAction(Icons.Outlined.ChatBubbleOutline,"پیام‌های خصوصی","گفت‌وگو با دوستان",{accountAction(onInbox)})
                ProfileHubAction(Icons.Outlined.Notifications,"اعلان‌های اجتماعی","پاسخ‌ها و تعامل‌های جدید",{accountAction(onNotifications)})
                ProfileHubAction(Icons.Outlined.CollectionsBookmark,"مجموعه‌های من","لیست‌های سینمایی ساخته‌شده توسط تو",{accountAction(onSocialCollections)})
            }}
            item("account"){CinemaCard(Modifier.padding(horizontal=20.dp).fillMaxWidth()){
                Text("حساب و تماشا",style=MaterialTheme.typography.titleLarge)
                ProfileHubAction(Icons.Outlined.SwitchAccount,"پروفایل‌های تماشا",if(kidsMode)"بازگشت با تأیید والدین"else "انتخاب پروفایل و پروفایل کودک",{accountAction(onViewerProfiles)})
                if(!kidsMode){
                    ProfileHubAction(Icons.Outlined.Settings,"تنظیمات","پخش، اینترنت، اعلان‌ها و حریم خصوصی",{accountAction(onSettings)})
                    TextButton({more=!more},Modifier.fillMaxWidth().testTag("profile-more")){Text(if(more)"بستن ابزارهای حساب"else "ابزارهای حساب و ایمنی",Modifier.weight(1f));Icon(if(more)Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)}
                    if(more){
                        ProfileHubAction(Icons.Outlined.Lock,"امنیت حساب","جلسه‌ها و دسترسی‌ها",{accountAction(onSecurity)})
                        ProfileHubAction(Icons.Outlined.Shield,"ایمنی و مسدودسازی","مدیریت تعامل‌های ناخواسته",{accountAction(onSafety)})
                        ProfileHubAction(Icons.Outlined.ChildCare,"کنترل والدین","دسترسی امن خانواده",{accountAction(onParentalControls)})
                        ProfileHubAction(Icons.Outlined.PersonAdd,"درخواست‌های دنبال‌کردن","کنترل حساب خصوصی",{accountAction(onFollowRequests)})
                        ProfileHubAction(Icons.Outlined.PeopleOutline,"دوستان نزدیک","مخاطبان استوری‌های خصوصی",{accountAction(onCloseFriends)})
                        ProfileHubAction(Icons.Outlined.MovieCreation,"استودیوی سازنده","مدیریت محتوای منتشرشده",{accountAction(onCreatorStudio)})
                        ProfileHubAction(Icons.Outlined.AutoAwesome,"سلیقهٔ سینمایی","Film DNA حسابت",{accountAction(onFilmDna)})
                        profile?.id?.let{id->ProfileHubAction(Icons.Outlined.WorkspacePremium,"اعتبار اجتماعی","فعالیت و نشان‌های حساب",{onReputation(id)})}
                    }
                }
            }}
            if(loggedIn&&!kidsMode)item("logout"){TextButton({logout=true},Modifier.padding(horizontal=20.dp).testTag("profile-logout")){Icon(Icons.Outlined.Logout,null);Spacer(Modifier.width(8.dp));Text("خروج از حساب")}}
        }
        SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter))
    }
    if(logout)AlertDialog(onDismissRequest={if(!logoutBusy)logout=false},title={Text("از حساب خارج می‌شوی؟")},text={Text("فهرست‌ها، پست‌ها و پیام‌های حساب باقی می‌مانند.")},
        confirmButton={TextButton({if(!logoutBusy){logoutBusy=true;scope.launch{try{backend.logout();logout=false;onLoggedOut()}catch(e:CancellationException){throw e}catch(_:Exception){snackbar.showSnackbar("خروج کامل نشد؛ دوباره تلاش کن.")}finally{logoutBusy=false}}}},enabled=!logoutBusy){Text("خروج")}},
        dismissButton={TextButton({logout=false},enabled=!logoutBusy){Text("انصراف")}})
}

private fun profileWatchTime(minutes:Long):String {
    val safe=minutes.coerceAtLeast(0)
    return if(safe<60)"$safe دقیقه"else "${safe/60} ساعت و ${safe%60} دقیقه"
}

@Composable
private fun ProfileIdentityCard(profile:AccountProfile?,loggedIn:Boolean,loading:Boolean,onEdit:()->Unit,onLogin:()->Unit,kidsMode:Boolean=false,viewer:ViewerProfile?=null) {
    CinemaCard(Modifier.padding(horizontal=20.dp).fillMaxWidth().testTag("profile-identity")){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)){
            Box(Modifier.size(72.dp).background(Brush.linearGradient(listOf(CinemaAccent,CinemaGold)),CircleShape).padding(3.dp)){
                val avatar=if(kidsMode)viewer?.avatarUrl else profile?.avatarUrl
                if(avatar?.isNotBlank()==true)CinemaImage(avatar,Modifier.fillMaxSize().clip(CircleShape))
                else Box(Modifier.fillMaxSize().background(CinemaSurface,CircleShape),contentAlignment=Alignment.Center){Icon(Icons.Outlined.PersonOutline,null,tint=CinemaAccent,modifier=Modifier.size(32.dp))}
            }
            Column(Modifier.weight(1f)){
                Text(if(kidsMode)viewer?.name?:"پروفایل کودک"else profile?.displayName?:if(loggedIn)"حساب من"else "سینمای تو، همین‌جا",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                if(kidsMode)Text("پروفایل تماشای کودک",color=CinemaSoft,style=MaterialTheme.typography.bodyMedium)
                else profile?.let{Text("@${it.username}",color=CinemaSoft,style=MaterialTheme.typography.bodyMedium)}
                if(!loggedIn)Text("فهرست‌های روی دستگاه محفوظ‌اند. برای همگام‌سازی و تعامل وارد شو.",color=CinemaSoft,style=MaterialTheme.typography.bodyMedium)
                if(!kidsMode&&profile?.privateAccount==true)Text("حساب خصوصی",color=CinemaAccent,style=MaterialTheme.typography.labelMedium)
            }
            if(!kidsMode&&profile?.verified==true)Icon(Icons.Outlined.Verified,"حساب تأییدشده",tint=CinemaAccent)
        }
        if(!kidsMode)profile?.bio?.takeIf(String::isNotBlank)?.let{Text(it,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(top=14.dp))}
        if(!kidsMode)profile?.let{Row(Modifier.fillMaxWidth().padding(top=14.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){
            Text("${it.followers} دنبال‌کننده",color=CinemaSoft,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f))
            Text("${it.following} دنبال‌شده",color=CinemaSoft,style=MaterialTheme.typography.bodyMedium,modifier=Modifier.weight(1f))
        }}
        if(loading)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=14.dp),color=CinemaAccent)
        CinemaAction(if(!loggedIn)Icons.Outlined.Login else if(kidsMode)Icons.Outlined.SwitchAccount else Icons.Outlined.Edit,
            if(!loggedIn)"ورود و ساخت حساب"else if(kidsMode)"خروج با تأیید والدین"else "ویرایش پروفایل",
            if(loggedIn)onEdit else onLogin,Modifier.fillMaxWidth().padding(top=14.dp).testTag("profile-identity-action"),primary=!loggedIn)
    }
}

@Composable
private fun ProfileHubAction(icon:ImageVector,title:String,subtitle:String,onClick:()->Unit,modifier:Modifier=Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min=64.dp).clickable(onClick=onClick).padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
        Icon(icon,null,tint=CinemaAccent,modifier=Modifier.size(24.dp))
        Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleSmall);Text(subtitle,color=CinemaSoft,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=3.dp))}
        Icon(Icons.Outlined.ChevronLeft,null,tint=CinemaSoft)
    }
}

@Composable
private fun ProfilePostPreview(post:SocialPost,onOpen:()->Unit) {
    CinemaCard(Modifier.fillMaxWidth().padding(horizontal=20.dp).clickable(onClick=onOpen).testTag("profile-post-${post.id}")){
        Text(post.media?.title?:"پست سینمایی",style=MaterialTheme.typography.titleMedium,color=CinemaAccent)
        if(post.spoiler){Row(Modifier.padding(top=10.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.VisibilityOff,null);Spacer(Modifier.width(8.dp));Text("دارای اسپویل · بازکردن پست")}}
        else Text(post.body,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.padding(top=10.dp))
        Text("${post.likes} پسند · ${post.comments} پاسخ",color=CinemaSoft,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=10.dp))
    }
}
