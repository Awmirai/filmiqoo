#!/usr/bin/env python3
"""One-time, exact-marker migration. Commits real source, never patches APKs at build time.

Run only on the reviewed cinema-redesign-iran-access branch. Each replacement fails
on unexpected source; running it again is a no-op. No credentials or remote calls.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
APP = "app/src/main/java/com/filmiqoo/app/"
changed = []

def replace(text, old, new, count=1):
    if old == new:
        return text
    found = text.count(old)
    if found == 0 and new in text:
        return text
    if found != count:
        raise RuntimeError(f"Expected {count} occurrences, found {found}: {old[:100]!r}")
    return text.replace(old, new)

def span(text, start, end, replacement):
    a, b = text.find(start), text.find(end, text.find(start) + len(start))
    if a < 0 or b < 0:
        if replacement in text:
            return text
        raise RuntimeError(f"Missing section: {start}")
    return text[:a] + replacement + text[b:]

def edit(path, transform):
    file = ROOT / path
    before = file.read_text(encoding="utf-8")
    after = transform(before)
    if after != before:
        file.write_text(after, encoding="utf-8")
        changed.append(path)
        print(path)

def main_activity(s):
    s = replace(s, "FilmiqooApp(initialDeepLink=deepLinkState.value)", "IranAccessGate { FilmiqooApp(initialDeepLink=deepLinkState.value) }")
    for old, new in [("PremiumHomeScreen(", "CinemaHomeScreen("), ("StreamingExploreScreen(", "CinemaDiscoverScreen("),
                     ("PremiumDetailScreen(", "CinemaDetailScreen("), ("PremiumCreateHubScreen(", "CinemaCreateScreen("),
                     ("LibraryScreen(", "CinemaLibraryScreen(")]:
        # Destination calls only. Do not rename an already-integrated CinemaLibraryScreen.
        s = re.sub(r"(?<![A-Za-z])" + re.escape(old), new, s)
    s = replace(s, "onClips={tab=2},", "onClips={overlay=OverlayRoute.Clips()},")
    start = "@Composable\nprivate fun FilmiqooBottomBar("
    end = "@Composable\nprivate fun TmdbSetupScreen("
    replacement = """@Composable
private fun FilmiqooBottomBar(
    selected:Int,
    kidsMode:Boolean=false,
    onSelected:(Int)->Unit
) {
    CinemaBottomBar(selected, kidsMode, onSelected)
}

