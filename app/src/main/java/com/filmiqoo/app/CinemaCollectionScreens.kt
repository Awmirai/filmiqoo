package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CinemaCreateScreen(
    social: SocialRepository, backend: BackendRepository, repository: TmdbRepository,
    loggedIn: Boolean, onRequireAuth: () -> Unit, onOpenClub: () -> Unit, onOpenClips: () -> Unit,
    onOpenStudio: () -> Unit, onBack: () -> Unit
) {
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf<String?>(null) }
    if (editorOpen) {
        PremiumCreateHubScreen(social, backend, repository, loggedIn, onRequireAuth, onOpenClub, onOpenClips, onOpenStudio,
            onBack = { editorOpen = false }, initialKind = kind)
        return
    }
    BackHandler(onBack = onBack)
    LazyColumn(Modifier.fillMaxSize().background(CinemaInk).statusBarsPadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.Default.ArrowForward, "بازگشت", tint = CinemaPaper) }
                Column(Modifier.weight(1f)) {
                    Text("اتاق خلاقیت", fontSize = 27.sp, fontWeight = FontWeight.Black, color = CinemaPaper)
                    Text("سلیقهٔ سینمایی‌ات را به اشتراک بگذار", color = CinemaSoft, fontSize = 12.sp)
                }
            }
        }
        item {
            CinemaCard(Modifier.fillMaxWidth(), accent = true) {
                Icon(Icons.Default.AutoAwesome, null, tint = CinemaAccent, modifier = Modifier.size(34.dp))
                Text("تماشا کردی؛ حالا روایت کن.", color = CinemaPaper, fontSize = 24.sp, lineHeight = 33.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 14.dp))
                Text("نقد، گفت‌وگو، کلیپ و نظرسنجی؛ با اتصال به عنوان و کنترل اسپویلر.", color = CinemaSoft, fontSize = 14.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 8.dp))
                CinemaAction(Icons.Default.EditNote, "باز کردن پیش‌نویس و ویرایشگر", { kind = null; editorOpen = true }, Modifier.fillMaxWidth().padding(top = 16.dp), primary = true)
            }
        }
        val choices = listOf(
            Triple("REVIEW", "یک نقد بنویس", "برداشتت از فیلم، با هشدار اسپویلر"),
            Triple("REEL", "یک کلیپ بساز", "ویدیوی کوتاهت را به یک عنوان وصل کن"),
            Triple("POLL", "نظر جمع را بپرس", "دو تا شش انتخاب برای یک بحث سینمایی"),
            Triple("POST", "گفت‌وگو را شروع کن", "یک سؤال خوب، شروع یک کلاب خوب است"),
            Triple("STORY", "استوری منتشر کن", "یک لحظه یا پیشنهاد کوتاه برای دنبال‌کننده‌ها"),
            Triple("CHANNEL", "کانال خودت را بساز", "فضایی برای سلیقه و مخاطبان خودت")
        )
        items(choices, key = { it.first }) { (value, title, subtitle) ->
            CinemaCard(Modifier.fillMaxWidth().clickable { kind = value; editorOpen = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (value) { "REVIEW" -> Icons.Default.RateReview; "REEL" -> Icons.Default.MovieCreation; "POLL" -> Icons.Default.Poll; "STORY" -> Icons.Default.AutoStories; "CHANNEL" -> Icons.Default.Campaign; else -> Icons.Default.Forum }, null, tint = CinemaAccent)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) { Text(title, color = CinemaPaper, fontSize = 16.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = CinemaSoft, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp)) }
                    Icon(Icons.Default.ChevronLeft, null, tint = CinemaSoft)
                }
            }
        }
        item { CinemaAction(Icons.Default.Insights, "آمار، زمان‌بندی و مدیریت محتوا", onOpenStudio, Modifier.fillMaxWidth()) }
    }
}
