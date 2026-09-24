package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private sealed interface ProfileLoad {
    data object Loading : ProfileLoad
    data class Ready(
        val profile: AccountProfile,
        val stats: LibraryStats,
        val watching: List<ContinueWatchingItem>,
        val favorites: List<MediaItem>
    ) : ProfileLoad
    data class Error(val message: String) : ProfileLoad
}

@Composable
fun ConnectedProfileScreen(
    backend: BackendRepository,
    repository: TmdbRepository,
    kidsMode: Boolean = false,
    onMedia: (MediaItem) -> Unit,
    onPlay: (PlaybackTarget) -> Unit,
    onCommunity: () -> Unit,
    onDownloads: () -> Unit,
    onLibrary: () -> Unit,
    onHistory: () -> Unit,
    onCreatorStudio: () -> Unit,
    onInbox: () -> Unit,
    onSettings: () -> Unit,
    onViewerProfiles: () -> Unit,
    onParentalControls: () -> Unit,
    onSecurity: () -> Unit,
    onSafety: () -> Unit,
    onFollowRequests: () -> Unit,
    onEditProfile: () -> Unit,
    onFilmDna: () -> Unit,
    onSocialCollections: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val scope=rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<ProfileLoad>(ProfileLoad.Loading) }

    LaunchedEffect(refresh) {
        state=ProfileLoad.Loading
        state=runCatching {
            ProfileLoad.Ready(
                profile=backend.me(),
                stats=backend.libraryStats(),
                watching=backend.continueWatching(),
                favorites=backend.favorites()
            )
        }.getOrElse { ProfileLoad.Error(it.message ?: "خطا در دریافت پروفایل") }
    }

    when(val s=state) {
        ProfileLoad.Loading -> LoadingPage("در حال همگام‌سازی پروفایل...")
        is ProfileLoad.Error -> ErrorPage(s.message) { refresh++ }
        is ProfileLoad.Ready -> {
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding=PaddingValues(bottom=28.dp)
            ) {
                item {
                    ProfileHero(s.profile,s.stats,onRefresh={refresh++})
                }

                if(s.watching.isNotEmpty()) {
                    item { SectionHeader("ادامه تماشا","همگام با آخرین موقعیت تماشا") }
                    item {
                        LazyRow(
                            contentPadding=PaddingValues(horizontal=16.dp),
                            horizontalArrangement=Arrangement.spacedBy(12.dp)
                        ) {
                            items(s.watching,key={it.target.mediaVersionId}) { item ->
                                ContinueProfileCard(item,repository) { onPlay(item.target) }
                            }
                        }
                    }
                }

                item { SectionHeader("کتابخانه من","موردعلاقه‌ها و لیست شخصی") }
                item {
                    if(s.favorites.isEmpty()) {
                        Surface(
                            color=FqSurface,
                            shape=RoundedCornerShape(18.dp),
                            modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp)
                        ) {
                            Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                                Icon(Icons.Default.BookmarkBorder,null,tint=FqGold)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("هنوز چیزی ذخیره نکردی",fontSize=12.sp)
                                    Text("از صفحه فیلم‌ها به Favorites اضافه کن.",color=FqMuted,fontSize=9.sp)
                                }
                            }
                        }
                    } else {
                        LazyRow(
                            contentPadding=PaddingValues(horizontal=16.dp),
                            horizontalArrangement=Arrangement.spacedBy(12.dp)
                        ) {
                            items(s.favorites,key={it.key}) { media ->
                                PosterCard(media,repository,{onMedia(media)})
                            }
                        }
                    }
                }

                item { SectionHeader(if(kidsMode)"Kids Center" else "مرکز حساب") }
                item {
                    if(kidsMode) {
                        ProfileActionRow(
                            icon=Icons.Default.ExitToApp,
                            title="خروج از Kids Mode",
                            subtitle="نیاز به تأیید والدین",
                            onClick=onViewerProfiles
                        )
                    } else {
                        ProfileActionRow(
                            icon=Icons.Default.SwitchAccount,
                            title="پروفایل‌های تماشا",
                            subtitle="Multi‑Profile، Kids Mode و Library جدا",
                            onClick=onViewerProfiles
                        )
                        ProfileActionRow(
                            icon=Icons.Default.AdminPanelSettings,
                            title="کنترل والدین",
                            subtitle="Parental PIN و محافظت خروج از Kids",
                            onClick=onParentalControls
                        )
                        ProfileActionRow(
                            icon=Icons.Default.Edit,
                            title="ویرایش پروفایل",
                            subtitle="Avatar، Cover، Username، Bio و حریم خصوصی",
                            onClick=onEditProfile
                        )
                        ProfileActionRow(
                            icon=Icons.Default.AutoAwesome,
                            title="Film DNA",
                            subtitle="سلیقه واقعی، ژانرها، زبان‌ها و Badgeهای تماشای تو",
                            onClick=onFilmDna
                        )
                        ProfileActionRow(
                            icon=Icons.Default.CollectionsBookmark,
                            title="Community Lists",
                            subtitle="Collectionهای عمومی فیلم‌بازها و Creatorها",
                            onClick=onSocialCollections
                        )
                        ProfileActionRow(
                            icon=Icons.Default.Analytics,
                            title="Creator Studio",
                            subtitle="Analytics، Reels، Stories و عملکرد کانال‌ها",
                            onClick=onCreatorStudio
                        )
                        ProfileActionRow(
                            icon=Icons.Default.MarkChatUnread,
                            title="پیام‌ها",
                            subtitle="DM، گروه‌ها، Roomها و پیام‌های خوانده‌نشده",
                            onClick=onInbox
                        )
                        ProfileActionRow(
                            icon=Icons.Default.Groups,
                            title="Community و Creator",
                            subtitle="روم‌ها، کانال‌ها و فعالیت اجتماعی",
                            onClick=onCommunity
                        )
                    }

                    ProfileActionRow(
                        icon=Icons.Default.VideoLibrary,
                        title="Library",
                        subtitle="Favorites، Watchlist و Collectionهای شخصی",
                        onClick=onLibrary
                    )
                    ProfileActionRow(
                        icon=Icons.Default.History,
                        title="تاریخچه تماشا",
                        subtitle="Resume، کامل‌شده‌ها و مدیریت تاریخچه",
                        onClick=onHistory
                    )
                    ProfileActionRow(
                        icon=Icons.Default.Download,
                        title="دانلودهای آفلاین",
                        subtitle="صف، Pause/Resume، Retry و پخش بدون اینترنت",
                        onClick=onDownloads
                    )

                    if(!kidsMode) {
                        ProfileActionRow(
                            icon=Icons.Default.Subtitles,
                            title="زبان، دوبله و زیرنویس",
                            subtitle="ترجیحات پخش فارسی و انگلیسی",
                            onClick=onSettings
                        )
                        ProfileActionRow(
                            icon=Icons.Default.PersonAddAlt1,
                            title="درخواست‌های Follow",
                            subtitle="Accept یا Decline درخواست‌های حساب خصوصی",
                            onClick=onFollowRequests
                        )
                        ProfileActionRow(
                            icon=Icons.Default.Shield,
                            title="مرکز ایمنی",
                            subtitle="Block، Mute و مدیریت تجربه اجتماعی",
                            onClick=onSafety
                        )
                        ProfileActionRow(
                            icon=Icons.Default.Security,
                            title="امنیت و دستگاه‌ها",
                            subtitle="Sessionهای فعال و خروج از دستگاه‌های دیگر",
                            onClick=onSecurity
                        )
                        ProfileActionRow(
                            icon=Icons.Default.Settings,
                            title="تنظیمات",
                            subtitle="پخش، دانلود، زیرنویس، اعلان‌ها و حریم خصوصی",
                            onClick=onSettings
                        )
                    }
                }

                if(!kidsMode) {
                    item {
                        OutlinedButton(
                            onClick={
                                scope.launch {
                                    backend.logout()
                                    onLoggedOut()
                                }
                            },
                            shape=RoundedCornerShape(14.dp),
                            colors=ButtonDefaults.outlinedButtonColors(contentColor=FqDanger),
                            modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=18.dp)
                        ) {
                            Icon(Icons.Default.Logout,null)
                            Spacer(Modifier.width(7.dp))
                            Text("خروج از حساب")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHero(
    profile: AccountProfile,
    stats: LibraryStats,
    onRefresh: () -> Unit
) {
    Box(Modifier.fillMaxWidth().height(370.dp)) {
        if(profile.coverUrl.isNotBlank()) {
            RemoteImage(profile.coverUrl,Modifier.fillMaxWidth().height(190.dp),ContentScale.Crop)
        } else {
            Box(
                Modifier.fillMaxWidth().height(190.dp).background(
                    Brush.linearGradient(listOf(Color(0xFF261D08),Color(0xFF111827),FqBg))
                )
            )
        }
        Box(
            Modifier.fillMaxWidth().height(210.dp).align(Alignment.BottomCenter).background(
                Brush.verticalGradient(listOf(Color.Transparent,FqBg))
            )
        )

        IconButton(
            onClick=onRefresh,
            modifier=Modifier.align(Alignment.TopEnd).padding(10.dp)
                .clip(CircleShape).background(Color.Black.copy(alpha=.45f))
        ) { Icon(Icons.Default.Refresh,null) }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal=18.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(94.dp).background(FqGold,CircleShape).padding(3.dp)
            ) {
                if(profile.avatarUrl.isNotBlank()) {
                    RemoteImage(profile.avatarUrl,Modifier.fillMaxSize().clip(CircleShape))
                } else {
                    Box(
                        Modifier.fillMaxSize().clip(CircleShape).background(FqSurface2),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(Icons.Default.Person,null,tint=FqGold,modifier=Modifier.size(48.dp))
                    }
                }
            }
            Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(top=9.dp)) {
                Text(profile.displayName,fontSize=22.sp,fontWeight=FontWeight.Bold)
                if(profile.verified) {
                    Spacer(Modifier.width(5.dp))
                    Icon(Icons.Default.Verified,null,tint=Color(0xFF4AB7FF),modifier=Modifier.size(18.dp))
                }
            }
            Text("@"+profile.username,color=FqMuted,fontSize=10.sp)
            if(profile.bio.isNotBlank()) {
                Text(profile.bio,fontSize=10.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))
            }

            Row(
                Modifier.fillMaxWidth().padding(top=14.dp),
                horizontalArrangement=Arrangement.SpaceEvenly
            ) {
                MiniProfileMetric(compactProfileCount(profile.followers),"دنبال‌کننده")
                MiniProfileMetric(compactProfileCount(profile.following),"دنبال‌شده")
                MiniProfileMetric(stats.distinctTitles.toString(),"عنوان دیده‌شده")
            }

            Row(
                Modifier.fillMaxWidth().padding(top=13.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                StatTile(
                    icon=Icons.Default.Schedule,
                    value=formatWatchTime(stats.watchTimeMinutes),
                    label="زمان تماشا",
                    modifier=Modifier.weight(1f)
                )
                StatTile(
                    icon=Icons.Default.CheckCircle,
                    value=stats.completedVersions.toString(),
                    label="تمام‌شده",
                    modifier=Modifier.weight(1f)
                )
                StatTile(
                    icon=Icons.Default.Bookmark,
                    value=stats.favorites.toString(),
                    label="Favorites",
                    modifier=Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun StatTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    modifier: Modifier=Modifier
) {
    Surface(color=FqSurface,shape=RoundedCornerShape(14.dp),modifier=modifier) {
        Column(Modifier.padding(vertical=10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(icon,null,tint=FqGold,modifier=Modifier.size(18.dp))
            Text(value,fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=4.dp))
            Text(label,color=FqMuted,fontSize=7.sp)
        }
    }
}

@Composable
private fun MiniProfileMetric(value:String,label:String) {
    Column(horizontalAlignment=Alignment.CenterHorizontally) {
        Text(value,fontSize=15.sp,fontWeight=FontWeight.Bold)
        Text(label,color=FqMuted,fontSize=8.sp)
    }
}

@Composable
private fun ContinueProfileCard(
    item: ContinueWatchingItem,
    repository: TmdbRepository,
    onClick: () -> Unit
) {
    Column(Modifier.width(250.dp).clickable { onClick() }) {
        Box(Modifier.fillMaxWidth().height(142.dp).clip(RoundedCornerShape(18.dp))) {
            RemoteImage(
                repository.backdrop(item.media.backdropPath ?: item.media.posterPath),
                Modifier.fillMaxSize(),
                ContentScale.Crop
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.78f)))
                )
            )
            Icon(Icons.Default.PlayCircle,null,tint=Color.White,modifier=Modifier.size(52.dp).align(Alignment.Center))
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(item.media.title,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                if(item.episodeLabel.isNotBlank()) {
                    Text(item.episodeLabel,color=FqMuted,fontSize=8.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
        }
        LinearProgressIndicator(
            progress={item.progress},
            color=FqGold,
            trackColor=FqSurface2,
            modifier=Modifier.fillMaxWidth().padding(top=5.dp).height(4.dp)
        )
        Text(
            formatPosition(item.positionMs)+" / "+formatPosition(item.durationMs),
            color=FqMuted,fontSize=8.sp,modifier=Modifier.padding(top=4.dp)
        )
    }
}

@Composable
private fun ProfileActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp)
            .clip(RoundedCornerShape(16.dp)).background(FqSurface)
            .clickable { onClick() }.padding(14.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(FqSurface2),contentAlignment=Alignment.Center) {
            Icon(icon,null,tint=FqGold)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title,fontSize=11.sp)
            Text(subtitle,color=FqMuted,fontSize=8.sp,modifier=Modifier.padding(top=3.dp))
        }
        Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
    }
}

private fun formatWatchTime(minutes: Long): String {
    if(minutes<60) return minutes.toString()+"m"
    val hours=minutes/60
    return if(hours<1000) hours.toString()+"h" else String.format(java.util.Locale.US,"%.1fK h",hours/1000.0)
}

private fun formatPosition(ms: Long): String {
    if(ms<=0) return "00:00"
    val total=ms/1000
    val h=total/3600
    val m=(total%3600)/60
    val s=total%60
    return if(h>0) String.format(java.util.Locale.US,"%d:%02d:%02d",h,m,s)
    else String.format(java.util.Locale.US,"%02d:%02d",m,s)
}

private fun compactProfileCount(value: Long): String = when {
    value>=1_000_000 -> String.format(java.util.Locale.US,"%.1fM",value/1_000_000.0)
    value>=1_000 -> String.format(java.util.Locale.US,"%.1fK",value/1_000.0)
    else -> value.toString()
}
