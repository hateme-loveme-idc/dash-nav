#!/usr/bin/env bash
# build dashd.jar (dex) for app_process. usage: ./build.sh
set -euo pipefail
cd "$(dirname "$0")"
SDK=/opt/android-sdk; JAR=$SDK/platforms/android-37.0/android.jar; D8=$SDK/build-tools/36.0.0/d8
rm -rf build && mkdir -p build/classes
/opt/java/bin/javac -source 11 -target 11 -Xlint:-options -cp "$JAR" -d build/classes $(find src -name '*.java')
JAVA_HOME=/opt/java PATH=/opt/java/bin:$PATH $D8 --min-api 34 --lib "$JAR" --output build $(find build/classes -name '*.class')
(cd build && zip -q dashd.jar classes.dex)
ls -la build/dashd.jar
