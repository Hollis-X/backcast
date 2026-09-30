#!/bin/sh

root=${1:?Pass the absolute project root}
json=${2:?Pass an org.json JAR}
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT HUP INT TERM

javac -proc:none -encoding UTF-8 -source 7 -target 7 -Xlint:-options -cp "$json" -d "$build" \
    "$root"/app/src/main/java/com/mkei/backcast/agent/*.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/EditDiff.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/EditTool.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/ReadTool.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/ShellTool.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/WriteTool.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/ToolPaths.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/GoalTool.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/GetGoalTool.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/RootShell.java \
    "$root"/tests/support/android/os/Build.java \
    "$root"/tests/support/android/os/SystemClock.java \
    "$root"/tests/AgentLoopRegressionTest.java \
    "$root"/tests/GoalContractRegressionTest.java \
    "$root"/tests/ContextCompactionRegressionTest.java \
    "$root"/tests/LlmUsageRegressionTest.java \
    "$root"/tests/PromptGuardRegressionTest.java \
    "$root"/tests/FileToolRegressionTest.java
compiled=$?
if [ "$compiled" -ne 0 ]; then exit "$compiled"; fi

java -cp "$build:$json" AgentLoopRegressionTest
loop_status=$?
java -cp "$build:$json" GoalContractRegressionTest
goal_status=$?
java -cp "$build:$json" ContextCompactionRegressionTest
context_status=$?
java -cp "$build:$json" LlmUsageRegressionTest
usage_status=$?
java -cp "$build:$json" PromptGuardRegressionTest
prompt_status=$?
java -cp "$build:$json" FileToolRegressionTest
file_status=$?
java -cp "$build:$json" "$root/tests/TurnUiRegressionTest.java" "$root"
ui_status=$?
java "$root/tests/RunHubRecoveryTest.java" "$root/app/src/main/java/com/mkei/backcast/RunHub.java"
recovery_status=$?
if [ "$loop_status" -ne 0 ] || [ "$goal_status" -ne 0 ] || [ "$context_status" -ne 0 ] || [ "$usage_status" -ne 0 ] || [ "$prompt_status" -ne 0 ] || [ "$file_status" -ne 0 ] || [ "$ui_status" -ne 0 ] || [ "$recovery_status" -ne 0 ]; then exit 1; fi
