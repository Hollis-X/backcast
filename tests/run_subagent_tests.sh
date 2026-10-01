#!/bin/sh
root=${1:?Pass the absolute project root}
json=${2:?Pass an org.json JAR}
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT HUP INT TERM
javac -proc:none -encoding UTF-8 -source 7 -target 7 -Xlint:-options -cp "$json" -d "$build" \
    "$root"/app/src/main/java/com/mkei/backcast/agent/*.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/SubAgentTools.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/GoalTool.java \
    "$root"/tests/support/android/os/SystemClock.java \
    "$root"/tests/SubAgentRegressionTest.java \
    "$root"/tests/SubAgentLoopIntegrationTest.java \
    "$root"/tests/SubAgentCommunicationRegressionTest.java
compiled=$?
if [ "$compiled" -ne 0 ]; then exit "$compiled"; fi
java -cp "$build:$json" SubAgentRegressionTest
core_status=$?
java -cp "$build:$json" SubAgentLoopIntegrationTest
integration_status=$?
java -cp "$build:$json" SubAgentCommunicationRegressionTest
communication_status=$?
if [ "$core_status" -ne 0 ] || [ "$integration_status" -ne 0 ] || [ "$communication_status" -ne 0 ]; then exit 1; fi
