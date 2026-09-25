package com.filmiqoo.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object FqDimens {
    val Xs=4.dp
    val Sm=8.dp
    val Md=12.dp
    val Lg=16.dp
    val Xl=24.dp
    val Xxl=32.dp
    val Screen=16.dp
    val SectionGap=28.dp
    val CardRadius=22.dp
    val SmallRadius=14.dp
    val PillRadius=999.dp
    val Touch=48.dp
}

val FqGlass=Color(0xE6111419)
val FqBorder=Color.White.copy(alpha=.10f)
val FqElevated=Color(0xFF141922)

@Composable
fun PremiumTopBar(
    title:String="FILMIQOO",
    subtitle:String?=null,
    onSearch:(()->Unit)?=null,
    onNotifications:(()->Unit)?=null,
    onProfile:(()->Unit)?=null
) {
    Row(
        Modifier.fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal=FqDimens.Screen,vertical=10.dp),
        verticalAlignment=Alignment.CenterVertically
    ) {
        FilmiqooBrandMark(size=40.dp)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style=MaterialTheme.typography.titleLarge,
                fontWeight=FontWeight.Black,
                color=FqText,
                maxLines=1,
                overflow=TextOverflow.Ellipsis
            )
            if(!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style=MaterialTheme.typography.bodySmall,
                    color=FqMuted,
                    maxLines=1,
                    overflow=TextOverflow.Ellipsis
                )
            }
        }
        if(onSearch!=null) {
            FqIconButton(
                icon=Icons.Default.Search,
                contentDescription="جستجو",
                onClick=onSearch
            )
        }
        if(onNotifications!=null) {
            BadgedBox(
                badge={
                    Badge(
                        containerColor=FqDanger,
                        modifier=Modifier.size(8.dp)
                    )
                }
            ) {
                FqIconButton(
                    icon=Icons.Default.NotificationsNone,
                    contentDescription="اعلان‌ها",
                    onClick=onNotifications
                )
            }
        }
        if(onProfile!=null) {
            FqIconButton(
                icon=Icons.Default.PersonOutline,
                contentDescription="پروفایل",
                onClick=onProfile
            )
        }
    }
}

@Composable
fun FilmiqooBrandMark(
    size:Dp=40.dp,
    modifier:Modifier=Modifier
) {
    Box(
        modifier.size(size)
            .clip(RoundedCornerShape(size*.30f))
            .background(
                Brush.linearGradient(
                    listOf(FqGoldSoft,FqGold)
                )
            ),
        contentAlignment=Alignment.Center
    ) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription=null,
            tint=Color(0xFF151000),
            modifier=Modifier.size(size*.68f)
        )
    }
}

