package com.filmiqoo.app

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object FqDimens {
    val Screen = 16.dp
    val SectionGap = 26.dp
    val CardRadius = 22.dp
    val SmallRadius = 14.dp
    val PillRadius = 999.dp
}

val FqGlass = Color(0xCC151922)
val FqBorder = Color.White.copy(alpha=.09f)
val FqElevated = Color(0xFF151923)
val FqBlue = Color(0xFF62A8FF)

@Composable
fun PremiumTopBar(
    title: String = "FILMIQOO",
    subtitle: String? = null,
    onSearch: (() -> Unit)? = null,
    onNotifications: (() -> Unit)? = null,
    onProfile: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal=FqDimens.Screen,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(FqGold,FqGoldSoft))),
            contentAlignment=Alignment.Center
        ) {
            Icon(Icons.Default.PlayArrow,null,tint=Color.Black,modifier=Modifier.size(29.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title,color=FqText,fontSize=20.sp,fontWeight=FontWeight.Black)
            if(!subtitle.isNullOrBlank()) {
                Text(subtitle,color=FqMuted,fontSize=9.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
            }
        }
        if(onSearch!=null) {
            IconButton(onClick=onSearch) { Icon(Icons.Default.Search,null) }
        }
        if(onNotifications!=null) {
            IconButton(onClick=onNotifications) {
                BadgedBox(badge={Badge(containerColor=FqDanger)}) {
                    Icon(Icons.Default.NotificationsNone,null)
                }
            }
        }
        if(onProfile!=null) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(FqSurface2)
                    .clickable { onProfile() },
                contentAlignment=Alignment.Center
            ) {
                Icon(Icons.Default.Person,null,tint=FqGold,modifier=Modifier.size(19.dp))
            }
        }
    }
}

@Composable
fun PremiumSectionHeader(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onMore: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().padding(start=FqDimens.Screen,end=FqDimens.Screen,top=24.dp,bottom=11.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        if(icon!=null) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(FqGold.copy(alpha=.12f)),
                contentAlignment=Alignment.Center
            ) {
                Icon(icon,null,tint=FqGold,modifier=Modifier.size(18.dp))
            }
            Spacer(Modifier.width(9.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title,fontSize=18.sp,fontWeight=FontWeight.Bold)
            if(!subtitle.isNullOrBlank()) {
                Text(subtitle,color=FqMuted,fontSize=9.sp,modifier=Modifier.padding(top=2.dp))
            }
        }
        if(onMore!=null) {
            TextButton(onClick=onMore,contentPadding=PaddingValues(horizontal=7.dp,vertical=2.dp)) {
                Text("مشاهده همه",fontSize=9.sp)
                Spacer(Modifier.width(2.dp))
                Icon(Icons.Default.ChevronLeft,null,modifier=Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun PremiumChip(
    icon: ImageVector? = null,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit = {}
) {
    Surface(
        color=if(active)FqGold else FqSurface2,
        contentColor=if(active)Color.Black else FqText,
        shape=RoundedCornerShape(FqDimens.PillRadius),
        modifier=Modifier.clickable { onClick() }
    ) {
        Row(
            Modifier.padding(horizontal=12.dp,vertical=8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            if(icon!=null) {
                Icon(icon,null,modifier=Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
            }
            Text(label,fontSize=9.sp,fontWeight=FontWeight.Bold)
        }
    }
}

@Composable
fun PremiumEmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=34.dp),
        horizontalAlignment=Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(68.dp).clip(CircleShape).background(FqSurface2),
            contentAlignment=Alignment.Center
        ) {
            Icon(icon,null,tint=FqMuted,modifier=Modifier.size(34.dp))
        }
        Text(title,fontSize=16.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=12.dp))
        Text(body,color=FqMuted,fontSize=10.sp,lineHeight=16.sp,modifier=Modifier.padding(top=5.dp))
        if(action!=null && onAction!=null) {
            OutlinedButton(onClick=onAction,modifier=Modifier.padding(top=13.dp)) {
                Text(action)
            }
        }
    }
}

@Composable
fun PremiumStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(16.dp),
        modifier=modifier
    ) {
        Column(Modifier.padding(horizontal=14.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Text(value,color=FqGold,fontSize=16.sp,fontWeight=FontWeight.Black)
            Text(label,color=FqMuted,fontSize=8.sp,modifier=Modifier.padding(top=2.dp))
        }
    }
}
