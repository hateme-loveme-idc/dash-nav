#!/bin/bash
# Host (JVM) tests for the pure-Java parts of the app.
set -e; cd "$(dirname "$0")"; export PATH=/opt/java/bin:$PATH
rm -rf out && mkdir out
javac -d out ../app/src/main/java/nav/dash/ObdParse.java ObdParseTest.java ../app/src/main/java/nav/dash/NavText.java ../app/src/main/java/nav/dash/NotifDump.java NotifDumpTest.java ../app/src/main/java/nav/dash/link/VideoRtp.java NavTextTest.java VideoRtpTest.java
java -cp out nav.dash.NavTextTest
java -cp out nav.dash.NotifDumpTest
java -cp out nav.dash.link.VideoRtpTest
java -cp out nav.dash.ObdParseTest
