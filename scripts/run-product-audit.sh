#!/usr/bin/env bash
set -euo pipefail
mkdir -p audit-evidence
for spec in 320x800:1.0:gesture 360x800:1.0:gesture 393x852:1.0:gesture 412x915:1.0:gesture 480x960:1.0:gesture 600x960:1.0:gesture 840x1100:1.0:gesture 840x393:1.0:gesture 393x852:2.0:gesture 320x800:2.0:gesture 393x852:1.0:threebutton 393x852:1.0:keyboard; do
  IFS=: read -r size scale mode <<< "$spec"
  label="$size-font$scale-$mode"
  adb shell wm density 160
  adb shell wm size "$size"
  adb shell settings put system font_scale "$scale"
  if [[ "$mode" == threebutton ]]; then
    adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
  else
    adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
  fi
  gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.filmiqoo.app.ProductAuditTest -Pandroid.testInstrumentationRunnerArguments.auditLabel="$label" --stacktrace
  cp -r app/build/outputs/connected_android_test_additional_output "audit-evidence/$label"
  adb shell settings get secure navigation_mode > "audit-evidence/$label/navigation-mode.txt"
done
# TTID includes the first splash frame, not login/network readiness. No performance percentage is inferred.
adb shell wm size 393x852
adb shell settings put system font_scale 1.0
package=com.filmiqoo.previewfix.preview
adb install -r app/build/outputs/apk/debug/app-debug.apk
for n in 1 2 3 4 5; do
  adb shell am force-stop "$package"
  adb shell am start -W -n "$package/com.filmiqoo.app.MainActivity" > "audit-evidence/startup-$n.txt"
  sleep 2
done
adb shell dumpsys meminfo "$package" > audit-evidence/main-activity-memory.txt
adb shell dumpsys gfxinfo "$package" > audit-evidence/main-activity-frames.txt
