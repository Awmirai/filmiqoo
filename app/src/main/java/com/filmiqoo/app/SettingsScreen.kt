package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    backend: BackendRepository,
    onBack: () -> Unit
) {
    val context=LocalContext.current
    val repo=remember { SettingsRepository(context.applicationContext,backend) }
    val scope=rememberCoroutineScope()
    var settings by remember { mutableStateOf(repo.local()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    LaunchedEffect(Unit) {
        if(backend.session.isLoggedIn) {
            runCatching { repo.load() }
                .onSuccess { settings=it }
                .onFailure { error=it.message }
        }
        loading=false
    }

    fun persist(next: AppSettings) {
        settings=next
        if(!backend.session.isLoggedIn) {
            AppPreferences(context).write(next)
            OfflineDownloadManager.setWifiOnly(context,next.wifiOnlyDownloads)
            return
        }
        saving=true
        scope.launch {
            runCatching { repo.save(next) }
                .onSuccess { settings=it;error=null }
                .onFailure { error=it.message }
            saving=false
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(FqBg),
        contentPadding=PaddingValues(bottom=30.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
                Column(Modifier.weight(1f)) {
                    Text("تنظیمات",fontSize=23.sp,fontWeight=FontWeight.Black)
                    Text("پخش، دانلود، حریم خصوصی و اعلان‌ها",color=FqMuted,fontSize=8.sp)
                }
                if(saving) CircularProgressIndicator(color=FqGold,strokeWidth=2.dp,modifier=Modifier.size(22.dp))
            }
        }

        if(loading) item { LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth()) }
        error?.let { e ->
            item {
                Text(e,color=FqDanger,fontSize=9.sp,modifier=Modifier.fillMaxWidth().padding(12.dp))
            }
        }

        item { SettingsSectionTitle("پخش",Icons.Default.PlayCircle) }
        item {
            SettingsSwitchRow("پخش خودکار قسمت بعد","بعد از پایان قسمت، قسمت بعدی شروع شود.",settings.autoplayNext) {
                persist(settings.copy(autoplayNext=it))
            }
            SettingsSwitchRow("پخش خودکار Preview","Preview و Heroها خودکار پخش شوند.",settings.autoplayPreviews) {
                persist(settings.copy(autoplayPreviews=it))
            }
            SettingsSwitchRow("رد کردن Intro","اگر Marker موجود باشد تیتراژ آغازین خودکار رد شود.",settings.skipIntro) {
                persist(settings.copy(skipIntro=it))
            }
            SettingsSwitchRow("رد کردن Recap","مرور قسمت قبل در صورت وجود Marker خودکار رد شود.",settings.skipRecap) {
                persist(settings.copy(skipRecap=it))
            }
            SettingsSwitchRow("رفتن به قسمت بعد در Credits","وقتی Credits شروع شد، اگر قسمت بعد موجود باشد سریع‌تر به Next Episode برو.",settings.skipCredits) {
                persist(settings.copy(skipCredits=it))
            }
        }

        item {
            SettingsChoiceRow(
                title="سرعت پیش‌فرض",
                subtitle="برای همه پخش‌های جدید",
                value=settings.defaultPlaybackSpeed.toString()+"x",
                options=listOf(".75x","1.0x","1.25x","1.5x","1.75x","2.0x")
            ) { selected ->
                persist(settings.copy(defaultPlaybackSpeed=selected.removeSuffix("x").toFloatOrNull() ?: 1f))
            }
        }

        item {
            SettingsChoiceRow(
                title="نسبت تصویر",
                subtitle="نمایش پیش‌فرض ویدیو در Player",
                value=resizeModeLabel(settings.playerResizeMode),
                options=listOf("Fit","Fill","Zoom")
            ) { selected ->
                persist(settings.copy(playerResizeMode=resizeModeCode(selected)))
            }
        }

        item { SettingsSectionTitle("صدا و زیرنویس",Icons.Default.Subtitles) }
        item {
            SettingsSwitchRow("زیرنویس پیش‌فرض","در صورت وجود Track مناسب فعال باشد.",settings.subtitlesEnabled) {
                persist(settings.copy(subtitlesEnabled=it))
            }
            SettingsChoiceRow(
                "زبان صدا","Track صوتی ترجیحی",
                languageLabel(settings.defaultAudioLanguage),
                listOf("فارسی","English","Deutsch","العربية","Türkçe","한국어","日本語")
            ) { persist(settings.copy(defaultAudioLanguage=languageCode(it))) }
            SettingsChoiceRow(
                "زبان زیرنویس","زبان ترجیحی Subtitle",
                languageLabel(settings.defaultSubtitleLanguage),
                listOf("فارسی","English","Deutsch","العربية","Türkçe","한국어","日本語")
            ) { persist(settings.copy(defaultSubtitleLanguage=languageCode(it))) }
            SettingsChoiceRow(
                "اندازه زیرنویس","اندازه پیش‌فرض متن Subtitle",
                subtitleScaleLabel(settings.subtitleScale),
                listOf("کوچک","معمولی","بزرگ","خیلی بزرگ")
            ) { persist(settings.copy(subtitleScale=subtitleScaleValue(it))) }
            SettingsChoiceRow(
                "موقعیت زیرنویس","فاصله Subtitle از پایین تصویر",
                subtitlePositionLabel(settings.subtitleBottomPadding),
                listOf("پایین","معمولی","بالاتر")
            ) { persist(settings.copy(subtitleBottomPadding=subtitlePositionValue(it))) }
        }

        item { SettingsSectionTitle("دانلود و اینترنت",Icons.Default.Download) }
        item {
            SettingsSwitchRow("دانلود فقط با Wi‑Fi","دانلودهای آفلاین روی دیتای موبایل شروع نشوند.",settings.wifiOnlyDownloads) {
                persist(settings.copy(wifiOnlyDownloads=it))
            }
            SettingsSwitchRow("Data Saver","برای شبکه ضعیف مصرف داده کمتر شود.",settings.dataSaver) {
                persist(settings.copy(dataSaver=it))
            }
        }

        item { SettingsSectionTitle("Community و Privacy",Icons.Default.Security) }
        item {
            SettingsSwitchRow("Spoiler Shield","محتوای علامت‌خورده تا لمس کاربر مخفی بماند.",settings.spoilerShield) {
                persist(settings.copy(spoilerShield=it))
            }
            SettingsSwitchRow("حساب خصوصی","Follower جدید نیاز به تأیید داشته باشد.",settings.privateAccount) {
                persist(settings.copy(privateAccount=it))
            }
        }

        item { SettingsSectionTitle("اعلان‌ها",Icons.Default.Notifications) }
        item {
            SettingsSwitchRow("اجتماعی","Follow، Like، Story reaction و فعالیت Creatorها.",settings.notificationsSocial) {
                persist(settings.copy(notificationsSocial=it))
            }
            SettingsSwitchRow("پیام‌ها","DM و Roomهای مهم.",settings.notificationsMessages) {
                persist(settings.copy(notificationsMessages=it))
            }
            SettingsSwitchRow("انتشار فیلم و سریال","قسمت جدید، Release و محتوای Watchlist.",settings.notificationsReleases) {
                persist(settings.copy(notificationsReleases=it))
            }
        }

        item {
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(20.dp),
                modifier=Modifier.fillMaxWidth().padding(14.dp)
            ) {
                Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.CloudDone,null,tint=FqGreen)
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text("همگام‌سازی تنظیمات",fontSize=11.sp,fontWeight=FontWeight.Bold)
                        Text(
                            if(backend.session.isLoggedIn)
                                "تنظیمات بین دستگاه‌های حساب Filmiqoo همگام می‌شوند."
                            else
                                "در Preview تنظیمات فقط روی همین دستگاه ذخیره می‌شوند.",
                            color=FqMuted,fontSize=8.sp,lineHeight=14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(title:String,icon:androidx.compose.ui.graphics.vector.ImageVector) {
    PremiumSectionHeader(title,icon=icon)
}

@Composable
private fun SettingsSwitchRow(
    title:String,
    subtitle:String,
    checked:Boolean,
    onChange:(Boolean)->Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp)
            .background(FqSurface,RoundedCornerShape(17.dp)).padding(12.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title,fontSize=10.sp,fontWeight=FontWeight.Bold)
            Text(subtitle,color=FqMuted,fontSize=7.sp,lineHeight=13.sp,modifier=Modifier.padding(top=3.dp))
        }
        Switch(checked=checked,onCheckedChange=onChange)
    }
}

@Composable
private fun SettingsChoiceRow(
    title:String,
    subtitle:String,
    value:String,
    options:List<String>,
    onSelected:(String)->Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp)
                .background(FqSurface,RoundedCornerShape(17.dp))
                .clickable { expanded=true }.padding(12.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title,fontSize=10.sp,fontWeight=FontWeight.Bold)
                Text(subtitle,color=FqMuted,fontSize=7.sp,modifier=Modifier.padding(top=3.dp))
            }
            Text(value,color=FqGold,fontSize=9.sp,fontWeight=FontWeight.Bold)
            Icon(Icons.Default.ExpandMore,null,tint=FqMuted)
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            options.forEach { option ->
                DropdownMenuItem(
                    text={Text(option)},
                    onClick={
                        expanded=false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

private fun languageLabel(code:String)=when(code) {
    "fa" -> "فارسی"
    "en" -> "English"
    "de" -> "Deutsch"
    "ar" -> "العربية"
    "tr" -> "Türkçe"
    "ko" -> "한국어"
    "ja" -> "日本語"
    else -> code
}

private fun languageCode(label:String)=when(label) {
    "فارسی" -> "fa"
    "English" -> "en"
    "Deutsch" -> "de"
    "العربية" -> "ar"
    "Türkçe" -> "tr"
    "한국어" -> "ko"
    "日本語" -> "ja"
    else -> "fa"
}


private fun resizeModeLabel(code:String)=when(code) {
    "fill" -> "Fill"
    "zoom" -> "Zoom"
    else -> "Fit"
}

private fun resizeModeCode(label:String)=when(label) {
    "Fill" -> "fill"
    "Zoom" -> "zoom"
    else -> "fit"
}

private fun subtitleScaleLabel(value:Float)=when {
    value < .9f -> "کوچک"
    value < 1.15f -> "معمولی"
    value < 1.4f -> "بزرگ"
    else -> "خیلی بزرگ"
}

private fun subtitleScaleValue(label:String)=when(label) {
    "کوچک" -> .8f
    "بزرگ" -> 1.25f
    "خیلی بزرگ" -> 1.5f
    else -> 1f
}

private fun subtitlePositionLabel(value:Float)=when {
    value < .07f -> "پایین"
    value < .16f -> "معمولی"
    else -> "بالاتر"
}

private fun subtitlePositionValue(label:String)=when(label) {
    "پایین" -> .04f
    "بالاتر" -> .2f
    else -> .1f
}
