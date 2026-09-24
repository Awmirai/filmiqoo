package com.filmiqoo.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ViewerProfile(
    val id:String,
    val name:String,
    val avatarUrl:String,
    val kidsMode:Boolean,
    val maturityLevel:String,
    val preferredAudioLanguage:String,
    val preferredSubtitleLanguage:String,
    val autoplayNext:Boolean,
    val pinProtected:Boolean
)

class ViewerProfileStore(context:Context) {
    private val prefs=context.applicationContext
        .getSharedPreferences("filmiqoo_active_viewer_profile_v1",Context.MODE_PRIVATE)

    fun activeId():String?=prefs.getString("id",null)?.takeIf(String::isNotBlank)

    fun active():ViewerProfile? {
        val id=activeId() ?: return null
        return ViewerProfile(
            id=id,
            name=prefs.getString("name","Profile").orEmpty(),
            avatarUrl=prefs.getString("avatar_url","").orEmpty(),
            kidsMode=prefs.getBoolean("kids_mode",false),
            maturityLevel=prefs.getString("maturity","all").orEmpty().ifBlank{"all"},
            preferredAudioLanguage=prefs.getString("audio","fa").orEmpty().ifBlank{"fa"},
            preferredSubtitleLanguage=prefs.getString("subtitle","fa").orEmpty().ifBlank{"fa"},
            autoplayNext=prefs.getBoolean("autoplay_next",true),
            pinProtected=prefs.getBoolean("pin_protected",false)
        )
    }

    fun activate(profile:ViewerProfile) {
        prefs.edit()
            .putString("id",profile.id)
            .putString("name",profile.name)
            .putString("avatar_url",profile.avatarUrl)
            .putBoolean("kids_mode",profile.kidsMode)
            .putString("maturity",profile.maturityLevel)
            .putString("audio",profile.preferredAudioLanguage)
            .putString("subtitle",profile.preferredSubtitleLanguage)
            .putBoolean("autoplay_next",profile.autoplayNext)
            .putBoolean("pin_protected",profile.pinProtected)
            .apply()
    }

    fun clear()=prefs.edit().clear().apply()
}

class ViewerProfilesRepository(
    private val backend:BackendRepository
) {
    suspend fun list():List<ViewerProfile> {
        val root=backend.getJson("/v1/viewer-profiles",authorized=true)
        val arr=root.optJSONArray("items") ?: return emptyList()
        return buildList {
            for(i in 0 until arr.length()) {
                val x=arr.optJSONObject(i) ?: continue
                add(parse(x))
            }
        }
    }

    suspend fun create(
        name:String,
        kidsMode:Boolean,
        maturityLevel:String,
        audioLanguage:String,
        subtitleLanguage:String,
        autoplayNext:Boolean
    ):String =
        backend.postJson(
            "/v1/viewer-profiles",
            JSONObject()
                .put("name",name.trim())
                .put("kidsMode",kidsMode)
                .put("maturityLevel",maturityLevel)
                .put("preferredAudioLanguage",audioLanguage)
                .put("preferredSubtitleLanguage",subtitleLanguage)
                .put("autoplayNext",autoplayNext),
            authorized=true
        ).optString("id")

    suspend fun update(profile:ViewerProfile) {
        backend.postJson(
            "/v1/viewer-profiles/"+profile.id,
            JSONObject()
                .put("name",profile.name.trim())
                .put("avatarUrl",profile.avatarUrl)
                .put("kidsMode",profile.kidsMode)
                .put("maturityLevel",profile.maturityLevel)
                .put("preferredAudioLanguage",profile.preferredAudioLanguage)
                .put("preferredSubtitleLanguage",profile.preferredSubtitleLanguage)
                .put("autoplayNext",profile.autoplayNext),
            authorized=true
        )
    }

    suspend fun unlock(id:String,pin:String):Boolean =
        backend.postJson(
            "/v1/viewer-profiles/"+id+"/unlock",
            JSONObject().put("pin",pin),
            authorized=true
        ).optBoolean("unlocked")

    suspend fun setPin(id:String,currentPin:String,newPin:String):Boolean =
        backend.postJson(
            "/v1/viewer-profiles/"+id+"/pin",
            JSONObject()
                .put("currentPin",currentPin)
                .put("newPin",newPin),
            authorized=true
        ).optBoolean("pinProtected")

    suspend fun delete(id:String):Boolean =
        backend.postJson(
            "/v1/viewer-profiles/"+id+"/delete",
            JSONObject(),
            authorized=true
        ).optBoolean("deleted")

    private fun parse(x:JSONObject)=ViewerProfile(
        id=x.optString("id"),
        name=x.optString("name"),
        avatarUrl=x.optString("avatarUrl"),
        kidsMode=x.optBoolean("kidsMode"),
        maturityLevel=x.optString("maturityLevel","all"),
        preferredAudioLanguage=x.optString("preferredAudioLanguage","fa"),
        preferredSubtitleLanguage=x.optString("preferredSubtitleLanguage","fa"),
        autoplayNext=x.optBoolean("autoplayNext",true),
        pinProtected=x.optBoolean("pinProtected")
    )
}

