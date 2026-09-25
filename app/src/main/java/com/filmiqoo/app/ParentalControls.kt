package com.filmiqoo.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.json.JSONObject

data class ParentalStatus(
    val pinProtected:Boolean,
    val retryAfterSeconds:Int
)

class ParentalControlsRepository(
    private val backend:BackendRepository
) {
    suspend fun status():ParentalStatus {
        val o=backend.getJson("/v1/parental/status",authorized=true)
        return ParentalStatus(
            pinProtected=o.optBoolean("pinProtected"),
            retryAfterSeconds=o.optInt("retryAfterSeconds")
        )
    }

    suspend fun setPin(password:String,pin:String):Boolean =
        backend.postJson(
            "/v1/parental/pin",
            JSONObject()
                .put("password",password)
                .put("pin",pin),
            authorized=true
        ).optBoolean("pinProtected")

    suspend fun verify(pin:String="",password:String=""):Boolean =
        backend.postJson(
            "/v1/parental/verify",
            JSONObject()
                .put("pin",pin)
                .put("password",password),
            authorized=true
        ).optBoolean("verified")
}

@Composable
fun ParentalGateScreen(
    backend:BackendRepository,
    onBack:()->Unit,
    onVerified:()->Unit
) {
    val repo=remember { ParentalControlsRepository(backend) }
    val scope=rememberCoroutineScope()
    var status by remember { mutableStateOf<ParentalStatus?>(null) }
    var secret by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled=!busy) { onBack() }

    LaunchedEffect(Unit) {
        runCatching { repo.status() }
            .onSuccess { status=it }
            .onFailure { error=it.message }
    }

    Box(
        Modifier.fillMaxSize().background(FqBg),
        contentAlignment=Alignment.Center
    ) {
        Surface(
            color=FqSurface,
            shape=RoundedCornerShape(28.dp),
            modifier=Modifier.fillMaxWidth().padding(22.dp)
        ) {
            Column(
                Modifier.padding(22.dp),
                horizontalAlignment=Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(70.dp).background(
                        FqGold.copy(alpha=.13f),
                        RoundedCornerShape(22.dp)
                    ),
                    contentAlignment=Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AdminPanelSettings,
                        null,
                        tint=FqGold,
                        modifier=Modifier.size(36.dp)
                    )
                }

                Text(
                    "خروج از Kids Mode",
                    fontSize=22.sp,
                    fontWeight=FontWeight.Black,
                    modifier=Modifier.padding(top=14.dp)
                )

                val current=status
                Text(
                    when {
                        current==null -> "در حال بررسی قفل والدین..."
                        current.retryAfterSeconds>0 ->
                            "قفل موقت فعاله. چند دقیقه بعد دوباره امتحان کن."
                        current.pinProtected ->
                            "PIN چهاررقمی والدین رو وارد کن."
                        else ->
                            "هنوز Parental PIN نساختی؛ رمز اصلی حساب رو وارد کن."
                    },
                    color=FqMuted,
                    fontSize=11.sp,
                    lineHeight=15.sp,
                    modifier=Modifier.padding(top=6.dp)
                )

                if(current==null) {
                    CircularProgressIndicator(
                        color=FqGold,
                        modifier=Modifier.padding(top=18.dp)
                    )
                } else {
                    OutlinedTextField(
                        value=secret,
                        onValueChange={
                            secret=if(current.pinProtected) {
                                it.filter(Char::isDigit).take(4)
                            } else {
                                it.take(128)
                            }
                        },
                        label={Text(if(current.pinProtected)"Parental PIN" else "رمز حساب")},
                        singleLine=true,
                        visualTransformation=PasswordVisualTransformation(),
                        keyboardOptions=KeyboardOptions(
                            keyboardType=if(current.pinProtected)
                                KeyboardType.NumberPassword
                            else
                                KeyboardType.Password
                        ),
                        leadingIcon={
                            Icon(
                                if(current.pinProtected)Icons.Default.Pin else Icons.Default.Password,
                                null
                            )
                        },
                        shape=RoundedCornerShape(15.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=16.dp)
                    )

                    Button(
                        enabled=!busy &&
                            current.retryAfterSeconds<=0 &&
                            if(current.pinProtected)secret.length==4 else secret.isNotBlank(),
                        onClick={
                            busy=true
                            error=null
                            scope.launch {
                                runCatching {
                                    if(current.pinProtected) {
                                        repo.verify(pin=secret)
                                    } else {
                                        repo.verify(password=secret)
                                    }
                                }.onSuccess { verified->
                                    if(verified) onVerified()
                                }.onFailure {
                                    error=it.message
                                }
                                busy=false
                            }
                        },
                        colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                        shape=RoundedCornerShape(14.dp),
                        modifier=Modifier.fillMaxWidth().padding(top=12.dp)
                    ) {
                        if(busy) {
                            CircularProgressIndicator(
                                color=Color.Black,
                                strokeWidth=2.dp,
                                modifier=Modifier.size(18.dp)
                            )
                        } else {
                            Icon(Icons.Default.LockOpen,null,tint=Color.Black)
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("تأیید والدین",color=Color.Black,fontWeight=FontWeight.Bold)
                    }
                }

                error?.let {
                    Text(
                        it,
                        color=FqDanger,
                        fontSize=11.sp,
                        modifier=Modifier.padding(top=10.dp)
                    )
                }

                TextButton(onClick=onBack,enabled=!busy) {
                    Text("برگشت به Kids")
                }
            }
        }
    }
}

