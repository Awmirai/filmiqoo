package com.filmiqoo.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun CinemaSpotlight(bundle: HomeBundle, onMedia: (MediaItem) -> Unit, onStory: (MediaItem, Int) -> Unit) {
    var category by rememberSaveable { mutableIntStateOf(0) }
    val choices = when (category) { 1 -> bundle.popularMovies; 2 -> bundle.popularTv; else -> bundle.trending }.distinctBy(::cinemaMediaKey).take(7)
    val pager = rememberPagerState { choices.size }
    LaunchedEffect(category) { if (choices.isNotEmpty()) pager.scrollToPage(0) }
    Column(Modifier.testTag("home-spotlight")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("برای امشب", "فیلم", "سریال").forEachIndexed { index, label -> CinemaTag(label, index == category) { category = index } }
        }
        if (choices.isNotEmpty()) HorizontalPager(pager, key = { cinemaMediaKey(choices[it]) }, modifier = Modifier.fillMaxWidth()) { index ->
            val media = choices[index]
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val height = (maxWidth * 1.2f).coerceIn(430.dp, 620.dp)
                Box(Modifier.fillMaxWidth().heightIn(min = height).background(CinemaInk)) {
                    CinemaImage(media.backdropPath ?: media.posterPath, Modifier.matchParentSize(), true)
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(CinemaInk.copy(alpha = .18f), Color.Transparent, CinemaInk.copy(alpha = .7f), CinemaInk))))
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 26.dp).padding(top = 220.dp, bottom = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(color = CinemaInk.copy(alpha = .65f), shape = RoundedCornerShape(30.dp), border = BorderStroke(1.dp, CinemaLine)) {
                            Text("انتخاب‌های سینمایی · ${index + 1} از ${choices.size}", color = CinemaGold, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
                        }
                        Text(media.title, color = CinemaPaper, fontWeight = FontWeight.Black, fontSize = 32.sp, lineHeight = 41.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                        Text(listOf(media.year, if (media.type == MediaType.TV) "سریال" else "فیلم", if (media.vote > 0) "★ ${String.format(Locale.US, "%.1f", media.vote)} TMDB" else "").filter(String::isNotBlank).joinToString("  ·  "), color = CinemaSoft, fontSize = 13.sp, modifier = Modifier.padding(vertical = 12.dp))
                        CinemaAction(Icons.Default.PlayCircleOutline, "کشف این عنوان", { onMedia(media) }, Modifier.widthIn(min = 210.dp), primary = true)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.Center) {
            repeat(choices.size) { index -> Box(Modifier.padding(3.dp).size(if (index == pager.currentPage) 22.dp else 6.dp, 6.dp).clip(CircleShape).background(if (index == pager.currentPage) CinemaAccent else CinemaLine)) }
        }
        CinemaHeading("در یک نگاه", "قصهٔ بعدی‌ات را پیدا کن")
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(bundle.trending.take(10), key = { _, it -> cinemaMediaKey(it) }) { index, media ->
                Column(Modifier.width(80.dp).clickable { onStory(media, index) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(shape = CircleShape, border = BorderStroke(2.dp, CinemaAccent), modifier = Modifier.size(78.dp)) {
                        CinemaImage(media.posterPath, Modifier.padding(4.dp).clip(CircleShape))
                    }
                    Text(media.title, color = CinemaPaper, fontSize = 11.sp, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
