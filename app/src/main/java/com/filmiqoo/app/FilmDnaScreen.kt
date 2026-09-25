package com.filmiqoo.app

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FilmDnaScreen(
    backend:BackendRepository,
    onBack:()->Unit
) {
    val context=LocalContext.current
    val repo=remember { FilmDnaRepository(backend) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var dna by remember { mutableStateOf<FilmDna?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        runCatching { repo.load() }
            .onSuccess { dna=it }
            .onFailure { error=it.message ?: "خطا در ساخت Film DNA" }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=6.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack) { Icon(Icons.Default.ArrowBack,null) }
            Column(Modifier.weight(1f)) {
                Text("Film DNA",fontSize=22.sp,fontWeight=FontWeight.Black)
                Text("سلیقه سینمایی واقعی پروفایل فعال",color=FqMuted,fontSize=11.sp)
            }
            IconButton(onClick={refresh++}) { Icon(Icons.Default.Refresh,null) }
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
                    .padding(12.dp)
            )
        }

        dna?.let { value ->
            androidx.compose.foundation.lazy.LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding=PaddingValues(bottom=30.dp)
            ) {
                item {
                    DnaHero(
                        dna=value,
                        onShare={
                            val topGenres=value.genres.take(3)
                                .joinToString("، ") { genreLabel(it.label) }
                            val text=buildString {
                                append("Film DNA من در Filmiqoo\n")
                                append(value.profileName+" • "+value.archetype+"\n")
                                if(topGenres.isNotBlank()) append("ژانرها: "+topGenres+"\n")
                                append("زمان تماشا: "+formatDnaWatchTime(value.stats.watchMinutes)+"\n")
                                append("عنوان‌های دیده‌شده: "+value.stats.distinctTitles)
                            }
                            val intent=Intent(Intent.ACTION_SEND).apply {
                                type="text/plain"
                                putExtra(Intent.EXTRA_TEXT,text)
                            }
                            context.startActivity(
                                Intent.createChooser(intent,"اشتراک Film DNA")
                            )
                        }
                    )
                }

                item {
                    DnaStatsGrid(value.stats)
                }

                if(value.genres.isNotEmpty()) {
                    item {
                        DnaAffinitySection(
                            title="ژانرهای تو",
                            subtitle="وزن‌دار بر اساس تماشا، Favorite و Watchlist",
                            icon=Icons.Default.Category,
                            items=value.genres,
                            labelMapper=::genreLabel
                        )
                    }
                }

                if(value.kinds.isNotEmpty()) {
                    item {
                        DnaAffinitySection(
                            title="فرمت موردعلاقه",
                            subtitle="فیلم، سریال یا انیمه",
                            icon=Icons.Default.MovieFilter,
                            items=value.kinds,
                            labelMapper=::kindLabel
                        )
                    }
                }

                if(value.languages.isNotEmpty()) {
                    item {
                        DnaAffinitySection(
                            title="سینمای جهان",
                            subtitle="زبان‌هایی که واقعاً بیشتر می‌بینی",
                            icon=Icons.Default.Public,
                            items=value.languages,
                            labelMapper=::languageLabelDna
                        )
                    }
                }

                if(value.decades.isNotEmpty()) {
                    item {
                        DnaAffinitySection(
                            title="دهه‌های محبوب",
                            subtitle="دوره‌هایی که بیشتر سمتشان رفتی",
                            icon=Icons.Default.History,
                            items=value.decades,
                            labelMapper={it}
                        )
                    }
                }

                item {
                    PremiumSectionHeader(
                        "Badgeهای Film DNA",
                        "Badge فقط از رفتار واقعی همین پروفایل ساخته می‌شود.",
                        Icons.Default.EmojiEvents
                    )
                }

                item {
                    if(value.badges.isEmpty()) {
                        Surface(
                            color=FqSurface,
                            shape=RoundedCornerShape(18.dp),
                            modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp)
                        ) {
                            Row(
                                Modifier.padding(14.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.AutoAwesome,null,tint=FqGold)
                                Spacer(Modifier.width(9.dp))
                                Column {
                                    Text("DNA هنوز در حال شکل‌گیریه",fontSize=12.sp,fontWeight=FontWeight.Bold)
                                    Text(
                                        "هرچی بیشتر تماشا، Favorite و Watchlist داشته باشی، Badgeهای واقعی بیشتری باز می‌شن.",
                                        color=FqMuted,
                                        fontSize=11.sp,
                                        lineHeight=14.sp
                                    )
                                }
                            }
                        }
                    } else {
                        LazyRow(
                            contentPadding=PaddingValues(horizontal=14.dp),
                            horizontalArrangement=Arrangement.spacedBy(9.dp)
                        ) {
                            items(value.badges,key={it.id}) { badge ->
                                DnaBadgeCard(badge)
                            }
                        }
                    }
                }

                item {
                    Surface(
                        color=FqSurface,
                        shape=RoundedCornerShape(20.dp),
                        modifier=Modifier.fillMaxWidth().padding(14.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Icon(Icons.Default.Info,null,tint=FqGold)
                                Spacer(Modifier.width(7.dp))
                                Text("Film DNA چطور ساخته می‌شه؟",fontSize=11.sp,fontWeight=FontWeight.Bold)
                            }
                            Text(
                                "از مدت تماشای ثبت‌شده، عنوان‌های کامل‌شده، Favorites و Watchlist همین Viewer Profile استفاده می‌کنه. این تحلیل شخصیت یا روان‌شناسی نیست؛ فقط خلاصه‌ی الگوی مصرف فیلم و سریاله.",
                                color=FqMuted,
                                fontSize=11.sp,
                                lineHeight=15.sp,
                                modifier=Modifier.padding(top=8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DnaHero(
    dna:FilmDna,
    onShare:()->Unit
) {
    Box(
        Modifier.fillMaxWidth().height(280.dp).background(
            Brush.linearGradient(
                listOf(Color(0xFF18243D),Color(0xFF3A2807),FqBg)
            )
        )
    ) {
        Column(
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start=18.dp,end=18.dp,bottom=20.dp)
        ) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape)
                        .background(FqGold.copy(alpha=.16f)),
                    contentAlignment=Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AutoAwesome,
                        null,
                        tint=FqGold,
                        modifier=Modifier.size(34.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(dna.profileName,color=FqMuted,fontSize=11.sp)
                    Text(
                        dna.archetype,
                        fontSize=24.sp,
                        fontWeight=FontWeight.Black,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick=onShare) {
                    Icon(Icons.Default.Share,null,tint=FqGold)
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top=16.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("دقت DNA",fontSize=11.sp,color=FqMuted)
                    LinearProgressIndicator(
                        progress={dna.confidence/100f},
                        color=FqGold,
                        trackColor=FqSurface3,
                        modifier=Modifier.fillMaxWidth().padding(top=6.dp).height(6.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    dna.confidence.toString()+"٪",
                    color=FqGold,
                    fontSize=14.sp,
                    fontWeight=FontWeight.Black
                )
            }

            Text(
                if(dna.confidence<35)
                    "با چند تماشای بیشتر DNA دقیق‌تر می‌شه."
                else
                    "این نتیجه از رفتار واقعی پروفایل فعلی ساخته شده.",
                color=Color.White.copy(alpha=.68f),
                fontSize=11.sp,
                modifier=Modifier.padding(top=7.dp)
            )
        }
    }
}

@Composable
private fun DnaStatsGrid(stats:FilmDnaStats) {
    Column(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement=Arrangement.spacedBy(8.dp)
        ) {
            DnaStat(
                Icons.Default.Schedule,
                formatDnaWatchTime(stats.watchMinutes),
                "زمان تماشا",
                Modifier.weight(1f)
            )
            DnaStat(
                Icons.Default.Movie,
                stats.distinctTitles.toString(),
                "عنوان",
                Modifier.weight(1f)
            )
            DnaStat(
                Icons.Default.CheckCircle,
                stats.completedVersions.toString(),
                "کامل‌شده",
                Modifier.weight(1f)
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top=8.dp),
            horizontalArrangement=Arrangement.spacedBy(8.dp)
        ) {
            DnaStat(
                Icons.Default.Percent,
                String.format(java.util.Locale.US,"%.0f%%",stats.completionRate),
                "Completion",
                Modifier.weight(1f)
            )
            DnaStat(
                Icons.Default.Favorite,
                stats.favorites.toString(),
                "Favorite",
                Modifier.weight(1f)
            )
            DnaStat(
                Icons.Default.Bookmark,
                stats.watchlist.toString(),
                "Watchlist",
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun DnaStat(
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    value:String,
    label:String,
    modifier:Modifier=Modifier
) {
    Surface(color=FqSurface,shape=RoundedCornerShape(16.dp),modifier=modifier) {
        Column(
            Modifier.padding(vertical=12.dp,horizontal=6.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Icon(icon,null,tint=FqGold,modifier=Modifier.size(18.dp))
            Text(value,fontSize=13.sp,fontWeight=FontWeight.Black,modifier=Modifier.padding(top=4.dp))
            Text(label,color=FqMuted,fontSize=11.sp)
        }
    }
}

@Composable
private fun DnaAffinitySection(
    title:String,
    subtitle:String,
    icon:androidx.compose.ui.graphics.vector.ImageVector,
    items:List<DnaAffinity>,
    labelMapper:(String)->String
) {
    Column(Modifier.fillMaxWidth().padding(top=5.dp)) {
        PremiumSectionHeader(title,subtitle,icon)
        Column(
            Modifier.fillMaxWidth().padding(horizontal=14.dp),
            verticalArrangement=Arrangement.spacedBy(7.dp)
        ) {
            items.take(6).forEachIndexed { index,item ->
                Surface(
                    color=FqSurface,
                    shape=RoundedCornerShape(15.dp),
                    modifier=Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(11.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(29.dp).clip(CircleShape).background(
                                if(index==0)FqGold.copy(alpha=.17f) else FqSurface2
                            ),
                            contentAlignment=Alignment.Center
                        ) {
                            Text(
                                (index+1).toString(),
                                color=if(index==0)FqGold else FqMuted,
                                fontSize=11.sp,
                                fontWeight=FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.width(9.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                Text(
                                    labelMapper(item.label),
                                    fontSize=11.sp,
                                    fontWeight=FontWeight.Bold,
                                    modifier=Modifier.weight(1f)
                                )
                                Text(
                                    String.format(java.util.Locale.US,"%.0f%%",item.strength*100),
                                    color=FqMuted,
                                    fontSize=11.sp
                                )
                            }
                            LinearProgressIndicator(
                                progress={item.strength},
                                color=if(index==0)FqGold else Color.White.copy(alpha=.55f),
                                trackColor=FqSurface3,
                                modifier=Modifier.fillMaxWidth().padding(top=6.dp).height(4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DnaBadgeCard(badge:DnaBadge) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(19.dp),
        modifier=Modifier.width(190.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(FqGold.copy(alpha=.14f)),
                contentAlignment=Alignment.Center
            ) {
                Icon(Icons.Default.EmojiEvents,null,tint=FqGold)
            }
            Text(
                badge.title,
                fontSize=11.sp,
                fontWeight=FontWeight.Bold,
                modifier=Modifier.padding(top=8.dp)
            )
            Text(
                "Level "+badge.level,
                color=FqGold,
                fontSize=11.sp,
                modifier=Modifier.padding(top=2.dp)
            )
            Text(
                badge.description,
                color=FqMuted,
                fontSize=11.sp,
                lineHeight=13.sp,
                maxLines=3,
                overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=6.dp)
            )
        }
    }
}

private fun formatDnaWatchTime(minutes:Long):String {
    if(minutes<60) return minutes.toString()+"m"
    val h=minutes/60
    return if(h<1000) h.toString()+"h"
    else String.format(java.util.Locale.US,"%.1fK h",h/1000.0)
}

private fun kindLabel(value:String)=when(value) {
    "movie" -> "فیلم"
    "series","tv" -> "سریال"
    "anime" -> "انیمه"
    else -> value
}

private fun languageLabelDna(value:String)=when(value.lowercase()) {
    "fa" -> "فارسی"
    "en" -> "انگلیسی"
    "ko" -> "کره‌ای"
    "ja" -> "ژاپنی"
    "hi" -> "هندی"
    "de" -> "آلمانی"
    "fr" -> "فرانسوی"
    "es" -> "اسپانیایی"
    "tr" -> "ترکی"
    "zh" -> "چینی"
    "ar" -> "عربی"
    else -> value.uppercase()
}

private fun genreLabel(value:String)=when(value.lowercase()) {
    "action" -> "اکشن"
    "adventure" -> "ماجراجویی"
    "animation" -> "انیمیشن"
    "comedy" -> "کمدی"
    "crime" -> "جنایی"
    "documentary" -> "مستند"
    "drama" -> "درام"
    "family" -> "خانوادگی"
    "fantasy" -> "فانتزی"
    "history" -> "تاریخی"
    "horror" -> "ترسناک"
    "music" -> "موسیقی"
    "mystery" -> "معمایی"
    "romance" -> "عاشقانه"
    "science fiction" -> "علمی‌تخیلی"
    "sci-fi & fantasy" -> "علمی‌تخیلی و فانتزی"
    "thriller" -> "هیجان‌انگیز"
    "war" -> "جنگی"
    "war & politics" -> "جنگ و سیاست"
    "western" -> "وسترن"
    "kids" -> "کودک"
    "reality" -> "Reality"
    else -> value
}
