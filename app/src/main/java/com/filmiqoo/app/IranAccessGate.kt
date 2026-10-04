package com.filmiqoo.app

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class IranAccessDeniedException(message: String) : IOException(message)

internal object IranAccessEvents { val denial = MutableStateFlow<String?>(null) }

/** A server denial invalidates the in-memory UI lease immediately, including failures swallowed by legacy screens. */
internal class IranAccessInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == 403 || response.code == 503) {
            val parsed = runCatching { JSONObject(response.peekBody(8192).string()) }.getOrNull()
            val code = parsed?.optString("code")
            if (code in setOf("IRAN_ONLY", "REGION_UNAVAILABLE", "REGION_NOT_ENFORCED")) {
                val message = parsed?.optString("error")?.takeIf(String::isNotBlank) ?: "دسترسی منطقه‌ای تأیید نشد."
                response.close()
                IranAccessEvents.denial.value = message
                throw IranAccessDeniedException(message)
            }
        }
        return response
    }
}

internal data class IranAccessViewState(val checking: Boolean = true, val allowed: Boolean = false, val message: String = "")

@Composable
fun IranAccessGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val networkOnline = rememberNetworkOnline()
    val session = remember { SessionStore(context.applicationContext) }
    val client = remember { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build() }
    var state by remember { mutableStateOf(IranAccessViewState()) }
    var retry by remember { mutableIntStateOf(0) }
    var validUntil by remember { mutableLongStateOf(0L) }
    val denial by IranAccessEvents.denial.collectAsState()

    LaunchedEffect(denial) {
        denial?.let { validUntil = 0L; state = IranAccessViewState(false, false, it) }
    }
    LaunchedEffect(lifecycle, retry, networkOnline) {
        if (!networkOnline) {
            validUntil = 0L
            state = IranAccessViewState(false, false, "برای بررسی محدودهٔ دسترسی، اتصال اینترنت لازم است.")
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (SystemClock.elapsedRealtime() >= validUntil) state = IranAccessViewState()
                try {
                    val answer = withContext(Dispatchers.IO) {
                        val base = session.baseUrl
                        if (!base.startsWith("https://")) throw IranAccessDeniedException("آدرس امن سرور تنظیم نشده است.")
                        val request = Request.Builder().url(base + "/v1/access").header("Cache-Control", "no-cache, no-store").get().build()
                        client.newCall(request).execute().use { response ->
                            val raw = response.body?.string().orEmpty()
                            val json = runCatching { JSONObject(raw) }.getOrNull()
                            val allowed = response.isSuccessful && json?.optBoolean("allowed") == true &&
                                json.optString("country") == "IR" && json.optString("policy") == "iran-only-v1" && json.optBoolean("enforced")
                            if (!allowed) {
                                val message = json?.optString("error")?.takeIf { it.isNotBlank() && it != "null" }
                                    ?: "نسخهٔ سرور هنوز امکان تأیید دسترسی ایران را ندارد. فعال‌سازی سمت سرور لازم است."
                                throw IranAccessDeniedException(message)
                            }
                            true
                        }
                    }
                    if (answer) {
                        validUntil = SystemClock.elapsedRealtime() + 60_000L
                        IranAccessEvents.denial.value = null
                        state = IranAccessViewState(false, true)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    validUntil = 0L
                    state = IranAccessViewState(false, false, if (failure is IranAccessDeniedException) failure.message.orEmpty() else "ارتباط امن با سرویس دسترسی برقرار نشد. اتصال را بررسی و دوباره تلاش کن.")
                }
                delay(45_000L)
            }
        }
    }
    if (state.allowed && denial == null) content() else IranAccessScreen(state) { retry++ }
}

@Composable
internal fun IranAccessScreen(state: IranAccessViewState, retry: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().background(CinemaInk).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("iran-access-screen"), verticalArrangement = Arrangement.Center) {
        Icon(if (state.checking) Icons.Default.Public else Icons.Default.LocationOn, null, tint = CinemaAccent, modifier = Modifier.size(56.dp))
        Text("FILMIQOO", color = CinemaPaper, fontSize = 28.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 22.dp))
        Text(if (state.checking) "در حال بررسی دسترسی…" else "ویژهٔ اتصال داخل ایران", color = CinemaPaper, fontSize = 21.sp, lineHeight = 30.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        Text(if (state.checking) "قبل از ورود، کشور اتصال به‌صورت امن توسط سرور بررسی می‌شود." else state.message,
            color = CinemaSoft, fontSize = 14.sp, lineHeight = 25.sp, modifier = Modifier.padding(top = 12.dp))
        if (state.checking) LinearProgressIndicator(color = CinemaAccent, modifier = Modifier.fillMaxWidth().padding(top = 24.dp))
        else {
            CinemaAction(Icons.Default.Refresh, "بررسی دوباره", retry, Modifier.fillMaxWidth().padding(top = 22.dp), primary = true)
            TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("تنظیمات اینترنت", color = CinemaSoft) }
        }
        Text("ملاک، کشور IP اتصال است؛ نه ملیت یا محل سکونت. اینترنت خارج ایران یا VPN خارجی دسترسی را مسدود می‌کند. اجازهٔ GPS درخواست نمی‌شود.",
            color = CinemaSoft, fontSize = 12.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 24.dp))
        TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://db-ip.com"))) } }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("IP Geolocation by DB-IP · CC BY 4.0", color = CinemaSoft, fontSize = 11.sp)
        }
    }
}
