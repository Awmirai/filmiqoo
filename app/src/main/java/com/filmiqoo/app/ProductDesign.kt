package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared rhythm. Window constraints, never device model names, decide the layout. */
internal object CinemaTokens {
    val gap = 8.dp
    val section = 24.dp
    val page = 20.dp
    val radius = 16.dp
    val cardRadius = 22.dp
    val icon = 24.dp
    val touch = 48.dp
    val contentMax = 1120.dp
    const val motionMs = 180
}
internal data class CinemaDestination(val id:Int,val label:String,val icon:ImageVector)
internal fun cinemaDestinations(kids:Boolean)=listOf(
    CinemaDestination(0,"خانه",Icons.Outlined.Home),
    CinemaDestination(1,"جستجو",Icons.Outlined.Search),
    CinemaDestination(2,"شبکه",Icons.Outlined.Forum),
    CinemaDestination(4,"هم‌تماشا",Icons.Outlined.Groups),
    CinemaDestination(3,"پروفایل",Icons.Outlined.AccountCircle)
).filter { !kids || it.id==0 || it.id==3 }

@Composable
internal fun CinemaNavigation(selected:Int,kids:Boolean,onSelected:(Int)->Unit,rail:Boolean=false) {
    val entries=cinemaDestinations(kids)
    val window=LocalConfiguration.current
    @Composable fun Item(entry:CinemaDestination,modifier:Modifier) {
        val active=entry.id==selected
        val largeLabels=window.fontScale>=1.6f && !rail
        val label=when {largeLabels && entry.id==3->"من";largeLabels && entry.id==4->"هم\nتماشا";else->entry.label}
        Column(modifier.heightIn(min=72.dp).selectable(active,role=Role.Tab,onClick={onSelected(entry.id)})
            .testTag("navigation-${entry.id}").semantics { contentDescription=entry.label }.padding(horizontal=2.dp,vertical=10.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            Surface(color=if(active)CinemaAccent.copy(alpha=.14f)else CinemaSurface,shape=MaterialTheme.shapes.medium) {
                Icon(entry.icon,null,tint=if(active)CinemaAccent else CinemaSoft,modifier=Modifier.padding(horizontal=14.dp,vertical=5.dp).size(CinemaTokens.icon))
            }
            Text(label,fontSize=12.sp,lineHeight=18.sp,color=if(active)CinemaPaper else CinemaSoft,
                fontWeight=if(active)FontWeight.Bold else FontWeight.Normal,textAlign=androidx.compose.ui.text.style.TextAlign.Center,
                maxLines=if(largeLabels && entry.id==4)2 else 1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=4.dp))
        }
    }
    if(rail) Surface(color=CinemaSurface,modifier=Modifier.width(112.dp).fillMaxHeight().testTag("navigation-rail")) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).selectableGroup()) {
            if(window.screenHeightDp>=480)Box(Modifier.fillMaxWidth().padding(vertical=24.dp),contentAlignment=Alignment.Center) { FilmiqooBrandMark(32.dp) }
            entries.forEach { Item(it,Modifier.fillMaxWidth()) }
        }
    } else Surface(color=CinemaSurface,modifier=Modifier.testTag("navigation-bar")) {
        Column(Modifier.navigationBarsPadding()) {
            HorizontalDivider(color=CinemaLine)
            Row(Modifier.fillMaxWidth().selectableGroup()) { entries.forEach {
                val weight=if(window.fontScale>=1.6f && window.screenWidthDp<400 && !kids)when(it.id){0->.9f;1->1.2f;2->1.05f;4->1.25f;else->.8f}else 1f
                Item(it,Modifier.weight(weight))
            } }
        }
    }
}

/** The bar owns its inset and consumes real layout space; it never floats over a poster. */
@Composable
internal fun CinemaAppShell(selected:Int,kids:Boolean,onSelected:(Int)->Unit,content:@Composable ()->Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(CinemaInk).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        val rail=maxWidth>=600.dp
        Row(Modifier.fillMaxSize()) {
            if(rail) CinemaNavigation(selected,kids,onSelected,true)
            Scaffold(modifier=Modifier.weight(1f),containerColor=CinemaInk,
                contentWindowInsets=WindowInsets(0,0,0,0),
                bottomBar={if(!rail) CinemaNavigation(selected,kids,onSelected)}) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                    .windowInsetsPadding(if(rail)WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)else WindowInsets(0,0,0,0)),contentAlignment=Alignment.TopCenter) {
                    Box(Modifier.widthIn(max=CinemaTokens.contentMax).fillMaxSize()) { content() }
                }
            }
        }
    }
}

@Composable
internal fun CinemaPageHeader(title:String,subtitle:String?=null,onBack:(()->Unit)?=null,actions:@Composable RowScope.()->Unit={}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
        if(onBack!=null) IconButton(onBack) { Icon(Icons.Outlined.ArrowForward,"بازگشت") }
        Column(Modifier.weight(1f).padding(horizontal=8.dp)) {
            Text(title,style=MaterialTheme.typography.headlineMedium,color=CinemaPaper)
            if(!subtitle.isNullOrBlank()) Text(subtitle,style=MaterialTheme.typography.bodySmall,color=CinemaSoft)
        }
        actions()
    }
}
