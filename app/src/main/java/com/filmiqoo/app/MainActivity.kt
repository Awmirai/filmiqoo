package com.filmiqoo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FilmiqooTheme {
                FilmiqooApp()
            }
        }
    }
}

@Composable
fun FilmiqooApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { TmdbRepository(context.applicationContext) }
    val store = remember { LocalStore(context.applicationContext) }

    var tab by remember { mutableIntStateOf(0) }
    var overlay by remember { mutableStateOf<OverlayRoute?>(null) }
    var showSearch by remember { mutableStateOf(false) }

    val closeOverlay = {
        overlay = null
        showSearch = false
    }

    BackHandler(enabled = overlay != null || showSearch) {
        closeOverlay()
    }

    Surface(Modifier.fillMaxSize(),color=FqBg) {
        when {
            showSearch -> SearchScreen(
                repository=repository,
                onMedia={ overlay=OverlayRoute.Detail(it); showSearch=false }
            )
            overlay != null -> when(val route=overlay!!) {
                is OverlayRoute.Detail -> DetailScreen(
                    media=route.media,
                    repository=repository,
                    store=store,
                    onBack=closeOverlay,
                    onMedia={ overlay=OverlayRoute.Detail(it) },
                    onChat={ overlay=OverlayRoute.Chat("روم رسمی " + it.title,it) },
                    onWatchParty={ overlay=OverlayRoute.WatchParty(it) }
                )
                is OverlayRoute.Story -> StoryViewer(
                    media=route.media,
                    repository=repository,
                    onClose=closeOverlay,
                    onMedia={ overlay=OverlayRoute.Detail(it) }
                )
                is OverlayRoute.Chat -> ChatRoomScreen(
                    title=route.title,
                    media=route.media,
                    repository=repository,
                    onBack=closeOverlay,
                    onMedia={ overlay=OverlayRoute.Detail(it) }
                )
                is OverlayRoute.CreatorPage -> CreatorProfileScreen(
                    creator=route.creator,
                    repository=repository,
                    store=store,
                    onBack=closeOverlay,
                    onMedia={ overlay=OverlayRoute.Detail(it) }
                )
                is OverlayRoute.WatchParty -> WatchPartyScreen(
                    media=route.media,
                    repository=repository,
                    onBack=closeOverlay
                )
                OverlayRoute.Create -> CreateHubScreen(onBack=closeOverlay)
                OverlayRoute.Notifications -> NotificationsScreen(onBack=closeOverlay)
            }
            else -> Scaffold(
                containerColor=FqBg,
                bottomBar={
                    FilmiqooBottomBar(
                        selected=tab,
                        onSelected={ index ->
                            if(index==2) {
                                overlay=OverlayRoute.Create
                            } else {
                                tab=index
                            }
                        }
                    )
                }
            ) { padding ->
                Box(Modifier.padding(padding)) {
                    when(tab) {
                        0 -> HomeScreen(
                            repository=repository,
                            onMedia={overlay=OverlayRoute.Detail(it)},
                            onStory={m,i->overlay=OverlayRoute.Story(m,i)},
                            onSearch={showSearch=true},
                            onNotifications={overlay=OverlayRoute.Notifications},
                            onWatchParty={overlay=OverlayRoute.WatchParty(it)}
                        )
                        1 -> ExploreScreen(
                            repository=repository,
                            store=store,
                            onMedia={overlay=OverlayRoute.Detail(it)},
                            onChat={overlay=OverlayRoute.Chat("گفت‌وگو درباره " + it.title,it)},
                            onCreator={overlay=OverlayRoute.CreatorPage(it)}
                        )
                        3 -> MessagesScreen(
                            onChat={overlay=OverlayRoute.Chat(it,null)},
                            onWatchParty={overlay=OverlayRoute.WatchParty(null)}
                        )
                        else -> ProfileScreen(
                            repository=repository,
                            store=store,
                            onCreator={
                                overlay=OverlayRoute.CreatorPage(
                                    Creator("Armin Studio","@arminstudio","4.8K","نقد، معرفی و تجربه شخصی از فیلم و سریال")
                                )
                            },
                            onWatchParty={overlay=OverlayRoute.WatchParty(null)},
                            onMessages={tab=3},
                            onMedia={overlay=OverlayRoute.Detail(it)}
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilmiqooBottomBar(
    selected: Int,
    onSelected: (Int) -> Unit
) {
    val entries = listOf(
        Triple(Icons.Default.Home,"خانه",0),
        Triple(Icons.Default.Explore,"اکسپلور",1),
        Triple(Icons.Default.Add,"",2),
        Triple(Icons.Default.ChatBubbleOutline,"پیام‌ها",3),
        Triple(Icons.Default.PersonOutline,"پروفایل",4)
    )

    NavigationBar(
        containerColor=Color(0xFF0B0D11),
        tonalElevation=10.dp
    ) {
        entries.forEach { item ->
            if(item.third==2) {
                NavigationBarItem(
                    selected=false,
                    onClick={onSelected(2)},
                    icon={
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).background(FqGold),
                            contentAlignment=Alignment.Center
                        ) {
                            Icon(Icons.Default.Add,null,tint=Color.Black,modifier=Modifier.size(28.dp))
                        }
                    },
                    label=null,
                    alwaysShowLabel=false
                )
            } else {
                NavigationBarItem(
                    selected=selected==item.third,
                    onClick={onSelected(item.third)},
                    icon={Icon(item.first,null)},
                    label={Text(item.second,fontSize=9.sp)},
                    colors=NavigationBarItemDefaults.colors(
                        selectedIconColor=FqGold,
                        selectedTextColor=FqGold,
                        indicatorColor=FqSurface2,
                        unselectedIconColor=FqMuted,
                        unselectedTextColor=FqMuted
                    )
                )
            }
        }
    }
}
