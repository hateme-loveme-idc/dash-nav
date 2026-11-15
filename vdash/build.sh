#!/bin/bash
# Build the virtual dash: classes for the server JVM, classes.dex for Android (app_process).
set -e; cd "$(dirname "$0")"
export PATH=/opt/java/bin:$PATH
rm -rf build && mkdir -p build/classes
javac --release 11 -d build/classes src/VDash.java
/opt/android-sdk/build-tools/36.0.0/d8 --min-api 35 --output build build/classes/*.class
echo "built: build/classes (JVM) + build/classes.dex (Android)"
