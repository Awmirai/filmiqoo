package com.filmiqoo.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal enum class IranAccessResult { VERIFIED_IRAN, LEGACY_PREVIEW }

/** The preview exception is deliberately limited to a missing endpoint, never a server denial. */
internal class IranAccessVerifier(
    private val client: OkHttpClient,
    private val legacyServerPreview: Boolean = BuildConfig.DEBUG && BuildConfig.LEGACY_SERVER_PREVIEW,
) {
    fun verify(baseUrl: String): IranAccessResult {
        if (!baseUrl.startsWith("https://")) throw IranAccessDeniedException("آدرس امن سرور تنظیم نشده است.")
        val request = Request.Builder().url(baseUrl.trimEnd('/') + "/v1/access")
            .header("Cache-Control", "no-cache, no-store").get().build()
        client.newCall(request).execute().use { response ->
            val json = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
            val regionDenied = json?.optString("code") in
                setOf("IRAN_ONLY", "REGION_UNAVAILABLE", "REGION_NOT_ENFORCED")
            if (response.isSuccessful && !regionDenied && json?.optBoolean("allowed") == true &&
                json.optString("country") == "IR" && json.optString("policy") == "iran-only-v1" && json.optBoolean("enforced")) {
                return IranAccessResult.VERIFIED_IRAN
            }
            if (legacyServerPreview && response.code == 404 && !regionDenied) {
                return IranAccessResult.LEGACY_PREVIEW
            }
            val message = json?.optString("error")?.takeIf { it.isNotBlank() && it != "null" }
                ?: if (response.code == 404) "این نسخه برای ورود به به‌روزرسانی سرور نیاز دارد. سرویس بررسی دسترسی هنوز فعال نیست."
                else "سرویس دسترسی، اتصال داخل ایران را تأیید نکرد. دوباره تلاش کن."
            throw IranAccessDeniedException(message)
        }
    }
}
