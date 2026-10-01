#!/bin/sh

root=${1:?Pass the absolute project root}
json=${2:?Pass an org.json JAR}
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT HUP INT TERM

javac -proc:none -encoding UTF-8 -source 7 -target 7 -Xlint:-options -cp "$json" -d "$build" \
    "$root"/app/src/main/java/com/mkei/backcast/agent/*.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/*.java \
    "$root"/tests/support/android/os/Build.java \
    "$root"/tests/support/android/os/SystemClock.java \
    "$root"/tests/AgentLoopRegressionTest.java \
    "$root"/tests/GoalContractRegressionTest.java \
    "$root"/tests/ContextCompactionRegressionTest.java \
    "$root"/tests/LlmUsageRegressionTest.java \
    "$root"/tests/LlmStreamLifecycleRegressionTest.java \
    "$root"/app/src/main/java/com/mkei/backcast/ui/TurnTrace.java \
    "$root"/app/src/main/java/com/mkei/backcast/ui/AgentPanelState.java \
    "$root"/tests/SummaryPreferencesRegressionTest.java \
    "$root"/tests/PromptGuardRegressionTest.java \
    "$root"/tests/FileToolRegressionTest.java \
    "$root"/tests/TemporaryCleanupRegressionTest.java \
    "$root"/tests/ProcessIsolationRegressionTest.java \
    "$root"/tests/ShellGuardRegressionTest.java \
    "$root"/tests/FileSubAgentStoreRegressionTest.java \
    "$root"/tests/SubAgentRegressionTest.java \
    "$root"/tests/SubAgentLoopIntegrationTest.java \
    "$root"/tests/SubAgentCommunicationRegressionTest.java \
    "$root"/tests/ToolkitRegressionTest.java \
    "$root"/tests/EmbeddedToolchainRegressionTest.java \
    "$root"/tests/UiSnapshotRegressionTest.java
compiled=$?
if [ "$compiled" -ne 0 ]; then exit "$compiled"; fi

if [ "${3:-}" = "snapshot" ]; then
    java -cp "$build:$json" UiSnapshotRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "ui" ]; then
    java -cp "$build:$json" "$root/tests/TurnUiRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "panel" ]; then
    java -cp "$build:$json" "$root/tests/AgentPanelRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "embedded" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.EmbeddedToolchainRegressionTest "$root"
    exit "$?"
fi
if [ "${3:-}" = "shell" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.ShellGuardRegressionTest
    exit "$?"
fi

java -cp "$build:$json" AgentLoopRegressionTest
loop_status=$?
java -cp "$build:$json" GoalContractRegressionTest
goal_status=$?
java -cp "$build:$json" ContextCompactionRegressionTest
context_status=$?
java -cp "$build:$json" LlmUsageRegressionTest
usage_status=$?
java -cp "$build:$json" LlmStreamLifecycleRegressionTest
stream_status=$?
java -cp "$build:$json" SummaryPreferencesRegressionTest "$root"
summary_status=$?
java -cp "$build:$json" PromptGuardRegressionTest
prompt_status=$?
java -cp "$build:$json" FileToolRegressionTest
file_status=$?
java -cp "$build:$json" TemporaryCleanupRegressionTest
temporary_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.ProcessIsolationRegressionTest
process_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.ShellGuardRegressionTest
shell_status=$?
java -cp "$build:$json" FileSubAgentStoreRegressionTest
child_store_status=$?
java -cp "$build:$json" SubAgentRegressionTest
subagent_status=$?
java -cp "$build:$json" SubAgentLoopIntegrationTest
subagent_loop_status=$?
java -cp "$build:$json" SubAgentCommunicationRegressionTest
communication_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.ToolkitRegressionTest
toolkit_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.EmbeddedToolchainRegressionTest "$root"
embedded_status=$?
java -cp "$build:$json" UiSnapshotRegressionTest
snapshot_status=$?
java -cp "$build:$json" "$root/tests/TurnUiRegressionTest.java" "$root"
ui_status=$?
java -cp "$build:$json" "$root/tests/AgentPanelRegressionTest.java" "$root"
panel_status=$?
java -cp "$build:$json" "$root/tests/ChatStorePagingRegressionTest.java" "$root"
paging_status=$?
java "$root/tests/RunHubRecoveryTest.java" "$root/app/src/main/java/com/mkei/backcast/RunHub.java"
recovery_status=$?
java "$root/tests/ResponsePreferencesRegressionTest.java" "$root"
preferences_status=$?
java "$root/tests/SettingsChoiceRegressionTest.java" "$root"
choices_status=$?
java "$root/tests/TranscriptScrollRegressionTest.java" "$root"
scroll_status=$?
if [ "$loop_status" -ne 0 ] || [ "$goal_status" -ne 0 ] || [ "$context_status" -ne 0 ] || [ "$usage_status" -ne 0 ] || [ "$stream_status" -ne 0 ] || [ "$summary_status" -ne 0 ] || [ "$prompt_status" -ne 0 ] || [ "$file_status" -ne 0 ] || [ "$temporary_status" -ne 0 ] || [ "$process_status" -ne 0 ] || [ "$shell_status" -ne 0 ] || [ "$child_store_status" -ne 0 ] || [ "$subagent_status" -ne 0 ] || [ "$subagent_loop_status" -ne 0 ] || [ "$communication_status" -ne 0 ] || [ "$toolkit_status" -ne 0 ] || [ "$embedded_status" -ne 0 ] || [ "$snapshot_status" -ne 0 ] || [ "$ui_status" -ne 0 ] || [ "$panel_status" -ne 0 ] || [ "$paging_status" -ne 0 ] || [ "$recovery_status" -ne 0 ] || [ "$preferences_status" -ne 0 ] || [ "$choices_status" -ne 0 ] || [ "$scroll_status" -ne 0 ]; then exit 1; fi