@Composable
fun ParentalControlsScreen(
    backend:BackendRepository,
    onBack:()->Unit
) {
    val repo=remember { ParentalControlsRepository(backend) }
    val scope=rememberCoroutineScope()
    var status by remember { mutableStateOf<ParentalStatus?>(null) }
    var password by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled=!busy) { onBack() }

    fun refresh() {
        scope.launch {
            runCatching { repo.status() }
                .onSuccess { status=it }
                .onFailure { error=it.message }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().background(FqBg)) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment=Alignment.CenterVertically
        ) {
            IconButton(onClick=onBack,enabled=!busy) {
                Icon(Icons.Default.ArrowBack,null)
            }
            Column(Modifier.weight(1f)) {
                Text("کنترل والدین",fontSize=22.sp,fontWeight=FontWeight.Black)
                Text("Kids Exit Gate و PIN والدین",color=FqMuted,fontSize=11.sp)
            }
            Icon(Icons.Default.ChildCare,null,tint=FqGold)
        }

        status?.let { current->
            Surface(
                color=if(current.pinProtected)FqGreen.copy(alpha=.08f) else FqGold.copy(alpha=.08f),
                shape=RoundedCornerShape(20.dp),
                modifier=Modifier.fillMaxWidth().padding(14.dp)
            ) {
                Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(
                        if(current.pinProtected)Icons.Default.Lock else Icons.Default.WarningAmber,
                        null,
                        tint=if(current.pinProtected)FqGreen else FqGold
                    )
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text(
                            if(current.pinProtected)"Parental PIN فعاله"
                            else "Parental PIN هنوز فعال نیست",
                            fontSize=11.sp,
                            fontWeight=FontWeight.Bold
                        )
                        Text(
                            if(current.pinProtected)
                                "خروج از Kids Mode با PIN والدین محافظت می‌شه."
                            else
                                "تا وقتی PIN نسازی، خروج از Kids با رمز اصلی حساب تأیید می‌شه.",
                            color=FqMuted,
                            fontSize=11.sp,
                            lineHeight=14.sp
                        )
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal=16.dp),
            verticalArrangement=Arrangement.spacedBy(9.dp)
        ) {
            OutlinedTextField(
                value=password,
                onValueChange={password=it.take(128)},
                label={Text("رمز اصلی حساب")},
                singleLine=true,
                visualTransformation=PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
                modifier=Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value=pin,
                onValueChange={pin=it.filter(Char::isDigit).take(4)},
                label={Text("PIN چهاررقمی جدید")},
                singleLine=true,
                visualTransformation=PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),
                modifier=Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value=confirmPin,
                onValueChange={confirmPin=it.filter(Char::isDigit).take(4)},
                label={Text("تکرار PIN")},
                singleLine=true,
                isError=confirmPin.isNotBlank() && confirmPin!=pin,
                visualTransformation=PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),
                modifier=Modifier.fillMaxWidth()
            )

            Button(
                enabled=!busy &&
                    password.isNotBlank() &&
                    pin.length==4 &&
                    pin==confirmPin,
                onClick={
                    busy=true
                    error=null
                    message=null
                    scope.launch {
                        runCatching { repo.setPin(password,pin) }
                            .onSuccess {
                                message="Parental PIN ذخیره شد."
                                password=""
                                pin=""
                                confirmPin=""
                                refresh()
                            }
                            .onFailure { error=it.message }
                        busy=false
                    }
                },
                colors=ButtonDefaults.buttonColors(containerColor=FqGold),
                modifier=Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Pin,null,tint=Color.Black)
                Spacer(Modifier.width(5.dp))
                Text(
                    if(status?.pinProtected==true)"تغییر PIN" else "فعال‌کردن PIN",
                    color=Color.Black
                )
            }

            if(status?.pinProtected==true) {
                OutlinedButton(
                    enabled=!busy && password.isNotBlank(),
                    onClick={
                        busy=true
                        error=null
                        message=null
                        scope.launch {
                            runCatching { repo.setPin(password,"") }
                                .onSuccess {
                                    message="Parental PIN حذف شد؛ خروج Kids با رمز حساب تأیید می‌شه."
                                    password=""
                                    pin=""
                                    confirmPin=""
                                    refresh()
                                }
                                .onFailure { error=it.message }
                            busy=false
                        }
                    },
                    colors=ButtonDefaults.outlinedButtonColors(contentColor=FqDanger),
                    modifier=Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.LockOpen,null)
                    Spacer(Modifier.width(5.dp))
                    Text("حذف Parental PIN")
                }
            }
        }

        error?.let {
            Text(it,color=FqDanger,fontSize=11.sp,modifier=Modifier.padding(16.dp))
        }
        message?.let {
            Text(it,color=FqGreen,fontSize=11.sp,modifier=Modifier.padding(16.dp))
        }
    }
}
