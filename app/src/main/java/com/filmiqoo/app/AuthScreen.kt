package com.filmiqoo.app

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class AuthStep { LOGIN, REGISTER, RECOVERY, RESET, COMPLETE }

internal data class AuthUiState(
    val step:AuthStep=AuthStep.LOGIN,
    val login:String="", val email:String="", val username:String="", val displayName:String="",
    val password:String="", val confirmation:String="", val code:String="",
    val loading:Boolean=false, val error:String?=null, val notice:String?=null,
    val resendSeconds:Int=0
) {
    val canSubmit:Boolean get() = !loading && when(step) {
        AuthStep.LOGIN -> login.isNotBlank() && password.isNotEmpty()
        AuthStep.REGISTER -> validAuthEmail(email) && username.matches(Regex("[A-Za-z0-9_.]{3,24}")) &&
            displayName.trim().length in 2..50 && validAuthPassword(password) && password==confirmation
        AuthStep.RECOVERY -> validAuthEmail(email)
        AuthStep.RESET -> validAuthEmail(email) && code.length==8 && code.all { it in '0'..'9' } &&
            validAuthPassword(password) && password==confirmation
        AuthStep.COMPLETE -> true
    }
}

internal fun validAuthEmail(value:String):Boolean = value.trim().let {
    it.length<=254 && it.matches(Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
}
internal fun validAuthPassword(value:String):Boolean =
    value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size in 10..128

@Composable
fun AuthScreen(backend:BackendRepository,onSuccess:()->Unit) {
    val scope=rememberCoroutineScope()
    var state by remember { mutableStateOf(AuthUiState()) }
    LaunchedEffect(state.resendSeconds) {
        if(state.resendSeconds>0) { delay(1000); state=state.copy(resendSeconds=(state.resendSeconds-1).coerceAtLeast(0)) }
    }
    fun changeStep(step:AuthStep) {
        if(state.loading) return
        state=state.copy(step=step,password="",confirmation="",code="",error=null,notice=null,
            email=state.email.ifBlank { state.login.takeIf(::validAuthEmail).orEmpty() })
    }
    fun requestCode() {
        if(state.loading || !validAuthEmail(state.email)) return
        state=state.copy(loading=true,error=null)
        scope.launch {
            try {
                val wait=backend.requestPasswordReset(state.email.trim())
                state=state.copy(step=AuthStep.RESET,password="",confirmation="",code="",resendSeconds=wait,
                    notice="اگر حسابی با این ایمیل وجود داشته باشد، کد بازیابی برای آن ارسال می‌شود. پوشهٔ هرزنامه را هم بررسی کنید.")
            } catch(e:CancellationException) { throw e
            } catch(e:Exception) { state=state.copy(error=e.message ?: "ارسال درخواست انجام نشد. دوباره تلاش کنید.")
            } finally { state=state.copy(loading=false) }
        }
    }
    fun submit() {
        if(!state.canSubmit) return
        if(state.step==AuthStep.COMPLETE) { changeStep(AuthStep.LOGIN);return }
        if(state.step==AuthStep.RECOVERY) { requestCode();return }
        val submitted=state
        state=state.copy(loading=true,error=null)
        scope.launch {
            try {
                val device=(Build.MANUFACTURER+" "+Build.MODEL).trim()
                when(submitted.step) {
                    AuthStep.LOGIN -> { backend.login(submitted.login.trim(),submitted.password,device);onSuccess() }
                    AuthStep.REGISTER -> { backend.register(submitted.email.trim(),submitted.username.trim(),submitted.displayName.trim(),submitted.password,device);onSuccess() }
                    AuthStep.RESET -> {
                        backend.resetPassword(submitted.email.trim(),submitted.code,submitted.password)
                        state=state.copy(step=AuthStep.COMPLETE,login=submitted.email.trim(),password="",confirmation="",code="",notice=null)
                    }
                    else -> Unit
                }
            } catch(e:CancellationException) { throw e
            } catch(e:Exception) { state=state.copy(error=e.message ?: "درخواست انجام نشد. دوباره تلاش کنید.")
            } finally { state=state.copy(loading=false) }
        }
    }
    BackHandler(enabled=state.step!=AuthStep.LOGIN) { if(!state.loading) changeStep(AuthStep.LOGIN) }
    AuthExperience(state,onChange={state=it.copy(error=null)},onStep=::changeStep,onSubmit=::submit,onResend=::requestCode)
}

@Composable
internal fun AuthExperience(
    state:AuthUiState,
    onChange:(AuthUiState)->Unit,
    onStep:(AuthStep)->Unit,
    onSubmit:()->Unit,
    onResend:()->Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(FqBg)) {
        val wide=maxWidth>=840.dp
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(FqGold.copy(alpha=.09f),Color.Transparent),radius=1100f)))
        Row(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
            verticalAlignment=Alignment.CenterVertically
        ) {
            if(wide) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(48.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
                    FilmiqooBrandMark(size=64.dp)
                    Text("آدم‌ها و قصه‌ها\nاینجا به هم می‌رسند.",style=MaterialTheme.typography.displayMedium,fontWeight=FontWeight.Bold)
                    Text("فیلم محبوبت را پیدا کن، از همان لحظه ادامه بده و تجربه‌اش را با دوستانت شریک شو.",style=MaterialTheme.typography.bodyLarge,color=FqMuted)
                    AuthBenefit(Icons.Default.PlayCircleOutline,"تماشای شخصی","فیلم‌ها، سریال‌ها و ادامهٔ تماشای تو")
                    AuthBenefit(Icons.Default.Forum,"کنار هم، حتی از دور","گفت‌وگو و تماشای گروهی با دوست‌ها")
                }
            }
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal=if(wide)32.dp else 24.dp,vertical=24.dp),
                horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement=Arrangement.Center
            ) {
                Column(Modifier.widthIn(max=480.dp).fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        FilmiqooBrandMark(size=46.dp)
                        Column {
                            Text("فیلمیکو",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                            Text("سینما، کنار هم.",style=MaterialTheme.typography.bodySmall,color=FqMuted)
                        }
                    }
                    Spacer(Modifier.height(if(wide)32.dp else 28.dp))
                    Surface(color=FqSurface,shape=RoundedCornerShape(28.dp),border=androidx.compose.foundation.BorderStroke(1.dp,FqBorder)) {
                        Column(Modifier.fillMaxWidth().padding(if(wide)28.dp else 22.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                            if(state.step==AuthStep.LOGIN || state.step==AuthStep.REGISTER) {
                                Row(Modifier.fillMaxWidth().background(FqBg,RoundedCornerShape(14.dp)).padding(4.dp)) {
                                    AuthModeTab("ورود",state.step==AuthStep.LOGIN,!state.loading,{onStep(AuthStep.LOGIN)},Modifier.weight(1f))
                                    AuthModeTab("ساخت حساب",state.step==AuthStep.REGISTER,!state.loading,{onStep(AuthStep.REGISTER)},Modifier.weight(1f))
                                }
                            } else if(state.step!=AuthStep.COMPLETE) {
                                TextButton(onClick={onStep(AuthStep.LOGIN)},enabled=!state.loading,contentPadding=PaddingValues(0.dp)) {
                                    Icon(Icons.Default.ArrowForward,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("بازگشت به ورود")
                                }
                            }
                            val title=when(state.step) {
                                AuthStep.LOGIN -> "خوش برگشتی"
                                AuthStep.REGISTER -> "قصهٔ تو از اینجا شروع می‌شود"
                                AuthStep.RECOVERY -> "رمزت را فراموش کردی؟"
                                AuthStep.RESET -> "رمز تازه، شروع دوباره"
                                AuthStep.COMPLETE -> "رمزت با موفقیت تغییر کرد"
                            }
                            if(state.step==AuthStep.COMPLETE) {
                                Box(Modifier.size(64.dp).background(FqGreen.copy(alpha=.12f),CircleShape),contentAlignment=Alignment.Center) {
                                    Icon(Icons.Default.CheckCircle,"موفقیت",tint=FqGreen,modifier=Modifier.size(32.dp))
                                }
                            }
                            Text(title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                            Text(when(state.step) {
                                AuthStep.LOGIN -> "برای ادامهٔ تماشا و دیدن دنیای سینما وارد شو."
                                AuthStep.REGISTER -> "یک حساب برای همهٔ فیلم‌ها و لحظه‌های مشترک."
                                AuthStep.RECOVERY -> "ایمیل حسابت را وارد کن تا کد بازیابی برایت ارسال شود."
                                AuthStep.RESET -> "کد ۸ رقمی ایمیل را وارد کن و یک رمز جدید بساز. کد تا ۱۵ دقیقه معتبر است."
                                AuthStep.COMPLETE -> "حالا با رمز جدید وارد شو. برای امنیت حساب، ورود دوباره روی دستگاه‌های دیگر هم لازم است."
                            },style=MaterialTheme.typography.bodyMedium,color=FqMuted)
                            key(state.step) {
                                when(state.step) {
                                    AuthStep.LOGIN -> {
                                        AuthField(state.login,{onChange(state.copy(login=it))},"ایمیل یا نام کاربری",Icons.Default.PersonOutline,enabled=!state.loading,onDone=onSubmit)
                                        AuthPassword(state.password,{onChange(state.copy(password=it))},"رمز عبور",enabled=!state.loading,onDone=onSubmit)
                                        TextButton(onClick={onStep(AuthStep.RECOVERY)},enabled=!state.loading,modifier=Modifier.align(Alignment.End)) { Text("رمز عبور را فراموش کرده‌ام") }
                                    }
                                    AuthStep.REGISTER -> {
                                        AuthField(state.displayName,{onChange(state.copy(displayName=it.take(50)))},"نام نمایشی",Icons.Default.Badge,enabled=!state.loading,ltr=false,onDone=onSubmit)
                                        AuthField(state.email,{onChange(state.copy(email=it.take(254)))},"ایمیل",Icons.Default.MailOutline,keyboard=KeyboardType.Email,enabled=!state.loading,onDone=onSubmit)
                                        AuthField(state.username,{onChange(state.copy(username=it.filter { c -> c in 'a'..'z'||c in 'A'..'Z'||c in '0'..'9'||c=='_'||c=='.' }.take(24)))},"نام کاربری",Icons.Default.AlternateEmail,helper="۳ تا ۲۴ حرف انگلیسی، عدد، نقطه یا زیرخط",enabled=!state.loading,onDone=onSubmit)
                                        AuthPassword(state.password,{onChange(state.copy(password=it))},"رمز عبور",enabled=!state.loading,helper="حداقل ۱۰ کاراکتر؛ از رمز تکراری استفاده نکن.",onDone=onSubmit)
                                        AuthPassword(state.confirmation,{onChange(state.copy(confirmation=it))},"تکرار رمز عبور",enabled=!state.loading,isError=state.confirmation.isNotEmpty()&&state.confirmation!=state.password,onDone=onSubmit)
                                    }
                                    AuthStep.RECOVERY -> AuthField(state.email,{onChange(state.copy(email=it.take(254)))},"ایمیل حساب",Icons.Default.MailOutline,keyboard=KeyboardType.Email,enabled=!state.loading,done=true,onDone=onSubmit)
                                    AuthStep.RESET -> {
                                        Text(state.email,color=FqText,style=MaterialTheme.typography.bodyMedium.copy(textDirection=TextDirection.Ltr),modifier=Modifier.fillMaxWidth())
                                        state.notice?.let { AuthNotice(it,false) }
                                        AuthField(state.code,{onChange(state.copy(code=normalizeAuthCode(it)))},"کد ۸ رقمی",Icons.Default.Pin,keyboard=KeyboardType.NumberPassword,enabled=!state.loading,onDone=onSubmit)
                                        AuthPassword(state.password,{onChange(state.copy(password=it))},"رمز عبور جدید",enabled=!state.loading,helper="حداقل ۱۰ کاراکتر",onDone=onSubmit)
                                        AuthPassword(state.confirmation,{onChange(state.copy(confirmation=it))},"تکرار رمز جدید",enabled=!state.loading,isError=state.confirmation.isNotEmpty()&&state.confirmation!=state.password,onDone=onSubmit)
                                    }
                                    AuthStep.COMPLETE -> Unit
                                }
                            }
                            state.error?.let { AuthNotice(it,true) }
                            FqPrimaryButton(
                                text=when(state.step) { AuthStep.LOGIN -> "ورود به فیلمیکو"; AuthStep.REGISTER -> "ساخت حساب"; AuthStep.RECOVERY -> "دریافت کد بازیابی"; AuthStep.RESET -> "ثبت رمز جدید"; AuthStep.COMPLETE -> "ورود با رمز جدید" },
                                onClick=onSubmit,enabled=state.canSubmit,loading=state.loading,
                                icon=if(state.step==AuthStep.RECOVERY)Icons.Default.Send else Icons.Default.ArrowBack,
                                modifier=Modifier.fillMaxWidth()
                            )
                            if(state.step==AuthStep.RESET) {
                                Column(Modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
                                    TextButton(onClick=onResend,enabled=!state.loading&&state.resendSeconds==0) { Text(if(state.resendSeconds>0)"ارسال مجدد در ${state.resendSeconds} ثانیه" else "ارسال دوبارهٔ کد",style=MaterialTheme.typography.labelMedium) }
                                    TextButton(onClick={onStep(AuthStep.RECOVERY)},enabled=!state.loading) { Text("تغییر ایمیل",style=MaterialTheme.typography.labelMedium) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("قصه‌های تازه منتظرت هستند.",color=FqMuted,style=MaterialTheme.typography.bodySmall,textAlign=TextAlign.Center)
                    AuthLegalLinks()
                }
            }
        }
    }
}

internal fun normalizeAuthCode(value:String):String = value.mapNotNull { c ->
    when(c) { in '0'..'9' -> c; in '۰'..'۹' -> ('0'.code+c.code-'۰'.code).toChar(); in '٠'..'٩' -> ('0'.code+c.code-'٠'.code).toChar(); else -> null }
}.joinToString("").take(8)

@Composable
private fun AuthModeTab(text:String,selected:Boolean,enabled:Boolean,onClick:()->Unit,modifier:Modifier) {
    TextButton(onClick=onClick,enabled=enabled,shape=RoundedCornerShape(11.dp),
        colors=ButtonDefaults.textButtonColors(containerColor=if(selected)FqSurface3 else Color.Transparent,contentColor=if(selected)FqText else FqMuted),
        modifier=modifier.heightIn(min=48.dp)) { Text(text,fontWeight=if(selected)FontWeight.Bold else FontWeight.Medium) }
}

@Composable
private fun AuthBenefit(icon:ImageVector,title:String,subtitle:String) {
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).background(FqSurface2,RoundedCornerShape(16.dp)),contentAlignment=Alignment.Center) { Icon(icon,null,tint=FqGoldSoft) }
        Column { Text(title,style=MaterialTheme.typography.titleMedium);Text(subtitle,color=FqMuted,style=MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun AuthField(value:String,onChange:(String)->Unit,label:String,icon:ImageVector,
    keyboard:KeyboardType=KeyboardType.Text,enabled:Boolean=true,helper:String?=null,ltr:Boolean=true,done:Boolean=false,onDone:()->Unit) {
    val focus=LocalFocusManager.current
    OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},singleLine=true,enabled=enabled,
        leadingIcon={Icon(icon,null,Modifier.size(21.dp))},shape=RoundedCornerShape(16.dp),
        textStyle=MaterialTheme.typography.bodyLarge.copy(textDirection=if(ltr)TextDirection.ContentOrLtr else TextDirection.ContentOrRtl),
        supportingText=if(helper!=null) { {Text(helper,style=MaterialTheme.typography.bodySmall)} } else null,
        keyboardOptions=KeyboardOptions(keyboardType=keyboard,imeAction=if(done)ImeAction.Done else ImeAction.Next),
        keyboardActions=KeyboardActions(onNext={focus.moveFocus(FocusDirection.Next)},onDone={onDone()}),modifier=Modifier.fillMaxWidth())
}

@Composable
private fun AuthPassword(value:String,onChange:(String)->Unit,label:String,enabled:Boolean,helper:String?=null,isError:Boolean=false,onDone:()->Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(value=value,onValueChange=onChange,label={Text(label)},singleLine=true,enabled=enabled,isError=isError,
        leadingIcon={Icon(Icons.Default.LockOutline,null,Modifier.size(21.dp))},
        trailingIcon={IconButton(onClick={visible=!visible},enabled=enabled) { Icon(if(visible)Icons.Default.VisibilityOff else Icons.Default.Visibility,if(visible)"پنهان کردن رمز" else "نمایش رمز") }},
        supportingText={if(isError)Text("دو رمز یکسان نیستند.") else if(helper!=null)Text(helper)},
        visualTransformation=if(visible)VisualTransformation.None else PasswordVisualTransformation(),
        textStyle=MaterialTheme.typography.bodyLarge.copy(textDirection=TextDirection.Ltr),
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=ImeAction.Done),
        keyboardActions=KeyboardActions(onDone={onDone()}),shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth())
}

@Composable
private fun AuthNotice(message:String,error:Boolean) {
    Surface(color=(if(error)FqDanger else FqBlue).copy(alpha=.08f),shape=RoundedCornerShape(12.dp),modifier=Modifier.fillMaxWidth().semantics { liveRegion=LiveRegionMode.Polite }) {
        Text(message,color=if(error)FqDanger else FqMutedStrong,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(12.dp))
    }
}

@Composable
private fun AuthLegalLinks() {
    val uri=LocalUriHandler.current
    var failure by remember { mutableStateOf(false) }
    Row(horizontalArrangement=Arrangement.Center,modifier=Modifier.fillMaxWidth()) {
        if(BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) TextButton(onClick={failure=runCatching { uri.openUri(BuildConfig.PRIVACY_POLICY_URL) }.isFailure}) { Text("حریم خصوصی",style=MaterialTheme.typography.labelMedium) }
        if(BuildConfig.TERMS_URL.isNotBlank()) TextButton(onClick={failure=runCatching { uri.openUri(BuildConfig.TERMS_URL) }.isFailure}) { Text("شرایط استفاده",style=MaterialTheme.typography.labelMedium) }
    }
    if(failure) Text("بازکردن پیوند ممکن نشد. مرورگر دستگاه را بررسی کنید.",color=FqDanger,style=MaterialTheme.typography.bodySmall)
}