@Composable
fun FqIconButton(
    icon:ImageVector,
    contentDescription:String,
    onClick:()->Unit,
    modifier:Modifier=Modifier,
    accent:Boolean=false
) {
    val background=if(accent) FqGold else Color.Transparent
    val foreground=if(accent) Color(0xFF171000) else FqText
    Surface(
        color=background,
        contentColor=foreground,
        shape=CircleShape,
        modifier=modifier.size(FqDimens.Touch)
            .clickable(
                role=Role.Button,
                onClick=onClick
            )
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
fun PremiumSectionHeader(
    title:String,
    subtitle:String?=null,
    icon:ImageVector?=null,
    onMore:(()->Unit)?=null
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(
                start=FqDimens.Screen,
                end=FqDimens.Screen,
                top=FqDimens.Xl,
                bottom=FqDimens.Md
            ),
        verticalAlignment=Alignment.CenterVertically
    ) {
        if(icon!=null) {
            Surface(
                color=FqGold.copy(alpha=.12f),
                contentColor=FqGold,
                shape=RoundedCornerShape(12.dp),
                modifier=Modifier.size(38.dp)
            ) {
                Box(contentAlignment=Alignment.Center) {
                    Icon(icon,null,modifier=Modifier.size(19.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style=MaterialTheme.typography.titleLarge,
                fontWeight=FontWeight.Bold,
                maxLines=1,
                overflow=TextOverflow.Ellipsis
            )
            if(!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style=MaterialTheme.typography.bodySmall,
                    color=FqMuted,
                    maxLines=2,
                    overflow=TextOverflow.Ellipsis,
                    modifier=Modifier.padding(top=2.dp)
                )
            }
        }
        if(onMore!=null) {
            TextButton(
                onClick=onMore,
                contentPadding=PaddingValues(horizontal=10.dp,vertical=8.dp)
            ) {
                Text(
                    "مشاهده همه",
                    style=MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.width(2.dp))
                Icon(
                    Icons.Default.ChevronLeft,
                    null,
                    modifier=Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
fun PremiumChip(
    icon:ImageVector?=null,
    label:String,
    active:Boolean=false,
    onClick:()->Unit={}
) {
    val background by animateColorAsState(
        if(active) FqGold else FqSurface2,
        label="chipBackground"
    )
    val foreground by animateColorAsState(
        if(active) Color(0xFF171000) else FqText,
        label="chipForeground"
    )
    val elevation by animateDpAsState(
        if(active) 2.dp else 0.dp,
        label="chipElevation"
    )
    Surface(
        color=background,
        contentColor=foreground,
        tonalElevation=elevation,
        shadowElevation=elevation,
        shape=RoundedCornerShape(FqDimens.PillRadius),
        modifier=Modifier.heightIn(min=40.dp)
            .clickable(role=Role.Button,onClick=onClick)
    ) {
        Row(
            Modifier.padding(horizontal=14.dp,vertical=9.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            if(icon!=null) {
                Icon(icon,null,modifier=Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                style=MaterialTheme.typography.labelMedium,
                fontWeight=FontWeight.Bold
            )
        }
    }
}

@Composable
fun PremiumEmptyState(
    icon:ImageVector,
    title:String,
    body:String,
    modifier:Modifier=Modifier,
    action:String?=null,
    onAction:(()->Unit)?=null
) {
    Column(
        modifier.fillMaxWidth()
            .padding(horizontal=28.dp,vertical=38.dp),
        horizontalAlignment=Alignment.CenterHorizontally
    ) {
        Surface(
            color=FqSurface2,
            contentColor=FqMutedStrong,
            shape=CircleShape,
            modifier=Modifier.size(72.dp)
        ) {
            Box(contentAlignment=Alignment.Center) {
                Icon(icon,null,modifier=Modifier.size(34.dp))
            }
        }
        Text(
            title,
            style=MaterialTheme.typography.titleMedium,
            fontWeight=FontWeight.Bold,
            textAlign=TextAlign.Center,
            modifier=Modifier.padding(top=14.dp)
        )
        Text(
            body,
            color=FqMuted,
            style=MaterialTheme.typography.bodySmall,
            textAlign=TextAlign.Center,
            modifier=Modifier.padding(top=6.dp)
        )
        if(action!=null && onAction!=null) {
            OutlinedButton(
                onClick=onAction,
                modifier=Modifier.padding(top=16.dp)
                    .heightIn(min=48.dp)
            ) {
                Text(action)
            }
        }
    }
}

@Composable
fun PremiumStat(
    value:String,
    label:String,
    modifier:Modifier=Modifier
) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(18.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
        modifier=modifier
    ) {
        Column(
            Modifier.padding(horizontal=14.dp,vertical=14.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Text(
                value,
                color=FqGold,
                style=MaterialTheme.typography.titleMedium,
                fontWeight=FontWeight.Black
            )
            Text(
                label,
                color=FqMuted,
                style=MaterialTheme.typography.labelSmall,
                modifier=Modifier.padding(top=3.dp)
            )
        }
    }
}

@Composable
fun FqCard(
    modifier:Modifier=Modifier,
    onClick:(()->Unit)?=null,
    content:@Composable ColumnScope.()->Unit
) {
    val base=modifier.fillMaxWidth()
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(FqDimens.CardRadius),
        border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
        modifier=if(onClick!=null) {
            base.clickable(role=Role.Button,onClick=onClick)
        } else base
    ) {
        Column(
            Modifier.padding(FqDimens.Lg),
            content=content
        )
    }
}

@Composable
fun FqPrimaryButton(
    text:String,
    onClick:()->Unit,
    modifier:Modifier=Modifier,
    icon:ImageVector?=null,
    enabled:Boolean=true,
    loading:Boolean=false
) {
    Button(
        enabled=enabled && !loading,
        onClick=onClick,
        colors=ButtonDefaults.buttonColors(
            containerColor=FqGold,
            contentColor=Color(0xFF171000),
            disabledContainerColor=FqSurface3,
            disabledContentColor=FqMuted
        ),
        shape=RoundedCornerShape(16.dp),
        contentPadding=PaddingValues(horizontal=18.dp,vertical=13.dp),
        modifier=modifier.heightIn(min=50.dp)
    ) {
        if(loading) {
            CircularProgressIndicator(
                color=Color(0xFF171000),
                strokeWidth=2.dp,
                modifier=Modifier.size(19.dp)
            )
        } else if(icon!=null) {
            Icon(icon,null,modifier=Modifier.size(20.dp))
        }
        if(loading || icon!=null) Spacer(Modifier.width(8.dp))
        Text(
            text,
            style=MaterialTheme.typography.labelLarge,
            fontWeight=FontWeight.Bold
        )
    }
}

@Composable
fun FqSecondaryButton(
    text:String,
    onClick:()->Unit,
    modifier:Modifier=Modifier,
    icon:ImageVector?=null,
    enabled:Boolean=true
) {
    OutlinedButton(
        enabled=enabled,
        onClick=onClick,
        shape=RoundedCornerShape(16.dp),
        border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder),
        contentPadding=PaddingValues(horizontal=18.dp,vertical=13.dp),
        modifier=modifier.heightIn(min=50.dp)
    ) {
        if(icon!=null) {
            Icon(icon,null,modifier=Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text,style=MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun FqLoadingState(
    label:String,
    modifier:Modifier=Modifier
) {
    Column(
        modifier.fillMaxSize()
            .padding(32.dp),
        horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.Center
    ) {
        CircularProgressIndicator(
            color=FqGold,
            strokeWidth=3.dp,
            modifier=Modifier.size(38.dp)
        )
        Text(
            label,
            color=FqMuted,
            style=MaterialTheme.typography.bodyMedium,
            textAlign=TextAlign.Center,
            modifier=Modifier.padding(top=14.dp)
        )
    }
}

@Composable
fun FqErrorState(
    message:String,
    retry:(()->Unit)?=null,
    modifier:Modifier=Modifier
) {
    Column(
        modifier.fillMaxSize()
            .padding(30.dp),
        horizontalAlignment=Alignment.CenterHorizontally,
        verticalArrangement=Arrangement.Center
    ) {
        Surface(
            color=FqDanger.copy(alpha=.12f),
            contentColor=FqDanger,
            shape=CircleShape,
            modifier=Modifier.size(72.dp)
        ) {
            Box(contentAlignment=Alignment.Center) {
                Icon(Icons.Default.CloudOff,null,modifier=Modifier.size(34.dp))
            }
        }
        Text(
            "مشکلی پیش اومد",
            style=MaterialTheme.typography.titleMedium,
            fontWeight=FontWeight.Bold,
            modifier=Modifier.padding(top=14.dp)
        )
        Text(
            message,
            color=FqMuted,
            style=MaterialTheme.typography.bodySmall,
            textAlign=TextAlign.Center,
            modifier=Modifier.padding(top=6.dp)
        )
        if(retry!=null) {
            FqPrimaryButton(
                text="تلاش دوباره",
                icon=Icons.Default.Refresh,
                onClick=retry,
                modifier=Modifier.padding(top=16.dp)
            )
        }
    }
}
