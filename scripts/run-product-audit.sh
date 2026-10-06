#!/usr/bin/env bash
set -euo pipefail
mkdir -p audit-evidence
audit_batch="${1:-all}"
case "$audit_batch" in
  core) audit_specs='320x800:1.0:gesture 360x800:1.0:gesture 393x852:1.0:gesture 412x915:1.0:gesture' ;;
  large) audit_specs='480x960:1.0:gesture 600x960:1.0:gesture 840x1100:1.0:gesture 840x393:1.0:gesture' ;;
  access) audit_specs='393x852:2.0:gesture 320x800:2.0:gesture 393x852:1.0:threebutton 393x852:1.0:keyboard' ;;
  all) audit_specs='320x800:1.0:gesture 360x800:1.0:gesture 393x852:1.0:gesture 412x915:1.0:gesture 480x960:1.0:gesture 600x960:1.0:gesture 840x1100:1.0:gesture 840x393:1.0:gesture 393x852:2.0:gesture 320x800:2.0:gesture 393x852:1.0:threebutton 393x852:1.0:keyboard' ;;
  *) exit 2 ;;
esac
# Each CI batch has a fresh emulator. Keep the complete twelve-profile matrix,
# while avoiding a long-lived graphics process across all window reconfigurations.
for spec in $audit_specs; do
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
  free -m > "audit-evidence/host-memory-$label-before.txt"
  if ! gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.filmiqoo.app.ProductAuditTest -Pandroid.testInstrumentationRunnerArguments.auditLabel="$label" -Pandroid.testInstrumentationRunnerArguments.auditStage="${FILMIQOO_AUDIT_STAGE:-after}" --stacktrace; then
    free -m > "audit-evidence/host-memory-$label-failure.txt"
    ps -eo pid,comm,rss > "audit-evidence/host-processes-$label-failure.txt"
    adb devices -l > "audit-evidence/devices-$label-failure.txt"
    exit 1
  fi
  cp -r app/build/outputs/connected_android_test_additional_output "audit-evidence/$label"
  adb shell settings get secure navigation_mode > "audit-evidence/$label/navigation-mode.txt"
done
if [[ "$audit_batch" != core && "$audit_batch" != all ]]; then exit 0; fi
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
