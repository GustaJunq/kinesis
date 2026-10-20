#!/usr/bin/env bash
# Runs only the UI smoke test on the CI emulator and turns its screenshots (logged as KSHOT|name|i|count|base64) into
# .jpg files in $SHOTS_DIR, which the workflow uploads as an artifact. The shots are also printed in the job log.
set -u
SHOTS_DIR="${SHOTS_DIR:-shots}"
mkdir -p "$SHOTS_DIR"
timeout 30 adb logcat -G 16M || true
timeout 30 adb logcat -c || true
LOGFILE="${RUNNER_TEMP:-/tmp}/kinesis-logcat.txt"
adb logcat -v time > "$LOGFILE" 2>&1 &
LOGCAT_PID=$!
# Keep the emulator's own launcher out of the pictures ("isn't responding" dialogs).
timeout 30 adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true
timeout 30 adb shell settings put secure anr_show_background 0 || true
timeout 30 adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true
./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.kinesis.editor.UiSmokeTest
status=$?
sleep 2
kill "$LOGCAT_PID" 2>/dev/null || true
python3 - "$LOGFILE" "$SHOTS_DIR" <<'PY'
import base64, collections, re, sys
log, out = sys.argv[1], sys.argv[2]
chunks = collections.defaultdict(dict)
for m in re.finditer(r"KSHOT\|([^|]+)\|(\d+)\|(\d+)\|([A-Za-z0-9+/=]+)", open(log, errors="replace").read()):
    chunks[m.group(1)][int(m.group(2))] = (int(m.group(3)), m.group(4))
for name, parts in chunks.items():
    count = next(iter(parts.values()))[0]
    if len(parts) != count:
        print(f"incomplete {name}: {len(parts)}/{count}")
        continue
    data = base64.b64decode("".join(parts[i][1] for i in range(count)))
    open(f"{out}/{name.replace('/', '_')}.jpg", "wb").write(data)
    print(f"saved {name} ({len(data)} bytes)")
PY
# The artifact is the main output; the same shots go to the job log too, so they can be read without downloading it.
echo "===KSHOT-BEGIN==="
grep -o 'KSHOT|[^ ]*' "$LOGFILE" || true
echo "===KSHOT-END==="
exit "$status"
