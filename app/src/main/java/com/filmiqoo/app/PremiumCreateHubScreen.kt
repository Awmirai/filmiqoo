package com.filmiqoo.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class CreateKind(
    val label:String,
    val subtitle:String
) {
    STORY("Story","عکس، ویدیو یا متن ۲۴ ساعته"),
    REEL("Reel","ویدیوی کوتاه برای Explore"),
    POST("Post","پست Community"),
    REVIEW("Review","نقد فیلم یا سریال"),
    POLL("Poll","نظرسنجی واقعی"),
    CHANNEL("Channel","ساخت Community اختصاصی")
}

private data class CreateDraft(
    val kind:String="POST",
    val caption:String="",
    val spoiler:Boolean=false,
    val allowComments:Boolean=true,
    val channelName:String="",
    val channelSlug:String="",
    val channelBio:String="",
    val visibility:String="public",
    val poll1:String="",
    val poll2:String="",
    val poll3:String="",
    val mediaBackendId:String?=null,
    val mediaTitle:String?=null
)

private class CreateDraftStore(context:Context) {
    private val prefs=context.getSharedPreferences("filmiqoo_create_draft_v2",Context.MODE_PRIVATE)

    fun read()=CreateDraft(
        kind=prefs.getString("kind","POST") ?: "POST",
        caption=prefs.getString("caption","") ?: "",
        spoiler=prefs.getBoolean("spoiler",false),
        allowComments=prefs.getBoolean("allow_comments",true),
        channelName=prefs.getString("channel_name","") ?: "",
        channelSlug=prefs.getString("channel_slug","") ?: "",
        channelBio=prefs.getString("channel_bio","") ?: "",
        visibility=prefs.getString("visibility","public") ?: "public",
        poll1=prefs.getString("poll1","") ?: "",
        poll2=prefs.getString("poll2","") ?: "",
        poll3=prefs.getString("poll3","") ?: "",
        mediaBackendId=prefs.getString("media_id",null),
        mediaTitle=prefs.getString("media_title",null)
    )

    fun write(d:CreateDraft) {
        prefs.edit()
            .putString("kind",d.kind)
            .putString("caption",d.caption)
            .putBoolean("spoiler",d.spoiler)
            .putBoolean("allow_comments",d.allowComments)
            .putString("channel_name",d.channelName)
            .putString("channel_slug",d.channelSlug)
            .putString("channel_bio",d.channelBio)
            .putString("visibility",d.visibility)
            .putString("poll1",d.poll1)
            .putString("poll2",d.poll2)
            .putString("poll3",d.poll3)
            .putString("media_id",d.mediaBackendId)
            .putString("media_title",d.mediaTitle)
            .apply()
    }

    fun clear()=prefs.edit().clear().apply()
}

