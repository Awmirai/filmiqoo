package com.filmiqoo.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.util.Locale

internal val CinemaInk = Color(0xFF090B10)
internal val CinemaSurface = Color(0xFF131720)
internal val CinemaLine = Color(0xFF272E3A)
internal val CinemaAccent = Color(0xFFFF4155)
internal val CinemaSoft = Color(0xFFA8B2C4)
internal val CinemaPaper = Color(0xFFF6F7FA)
internal val CinemaGold = Color(0xFFF3CC83)

internal fun cinemaMediaKey(media: MediaItem): String = if (media.id > 0) "${media.type}:${media.id}" else "catalog:${media.backendId ?: media.title}"
internal fun cinemaImage(path: String?, backdrop: Boolean = false): String? = path?.takeIf { it.isNotBlank() && it != "null" }?.let {
    if (it.startsWith("https://") || it.startsWith("http://")) it else "https://image.tmdb.org/t/p/" + (if (backdrop) "w1280" else "w500") + it
}

@Composable
internal fun CinemaImage(path: String?, modifier: Modifier = Modifier, backdrop: Boolean = false) {
    Box(modifier.background(CinemaSurface), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Movie, null, tint = CinemaSoft.copy(alpha = .22f), modifier = Modifier.size(40.dp))
        AsyncImage(model = cinemaImage(path, backdrop), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun CinemaCard(modifier: Modifier = Modifier, accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, color = CinemaSurface, shape = RoundedCornerShape(CinemaTokens.cardRadius),
        border = BorderStroke(1.dp, if (accent) CinemaAccent.copy(alpha = .34f) else CinemaLine)) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
internal fun CinemaHeading(title: String, subtitle: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = CinemaTokens.page, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = CinemaPaper)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 12.sp, lineHeight = 19.sp, color = CinemaSoft, modifier = Modifier.padding(top = 3.dp))
        }
        if (onAction != null) TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action ?: "همه", color = CinemaAccent) }
    }
}

@Composable
internal fun CinemaTag(label: String, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val touch = if (onClick != null) Modifier.heightIn(min = CinemaTokens.touch).selectable(selected = selected, role = Role.Tab, onClick = onClick) else Modifier
    Surface(modifier = touch, color = if (selected) CinemaAccent.copy(alpha = .14f) else CinemaSurface,
        contentColor = if (selected) CinemaAccent else CinemaSoft, shape = RoundedCornerShape(13.dp),
        border = BorderStroke(1.dp, if (selected) CinemaAccent.copy(alpha = .4f) else CinemaLine)) {
        Box(Modifier.padding(horizontal = 13.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
internal fun CinemaAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    Button(onClick, modifier.heightIn(min = 52.dp), enabled = enabled, shape = RoundedCornerShape(CinemaTokens.radius),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) CinemaAccent else CinemaSurface,
            contentColor = if (primary) CinemaInk else CinemaPaper),
        border = if (primary) null else BorderStroke(1.dp, CinemaLine), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        if(androidx.compose.ui.platform.LocalDensity.current.fontScale<1.6f) {
            Icon(icon, null, modifier = Modifier.size(21.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun CinemaPoster(media: MediaItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(role = Role.Button, onClickLabel = "جزئیات ${media.title}", onClick = onClick).testTag("poster-${cinemaMediaKey(media)}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(17.dp))) {
            CinemaImage(media.posterPath, Modifier.fillMaxSize())
            Box(Modifier.fillMaxWidth().height(64.dp).align(Alignment.BottomCenter).background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f)))))
            if (media.vote > 0) Surface(color = Color(0xD9090B10), shape = RoundedCornerShape(9.dp), modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Text("★ " + String.format(Locale.US, "%.1f", media.vote), fontSize = 12.sp, color = CinemaGold, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp))
            }
            if (media.streamReady && !media.mediaVersionId.isNullOrBlank()) Surface(color = CinemaAccent, shape = CircleShape,
                modifier = Modifier.align(Alignment.BottomEnd).padding(9.dp)) {
                Icon(Icons.Default.PlayArrow, "دارای فایل پخش", modifier = Modifier.padding(5.dp).size(20.dp), tint = Color.White)
            }
        }
        Text(media.title, color = CinemaPaper, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(listOf(media.year, if (media.type == MediaType.MOVIE) "فیلم" else "سریال").filter(String::isNotBlank).joinToString(" · "),
            color = CinemaSoft, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
internal fun CinemaShelf(title: String, subtitle: String?, media: List<MediaItem>, onMedia: (MediaItem) -> Unit, onMore: (() -> Unit)? = null) {
    if (media.isEmpty()) return
    Column {
        CinemaHeading(title, subtitle, onAction = onMore)
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            items(media.distinctBy(::cinemaMediaKey), key = ::cinemaMediaKey) { item -> CinemaPoster(item, { onMedia(item) }, Modifier.width(140.dp)) }
        }
    }
}

@Composable
internal fun CinemaNotice(title: String, message: String, icon: ImageVector = Icons.Default.Info, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    CinemaCard(Modifier.fillMaxWidth()) {
        Icon(icon, null, tint = CinemaAccent, modifier = Modifier.size(30.dp))
        Text(title, fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, color = CinemaPaper, modifier = Modifier.padding(top = 12.dp))
        Text(message, fontSize = 13.sp, lineHeight = 22.sp, color = CinemaSoft, modifier = Modifier.padding(top = 6.dp))
        if (onAction != null) TextButton(onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel ?: "دوباره تلاش کن", color = CinemaAccent) }
    }
}

@Composable
internal fun CinemaBottomBar(selected: Int, kidsMode: Boolean, onSelected: (Int) -> Unit) {
    CinemaNavigation(selected, kidsMode, onSelected)
}
