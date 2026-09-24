package com.filmiqoo.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneBookmarksSheet(
    backend:BackendRepository,
    mediaVersionId:String,
    currentPositionMs:Long,
    onSeekTo:(Long)->Unit,
    onDismiss:()->Unit
) {
    val repo=remember { SceneBookmarksRepository(backend) }
    val scope=rememberCoroutineScope()

    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var items by remember { mutableStateOf<List<SceneBookmark>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<SceneBookmark?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mediaVersionId,refresh) {
        loading=true
        error=null
        runCatching { repo.forVersion(mediaVersionId) }
            .onSuccess { items=it }
            .onFailure { error=it.message ?: "دریافت Bookmarkها ناموفق بود" }
        loading=false
    }

    ModalBottomSheet(
        onDismissRequest=onDismiss,
        containerColor=FqSurface
    ) {
        Column(
            Modifier.fillMaxWidth()
                .fillMaxHeight(.82f)
                .padding(bottom=20.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal=16.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(44.dp)
                        .background(FqGold.copy(alpha=.13f),RoundedCornerShape(13.dp)),
                    contentAlignment=Alignment.Center
                ) {
                    Icon(Icons.Default.Bookmarks,null,tint=FqGold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Scene Bookmarks",fontSize=20.sp,fontWeight=FontWeight.Black)
                    Text(
                        "لحظه‌های خصوصی خودت • فقط برای حساب تو",
                        color=FqMuted,
                        fontSize=8.sp
                    )
                }
                Surface(
                    color=FqSurface2,
                    shape=RoundedCornerShape(9.dp)
                ) {
                    Text(
                        formatSceneTime(currentPositionMs),
                        color=FqGold,
                        fontSize=8.sp,
                        fontWeight=FontWeight.Bold,
                        modifier=Modifier.padding(horizontal=8.dp,vertical=5.dp)
                    )
                }
            }

            Surface(
                color=FqSurface2,
                shape=RoundedCornerShape(18.dp),
                modifier=Modifier.fillMaxWidth()
                    .padding(horizontal=14.dp,vertical=10.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    OutlinedTextField(
                        value=note,
                        onValueChange={note=it.take(1200)},
                        label={Text("یادداشت این صحنه")},
                        placeholder={Text("مثلاً: دیالوگ عالی / صحنه موردعلاقه...")},
                        minLines=2,
                        maxLines=4,
                        modifier=Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value=tag,
                        onValueChange={tag=it.take(48)},
                        label={Text("Tag اختیاری")},
                        placeholder={Text("مثلاً Plot Twist")},
                        singleLine=true,
                        modifier=Modifier.fillMaxWidth().padding(top=7.dp)
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top=8.dp),
                        verticalAlignment=Alignment.CenterVertically
                    ) {
                        Text(
                            "در "+formatSceneTime(currentPositionMs)+" ذخیره می‌شود",
                            color=FqMuted,
                            fontSize=7.sp,
                            modifier=Modifier.weight(1f)
                        )
                        Button(
                            enabled=!saving,
                            onClick={
                                saving=true
                                scope.launch {
                                    runCatching {
                                        repo.create(
                                            mediaVersionId=mediaVersionId,
                                            positionMs=currentPositionMs,
                                            note=note,
                                            tag=tag
                                        )
                                    }.onSuccess {
                                        note=""
                                        tag=""
                                        refresh++
                                    }.onFailure {
                                        error=it.message ?: "ذخیره Bookmark ناموفق بود"
                                    }
                                    saving=false
                                }
                            },
                            colors=ButtonDefaults.buttonColors(containerColor=FqGold)
                        ) {
                            if(saving) {
                                CircularProgressIndicator(
                                    color=Color.Black,
                                    strokeWidth=2.dp,
                                    modifier=Modifier.size(16.dp)
                                )
                            } else {
                                Icon(Icons.Default.BookmarkAdd,null,tint=Color.Black)
                            }
                            Spacer(Modifier.width(4.dp))
                            Text("ذخیره",color=Color.Black,fontSize=8.sp)
                        }
                    }
                }
            }

            if(loading) {
                LinearProgressIndicator(
                    color=FqGold,
                    modifier=Modifier.fillMaxWidth()
                )
            }

            error?.let {
                Text(
                    it,
                    color=FqDanger,
                    fontSize=8.sp,
                    modifier=Modifier.padding(horizontal=16.dp,vertical=6.dp)
                )
            }

            if(!loading && items.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment=Alignment.Center
                ) {
                    Column(horizontalAlignment=Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.BookmarkBorder,
                            null,
                            tint=FqMuted,
                            modifier=Modifier.size(42.dp)
                        )
                        Text(
                            "هنوز Scene Bookmark نداری",
                            fontSize=11.sp,
                            fontWeight=FontWeight.Bold,
                            modifier=Modifier.padding(top=8.dp)
                        )
                        Text(
                            "هر لحظه‌ای که می‌خوای بعداً دقیقاً بهش برگردی ذخیره کن.",
                            color=FqMuted,
                            fontSize=8.sp,
                            modifier=Modifier.padding(top=4.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding=PaddingValues(horizontal=14.dp,vertical=7.dp),
                    verticalArrangement=Arrangement.spacedBy(7.dp),
                    modifier=Modifier.weight(1f)
                ) {
                    items(items,key={it.id}) { item ->
                        SceneBookmarkRow(
                            item=item,
                            onSeek={
                                onSeekTo(item.positionMs)
                                onDismiss()
                            },
                            onEdit={editTarget=item},
                            onDelete={
                                scope.launch {
                                    runCatching { repo.delete(item.id) }
                                        .onSuccess { refresh++ }
                                        .onFailure { error=it.message }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    editTarget?.let { item ->
        SceneBookmarkEditDialog(
            item=item,
            onDismiss={editTarget=null},
            onSave={newNote,newTag->
                scope.launch {
                    runCatching { repo.update(item.id,newNote,newTag) }
                        .onSuccess {
                            editTarget=null
                            refresh++
                        }
                        .onFailure { error=it.message }
                }
            }
        )
    }
}

@Composable
private fun SceneBookmarkRow(
    item:SceneBookmark,
    onSeek:()->Unit,
    onEdit:()->Unit,
    onDelete:()->Unit
) {
    Surface(
        color=FqSurface2,
        shape=RoundedCornerShape(17.dp),
        modifier=Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(11.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            Surface(
                color=FqGold.copy(alpha=.12f),
                shape=RoundedCornerShape(10.dp),
                modifier=Modifier.clickable { onSeek() }
            ) {
                Row(
                    Modifier.padding(horizontal=9.dp,vertical=7.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        null,
                        tint=FqGold,
                        modifier=Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        formatSceneTime(item.positionMs),
                        color=FqGold,
                        fontSize=8.sp,
                        fontWeight=FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.width(9.dp))

            Column(
                Modifier.weight(1f).clickable { onSeek() }
            ) {
                Text(
                    item.note.ifBlank { "Scene Bookmark" },
                    fontSize=9.sp,
                    fontWeight=FontWeight.Bold,
                    maxLines=2,
                    overflow=TextOverflow.Ellipsis
                )
                if(item.tag.isNotBlank()) {
                    Text(
                        "#"+item.tag,
                        color=FqGold,
                        fontSize=7.sp,
                        modifier=Modifier.padding(top=3.dp)
                    )
                }
            }

            IconButton(onClick=onEdit) {
                Icon(Icons.Default.Edit,null,tint=FqMuted)
            }
            IconButton(onClick=onDelete) {
                Icon(Icons.Default.DeleteOutline,null,tint=FqDanger)
            }
        }
    }
}

@Composable
private fun SceneBookmarkEditDialog(
    item:SceneBookmark,
    onDismiss:()->Unit,
    onSave:(String,String)->Unit
) {
    var note by remember(item.id) { mutableStateOf(item.note) }
    var tag by remember(item.id) { mutableStateOf(item.tag) }

    AlertDialog(
        onDismissRequest=onDismiss,
        icon={Icon(Icons.Default.Bookmark,null,tint=FqGold)},
        title={Text("ویرایش Scene Bookmark")},
        text={
            Column {
                Text(
                    formatSceneTime(item.positionMs),
                    color=FqGold,
                    fontSize=9.sp,
                    fontWeight=FontWeight.Bold
                )
                OutlinedTextField(
                    value=note,
                    onValueChange={note=it.take(1200)},
                    label={Text("یادداشت")},
                    minLines=3,
                    maxLines=6,
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                )
                OutlinedTextField(
                    value=tag,
                    onValueChange={tag=it.take(48)},
                    label={Text("Tag")},
                    singleLine=true,
                    modifier=Modifier.fillMaxWidth().padding(top=7.dp)
                )
            }
        },
        confirmButton={
            Button(
                onClick={onSave(note.trim(),tag.trim())},
                colors=ButtonDefaults.buttonColors(containerColor=FqGold)
            ) { Text("ذخیره",color=Color.Black) }
        },
        dismissButton={
            TextButton(onClick=onDismiss){Text("لغو")}
        }
    )
}

fun formatSceneTime(ms:Long):String {
    val total=(ms.coerceAtLeast(0L)/1000L)
    val h=total/3600L
    val m=(total%3600L)/60L
    val s=total%60L
    return if(h>0L) {
        String.format(Locale.US,"%02d:%02d:%02d",h,m,s)
    } else {
        String.format(Locale.US,"%02d:%02d",m,s)
    }
}