@Composable
fun ViewerProfilesScreen(
    backend:BackendRepository,
    onBack:()->Unit,
    onActivated:(ViewerProfile)->Unit
) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val store=remember { ViewerProfileStore(context.applicationContext) }
    val repo=remember { ViewerProfilesRepository(backend) }
    val scope=rememberCoroutineScope()

    var profiles by remember { mutableStateOf<List<ViewerProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var editTarget by remember { mutableStateOf<ViewerProfile?>(null) }
    var pinSettingsTarget by remember { mutableStateOf<ViewerProfile?>(null) }
    var unlockTarget by remember { mutableStateOf<ViewerProfile?>(null) }
    var unlockForEdit by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    LaunchedEffect(refresh) {
        loading=true
        error=null
        runCatching { repo.list() }
            .onSuccess { list->
                profiles=list
                val active=store.activeId()
                if(list.isNotEmpty() && list.none { it.id==active }) {
                    store.activate(list.first())
                }
            }
            .onFailure { error=it.message }
        loading=false
    }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Box(
            Modifier.fillMaxWidth().background(
                Brush.verticalGradient(
                    listOf(Color(0xFF161E31),Color(0xFF221A08),FqBg)
                )
            )
        ) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
                Column(Modifier.weight(1f)) {
                    Text("چه کسی تماشا می‌کند؟",fontSize=22.sp,fontWeight=FontWeight.Black)
                    Text("History، Continue و Library برای هر پروفایل جداست.",color=FqMuted,fontSize=8.sp)
                }
                if(profiles.size<5) {
                    IconButton(onClick={creating=true}) {
                        Icon(Icons.Default.PersonAdd,null,tint=FqGold)
                    }
                }
            }
        }

        if(loading) {
            LinearProgressIndicator(color=FqGold,modifier=Modifier.fillMaxWidth())
        }

        error?.let {
            Text(
                it,
                color=FqDanger,
                fontSize=8.sp,
                modifier=Modifier.fillMaxWidth().padding(12.dp)
            )
        }

        if(!loading) {
            LazyVerticalGrid(
                columns=GridCells.Fixed(2),
                contentPadding=PaddingValues(16.dp),
                horizontalArrangement=Arrangement.spacedBy(14.dp),
                verticalArrangement=Arrangement.spacedBy(16.dp),
                modifier=Modifier.fillMaxSize()
            ) {
                items(profiles,key={it.id}) { profile ->
                    ViewerProfileCard(
                        profile=profile,
                        active=store.activeId()==profile.id,
                        onClick={
                            if(profile.pinProtected && store.activeId()!=profile.id) {
                                unlockForEdit=false
                                unlockTarget=profile
                            } else {
                                store.activate(profile)
                                onActivated(profile)
                            }
                        },
                        onEdit={
                            if(profile.pinProtected) {
                                unlockForEdit=true
                                unlockTarget=profile
                            } else {
                                editTarget=profile
                            }
                        }
                    )
                }

                if(profiles.size<5) {
                    item {
                        AddViewerProfileCard(onClick={creating=true})
                    }
                }
            }
        }
    }

    if(creating) {
        ViewerProfileEditorDialog(
            existing=null,
            canDelete=false,
            onDismiss={creating=false},
            onSave={draft->
                scope.launch {
                    runCatching {
                        repo.create(
                            name=draft.name,
                            kidsMode=draft.kidsMode,
                            maturityLevel=draft.maturityLevel,
                            audioLanguage=draft.preferredAudioLanguage,
                            subtitleLanguage=draft.preferredSubtitleLanguage,
                            autoplayNext=draft.autoplayNext
                        )
                    }.onSuccess {
                        creating=false
                        refresh++
                    }.onFailure { error=it.message }
                }
            },
            onDelete={},
            onPinSettings={}
        )
    }

    editTarget?.let { profile ->
        ViewerProfileEditorDialog(
            existing=profile,
            canDelete=profiles.size>1,
            onDismiss={editTarget=null},
            onSave={draft->
                scope.launch {
                    runCatching { repo.update(draft) }
                        .onSuccess {
                            if(store.activeId()==draft.id) store.activate(draft)
                            editTarget=null
                            refresh++
                        }
                        .onFailure { error=it.message }
                }
            },
            onDelete={
                scope.launch {
                    runCatching { repo.delete(profile.id) }
                        .onSuccess {
                            if(store.activeId()==profile.id) store.clear()
                            editTarget=null
                            refresh++
                        }
                        .onFailure { error=it.message }
                }
            },
            onPinSettings={
                pinSettingsTarget=profile
                editTarget=null
            }
        )
    }

    unlockTarget?.let { profile ->
        ViewerPinUnlockDialog(
            profile=profile,
            onDismiss={unlockTarget=null},
            onUnlock={pin->
                scope.launch {
                    runCatching { repo.unlock(profile.id,pin) }
                        .onSuccess { unlocked->
                            if(unlocked) {
                                unlockTarget=null
                                if(unlockForEdit) {
                                    editTarget=profile
                                } else {
                                    store.activate(profile)
                                    onActivated(profile)
                                }
                            }
                        }
                        .onFailure { error=it.message }
                }
            }
        )
    }

    pinSettingsTarget?.let { profile ->
        ViewerPinSettingsDialog(
            profile=profile,
            onDismiss={pinSettingsTarget=null},
            onSave={currentPin,newPin->
                scope.launch {
                    runCatching { repo.setPin(profile.id,currentPin,newPin) }
                        .onSuccess { protected->
                            val updated=profile.copy(pinProtected=protected)
                            if(store.activeId()==profile.id) store.activate(updated)
                            pinSettingsTarget=null
                            refresh++
                        }
                        .onFailure { error=it.message }
                }
            }
        )
    }
}

