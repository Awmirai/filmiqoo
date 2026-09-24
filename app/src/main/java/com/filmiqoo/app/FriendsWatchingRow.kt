package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

@Composable
fun FriendsWatchingRow(
    items:List<FriendWatchingNow>,
    repository:TmdbRepository,
    onMedia:(MediaItem)->Unit
) {
    LazyRow(
        contentPadding=PaddingValues(horizontal=FqDimens.Screen),
        horizontalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        items(items,key={it.user.id+":"+it.media.key}) { item ->
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(20.dp),
                modifier=Modifier.width(250.dp).clickable { onMedia(item.media) }
            ) {
                Box(
                    Modifier.fillMaxWidth().height(142.dp)
                ) {
                    RemoteImage(
                        repository.backdrop(item.media.backdropPath ?: item.media.posterPath),
                        Modifier.fillMaxSize(),
                        ContentScale.Crop
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha=.08f),
                                    Color.Black.copy(alpha=.25f),
                                    Color.Black.copy(alpha=.88f)
                                )
                            )
                        )
                    )

                    Row(
                        Modifier.align(Alignment.TopStart).padding(9.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        RemoteImage(
                            item.user.avatarUrl.takeIf(String::isNotBlank),
                            Modifier.size(34.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.width(7.dp))
                        Column {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text(
                                    item.user.displayName,
                                    color=Color.White,
                                    fontSize=8.sp,
                                    fontWeight=FontWeight.Bold,
                                    maxLines=1,
                                    overflow=TextOverflow.Ellipsis
                                )
                                if(item.user.verified) {
                                    Spacer(Modifier.width(3.dp))
                                    Icon(
                                        Icons.Default.Verified,
                                        null,
                                        tint=Color(0xFF4AB7FF),
                                        modifier=Modifier.size(12.dp)
                                    )
                                }
                            }
                            Text(
                                "الان داره می‌بینه",
                                color=FqGreen,
                                fontSize=6.sp
                            )
                        }
                    }

                    Column(
                        Modifier.align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(11.dp)
                    ) {
                        Text(
                            item.media.title,
                            color=Color.White,
                            fontSize=13.sp,
                            fontWeight=FontWeight.Black,
                            maxLines=1,
                            overflow=TextOverflow.Ellipsis
                        )
                        Row(
                            Modifier.padding(top=4.dp),
                            verticalAlignment=Alignment.CenterVertically
                        ) {
                            Surface(
                                color=FqGold,
                                shape=CircleShape
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    null,
                                    tint=Color.Black,
                                    modifier=Modifier.size(21.dp).padding(3.dp)
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                listOf(
                                    item.episodeLabel,
                                    item.episodeTitle.orEmpty()
                                ).filter(String::isNotBlank).joinToString(" • ")
                                    .ifBlank {
                                        if(item.media.type==MediaType.MOVIE)"فیلم" else "سریال"
                                    },
                                color=Color.White.copy(alpha=.75f),
                                fontSize=7.sp,
                                maxLines=1,
                                overflow=TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
