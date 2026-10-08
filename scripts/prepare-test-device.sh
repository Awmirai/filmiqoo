#!/usr/bin/env bash
set -euo pipefail
# Only isolate the disposable AVD's unrelated launcher. Never run this setup on
# a physical phone, and never suppress application or system error dialogs.
test_qemu=$(adb shell getprop ro.kernel.qemu | tr -d '\r')
test_boot_qemu=$(adb shell getprop ro.boot.qemu | tr -d '\r')
if [[ "$test_qemu" != 1 && "$test_boot_qemu" != 1 ]]; then
  echo 'Test-device preparation requires an Android emulator.' >&2
  exit 1
fi
test_launcher=$(adb shell pm list packages com.google.android.apps.nexuslauncher | tr -d '\r')
if [[ "$test_launcher" == package:com.google.android.apps.nexuslauncher ]]; then
  adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher
  adb shell am force-stop com.google.android.apps.nexuslauncher
fi
adb shell settings put secure immersive_mode_confirmations confirmed
