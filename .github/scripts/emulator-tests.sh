#!/usr/bin/env bash
# Runs the instrumented tests on the CI emulator and prints test logs and screenshots (base64 JPEG chunks
# tagged KSHOT) so they can be reviewed from the job log.
set -u
adb logcat -G 16M || true
adb logcat -c || true
./gradlew --no-daemon :app:connectedDebugAndroidTest
status=$?
echo "::group::Kinesis logs"
adb logcat -d -v raw -s KTEST:I AndroidRuntime:E TestRunner:E Kinesis:W KinesisGL:W KinesisVideo:W KinesisExport:W KinesisAudio:W
echo "::endgroup::"
echo "===KSHOT-BEGIN==="
adb logcat -d -v raw -s KSHOT:I
echo "===KSHOT-END==="
exit $status