@Composable
private fun ViewerProfileCard(
    profile:ViewerProfile,
    active:Boolean,
    onClick:()->Unit,
    onEdit:()->Unit
) {
    Surface(
        color=if(active)FqGold.copy(alpha=.1f) else FqSurface,
        shape=RoundedCornerShape(22.dp),
        modifier=Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Column(
            Modifier.padding(13.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Box {
                if(profile.avatarUrl.isNotBlank()) {
                    RemoteImage(
                        profile.avatarUrl,
                        Modifier.size(100.dp).clip(CircleShape)
                    )
                } else {
                    Box(
                        Modifier.size(100.dp).clip(CircleShape).background(
                            if(profile.kidsMode)Color(0xFF163B58) else FqSurface2
                        ),
                        contentAlignment=Alignment.Center
                    ) {
                        Text(
                            profile.name.take(1).uppercase(),
                            fontSize=35.sp,
                            fontWeight=FontWeight.Black,
                            color=if(profile.kidsMode)Color(0xFF63D6FF) else FqGold
                        )
                    }
                }

                if(active) {
                    Box(
                        Modifier.size(28.dp).align(Alignment.BottomEnd)
                            .background(FqGold,CircleShape),
                        contentAlignment=Alignment.Center
                    ) {
                        Icon(Icons.Default.Check,null,tint=Color.Black,modifier=Modifier.size(17.dp))
                    }
                }
            }

            Text(
                profile.name,
                fontSize=12.sp,
                fontWeight=FontWeight.Bold,
                maxLines=1,
                overflow=TextOverflow.Ellipsis,
                modifier=Modifier.padding(top=9.dp)
            )

            Row(
                Modifier.padding(top=5.dp),
                verticalAlignment=Alignment.CenterVertically
            ) {
                if(profile.pinProtected) {
                    Icon(
                        Icons.Default.Lock,
                        null,
                        tint=FqGold,
                        modifier=Modifier.size(13.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                }
                if(profile.kidsMode) {
                    Surface(
                        color=Color(0xFF12384E),
                        shape=RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            "KIDS",
                            color=Color(0xFF63D6FF),
                            fontSize=6.sp,
                            fontWeight=FontWeight.Black,
                            modifier=Modifier.padding(horizontal=7.dp,vertical=4.dp)
                        )
                    }
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    if(active)"پروفایل فعال" else "انتخاب پروفایل",
                    color=if(active)FqGold else FqMuted,
                    fontSize=7.sp
                )
            }

            TextButton(
                onClick=onEdit,
                contentPadding=PaddingValues(horizontal=8.dp,vertical=2.dp)
            ) {
                Icon(Icons.Default.Edit,null,modifier=Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("ویرایش",fontSize=7.sp)
            }
        }
    }
}

@Composable
private fun AddViewerProfileCard(onClick:()->Unit) {
    Surface(
        color=FqSurface,
        shape=RoundedCornerShape(22.dp),
        modifier=Modifier.fillMaxWidth().clickable { onClick() }
    ) {
        Column(
            Modifier.padding(vertical=32.dp,horizontal=12.dp),
            horizontalAlignment=Alignment.CenterHorizontally
        ) {
            Box(
                Modifier.size(78.dp).background(FqSurface2,CircleShape),
                contentAlignment=Alignment.Center
            ) {
                Icon(Icons.Default.Add,null,tint=FqGold,modifier=Modifier.size(34.dp))
            }
            Text(
                "پروفایل جدید",
                fontSize=11.sp,
                fontWeight=FontWeight.Bold,
                modifier=Modifier.padding(top=10.dp)
            )
            Text("حداکثر ۵ پروفایل",color=FqMuted,fontSize=7.sp)
        }
    }
}

@Composable
private fun ViewerProfileEditorDialog(
    existing:ViewerProfile?,
    canDelete:Boolean,
    onDismiss:()->Unit,
    onSave:(ViewerProfile)->Unit,
    onDelete:()->Unit,
    onPinSettings:(ViewerProfile)->Unit
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var kids by remember(existing?.id) { mutableStateOf(existing?.kidsMode ?: false) }
    var maturity by remember(existing?.id) {
        mutableStateOf(existing?.maturityLevel ?: if(kids)"kids" else "all")
    }
    var audio by remember(existing?.id) { mutableStateOf(existing?.preferredAudioLanguage ?: "fa") }
    var subtitle by remember(existing?.id) { mutableStateOf(existing?.preferredSubtitleLanguage ?: "fa") }
    var autoplay by remember(existing?.id) { mutableStateOf(existing?.autoplayNext ?: true) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest=onDismiss,
        title={Text(if(existing==null)"پروفایل جدید" else "ویرایش "+existing.name)},
        text={
            Column {
                OutlinedTextField(
                    value=name,
                    onValueChange={name=it.take(40)},
                    label={Text("نام پروفایل")},
                    singleLine=true,
                    modifier=Modifier.fillMaxWidth()
                )

                Row(
                    Modifier.fillMaxWidth().padding(top=10.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.ChildCare,null,tint=if(kids)Color(0xFF63D6FF) else FqMuted)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Kids Mode",fontSize=10.sp,fontWeight=FontWeight.Bold)
                        Text("Social و Create در این حالت مخفی می‌شن.",color=FqMuted,fontSize=7.sp)
                    }
                    Switch(
                        checked=kids,
                        onCheckedChange={
                            kids=it
                            if(it) maturity="kids"
                        }
                    )
                }

                Text("سطح محتوا",fontSize=9.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=10.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    listOf(
                        "kids" to "کودک",
                        "teen" to "نوجوان",
                        "all" to "همه"
                    ).forEach { item ->
                        FilterChip(
                            selected=maturity==item.first,
                            onClick={
                                maturity=item.first
                                kids=item.first=="kids"
                            },
                            label={Text(item.second,fontSize=7.sp)}
                        )
                    }
                }

                Text("زبان صدا",fontSize=9.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp))
                ViewerLanguageRow(selected=audio,onSelected={audio=it})

                Text("زبان زیرنویس",fontSize=9.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=8.dp))
                ViewerLanguageRow(selected=subtitle,onSelected={subtitle=it})

                Row(
                    Modifier.fillMaxWidth().padding(top=8.dp),
                    verticalAlignment=Alignment.CenterVertically
                ) {
                    Text("Auto‑next",fontSize=9.sp,modifier=Modifier.weight(1f))
                    Switch(checked=autoplay,onCheckedChange={autoplay=it})
                }

                if(existing!=null) {
                    OutlinedButton(
                        onClick={onPinSettings(existing)},
                        modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                    ) {
                        Icon(
                            if(existing.pinProtected)Icons.Default.Lock else Icons.Default.LockOpen,
                            null,
                            tint=if(existing.pinProtected)FqGold else FqMuted
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if(existing.pinProtected)"تغییر / حذف PIN" else "تنظیم PIN پروفایل",
                            fontSize=8.sp
                        )
                    }
                }

                if(canDelete && existing!=null) {
                    TextButton(
                        onClick={confirmDelete=true},
                        modifier=Modifier.padding(top=5.dp)
                    ) {
                        Icon(Icons.Default.DeleteOutline,null,tint=FqDanger)
                        Spacer(Modifier.width(4.dp))
                        Text("حذف پروفایل",color=FqDanger,fontSize=8.sp)
                    }
                }
            }
        },
        confirmButton={
            Button(
                enabled=name.trim().isNotEmpty(),
                onClick={
                    onSave(
                        ViewerProfile(
                            id=existing?.id.orEmpty(),
                            name=name.trim(),
                            avatarUrl=existing?.avatarUrl.orEmpty(),
                            kidsMode=kids,
                            maturityLevel=maturity,
                            preferredAudioLanguage=audio,
                            preferredSubtitleLanguage=subtitle,
                            autoplayNext=autoplay,
                            pinProtected=existing?.pinProtected ?: false
                        )
                    )
                },
                colors=ButtonDefaults.buttonColors(containerColor=FqGold)
            ) { Text("ذخیره",color=Color.Black) }
        },
        dismissButton={TextButton(onClick=onDismiss){Text("لغو")}}
    )

    if(confirmDelete) {
        AlertDialog(
            onDismissRequest={confirmDelete=false},
            icon={Icon(Icons.Default.DeleteForever,null,tint=FqDanger)},
            title={Text("حذف این پروفایل؟")},
            text={Text("History، Favorites و Watchlist مخصوص این Viewer Profile هم حذف می‌شن.")},
            confirmButton={
                TextButton(onClick={
                    confirmDelete=false
                    onDelete()
                }) { Text("حذف",color=FqDanger) }
            },
            dismissButton={TextButton(onClick={confirmDelete=false}){Text("لغو")}}
        )
    }
}

