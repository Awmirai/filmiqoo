#!/usr/bin/env bash
set -euo pipefail
mkdir -p audit-evidence
audit_batch="${1:-all}"
case "$audit_batch" in
  core) audit_specs='320x800:1.0:gesture 360x800:1.0:gesture 393x852:1.0:gesture 412x915:1.0:gesture' ;;
  large) audit_specs='480x960:1.0:gesture 600x960:1.0:gesture 840x1100:1.0:gesture' ;;
  landscape) audit_specs='840x393:1.0:gesture' ;;
  access) audit_specs='393x852:2.0:gesture 320x800:2.0:gesture 393x852:1.0:threebutton 393x852:1.0:keyboard' ;;
  all) audit_specs='320x800:1.0:gesture 360x800:1.0:gesture 393x852:1.0:gesture 412x915:1.0:gesture 480x960:1.0:gesture 600x960:1.0:gesture 840x1100:1.0:gesture 840x393:1.0:gesture 393x852:2.0:gesture 320x800:2.0:gesture 393x852:1.0:threebutton 393x852:1.0:keyboard' ;;
  *) exit 2 ;;
esac
# Each CI batch has a fresh emulator. Keep the complete twelve-profile matrix,
# with landscape isolated from prior portrait reconfigurations.
capture_audit_failure() {
  local audit_label="$1"
  local audit_diagnostics="audit-evidence/native-$audit_label"
  local audit_crash_root=/tmp/android-runner
  mkdir -p "$audit_diagnostics"
  free -m > "audit-evidence/host-memory-$audit_label-failure.txt" || true
  # Command names and RSS only; never retain process arguments or environment values.
  ps -eo pid,comm,rss --sort=-rss | head -n 81 > "audit-evidence/host-processes-$audit_label-failure.txt" || true
  adb devices -l > "audit-evidence/devices-$audit_label-failure.txt" || true
  { timeout 10s sudo -n dmesg --ctime 2>&1 || true; } | tail -n 200 > "$audit_diagnostics/kernel-tail.txt"
  { timeout 10s sudo -n journalctl -k --since '10 minutes ago' --no-pager 2>&1 || true; } | tail -n 200 > "$audit_diagnostics/journal-kernel-tail.txt"
  # Crashpad's .db path may be a directory. Inventory its bounded tree rather than
  # copying large core/minidump files or unrelated /tmp content into an artifact.
  if [[ -d "$audit_crash_root" ]]; then
    { find "$audit_crash_root" -mindepth 1 -maxdepth 4 -printf '%y %p %s bytes\n' 2>/dev/null || true; } |
      head -n 80 > "$audit_diagnostics/emulator-crash-inventory.txt" || true
    local audit_copied=0
    local audit_log
    while IFS= read -r -d '' audit_log; do
      [[ "$audit_copied" -lt 4 ]] || break
      cp -- "$audit_log" "$audit_diagnostics/emulator-$audit_copied.log" || true
      audit_copied=$((audit_copied + 1))
    done < <(find "$audit_crash_root" -maxdepth 4 -type f -name '*.log' -size -1024k -print0 2>/dev/null)
  fi
}
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
    capture_audit_failure "$label"
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