@Composable
fun PremiumCreateHubScreen(
    social:SocialRepository,
    backend:BackendRepository,
    repository:TmdbRepository,
    loggedIn:Boolean,
    onRequireAuth:()->Unit,
    onBack:()->Unit
) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val drafts=remember { CreateDraftStore(context.applicationContext) }
    val savedDraft=remember { drafts.read() }

    var kind by remember {
        mutableStateOf(
            runCatching { CreateKind.valueOf(savedDraft.kind) }
                .getOrDefault(CreateKind.POST)
        )
    }
    var caption by remember { mutableStateOf(savedDraft.caption) }
    var spoiler by remember { mutableStateOf(savedDraft.spoiler) }
    var allowComments by remember { mutableStateOf(savedDraft.allowComments) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    var channelName by remember { mutableStateOf(savedDraft.channelName) }
    var channelSlug by remember { mutableStateOf(savedDraft.channelSlug) }
    var channelBio by remember { mutableStateOf(savedDraft.channelBio) }
    var visibility by remember { mutableStateOf(savedDraft.visibility) }

    val pollOptions=remember {
        mutableStateListOf(savedDraft.poll1,savedDraft.poll2,savedDraft.poll3)
    }

    var taggedMedia by remember {
        mutableStateOf<MediaItem?>(
            savedDraft.mediaBackendId?.let {
                MediaItem(
                    id=0,
                    type=MediaType.MOVIE,
                    title=savedDraft.mediaTitle ?: "عنوان انتخاب‌شده",
                    backendId=it
                )
            }
        )
    }

    var showMediaPicker by remember { mutableStateOf(false) }
    var publishing by remember { mutableStateOf(false) }
    var publishStage by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }
    var draftSaved by remember { mutableStateOf(false) }

    val picker=rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if(uri!=null) selectedUri=uri }

    BackHandler(enabled=!publishing) { onBack() }

    fun currentDraft()=CreateDraft(
        kind=kind.name,
        caption=caption,
        spoiler=spoiler,
        allowComments=allowComments,
        channelName=channelName,
        channelSlug=channelSlug,
        channelBio=channelBio,
        visibility=visibility,
        poll1=pollOptions.getOrElse(0){""},
        poll2=pollOptions.getOrElse(1){""},
        poll3=pollOptions.getOrElse(2){""},
        mediaBackendId=taggedMedia?.backendId,
        mediaTitle=taggedMedia?.title
    )

    val canPublish=when(kind) {
        CreateKind.REEL -> selectedUri!=null
        CreateKind.STORY -> selectedUri!=null || caption.isNotBlank()
        CreateKind.POST,CreateKind.REVIEW -> caption.trim().isNotBlank()
        CreateKind.POLL ->
            caption.trim().isNotBlank() &&
                pollOptions.map(String::trim).filter(String::isNotBlank).distinct().size>=2
        CreateKind.CHANNEL ->
            channelName.trim().length>=2 &&
                channelSlug.trim().length>=3
    }

    LazyColumn(
        Modifier.fillMaxSize().background(FqBg),
        contentPadding=PaddingValues(bottom=30.dp)
    ) {
        item {
            Box(
                Modifier.fillMaxWidth().background(
                    Brush.verticalGradient(listOf(Color(0xFF171F31),FqBg))
                )
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    IconButton(onClick=onBack,enabled=!publishing) {
                        Icon(Icons.Default.Close,null)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Filmiqoo Studio",fontSize=23.sp,fontWeight=FontWeight.Black)
                        Text("ساخت و انتشار محتوا",color=FqMuted,fontSize=8.sp)
                    }
                    TextButton(
                        onClick={
                            drafts.write(currentDraft())
                            draftSaved=true
                        }
                    ) {
                        Icon(Icons.Default.Drafts,null,modifier=Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Draft",fontSize=8.sp)
                    }
                }
            }
        }

        item {
            Text(
                "نوع محتوا",
                fontSize=12.sp,
                fontWeight=FontWeight.Bold,
                modifier=Modifier.padding(start=16.dp,end=16.dp,top=12.dp,bottom=7.dp)
            )
        }

        item {
            LazyRow(
                contentPadding=PaddingValues(horizontal=14.dp),
                horizontalArrangement=Arrangement.spacedBy(8.dp)
            ) {
                items(CreateKind.entries) { item ->
                    FilterChip(
                        selected=kind==item,
                        onClick={
                            kind=item
                            error=null
                            if(item==CreateKind.REEL || item==CreateKind.STORY) {
                                picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                    )
                                )
                            }
                        },
                        label={Text(item.label,fontSize=8.sp)},
                        leadingIcon={
                            Icon(
                                when(item) {
                                    CreateKind.STORY -> Icons.Default.AutoStories
                                    CreateKind.REEL -> Icons.Default.VideoLibrary
                                    CreateKind.POST -> Icons.Default.PostAdd
                                    CreateKind.REVIEW -> Icons.Default.RateReview
                                    CreateKind.POLL -> Icons.Default.Poll
                                    CreateKind.CHANNEL -> Icons.Default.Campaign
                                },
                                null,
                                modifier=Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
        }

        item {
            Surface(
                color=FqSurface,
                shape=RoundedCornerShape(22.dp),
                modifier=Modifier.fillMaxWidth().padding(14.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(
                            when(kind) {
                                CreateKind.STORY -> Icons.Default.AutoStories
                                CreateKind.REEL -> Icons.Default.VideoLibrary
                                CreateKind.POST -> Icons.Default.PostAdd
                                CreateKind.REVIEW -> Icons.Default.RateReview
                                CreateKind.POLL -> Icons.Default.Poll
                                CreateKind.CHANNEL -> Icons.Default.Campaign
                            },
                            null,
                            tint=FqGold
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(kind.label,fontSize=15.sp,fontWeight=FontWeight.Bold)
                            Text(kind.subtitle,color=FqMuted,fontSize=8.sp)
                        }
                    }

                    if(kind==CreateKind.CHANNEL) {
                        ChannelComposer(
                            name=channelName,
                            slug=channelSlug,
                            bio=channelBio,
                            visibility=visibility,
                            onName={channelName=it.take(80)},
                            onSlug={
                                channelSlug=it.lowercase()
                                    .filter { c->c.isLetterOrDigit() || c=='_' || c=='.' }
                                    .take(32)
                            },
                            onBio={channelBio=it.take(500)},
                            onVisibility={visibility=it}
                        )
                    } else {
                        selectedUri?.let { uri ->
                            Box(
                                Modifier.fillMaxWidth().height(260.dp)
                                    .padding(top=12.dp)
                                    .clip(RoundedCornerShape(18.dp))
                            ) {
                                AsyncImage(
                                    model=uri,
                                    contentDescription=null,
                                    contentScale=ContentScale.Crop,
                                    modifier=Modifier.fillMaxSize()
                                )
                                FilledTonalIconButton(
                                    onClick={selectedUri=null},
                                    modifier=Modifier.align(Alignment.TopEnd).padding(7.dp)
                                ) {
                                    Icon(Icons.Default.Close,null)
                                }
                            }
                        }

                        if((kind==CreateKind.STORY || kind==CreateKind.REEL) && selectedUri==null) {
                            OutlinedButton(
                                onClick={
                                    picker.launch(
                                        PickVisualMediaRequest(
                                            ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                        )
                                    )
                                },
                                modifier=Modifier.fillMaxWidth().padding(top=12.dp)
                            ) {
                                Icon(Icons.Default.AddPhotoAlternate,null)
                                Spacer(Modifier.width(5.dp))
                                Text("انتخاب عکس یا ویدیو")
                            }
                        }

                        OutlinedTextField(
                            value=caption,
                            onValueChange={caption=it.take(5000)},
                            label={
                                Text(
                                    when(kind) {
                                        CreateKind.REVIEW -> "متن Review"
                                        CreateKind.POLL -> "سؤال Poll"
                                        CreateKind.STORY -> "متن / Caption"
                                        else -> "Caption"
                                    }
                                )
                            },
                            minLines=3,
                            maxLines=8,
                            shape=RoundedCornerShape(15.dp),
                            modifier=Modifier.fillMaxWidth().padding(top=12.dp)
                        )

                        if(kind==CreateKind.POLL) {
                            Text(
                                "گزینه‌ها",
                                fontSize=10.sp,
                                fontWeight=FontWeight.Bold,
                                modifier=Modifier.padding(top=12.dp,bottom=5.dp)
                            )
                            pollOptions.forEachIndexed { index,value ->
                                OutlinedTextField(
                                    value=value,
                                    onValueChange={pollOptions[index]=it.take(120)},
                                    label={Text("گزینه "+(index+1))},
                                    singleLine=true,
                                    trailingIcon={
                                        if(index>=2 && value.isNotBlank()) {
                                            IconButton(onClick={pollOptions[index]=""}) {
                                                Icon(Icons.Default.Close,null)
                                            }
                                        }
                                    },
                                    modifier=Modifier.fillMaxWidth().padding(vertical=3.dp)
                                )
                            }
                            if(pollOptions.size<6) {
                                TextButton(onClick={pollOptions.add("")}) {
                                    Icon(Icons.Default.Add,null)
                                    Text("گزینه بیشتر",fontSize=8.sp)
                                }
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth().padding(top=10.dp),
                            horizontalArrangement=Arrangement.spacedBy(7.dp)
                        ) {
                            FilterChip(
                                selected=spoiler,
                                onClick={spoiler=!spoiler},
                                label={Text("Spoiler",fontSize=8.sp)},
                                leadingIcon={Icon(Icons.Default.VisibilityOff,null,modifier=Modifier.size(15.dp))}
                            )
                            if(kind==CreateKind.REEL) {
                                FilterChip(
                                    selected=allowComments,
                                    onClick={allowComments=!allowComments},
                                    label={Text(if(allowComments)"Comment روشن" else "Comment خاموش",fontSize=8.sp)},
                                    leadingIcon={Icon(Icons.Default.ChatBubbleOutline,null,modifier=Modifier.size(15.dp))}
                                )
                            }
                        }

                        Surface(
                            color=FqSurface2,
                            shape=RoundedCornerShape(15.dp),
                            modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                                .clickable { showMediaPicker=true }
                        ) {
                            Row(
                                Modifier.padding(11.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Movie,null,tint=FqGold)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        taggedMedia?.title ?: "تگ فیلم یا سریال",
                                        fontSize=9.sp,
                                        fontWeight=FontWeight.Bold
                                    )
                                    Text(
                                        if(taggedMedia==null)
                                            "اختیاری • محتوا رو به Catalog وصل کن"
                                        else
                                            "متصل به Catalog Filmiqoo",
                                        color=FqMuted,
                                        fontSize=7.sp
                                    )
                                }
                                if(taggedMedia!=null) {
                                    IconButton(onClick={taggedMedia=null}) {
                                        Icon(Icons.Default.Close,null)
                                    }
                                } else {
                                    Icon(Icons.Default.ChevronLeft,null,tint=FqMuted)
                                }
                            }
                        }
                    }
                }
            }
        }

        error?.let {
            item {
                Text(
                    it,
                    color=FqDanger,
                    fontSize=9.sp,
                    modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=5.dp)
                )
            }
        }

        if(publishing) {
            item {
                Surface(
                    color=FqGold.copy(alpha=.09f),
                    shape=RoundedCornerShape(18.dp),
                    modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=6.dp)
                ) {
                    Row(Modifier.padding(13.dp),verticalAlignment=Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            color=FqGold,
                            strokeWidth=2.dp,
                            modifier=Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("در حال انتشار",fontSize=10.sp,fontWeight=FontWeight.Bold)
                            Text(publishStage,color=FqMuted,fontSize=8.sp)
                        }
                    }
                }
            }
        }

        item {
            Button(
                enabled=canPublish && !publishing,
                onClick={
                    if(!loggedIn) {
                        onRequireAuth()
                        return@Button
                    }
                    publishing=true
                    error=null
                    scope.launch {
                        runCatching {
                            val mediaId=taggedMedia?.backendId
                            when(kind) {
                                CreateKind.POST -> {
                                    publishStage="در حال ثبت Post..."
                                    social.createPost(
                                        body=caption.trim(),
                                        type="post",
                                        spoiler=spoiler,
                                        mediaTitleId=mediaId
                                    )
                                    "Post منتشر شد."
                                }
                                CreateKind.REVIEW -> {
                                    publishStage="در حال انتشار Review..."
                                    social.createPost(
                                        body=caption.trim(),
                                        type="review",
                                        spoiler=spoiler,
                                        mediaTitleId=mediaId
                                    )
                                    "Review منتشر شد."
                                }
                                CreateKind.POLL -> {
                                    publishStage="در حال ساخت Poll..."
                                    social.createPost(
                                        body=caption.trim(),
                                        type="poll",
                                        spoiler=spoiler,
                                        mediaTitleId=mediaId,
                                        pollOptions=pollOptions.map(String::trim)
                                            .filter(String::isNotBlank)
                                            .distinct()
                                    )
                                    "Poll منتشر شد."
                                }
                                CreateKind.STORY -> {
                                    if(selectedUri!=null) {
                                        publishStage="در حال آپلود Story..."
                                        val ticket=social.uploadMedia(context,selectedUri!!,"story")
                                        publishStage="در حال انتشار Story..."
                                        social.createMediaStory(
                                            ticket=ticket,
                                            caption=caption.trim(),
                                            spoiler=spoiler,
                                            mediaTitleId=mediaId
                                        )
                                    } else {
                                        publishStage="در حال انتشار Story متنی..."
                                        social.createTextStory(
                                            caption=caption.trim(),
                                            spoiler=spoiler,
                                            mediaTitleId=mediaId
                                        )
                                    }
                                    "Story برای ۲۴ ساعت منتشر شد."
                                }
                                CreateKind.REEL -> {
                                    publishStage="در حال آپلود Reel..."
                                    val ticket=social.uploadMedia(context,selectedUri!!,"reel")
                                    publishStage="در حال انتشار Reel..."
                                    social.createReel(
                                        uploadId=ticket.uploadId,
                                        caption=caption.trim(),
                                        spoiler=spoiler,
                                        allowComments=allowComments,
                                        mediaTitleId=mediaId
                                    )
                                    "Reel روی Explore منتشر شد."
                                }
                                CreateKind.CHANNEL -> {
                                    publishStage="در حال ساخت Channel..."
                                    social.createChannel(
                                        name=channelName.trim(),
                                        slug=channelSlug.trim(),
                                        bio=channelBio.trim(),
                                        visibility=visibility
                                    )
                                    "Channel @"+channelSlug.trim()+" ساخته شد."
                                }
                            }
                        }.onSuccess {
                            success=it
                            drafts.clear()
                            caption=""
                            selectedUri=null
                            spoiler=false
                            taggedMedia=null
                            pollOptions.clear()
                            pollOptions.add("")
                            pollOptions.add("")
                            pollOptions.add("")
                        }.onFailure {
                            error=it.message ?: "انتشار ناموفق بود"
                        }
                        publishStage=""
                        publishing=false
                    }
                },
                colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                shape=RoundedCornerShape(15.dp),
                modifier=Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=8.dp)
            ) {
                Icon(Icons.Default.Publish,null,tint=Color.Black)
                Spacer(Modifier.width(6.dp))
                Text(
                    when(kind) {
                        CreateKind.REEL -> "آپلود و انتشار Reel"
                        CreateKind.STORY -> "انتشار Story"
                        CreateKind.POLL -> "انتشار Poll"
                        CreateKind.CHANNEL -> "ساخت Channel"
                        else -> "انتشار"
                    },
                    color=Color.Black,
                    fontWeight=FontWeight.Bold
                )
            }
        }

        item {
            Text(
                "UGC روی Object Storage/CDN Filmiqoo ذخیره می‌شود؛ Telegram فقط برای فایل اصلی فیلم و سریال باقی می‌ماند.",
                color=FqMuted,
                fontSize=7.sp,
                lineHeight=13.sp,
                modifier=Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=5.dp)
            )
        }
    }

    if(showMediaPicker) {
        MediaTagPickerDialog(
            backend=backend,
            repository=repository,
            onDismiss={showMediaPicker=false},
            onSelected={
                taggedMedia=it
                showMediaPicker=false
            }
        )
    }

    if(draftSaved) {
        LaunchedEffect(Unit) {
            delay(1600)
            draftSaved=false
        }
        Snackbar(
            modifier=Modifier.padding(16.dp),
            action={TextButton(onClick={draftSaved=false}){Text("باشه")}}
        ) { Text("Draft ذخیره شد.") }
    }

    success?.let { message ->
        AlertDialog(
            onDismissRequest={success=null},
            icon={Icon(Icons.Default.CheckCircle,null,tint=FqGreen)},
            title={Text("منتشر شد")},
            text={Text(message)},
            confirmButton={TextButton(onClick={success=null}){Text("باشه")}}
        )
    }
}