@Composable
private fun ViewerLanguageRow(
    selected:String,
    onSelected:(String)->Unit
) {
    Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
        listOf(
            "fa" to "FA",
            "en" to "EN",
            "de" to "DE",
            "tr" to "TR",
            "ko" to "KO",
            "ja" to "JA"
        ).forEach { item ->
            FilterChip(
                selected=selected==item.first,
                onClick={onSelected(item.first)},
                label={Text(item.second,fontSize=7.sp)}
            )
        }
    }
}


@Composable
private fun ViewerPinUnlockDialog(
    profile:ViewerProfile,
    onDismiss:()->Unit,
    onUnlock:(String)->Unit
) {
    var pin by remember(profile.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest=onDismiss,
        icon={Icon(Icons.Default.Lock,null,tint=FqGold)},
        title={Text("PIN • "+profile.name)},
        text={
            Column {
                Text(
                    "برای ورود به این پروفایل PIN چهاررقمی رو وارد کن.",
                    color=FqMuted,
                    fontSize=8.sp
                )
                OutlinedTextField(
                    value=pin,
                    onValueChange={pin=it.filter(Char::isDigit).take(4)},
                    label={Text("PIN")},
                    singleLine=true,
                    visualTransformation=PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),
                    modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                )
            }
        },
        confirmButton={
            Button(
                enabled=pin.length==4,
                onClick={onUnlock(pin)},
                colors=ButtonDefaults.buttonColors(containerColor=FqGold)
            ) { Text("ورود",color=Color.Black) }
        },
        dismissButton={TextButton(onClick=onDismiss){Text("لغو")}}
    )
}

