#!/usr/bin/env bash
# Runs the instrumented tests on the CI emulator and prints test logs and screenshots (base64 JPEG chunks
# tagged KSHOT) so they can be reviewed from the job log.
set -u
timeout 30 adb logcat -G 16M || true
timeout 30 adb logcat -c || true
# Stream the log to a file on the runner: when the emulator dies mid-test, `adb logcat -d` has nothing to read any more,
# but this file keeps everything up to the last line the emulator wrote.
LOGFILE="${RUNNER_TEMP:-/tmp}/kinesis-logcat.txt"
adb logcat -v time > "$LOGFILE" 2>&1 &
LOGCAT_PID=$!
# The emulator's own launcher often answers "isn't responding" in the middle of a test and the dialog ends up in the
# screenshots: close system dialogs, hide background ANRs and switch the launcher off (the tests start the app directly).
timeout 30 adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS || true
timeout 30 adb shell settings put secure anr_show_background 0 || true
timeout 30 adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true
./gradlew --no-daemon :app:connectedDebugAndroidTest
status=$?
sleep 2
kill "$LOGCAT_PID" 2>/dev/null || true
echo "::group::Kinesis logs"
grep -E "I/KTEST|[EW]/(AndroidRuntime|TestRunner|Kinesis[A-Za-z]*) *\(" "$LOGFILE" | tail -n 400 || true
echo "::endgroup::"
if [ "$status" -ne 0 ]; then
  echo "::group::Last 120 lines of logcat before the failure"
  tail -n 120 "$LOGFILE" || true
  echo "::endgroup::"
fi
echo "===KSHOT-BEGIN==="
# `timeout`: with the emulator gone, adb waits for a device forever (that is what held the job until its time limit).
timeout 30 adb logcat -d -v raw -s KSHOT:I || true
echo "===KSHOT-END==="
exit $status
