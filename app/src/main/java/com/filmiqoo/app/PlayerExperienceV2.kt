package com.filmiqoo.app
import androidx.compose.material.icons.filled.VisibilityOff

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ManageSearch
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PlayerPanel = Color(0xF20B0B0E)
private val PlayerPanelElevated = Color(0xFF121216)
private val PlayerStroke = Color.White.copy(alpha=.10f)
private val PlayerMuted = Color.White.copy(alpha=.58f)
private val PlayerAccentSoft = FqGold.copy(alpha=.12f)

@Composable
internal fun PlayerChromeTopBarV2(
    target: PlaybackTarget,
    currentVariant: PlaybackVariant?,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal=14.dp, vertical=10.dp)
    ) {
        Row(
            modifier=Modifier.align(Alignment.CenterStart),
            horizontalArrangement=Arrangement.spacedBy(8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            PlayerRoundAction(
                icon=Icons.Default.MoreVert,
                contentDescription="ابزارهای پخش",
                onClick=onMore
            )
            FilmiqooCastRouteButton()
            PlayerRoundAction(
                icon=Icons.Default.VisibilityOff,
                contentDescription="پنهان‌کردن کنترل‌ها",
                onClick=onHide
            )

        }

        Column(
            modifier=Modifier
                .align(Alignment.Center)
                .fillMaxWidth(.55f),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Text(
                target.title,
                color=Color.White,
                fontSize=15.sp,
                fontWeight=FontWeight.Black,
                maxLines=1,
                overflow=TextOverflow.Ellipsis
            )
            val meta=listOf(
                target.subtitle,
                currentVariant?.label.orEmpty()
            ).filter(String::isNotBlank).distinct().joinToString(" • ")
            if(meta.isNotBlank()) {
                Text(
                    meta,
                    color=PlayerMuted,
                    fontSize=10.sp,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=2.dp)
                )
            }
        }

        PlayerRoundAction(
            icon=Icons.Default.ArrowBack,
            contentDescription="بازگشت",
            onClick=onBack,
            modifier=Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun PlayerRoundAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color=Color.Black.copy(alpha=.52f),
        contentColor=Color.White,
        shape=CircleShape,
        border=BorderStroke(1.dp,PlayerStroke),
        modifier=modifier
            .size(46.dp)
            .clickable(onClick=onClick)
    ) {
        Box(contentAlignment=Alignment.Center) {
            Icon(
                icon,
                contentDescription=contentDescription,
                modifier=Modifier.size(22.dp)
            )
        }
    }
}

@Composable
internal fun PlayerCenterControlsV2(
    isPlaying: Boolean,
    onBack10: () -> Unit,
    onPlayPause: () -> Unit,
    onForward10: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        val compact=maxWidth<360.dp || maxHeight<520.dp
        val playSize=if(compact)58.dp else 68.dp
        val seekSize=if(compact)48.dp else 54.dp
        val gap=if(compact)18.dp else 26.dp

        Row(
            modifier=Modifier.align(Alignment.Center),
            verticalAlignment=Alignment.CenterVertically,
            horizontalArrangement=Arrangement.spacedBy(gap)
        ) {
            PlayerSeekAction(
                icon=Icons.Default.Replay10,
                size=seekSize,
                contentDescription="۱۰ ثانیه عقب",
                onClick=onBack10
            )

            Surface(
                color=Color(0xE61B1B20),
                contentColor=Color.White,
                shape=CircleShape,
                border=BorderStroke(1.25.dp,FqGold.copy(alpha=.86f)),
                shadowElevation=12.dp,
                modifier=Modifier
                    .size(playSize)
                    .clickable(onClick=onPlayPause)
            ) {
                Box(contentAlignment=Alignment.Center) {
                    Icon(
                        if(isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription=if(isPlaying)"توقف" else "پخش",
                        modifier=Modifier.size(if(compact)30.dp else 36.dp)
                    )
                }
            }

            PlayerSeekAction(
                icon=Icons.Default.Forward10,
                size=seekSize,
                contentDescription="۱۰ ثانیه جلو",
                onClick=onForward10
            )
        }
    }
}

