#!/usr/bin/env bash
# D16: the Swift seam is "dumb hands". This script fails CI when iosApp/ grows a brain.
#  1. Every Kotlin symbol used from Swift must be one of the three allowed surfaces.
#  2. No Swift file may touch the APIs that belong to Kotlin iosMain (Bluetooth, notifications,
#     CLLocationManager, timers, SQLite).
set -euo pipefail
cd "$(dirname "$0")/.."

swift_files=$(find iosApp -name '*.swift' -not -path '*/build/*')
status=0

# 1. Kotlin symbols: anything that looks like a framework type (`Northform.X` or a known exported name)
#    other than the allow-list. The framework's module name is `Northform` (app/build.gradle.kts).
allowed='NorthformApp|LocationUpdates|LocationSink|LiveActivityBridge|LiveActivityContent|LiveActivityListener|LiveActivityPhase|Cancellable'
if grep -nE 'Northform\.[A-Z][A-Za-z]+' $swift_files | grep -vE "Northform\.(${allowed})\b" ; then
    echo "check-seam: Swift references a Kotlin symbol outside the D16 allow-list" >&2
    status=1
fi

# 2. Platform decision APIs that must live in Kotlin iosMain (D8, D9, D14).
forbidden='CBCentralManager|CBPeripheral|UNUserNotificationCenter|CLLocationManager|Timer\.scheduled|DispatchSource\.makeTimerSource|sqlite3|UserDefaults'
# Code only: `///` and `//` comment lines may name these APIs to explain why they are NOT here.
if grep -nE "$forbidden" $swift_files | grep -vE '^[^:]+:[0-9]+:\s*//' ; then
    echo "check-seam: Swift touches an API reserved for Kotlin (see D8/D9/D14)" >&2
    status=1
fi

if [ $status -eq 0 ]; then echo "check-seam: ok ($(echo "$swift_files" | wc -l | tr -d ' ') Swift files)"; fi
exit $status