@Composable
private fun ChannelComposer(
    name:String,
    slug:String,
    bio:String,
    visibility:String,
    onName:(String)->Unit,
    onSlug:(String)->Unit,
    onBio:(String)->Unit,
    onVisibility:(String)->Unit
) {
    OutlinedTextField(
        value=name,
        onValueChange=onName,
        label={Text("نام Channel")},
        singleLine=true,
        shape=RoundedCornerShape(15.dp),
        modifier=Modifier.fillMaxWidth().padding(top=12.dp)
    )
    OutlinedTextField(
        value=slug,
        onValueChange=onSlug,
        label={Text("Channel ID")},
        prefix={Text("@")},
        singleLine=true,
        shape=RoundedCornerShape(15.dp),
        modifier=Modifier.fillMaxWidth().padding(top=8.dp)
    )
    OutlinedTextField(
        value=bio,
        onValueChange=onBio,
        label={Text("معرفی Channel")},
        minLines=3,
        maxLines=5,
        shape=RoundedCornerShape(15.dp),
        modifier=Modifier.fillMaxWidth().padding(top=8.dp)
    )
    Row(
        Modifier.fillMaxWidth().padding(top=10.dp),
        horizontalArrangement=Arrangement.spacedBy(7.dp)
    ) {
        listOf(
            "public" to "عمومی",
            "private" to "خصوصی",
            "invite" to "دعوتی"
        ).forEach { item ->
            FilterChip(
                selected=visibility==item.first,
                onClick={onVisibility(item.first)},
                label={Text(item.second,fontSize=8.sp)}
            )
        }
    }
}