@Composable
private fun PlayerSeekAction(
    icon: ImageVector,
    size: Dp,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        color=Color.Black.copy(alpha=.54f),
        contentColor=Color.White,
        shape=CircleShape,
        border=BorderStroke(1.dp,Color.White.copy(alpha=.14f)),
        modifier=Modifier
            .size(size)
            .clickable(onClick=onClick)
    ) {
        Box(contentAlignment=Alignment.Center) {
            Icon(
                icon,
                contentDescription=contentDescription,
                modifier=Modifier.size(size*.54f)
            )
        }
    }
}

@Composable
internal fun PlayerBottomControlsV2(
    positionMs: Long,
    durationMs: Long,
    fraction: Float,
    speed: Float,
    hotMoments: List<PulseMoment>,
    onHotMoment: (PulseMoment) -> Unit,
    onSeekStart: () -> Unit,
    onFractionChanged: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onSpeed: () -> Unit,
    onCaptions: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal=16.dp)
            .padding(bottom=14.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            Slider(
                value=fraction.coerceIn(0f,1f),
                onValueChange={
                    onSeekStart()
                    onFractionChanged(it)
                },
                onValueChangeFinished=onSeekFinished,
                colors=SliderDefaults.colors(
                    thumbColor=FqGold,
                    activeTrackColor=FqGold,
                    inactiveTrackColor=Color.White.copy(alpha=.24f)
                ),
                modifier=Modifier.fillMaxWidth()
            )

            if(durationMs>0L) {
                hotMoments
                    .filter { it.positionMs in 1 until durationMs }
                    .take(5)
                    .forEach { moment ->
                        val f=(moment.positionMs.toFloat()/durationMs.toFloat())
                            .coerceIn(0f,1f)
                        Box(
                            modifier=Modifier
                                .align(Alignment.CenterStart)
                                .offset(x=(maxWidth-10.dp)*f)
                                .size(10.dp)
                                .background(FqGold,CircleShape)
                                .clickable { onHotMoment(moment) }
                        )
                    }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal=2.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Ltr
            ) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(
                        playerUiTime(positionMs),
                        color=Color.White,
                        fontSize=11.sp,
                        fontWeight=FontWeight.SemiBold
                    )
                    Text(
                        "  /  "+playerUiTime(durationMs),
                        color=PlayerMuted,
                        fontSize=10.sp
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            PlayerCompactChip(
                icon=Icons.Default.Speed,
                text=playerUiSpeed(speed),
                onClick=onSpeed
            )
            Spacer(Modifier.width(7.dp))
            PlayerCompactChip(
                icon=Icons.Default.Subtitles,
                text="CC",
                onClick=onCaptions
            )
            Spacer(Modifier.width(7.dp))
            Surface(
                color=Color.Black.copy(alpha=.48f),
                contentColor=Color.White,
                shape=RoundedCornerShape(12.dp),
                border=BorderStroke(1.dp,PlayerStroke),
                modifier=Modifier
                    .size(40.dp)
                    .clickable(onClick=onMore)
            ) {
                Box(contentAlignment=Alignment.Center) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription="ابزارهای پخش",
                        modifier=Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerCompactChip(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Surface(
        color=Color.Black.copy(alpha=.48f),
        contentColor=Color.White,
        shape=RoundedCornerShape(12.dp),
        border=BorderStroke(1.dp,PlayerStroke),
        modifier=Modifier.clickable(onClick=onClick)
    ) {
        Row(
            Modifier.padding(horizontal=9.dp,vertical=7.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Icon(icon,null,modifier=Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(text,fontSize=10.sp,fontWeight=FontWeight.Bold)
        }
    }
}

private data class PlayerToolActionV2(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val accent: Boolean = false,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerToolsSheetV2(
    downloadQueued: Boolean,
    hasQueue: Boolean,
    autoPersianSubtitleEnabled: Boolean,
    autoSubtitleBusy: Boolean,
    autoSubtitleStatus: String?,
    activeSubtitleLabel: String?,
    onDismiss: () -> Unit,
    onAutoPersianSubtitle: (Boolean) -> Unit,
    onSubtitleSettings: () -> Unit,
    onSettings: () -> Unit,
    onDownload: () -> Unit,
    onMoments: () -> Unit,
    onBookmarks: () -> Unit,
    onDialogueSearch: () -> Unit,
    onQueue: () -> Unit,
    onHandoff: () -> Unit,
    onShare: () -> Unit,
    onPip: () -> Unit,
    onLock: () -> Unit
) {
    val sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)

    BackHandler(onBack=onDismiss)

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        sheetState=sheetState,
        containerColor=PlayerPanel,
        contentColor=Color.White,
        tonalElevation=0.dp,
        scrimColor=Color.Black.copy(alpha=.72f),
        dragHandle=null
    ) {
        LazyColumn(
            modifier=Modifier
                .fillMaxWidth()
                .fillMaxHeight(.86f)
                .navigationBarsPadding(),
            contentPadding=PaddingValues(
                start=14.dp,
                end=14.dp,
                top=10.dp,
                bottom=28.dp
            ),
            verticalArrangement=Arrangement.spacedBy(10.dp)
        ) {
            item(key="header") {
                PlayerToolsHeaderV2(onDismiss)
            }

            item(key="subtitle-card") {
                AutoSubtitleCardV2(
                    enabled=autoPersianSubtitleEnabled,
                    busy=autoSubtitleBusy,
                    status=autoSubtitleStatus,
                    activeLabel=activeSubtitleLabel,
                    onEnabled=onAutoPersianSubtitle,
                    onSettings=onSubtitleSettings
                )
            }

            item(key="playback-title") {
                PlayerSheetSectionTitleV2("پخش")
            }

            val primary=buildList {
                add(
                    PlayerToolActionV2(
                        icon=Icons.Default.Tune,
                        title="تنظیمات پخش",
                        subtitle="کیفیت، صدا، سرعت، تصویر و تایمر خواب",
                        accent=true,
                        onClick=onSettings
                    )
                )
                add(
                    PlayerToolActionV2(
                        icon=if(downloadQueued) Icons.Default.DownloadDone else Icons.Default.Download,
                        title=if(downloadQueued)"دانلود در صف" else "دانلود آفلاین",
                        subtitle="تماشای آفلاین با کیفیت دلخواه",
                        accent=true,
                        onClick=onDownload
                    )
                )
                add(
                    PlayerToolActionV2(
                        icon=Icons.Default.Whatshot,
                        title="لحظه‌ها و واکنش‌ها",
                        subtitle="لحظه‌های مهم و واکنش‌های تماشاگرها",
                        onClick=onMoments
                    )
                )
                add(
                    PlayerToolActionV2(
                        icon=Icons.Default.BookmarkAdd,
                        title="نشانه‌گذاری صحنه",
                        subtitle="این لحظه را برای تماشای دوباره ذخیره کن",
                        onClick=onBookmarks
                    )
                )
                add(
                    PlayerToolActionV2(
                        icon=Icons.Default.ManageSearch,
                        title="پیدا کردن دیالوگ",
                        subtitle="داخل دیالوگ‌های همین عنوان جستجو کن",
                        onClick=onDialogueSearch
                    )
                )
                if(hasQueue) {
                    add(
                        PlayerToolActionV2(
                            icon=Icons.Default.QueuePlayNext,
                            title="قسمت‌ها",
                            subtitle="لیست قسمت‌ها و انتخاب سریع",
                            onClick=onQueue
                        )
                    )
                }
            }

            item(key="primary-card") {
                PlayerToolGroupV2(primary)
            }

            item(key="device-title") {
                PlayerSheetSectionTitleV2("دستگاه و نمایش")
            }

            item(key="device-card") {
                PlayerToolGroupV2(
                    listOf(
                        PlayerToolActionV2(
                            icon=Icons.Default.DevicesOther,
                            title="تلویزیون و دستگاه‌ها",
                            subtitle="Cast، Smart View و انتقال پخش به دستگاه‌های دیگه",
                            onClick=onHandoff
                        ),
                        PlayerToolActionV2(
                            icon=Icons.Default.Share,
                            title="اشتراک‌گذاری",
                            subtitle="عنوان یا همین لحظه از پخش را به اشتراک بگذار",
                            onClick=onShare
                        ),
                        PlayerToolActionV2(
                            icon=Icons.Default.PictureInPictureAlt,
                            title="تصویر در تصویر",
                            subtitle="پخش را در یک پنجره کوچک روی برنامه‌های دیگر ادامه بده",
                            onClick=onPip
                        ),
                        PlayerToolActionV2(
                            icon=Icons.Default.LockOpen,
                            title="قفل کنترل‌ها",
                            subtitle="از لمس تصادفی صفحه هنگام تماشا جلوگیری کن",
                            onClick=onLock
                        )
                    )
                )
            }

            item(key="safe-bottom") {
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun PlayerToolsHeaderV2(
    onDismiss: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min=54.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "ابزارهای پخش",
                fontSize=19.sp,
                fontWeight=FontWeight.Black
            )
            Text(
                "همه چیز بدون خروج از ویدیو",
                color=PlayerMuted,
                fontSize=10.sp,
                modifier=Modifier.padding(top=2.dp)
            )
        }
        IconButton(onClick=onDismiss) {
            Icon(Icons.Default.Close,"بستن")
        }
    }
}

@Composable
private fun AutoSubtitleCardV2(
    enabled: Boolean,
    busy: Boolean,
    status: String?,
    activeLabel: String?,
    onEnabled: (Boolean) -> Unit,
    onSettings: () -> Unit
) {
    Surface(
        color=Color(0xFF170E12),
        shape=RoundedCornerShape(24.dp),
        border=BorderStroke(1.dp,FqGold.copy(alpha=.34f)),
        modifier=Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Surface(
                    color=PlayerAccentSoft,
                    contentColor=FqGold,
                    shape=RoundedCornerShape(13.dp),
                    modifier=Modifier.size(44.dp)
                ) {
                    Box(contentAlignment=Alignment.Center) {
                        Icon(
                            Icons.Default.ClosedCaption,
                            contentDescription=null,
                            modifier=Modifier.size(23.dp)
                        )
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "زیرنویس فارسی خودکار",
                        fontSize=15.sp,
                        fontWeight=FontWeight.Black
                    )
                    Text(
                        "اول زیرنویس داخلی، بعد بهترین نتیجه اینترنتیِ هماهنگ با ریلیز بررسی می‌شود.",
                        color=PlayerMuted,
                        fontSize=10.sp,
                        lineHeight=14.sp,
                        modifier=Modifier.padding(top=2.dp)
                    )
                }
                if(busy) {
                    CircularProgressIndicator(
                        modifier=Modifier.size(25.dp),
                        strokeWidth=2.dp,
                        color=FqGold
                    )
                } else {
                    Switch(
                        checked=enabled,
                        onCheckedChange=onEnabled,
                        colors=SwitchDefaults.colors(
                            checkedThumbColor=Color.White,
                            checkedTrackColor=FqGold,
                            uncheckedThumbColor=Color.White.copy(alpha=.76f),
                            uncheckedTrackColor=Color.White.copy(alpha=.14f)
                        )
                    )
                }
            }

            val info=status ?: activeLabel?.let { "فعال • $it" }
            if(!info.isNullOrBlank()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top=11.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(
                        if(enabled)Icons.Default.CheckCircle else Icons.Default.ClosedCaption,
                        null,
                        tint=if(enabled)FqGreen else PlayerMuted,
                        modifier=Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        info,
                        color=if(enabled)FqGreen else PlayerMuted,
                        fontSize=10.sp,
                        maxLines=2,
                        overflow=TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Surface(
                color=Color.White.copy(alpha=.045f),
                shape=RoundedCornerShape(16.dp),
                border=BorderStroke(1.dp,Color.White.copy(alpha=.06f)),
                modifier=Modifier
                    .fillMaxWidth()
                    .clickable(onClick=onSettings)
            ) {
                Row(
                    Modifier.padding(horizontal=12.dp,vertical=11.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Surface(
                        color=Color.White.copy(alpha=.055f),
                        shape=RoundedCornerShape(10.dp),
                        modifier=Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment=Alignment.Center) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription=null,
                                tint=Color.White.copy(alpha=.86f),
                                modifier=Modifier.size(19.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "تنظیمات زیرنویس",
                            fontSize=12.sp,
                            fontWeight=FontWeight.Bold
                        )
                        Text(
                            "اندازه، موقعیت، استایل و فایل زیرنویس دستی",
                            color=PlayerMuted,
                            fontSize=9.sp
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowRight,
                        null,
                        tint=Color.White.copy(alpha=.35f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerSheetSectionTitleV2(
    title: String
) {
    Text(
        title,
        color=PlayerMuted,
        fontSize=10.sp,
        fontWeight=FontWeight.Bold,
        modifier=Modifier.padding(horizontal=4.dp).padding(top=2.dp)
    )
}

@Composable
private fun PlayerToolGroupV2(
    actions: List<PlayerToolActionV2>
) {
    Surface(
        color=PlayerPanelElevated,
        shape=RoundedCornerShape(22.dp),
        border=BorderStroke(1.dp,Color.White.copy(alpha=.065f)),
        modifier=Modifier.fillMaxWidth()
    ) {
        Column {
            actions.forEachIndexed { index,action ->
                PlayerToolRowV2(action)
                if(index<actions.lastIndex) {
                    HorizontalDivider(
                        color=Color.White.copy(alpha=.055f),
                        modifier=Modifier.padding(start=58.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayerToolRowV2(
    action: PlayerToolActionV2
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick=action.onClick)
            .padding(horizontal=13.dp,vertical=11.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Surface(
            color=if(action.accent)PlayerAccentSoft else Color.White.copy(alpha=.045f),
            contentColor=if(action.accent)FqGold else Color.White.copy(alpha=.80f),
            shape=RoundedCornerShape(12.dp),
            modifier=Modifier.size(40.dp)
        ) {
            Box(contentAlignment=Alignment.Center) {
                Icon(
                    action.icon,
                    contentDescription=null,
                    modifier=Modifier.size(21.dp)
                )
            }
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                action.title,
                fontSize=12.sp,
                fontWeight=FontWeight.Bold
            )
            Text(
                action.subtitle,
                color=PlayerMuted,
                fontSize=9.sp,
                lineHeight=13.sp,
                maxLines=2,
                overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=1.dp)
            )
        }
        Icon(
            Icons.Default.KeyboardArrowRight,
            null,
            tint=Color.White.copy(alpha=.28f),
            modifier=Modifier.size(20.dp)
        )
    }
}


private fun playerUiTime(ms: Long): String {
    if(ms<=0L) return "00:00"
    val total=ms/1000L
    val hours=total/3600L
    val minutes=(total%3600L)/60L
    val seconds=total%60L
    return if(hours>0L) {
        "%02d:%02d:%02d".format(hours,minutes,seconds)
    } else {
        "%02d:%02d".format(minutes,seconds)
    }
}

private fun playerUiSpeed(value: Float): String =
    if(kotlin.math.abs(value-value.toInt())<.01f) {
        value.toInt().toString()+"x"
    } else {
        "%.2gx".format(value)
    }
