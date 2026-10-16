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

javac -proc:none -encoding UTF-8 -source 17 -target 17 -Xlint:-options -cp "$json" -d "$build" \
    "$root"/app/src/main/java/com/mkei/backcast/agent/*.java \
    "$root"/app/src/main/java/com/mkei/backcast/tool/*.java \
    "$root"/app/src/main/java/com/mkei/backcast/mcp/*.java \
    "$root"/tests/support/android/os/Build.java \
    "$root"/tests/support/android/os/SystemClock.java \
    "$root"/tests/AgentLoopRegressionTest.java \
    "$root"/tests/RequestPolicyRegressionTest.java \
    "$root"/tests/DiagnosticsRegressionTest.java \
    "$root"/tests/NetworkSdkRegressionTest.java \
    "$root"/tests/GoalContractRegressionTest.java \
    "$root"/tests/ContextCompactionRegressionTest.java \
    "$root"/tests/LlmUsageRegressionTest.java \
    "$root"/tests/LlmStreamLifecycleRegressionTest.java \
    "$root"/tests/OpenAiSdkRegressionTest.java \
    "$root"/app/src/main/java/com/mkei/backcast/ui/TurnTrace.java \
    "$root"/app/src/main/java/com/mkei/backcast/ui/MarkdownRenderQueue.java \
    "$root"/tests/MarkdownRenderRegressionTest.java \
    "$root"/app/src/main/java/com/mkei/backcast/ui/AgentPanelState.java \
    "$root"/tests/SummaryPreferencesRegressionTest.java \
    "$root"/tests/PromptGuardRegressionTest.java \
    "$root"/tests/FileToolRegressionTest.java \
    "$root"/tests/FileSearchRegressionTest.java \
    "$root"/tests/support/RecordFixtures.java \
    "$root"/tests/SubAgentRegressionTest.java \
    "$root"/tests/SubAgentLoopIntegrationTest.java \
    "$root"/tests/SubAgentCommunicationRegressionTest.java \
    "$root"/tests/SubAgentProgressRegressionTest.java \
    "$root"/tests/SubAgentTaskLifecycleRegressionTest.java \
    "$root"/tests/ToolkitRegressionTest.java \
    "$root"/tests/RootToolRegressionTest.java \
    "$root"/tests/ToolchainFixtures.java \
    "$root"/tests/ToolkitDiagnosticsRegressionTest.java \
    "$root"/tests/ProgramArgumentRegressionTest.java \
    "$root"/tests/MultipleWorkspaceRegressionTest.java \
    "$root"/tests/ToolBatchProbeRegressionTest.java \
    "$root"/tests/ToolkitOperationManagerRegressionTest.java \
    "$root"/tests/ToolkitOperationRegressionTest.java \
    "$root"/tests/UiSnapshotRegressionTest.java \
    "$root"/tests/PlanToolRegressionTest.java \
    "$root"/tests/AskUserToolRegressionTest.java \
    "$root"/tests/LineDeltaRegressionTest.java \
    "$root"/tests/LlmUrlRegressionTest.java
compiled=$?
if [ "$compiled" -ne 0 ]; then exit "$compiled"; fi

# Optional third argument runs a single test by name, e.g. AgentLoopRegressionTest.
only=${3:-}
status=0
run() {
    name=$1; shift
    if [ -n "$only" ] && [ "$only" != "$name" ]; then return; fi
    "$@" || { echo "FAILED: $name" >&2; status=1; }
}
cls() { run "$1" java -cp "$build:$json" "$@"; }

cls AgentLoopRegressionTest
cls RequestPolicyRegressionTest
cls DiagnosticsRegressionTest
cls NetworkSdkRegressionTest
cls GoalContractRegressionTest
cls ContextCompactionRegressionTest
cls LlmUsageRegressionTest
cls LlmStreamLifecycleRegressionTest
cls OpenAiSdkRegressionTest "$root"
cls MarkdownRenderRegressionTest "$root"
cls SummaryPreferencesRegressionTest "$root"
cls PromptGuardRegressionTest
cls FileToolRegressionTest
cls com.mkei.backcast.tool.FileSearchRegressionTest
cls SubAgentRegressionTest
cls SubAgentLoopIntegrationTest
cls SubAgentCommunicationRegressionTest
cls SubAgentProgressRegressionTest
cls SubAgentTaskLifecycleRegressionTest
cls com.mkei.backcast.tool.ToolkitRegressionTest
cls com.mkei.backcast.tool.RootToolRegressionTest
cls com.mkei.backcast.tool.ToolkitDiagnosticsRegressionTest
cls com.mkei.backcast.tool.MultipleWorkspaceRegressionTest
cls com.mkei.backcast.tool.ToolBatchProbeRegressionTest
cls com.mkei.backcast.tool.ToolkitOperationManagerRegressionTest
cls com.mkei.backcast.tool.ToolkitOperationRegressionTest
cls com.mkei.backcast.tool.ProgramArgumentRegressionTest
cls UiSnapshotRegressionTest
cls PlanToolRegressionTest
cls AskUserToolRegressionTest
cls LineDeltaRegressionTest
cls LlmUrlRegressionTest
run SQLiteSubAgentStoreRegressionTest java -cp "$build:$json" "$root/tests/SQLiteSubAgentStoreRegressionTest.java" "$root"
run ChatStorePagingRegressionTest java -cp "$build:$json" "$root/tests/ChatStorePagingRegressionTest.java" "$root"
run McpRegressionTest java -cp "$build:$json" "$root/tests/McpRegressionTest.java" "$root"
run RunHubRecoveryTest java -cp "$json" "$root/tests/RunHubRecoveryTest.java" "$root/app/src/main/java/com/mkei/backcast/RunHub.java"
run ResponsePreferencesRegressionTest java "$root/tests/ResponsePreferencesRegressionTest.java" "$root"
run ResourceReachabilityCheck python3 -B "$root/tests/ResourceReachabilityCheck.py" "$root"
exit "$status"