@Composable
fun MediaTagPickerDialog(
    backend:BackendRepository,
    repository:TmdbRepository,
    onDismiss:()->Unit,
    onSelected:(MediaItem)->Unit
) {
    val context=LocalContext.current
    val search=remember { UniversalSearchRepository(context.applicationContext,backend) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        if(query.trim().length<2) {
            results=emptyList()
            return@LaunchedEffect
        }
        delay(300)
        loading=true
        error=null
        runCatching { search.search(query.trim()).media }
            .onSuccess { results=it }
            .onFailure { error=it.message }
        loading=false
    }

    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text("تگ فیلم یا سریال")},
        text={
            Column(Modifier.heightIn(max=520.dp)) {
                OutlinedTextField(
                    value=query,
                    onValueChange={query=it},
                    placeholder={Text("اسم فیلم یا سریال...")},
                    leadingIcon={Icon(Icons.Default.Search,null)},
                    singleLine=true,
                    modifier=Modifier.fillMaxWidth()
                )
                if(loading) {
                    LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth().padding(top=6.dp))
                }
                error?.let {
                    Text(it,color=FqDanger,fontSize=8.sp,modifier=Modifier.padding(top=6.dp))
                }
                LazyColumn(
                    verticalArrangement=Arrangement.spacedBy(6.dp),
                    modifier=Modifier.padding(top=8.dp)
                ) {
                    items(results.take(15),key={it.key}) { media ->
                        Surface(
                            color=FqSurface2,
                            shape=RoundedCornerShape(14.dp),
                            modifier=Modifier.fillMaxWidth().clickable { onSelected(media) }
                        ) {
                            Row(
                                Modifier.padding(8.dp),
                                verticalAlignment=Alignment.CenterVertically
                            ) {
                                RemoteImage(
                                    repository.poster(media.posterPath),
                                    Modifier.width(42.dp).height(60.dp).clip(RoundedCornerShape(8.dp)),
                                    ContentScale.Crop
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(media.title,fontSize=9.sp,fontWeight=FontWeight.Bold)
                                    Text(
                                        listOf(media.year,if(media.type==MediaType.MOVIE)"فیلم" else "سریال")
                                            .filter(String::isNotBlank).joinToString(" • "),
                                        color=FqMuted,fontSize=7.sp
                                    )
                                }
                                Icon(Icons.Default.AddCircle,null,tint=FqGold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton={TextButton(onClick=onDismiss){Text("بستن")}}
    )
}
