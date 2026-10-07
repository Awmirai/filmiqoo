#!/usr/bin/env bash
set -euo pipefail
mkdir -p experience-evidence
(
  for native_sample in $(seq 1 900); do
    date -u '+%Y-%m-%dT%H:%M:%SZ'
    free -m
    ps -eo pid,comm,state,rss | awk 'NR == 1 || $2 ~ /qemu|emulator|java/'
    for native_memory in memory.current memory.max memory.events; do
      if [[ -f /sys/fs/cgroup/$native_memory ]]; then cat "/sys/fs/cgroup/$native_memory"; fi
    done
    timeout 2s adb get-state 2>&1 || true
    sleep 2
  done
) > experience-evidence/host-timeline.txt &
native_monitor_pid=$!
capture_native_exit() {
  native_status=$?
  kill "$native_monitor_pid" 2>/dev/null || true
  wait "$native_monitor_pid" 2>/dev/null || true
  if [[ -f /tmp/filmiqoo-functional-qemu.log ]]; then
    tail -n 600 /tmp/filmiqoo-functional-qemu.log > experience-evidence/qemu-output-tail.txt
  fi
  free -m > experience-evidence/host-memory-after.txt
  adb devices -l > experience-evidence/devices-after.txt 2>&1 || true
  if [[ "$native_status" != 0 ]]; then
    { timeout 10s sudo -n dmesg --ctime 2>&1 || true; } | tail -n 200 > experience-evidence/kernel-tail.txt
    { timeout 10s sudo -n journalctl -k --since '10 minutes ago' --no-pager 2>&1 || true; } | tail -n 200 > experience-evidence/journal-kernel-tail.txt
  fi
  exit "$native_status"
}
trap capture_native_exit EXIT
gradle :app:connectedDebugAndroidTest --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx2g -Dfile.encoding=UTF-8" -Pkotlin.compiler.execution.strategy=in-process \
  -Pandroid.testInstrumentationRunnerArguments.notClass=com.filmiqoo.app.ProductAuditTest,com.filmiqoo.app.ProductAudit090Test --stacktrace
# A lost device or incomplete inventory must never be reported as a successful test run.
python3 - <<'PY'
import json
from pathlib import Path
from xml.etree import ElementTree as ET
files=list(Path('app/build/outputs/androidTest-results/connected').rglob('TEST-*.xml'))
summary={key:sum(int(ET.parse(p).getroot().get(key,'0')) for p in files) for key in ('tests','failures','errors','skipped')}
Path('experience-evidence/inventory.json').write_text(json.dumps(summary,indent=2),encoding='utf-8')
if summary != {'tests':90,'failures':0,'errors':0,'skipped':0}:
    raise SystemExit('Unexpected or incomplete native test inventory: '+str(summary))
print('Verified complete native test inventory:',summary)
PY
