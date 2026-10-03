#!/bin/sh
root=${1:?Pass the absolute project root}
json=${2:?Pass an org.json JAR}
sdk=${BACKCAST_SDK_CLASSPATH:-}
if [ -z "$sdk" ] && [ -f "$root/app/build/agent-test-classpath.txt" ]; then
    sdk=$(cat "$root/app/build/agent-test-classpath.txt")
fi
if [ -z "$sdk" ]; then
    echo 'Run Gradle :app:writeAgentTestClasspath first, or set BACKCAST_SDK_CLASSPATH.' >&2
    exit 1
fi
json="$json:$sdk"
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT HUP INT TERM
javac -proc:none -encoding UTF-8 -source 8 -target 8 -Xlint:-options -cp "$json" -d "$build" \
    "$root"/app/src/main/java/com/mkei/backcast/agent/*.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/*.java \
    "$root"/app/src/main/java/com/mkei/backcast/ui/AgentPanelState.java \
    "$root"/tests/support/android/os/Build.java \
    "$root"/tests/support/android/os/SystemClock.java
compiled=$?
if [ "$compiled" -ne 0 ]; then exit "$compiled"; fi
java -cp "$build:$json" "$root/tests/AgentPanelRegressionTest.java" "$root"