@Composable
private fun ViewerPinSettingsDialog(
    profile:ViewerProfile,
    onDismiss:()->Unit,
    onSave:(String,String)->Unit
) {
    var currentPin by remember(profile.id) { mutableStateOf("") }
    var newPin by remember(profile.id) { mutableStateOf("") }
    var confirmPin by remember(profile.id) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest=onDismiss,
        icon={Icon(Icons.Default.AdminPanelSettings,null,tint=FqGold)},
        title={Text(if(profile.pinProtected)"مدیریت PIN" else "تنظیم PIN")},
        text={
            Column {
                Text(
                    if(profile.pinProtected)
                        "برای تغییر یا حذف PIN، اول PIN فعلی رو وارد کن."
                    else
                        "با PIN، ورود به این پروفایل محافظت می‌شه.",
                    color=FqMuted,
                    fontSize=8.sp
                )

                if(profile.pinProtected) {
                    OutlinedTextField(
                        value=currentPin,
                        onValueChange={currentPin=it.filter(Char::isDigit).take(4)},
                        label={Text("PIN فعلی")},
                        singleLine=true,
                        visualTransformation=PasswordVisualTransformation(),
                        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),
                        modifier=Modifier.fillMaxWidth().padding(top=10.dp)
                    )
                }

                OutlinedTextField(
                    value=newPin,
                    onValueChange={newPin=it.filter(Char::isDigit).take(4)},
                    label={Text("PIN جدید")},
                    singleLine=true,
                    visualTransformation=PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                )

                OutlinedTextField(
                    value=confirmPin,
                    onValueChange={confirmPin=it.filter(Char::isDigit).take(4)},
                    label={Text("تکرار PIN جدید")},
                    singleLine=true,
                    visualTransformation=PasswordVisualTransformation(),
                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),
                    isError=confirmPin.isNotEmpty() && confirmPin!=newPin,
                    modifier=Modifier.fillMaxWidth().padding(top=8.dp)
                )

                if(profile.pinProtected) {
                    TextButton(
                        enabled=currentPin.length==4,
                        onClick={onSave(currentPin,"")},
                        modifier=Modifier.padding(top=5.dp)
                    ) {
                        Icon(Icons.Default.LockOpen,null,tint=FqDanger)
                        Spacer(Modifier.width(4.dp))
                        Text("حذف PIN",color=FqDanger,fontSize=8.sp)
                    }
                }
            }
        },
        confirmButton={
            Button(
                enabled=newPin.length==4 &&
                    confirmPin==newPin &&
                    (!profile.pinProtected || currentPin.length==4),
                onClick={onSave(currentPin,newPin)},
                colors=ButtonDefaults.buttonColors(containerColor=FqGold)
            ) {
                Text(if(profile.pinProtected)"تغییر PIN" else "فعال‌کردن PIN",color=Color.Black)
            }
        },
        dismissButton={TextButton(onClick=onDismiss){Text("لغو")}}
    )
}
