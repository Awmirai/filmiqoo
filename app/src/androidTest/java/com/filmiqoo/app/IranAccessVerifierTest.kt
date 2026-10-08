package com.filmiqoo.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class IranAccessVerifierTest {
    private val confirmed = """{"allowed":true,"country":"IR","policy":"iran-only-v1","enforced":true}"""

    private fun verifier(status: Int, body: String, preview: Boolean): IranAccessVerifier {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https://server.example/v1/access", chain.request().url.toString())
            assertEquals("no-cache, no-store", chain.request().header("Cache-Control"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("Test response")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        return IranAccessVerifier(client, preview)
    }

    @Test fun legacy404AllowsExplicitPreview() {
        assertEquals(IranAccessResult.LEGACY_PREVIEW, verifier(404, "404 page not found", true).verify("https://server.example/"))
    }

    @Test fun legacy404StillBlocksStrictBuilds() {
        assertThrows(IranAccessDeniedException::class.java) {
            verifier(404, "404 page not found", false).verify("https://server.example")
        }
    }

    @Test fun confirmedIranAllowsBothModes() {
        for (preview in listOf(false, true)) {
            assertEquals(IranAccessResult.VERIFIED_IRAN, verifier(200, confirmed, preview).verify("https://server.example"))
        }
    }

    @Test fun serverDenialsNeverBecomePreviewAccess() {
        for (status in listOf(200, 403, 404, 503)) {
            for (code in listOf("IRAN_ONLY", "REGION_UNAVAILABLE", "REGION_NOT_ENFORCED")) {
                val failure = assertThrows(IranAccessDeniedException::class.java) {
                    verifier(status, """{"code":"$code","error":"Blocked by server"}""", true).verify("https://server.example")
                }
                assertEquals("Blocked by server", failure.message)
            }
        }
    }

    @Test fun invalidProofCannotAuthorizeEitherMode() {
        for (body in listOf("not json", "{}", confirmed.replace("IR", "DE"),
            confirmed.replace("iran-only-v1", "unknown"), confirmed.replace("true", "false"))) {
            for (preview in listOf(false, true)) {
                assertThrows(IranAccessDeniedException::class.java) {
                    verifier(200, body, preview).verify("https://server.example")
                }
            }
        }
    }

    @Test fun serviceFailuresNeverBecomePreviewAccess() {
        for (status in listOf(401, 403, 405, 429, 500, 502, 503)) {
            assertThrows(IranAccessDeniedException::class.java) {
                verifier(status, "", true).verify("https://server.example")
            }
        }
    }

    @Test fun networkFailureNeverBecomesPreviewAccess() {
        val client = OkHttpClient.Builder().addInterceptor { throw IOException("Offline") }.build()
        val failure = assertThrows(IOException::class.java) {
            IranAccessVerifier(client, true).verify("https://server.example")
        }
        assertEquals("Offline", failure.message)
    }

    @Test fun cleartextIsRejectedBeforeRequest() {
        val client = OkHttpClient.Builder().addInterceptor { fail("No request may be sent"); error("unreachable") }.build()
        assertThrows(IranAccessDeniedException::class.java) {
            IranAccessVerifier(client, true).verify("http://server.example")
        }
    }
}
