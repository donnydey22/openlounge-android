#!/bin/bash
set -x
OUT=out/api$API; mkdir -p $OUT
P=com.donnydey.openlounge
adb install -r debug.apk
# cold launches (WebView starts behind the native launch overlay), 4 frames each
for k in 0 1 2 3; do
  adb shell am force-stop $P; adb shell am start -W -n $P/.MainActivity > /dev/null 2>&1; sleep 35
  for f in 0 1 2 3; do adb exec-out screencap -p > $OUT/cold-$k-$f.png; sleep 1; done
done
PID=$(adb shell pidof $P | tr -d '\r')
adb forward tcp:9222 localabstract:webview_devtools_remote_$PID
OUT=$OUT N=5 node drive.mjs > $OUT/drive.log 2>&1
exit 0