"""
    s = span(s, start, end, replacement)
    return s

edit(APP + "MainActivity.kt", main_activity)

def metadata(s):
    s = span(s, "    suspend fun home(): HomeBundle = coroutineScope {", "    suspend fun trending():", "    suspend fun home(): HomeBundle = CinemaDataRepository(context, backend).home()\n\n")
    s = span(s, "    suspend fun detail(media: MediaItem): MediaDetail {", "    suspend fun person(", "    suspend fun detail(media: MediaItem): MediaDetail = CinemaDataRepository(context, backend).title(media).detail\n\n")
    s = replace(s, "        serverResult.getOrNull()?.let { return@withContext it }", """        serverResult.exceptionOrNull()?.let {
            if (it is IranAccessDeniedException || it is kotlinx.coroutines.CancellationException) throw it
        }
        serverResult.getOrNull()?.let { return@withContext it }""")
    s = replace(s, '"language" to "fa-IR",\n                "query" to query,', '"language" to "en-US",\n                "query" to query,')
    s = s.replace('.distinctBy { it.type to it.id }', '.distinctBy(::cinemaMediaKey)')
    s = replace(s, '            title = title.ifBlank { original.ifBlank { "بدون عنوان" } },',
        '            title = (if (obj.optString("original_language") == "fa") original.ifBlank { title } else title).ifBlank { original.ifBlank { "بدون عنوان" } },')
    s = replace(s, '                val media = parseMedia(item, fallback)', '                if (item.optString("media_type") == "person" || item.optBoolean("adult")) continue\n                val media = parseMedia(item, fallback)')
    return s
edit(APP + "TmdbRepository.kt", metadata)
edit(APP + "PremiumDetailScreen.kt", lambda s: replace(s, "private fun PremiumCommunityPanel(", "internal fun PremiumCommunityPanel("))

def composer(s):
    s = replace(s, "    onOpenStudio:()->Unit,\n    onBack:()->Unit\n)", "    onOpenStudio:()->Unit,\n    onBack:()->Unit,\n    initialKind:String?=null\n)")
    return replace(s, "CreateKind.valueOf(savedDraft.kind)", "CreateKind.valueOf(initialKind ?: savedDraft.kind)")
edit(APP + "PremiumCreateHubScreen.kt", composer)
edit(APP + "LibraryScreen.kt", lambda s: s.replace("استودیوی من", "کتابخانهٔ من"))
edit(APP + "BackendRepository.kt", lambda s: replace(s, "    private val client = OkHttpClient.Builder()", "    private val client = OkHttpClient.Builder()\n        .addInterceptor(IranAccessInterceptor())"))

def theme(s):
    for old, new in [("0xFF070708", "0xFF090B10"), ("0xFF111113", "0xFF131720"), ("0xFF18181B", "0xFF1A202B"),
                     ("0xFF202024", "0xFF232C38"), ("0xFF29292E", "0xFF303B49"), ("0xFFE50914", "0xFFFF4155"),
                     ("0xFFADB5C2", "0xFFA8B2C4")]:
        s = s.replace(old, new)
    return s
edit(APP + "Theme.kt", theme)

def gradle(s):
    s = replace(s, '        versionCode = System.getenv("FILMIQOO_VERSION_CODE")?.toIntOrNull() ?: 4', '        versionCode = System.getenv("FILMIQOO_VERSION_CODE")?.toIntOrNull() ?: 5')
    s = replace(s, '        versionName = System.getenv("FILMIQOO_VERSION_NAME") ?: "0.4-connected-preview"', '        versionName = System.getenv("FILMIQOO_VERSION_NAME") ?: "0.5-cinema-iran-rc"')
    s = replace(s, '        targetSdk = 35', '        targetSdk = 35\n        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"')
    deps = '''    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
'''
    s = replace(s, 'dependencies {\n', 'dependencies {\n' + deps)
    return s
edit("app/build.gradle.kts", gradle)

def proxy(s):
    extra = '''	"primary_release_date.gte": {},
	"primary_release_date.lte": {},
	"first_air_date.gte": {},
	"first_air_date.lte": {},
	"vote_count.gte": {},
'''
    return replace(s, 'var tmdbProxyQueryKeys = map[string]struct{}{\n', 'var tmdbProxyQueryKeys = map[string]struct{}{\n' + extra)
edit("backend/internal/server/tmdb_proxy.go", proxy)

def server(s):
    s = replace(s, "type Server struct {\n", "type Server struct {\n\tregionAccess *regionAccessPolicy\n")
    s = replace(s, "\t\tcfg: cfg,", "\t\tcfg: cfg,\n\t\tregionAccess: loadRegionAccess(cfg.Environment),")
    s = replace(s, "\tr.Use(middleware.RealIP)", "\tr.Use(s.regionStatusMiddleware)\n\tr.Use(s.enforceRegion)\n\tr.Use(middleware.RealIP)")
    s = replace(s, "func (s *Server) ready(w http.ResponseWriter, r *http.Request) {", """func (s *Server) ready(w http.ResponseWriter, r *http.Request) {
    if !s.regionAccess.ready() {
        writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status":"region access configuration unavailable"})
        return
    }""")
    return s
edit("backend/internal/server/server.go", server)

def docker(s):
    s = replace(s, "RUN CGO_ENABLED=0 GOOS=linux go build", 'ARG GEO_DATA_MONTH=2026-10\nRUN go run ./cmd/countrydata -month "$GEO_DATA_MONTH" -out /out/iran-country.json\nRUN CGO_ENABLED=0 GOOS=linux go build')
    return replace(s, "COPY --from=build /out/filmiqoo-api /filmiqoo-api", "COPY --from=build /out/filmiqoo-api /filmiqoo-api\nCOPY --from=build /out/iran-country.json /geo/iran-country.json")
edit("backend/Dockerfile", docker)

def compose(s):
    s = replace(s, '      BUILD_COMMIT: ${FILMIQOO_COMMIT:?FILMIQOO_COMMIT is required}', '      BUILD_COMMIT: ${FILMIQOO_COMMIT:?FILMIQOO_COMMIT is required}\n      IRAN_ONLY_ENABLED: "true"\n      GEO_PROXY_HEADER_SECRET: ${GEO_PROXY_HEADER_SECRET:-}\n      GEO_COUNTRY_DATA_PATH: /geo/iran-country.json')
    return replace(s, '      FILMIQOO_TLS_EMAIL: ${FILMIQOO_TLS_EMAIL:?FILMIQOO_TLS_EMAIL is required}', '      FILMIQOO_TLS_EMAIL: ${FILMIQOO_TLS_EMAIL:?FILMIQOO_TLS_EMAIL is required}\n      GEO_PROXY_HEADER_SECRET: ${GEO_PROXY_HEADER_SECRET:-}')
edit("deploy/production/docker-compose.yml", compose)
edit("deploy/production/Caddyfile", lambda s: replace(s, '    reverse_proxy api:8080', '''    reverse_proxy api:8080 {
        header_up X-Filmiqoo-Client-IP {remote_host}
        header_up X-Filmiqoo-Edge-Secret {$GEO_PROXY_HEADER_SECRET}
    }'''))

# Optional newly introduced files are present in the integration branch, not in the audit baseline.
for file in ["CinemaCollectionScreens.kt"]:
    if (ROOT / (APP + file)).exists():
        edit(APP + file, lambda s: replace(s, "import androidx.compose.foundation.background\n", "import androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable\n") if "import androidx.compose.foundation.clickable\n" not in s else s)
if (ROOT / (APP + "CinemaDataRepository.kt")).exists():
    edit(APP + "CinemaDataRepository.kt", lambda s: s.replace('.distinctBy { it.type to it.id }', '.distinctBy(::cinemaMediaKey)'))
if (ROOT / (APP + "CinemaDetailContent.kt")).exists():
    def detail(s):
        s = replace(s, 'import androidx.compose.ui.platform.LocalLayoutDirection', 'import androidx.compose.ui.platform.LocalDensity\nimport androidx.compose.ui.platform.LocalLayoutDirection') if 'import androidx.compose.ui.platform.LocalDensity' not in s else s
        return replace(s, 'val heroHeight = (maxWidth * .88f).coerceIn(310.dp, 420.dp)', 'val heroHeight = (maxWidth * .88f).coerceIn(310.dp, 420.dp) * LocalDensity.current.fontScale.coerceIn(1f, 1.7f)')
    edit(APP + "CinemaDetailContent.kt", detail)
if (ROOT / (APP + "IranAccessGate.kt")).exists():
    def gate(s):
        if 'import androidx.compose.foundation.verticalScroll' not in s:
            s = replace(s, 'import androidx.compose.foundation.background\n', 'import androidx.compose.foundation.background\nimport androidx.compose.foundation.rememberScrollState\nimport androidx.compose.foundation.verticalScroll\n')
        return replace(s, '.background(CinemaInk).safeDrawingPadding().padding(24.dp).testTag("iran-access-screen")', '.background(CinemaInk).safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("iran-access-screen")')
    edit(APP + "IranAccessGate.kt", gate)

print(f"Integrated {len(changed)} source files. Run this script again to verify idempotency.")
