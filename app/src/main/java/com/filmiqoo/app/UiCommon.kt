package com.filmiqoo.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
import coil.compose.AsyncImage
import java.util.Locale

@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    Box(modifier.background(FqSurface2)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Default.Movie,
                contentDescription = null,
                tint = FqMuted.copy(alpha=.45f),
                modifier = Modifier.size(42.dp).align(Alignment.Center)
            )
        }
    }
}

@Composable
fun BrandTopBar(
    onSearch: (() -> Unit)? = null,
    onNotifications: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal=18.dp, vertical=12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(31.dp).clip(RoundedCornerShape(9.dp)).background(FqGold),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, null, tint=Color.Black, modifier=Modifier.size(24.dp))
            }
            Spacer(Modifier.width(9.dp))
            Text("FILMIQOO", color=FqGold, fontSize=22.sp, fontWeight=FontWeight.Bold)
        }
        Spacer(Modifier.weight(1f))
        if (onSearch != null) {
            IconButton(onClick=onSearch) { Icon(Icons.Default.Search, null) }
        }
        if (onNotifications != null) {
            IconButton(onClick=onNotifications) {
                BadgedBox(badge = { Badge(containerColor=FqDanger) { Text("3") } }) {
                    Icon(Icons.Default.NotificationsNone, null)
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, subtitle: String? = null, onMore: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=24.dp,bottom=11.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize=20.sp, fontWeight=FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color=FqMuted, fontSize=11.sp, modifier=Modifier.padding(top=2.dp))
            }
        }
        if (onMore != null) {
            Text(
                "مشاهده همه",
                color=FqGold,
                fontSize=11.sp,
                modifier=Modifier.clickable { onMore() }.padding(6.dp)
            )
        }
    }
}

@Composable
fun PosterCard(
    media: MediaItem,
    repository: TmdbRepository,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.width(142.dp).clickable { onClick() }) {
        Box(
            Modifier.fillMaxWidth().height(205.dp).clip(RoundedCornerShape(18.dp))
        ) {
            RemoteImage(repository.poster(media.posterPath), Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxWidth().height(86.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha=.88f))))
            )
            if (media.vote > 0) {
                Surface(
                    color=Color.Black.copy(alpha=.65f),
                    shape=RoundedCornerShape(8.dp),
                    modifier=Modifier.padding(8.dp).align(Alignment.TopStart)
                ) {
                    Row(Modifier.padding(horizontal=7.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.Star,null,tint=FqGold,modifier=Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(formatVote(media.vote),fontSize=10.sp)
                    }
                }
            }
            Text(
                media.title,
                modifier=Modifier.align(Alignment.BottomStart).padding(11.dp),
                maxLines=2,
                overflow=TextOverflow.Ellipsis,
                fontSize=15.sp,
                fontWeight=FontWeight.Bold
            )
        }
        Text(
            listOf(media.year, if(media.type==MediaType.MOVIE) "فیلم" else "سریال").filter{it.isNotBlank()}.joinToString(" • "),
            color=FqMuted,
            fontSize=10.sp,
            maxLines=1,
            modifier=Modifier.padding(top=6.dp)
        )
    }
}

@Composable
fun StoryBubble(
    media: MediaItem,
    repository: TmdbRepository,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment=Alignment.CenterHorizontally,
        modifier=Modifier.width(76.dp).clickable { onClick() }
    ) {
        Box(
            Modifier.size(66.dp)
                .background(
                    Brush.sweepGradient(listOf(FqGold,FqDanger,Color(0xFF8C52FF),FqGold)),
                    CircleShape
                ).padding(2.dp)
        ) {
            RemoteImage(
                repository.poster(media.posterPath),
                Modifier.fillMaxSize().clip(CircleShape)
            )
        }
        Text(
            media.title,
            maxLines=1,
            overflow=TextOverflow.Ellipsis,
            fontSize=9.sp,
            modifier=Modifier.padding(top=5.dp)
        )
    }
}

@Composable
fun MetricPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(color=FqSurface2.copy(alpha=.92f),shape=RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(horizontal=10.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Icon(icon,null,tint=FqGold,modifier=Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(text,fontSize=10.sp)
        }
    }
}

@Composable
fun LoadingPage(label: String = "در حال دریافت اطلاعات واقعی...") {
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            CircularProgressIndicator(color=FqGold,strokeWidth=3.dp,modifier=Modifier.size(36.dp))
            Text(label,color=FqMuted,fontSize=12.sp,modifier=Modifier.padding(top=12.dp))
        }
    }
}

@Composable
fun ErrorPage(message: String, retry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(26.dp),contentAlignment=Alignment.Center) {
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(Icons.Default.CloudOff,null,tint=FqMuted,modifier=Modifier.size(52.dp))
            Text("ارتباط با TMDB برقرار نشد",fontSize=18.sp,modifier=Modifier.padding(top=14.dp))
            Text(message,color=FqMuted,fontSize=11.sp,modifier=Modifier.padding(top=8.dp))
            Button(
                onClick=retry,
                colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                modifier=Modifier.padding(top=18.dp)
            ) { Text("تلاش دوباره") }
        }
    }
}

fun formatVote(value: Double): String = String.format(Locale.US, "%.1f", value)

fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری با"))
}

fun openYoutube(context: Context, key: String) {
    val url = "https://www.youtube.com/watch?v=" + key
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
