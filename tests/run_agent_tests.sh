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
    "$root"/app/src/main/java/com/mkei/backcast/mcp/*.java \
    "$root"/tests/support/android/os/Build.java \
    "$root"/tests/support/android/os/SystemClock.java \
    "$root"/tests/AgentLoopRegressionTest.java \
    "$root"/tests/RequestPolicyRegressionTest.java \
    "$root"/tests/DiagnosticsRegressionTest.java \
    "$root"/tests/NetworkSdkRegressionTest.java \
    "$root"/tests/NetworkRoutingRegressionTest.java \
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
    "$root"/tests/TemporaryCleanupRegressionTest.java \
    "$root"/tests/ProcessIsolationRegressionTest.java \
    "$root"/tests/RootExecutionRegressionTest.java \
    "$root"/tests/TaskWorkspaceRegressionTest.java \
    "$root"/tests/ShellGuardRegressionTest.java \
    "$root"/tests/FileSubAgentStoreRegressionTest.java \
    "$root"/tests/SubAgentRegressionTest.java \
    "$root"/tests/SubAgentLoopIntegrationTest.java \
    "$root"/tests/SubAgentCommunicationRegressionTest.java \
    "$root"/tests/SubAgentProgressRegressionTest.java \
    "$root"/tests/ToolkitRegressionTest.java \
    "$root"/tests/ToolchainFixtures.java \
    "$root"/tests/ToolkitDiagnosticsRegressionTest.java \
    "$root"/tests/ToolPackageManagementRegressionTest.java \
    "$root"/tests/EmbeddedToolchainRegressionTest.java \
    "$root"/tests/EmbeddedInstallProgressRegressionTest.java \
    "$root"/tests/ArtRuntimeLauncherRegressionTest.java \
    "$root"/tests/ProgramArgumentRegressionTest.java \
    "$root"/tests/MultipleWorkspaceRegressionTest.java \
    "$root"/tests/ToolBatchProbeRegressionTest.java \
    "$root"/tests/ToolkitOperationRegressionTest.java \
    "$root"/tests/ObjectionBootstrapRegressionTest.java \
    "$root"/tests/UiSnapshotRegressionTest.java
compiled=$?
if [ "$compiled" -ne 0 ]; then exit "$compiled"; fi

if [ "${3:-}" = "snapshot" ]; then
    java -cp "$build:$json" UiSnapshotRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "root-execution" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.RootExecutionRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "file-search" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.FileSearchRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "task-workspace" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.TaskWorkspaceRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "mcp" ]; then
    java -cp "$build:$json" "$root/tests/McpRegressionTest.java" "$root" || exit "$?"
    java "$root/tests/McpConfigUiRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "request-policy" ]; then
    java -cp "$build:$json" RequestPolicyRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "network-sdk" ]; then
    java -cp "$build:$json" NetworkSdkRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "network-routing" ]; then
    java -cp "$build:$json" NetworkRoutingRegressionTest "$root" || exit "$?"
    java -cp "$build:$json" "$root/tests/DeviceNetworkPolicyRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "markdown" ]; then
    java -cp "$build:$json" MarkdownRenderRegressionTest "$root" || exit "$?"
    java -cp "$build:$json" "$root/tests/TurnUiRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "diagnostics" ]; then
    java -cp "$build:$json" DiagnosticsRegressionTest || exit "$?"
    java -cp "$build:$json" "$root/tests/ChatStorePagingRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "slash" ]; then
    java -cp "$build:$json" "$root/tests/SlashMenuUiRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "model-picker" ]; then
    java -cp "$build:$json" "$root/tests/AiModelPickerUiRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "http" ]; then
    java -cp "$build:$json" OpenAiSdkRegressionTest "$root" || exit "$?"
    java -cp "$build:$json" LlmUsageRegressionTest || exit "$?"
    java -cp "$build:$json" LlmStreamLifecycleRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "child-progress" ]; then
    java -cp "$build:$json" SubAgentProgressRegressionTest
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
if [ "${3:-}" = "toolkit" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.ToolkitRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "toolkit-diagnostics" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.ToolkitDiagnosticsRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "tool-package" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.ToolPackageManagementRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "settings" ]; then
    java "$root/tests/SettingsNavigationRegressionTest.java" "$root" || exit "$?"
    java "$root/tests/ResponsePreferencesRegressionTest.java" "$root" || exit "$?"
    java "$root/tests/SettingsChoiceRegressionTest.java" "$root" || exit "$?"
    java "$root/tests/AiConfigRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "multidex" ]; then
    java "$root/tests/MultiDexConfigRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "resource-budget" ]; then
    java "$root/tests/AideResourceBudgetRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "resources" ]; then
    python3 -B "$root/tests/ResourceReachabilityCheck.py" "$root"
    exit "$?"
fi
if [ "${3:-}" = "install-progress" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.EmbeddedInstallProgressRegressionTest || exit "$?"
    java "$root/tests/ToolInstallProgressUiRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "program-arguments" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.ProgramArgumentRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "art-runtime" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.ArtRuntimeLauncherRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "workspaces" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.MultipleWorkspaceRegressionTest || exit "$?"
    java "$root/tests/WorkspaceRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "batch-probe" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.ToolBatchProbeRegressionTest || exit "$?"
    java -cp "$build:$json" "$root/tests/ToolBatchProbeUiRegressionTest.java" "$root"
    exit "$?"
fi
if [ "${3:-}" = "toolkit-operation" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.ToolkitOperationRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "objection-bootstrap" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.ObjectionBootstrapRegressionTest "$root"
    exit "$?"
fi
if [ "${3:-}" = "shell" ]; then
    java -cp "$build:$json" com.mkei.backcast.tool.ShellGuardRegressionTest
    exit "$?"
fi
if [ "${3:-}" = "temporary" ]; then
    java -cp "$build:$json" TemporaryCleanupRegressionTest
    exit "$?"
fi

java -cp "$build:$json" AgentLoopRegressionTest
loop_status=$?
java -cp "$build:$json" RequestPolicyRegressionTest
request_policy_status=$?
java -cp "$build:$json" DiagnosticsRegressionTest
error_storage_status=$?
java -cp "$build:$json" NetworkSdkRegressionTest
network_sdk_status=$?
java -cp "$build:$json" NetworkRoutingRegressionTest "$root"
network_routing_status=$?
java -cp "$build:$json" "$root/tests/DeviceNetworkPolicyRegressionTest.java" "$root"
device_network_status=$?
java -cp "$build:$json" MarkdownRenderRegressionTest "$root"
markdown_status=$?
java -cp "$build:$json" GoalContractRegressionTest
goal_status=$?
java -cp "$build:$json" ContextCompactionRegressionTest
context_status=$?
java -cp "$build:$json" LlmUsageRegressionTest
usage_status=$?
java -cp "$build:$json" LlmStreamLifecycleRegressionTest
stream_status=$?
java -cp "$build:$json" OpenAiSdkRegressionTest "$root"
http_runtime_status=$?
java -cp "$build:$json" SummaryPreferencesRegressionTest "$root"
summary_status=$?
java -cp "$build:$json" PromptGuardRegressionTest
prompt_status=$?
java -cp "$build:$json" FileToolRegressionTest
file_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.FileSearchRegressionTest
file_search_status=$?
java -cp "$build:$json" TemporaryCleanupRegressionTest
temporary_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.ProcessIsolationRegressionTest
process_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.RootExecutionRegressionTest
root_execution_status=$?
if [ "$root_execution_status" -ne 0 ]; then exit 1; fi
java -cp "$build:$json" com.mkei.backcast.tool.TaskWorkspaceRegressionTest || exit "$?"
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
java -cp "$build:$json" SubAgentProgressRegressionTest
child_progress_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.ToolkitRegressionTest
toolkit_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.ToolkitDiagnosticsRegressionTest
diagnostics_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.ToolPackageManagementRegressionTest
package_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.EmbeddedToolchainRegressionTest "$root"
embedded_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.EmbeddedInstallProgressRegressionTest
install_progress_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.ProgramArgumentRegressionTest
program_arguments_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.ArtRuntimeLauncherRegressionTest
art_runtime_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.MultipleWorkspaceRegressionTest
workspace_status=$?
java "$root/tests/WorkspaceRegressionTest.java" "$root"
workspace_ui_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.ToolBatchProbeRegressionTest
batch_probe_status=$?
java -cp "$build:$json" "$root/tests/ToolBatchProbeUiRegressionTest.java" "$root"
batch_probe_ui_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.ToolkitOperationRegressionTest
toolkit_operation_status=$?
java -cp "$build:$json" com.mkei.backcast.tool.ObjectionBootstrapRegressionTest "$root"
objection_bootstrap_status=$?
java "$root/tests/ToolInstallProgressUiRegressionTest.java" "$root"
install_ui_status=$?
java -cp "$build:$json" UiSnapshotRegressionTest
snapshot_status=$?
java -cp "$build:$json" "$root/tests/TurnUiRegressionTest.java" "$root"
ui_status=$?
java -cp "$build:$json" "$root/tests/AgentPanelRegressionTest.java" "$root"
panel_status=$?
java -cp "$build:$json" "$root/tests/AiModelPickerUiRegressionTest.java" "$root"
model_picker_status=$?
java -cp "$build:$json" "$root/tests/SlashMenuUiRegressionTest.java" "$root"
slash_ui_status=$?
java -cp "$build:$json" "$root/tests/ChatStorePagingRegressionTest.java" "$root"
paging_status=$?
java -cp "$json" "$root/tests/RunHubRecoveryTest.java" "$root/app/src/main/java/com/mkei/backcast/RunHub.java"
recovery_status=$?
java "$root/tests/ResponsePreferencesRegressionTest.java" "$root"
preferences_status=$?
java "$root/tests/SettingsChoiceRegressionTest.java" "$root"
choices_status=$?
java "$root/tests/SettingsNavigationRegressionTest.java" "$root"
navigation_status=$?
java "$root/tests/AiConfigRegressionTest.java" "$root"
ai_config_status=$?
java "$root/tests/MultiDexConfigRegressionTest.java" "$root"
multidex_status=$?
java "$root/tests/AideResourceBudgetRegressionTest.java" "$root"
resource_budget_status=$?
java "$root/tests/TranscriptScrollRegressionTest.java" "$root"
scroll_status=$?
python3 -B "$root/tests/ResourceReachabilityCheck.py" "$root"
resource_reachability_status=$?
java -cp "$build:$json" "$root/tests/McpRegressionTest.java" "$root"
mcp_status=$?
java "$root/tests/McpConfigUiRegressionTest.java" "$root"
mcp_ui_status=$?
if [ "$slash_ui_status" -ne 0 ]; then exit 1; fi
if [ "$mcp_ui_status" -ne 0 ]; then exit 1; fi
if [ "$mcp_status" -ne 0 ]; then exit 1; fi
if [ "$resource_reachability_status" -ne 0 ]; then exit 1; fi
if [ "$network_routing_status" -ne 0 ] || [ "$device_network_status" -ne 0 ] || [ "$markdown_status" -ne 0 ] || [ "$objection_bootstrap_status" -ne 0 ]; then exit 1; fi
if [ "$diagnostics_status" -ne 0 ] || [ "$navigation_status" -ne 0 ] || [ "$ai_config_status" -ne 0 ] || [ "$package_status" -ne 0 ] || [ "$multidex_status" -ne 0 ] || [ "$resource_budget_status" -ne 0 ] || [ "$install_progress_status" -ne 0 ] || [ "$program_arguments_status" -ne 0 ] || [ "$art_runtime_status" -ne 0 ] || [ "$install_ui_status" -ne 0 ] || [ "$workspace_status" -ne 0 ] || [ "$workspace_ui_status" -ne 0 ] || [ "$batch_probe_status" -ne 0 ] || [ "$batch_probe_ui_status" -ne 0 ] || [ "$toolkit_operation_status" -ne 0 ]; then exit 1; fi
if [ "$network_sdk_status" -ne 0 ] || [ "$model_picker_status" -ne 0 ] || [ "$error_storage_status" -ne 0 ] || [ "$http_runtime_status" -ne 0 ] || [ "$request_policy_status" -ne 0 ] || [ "$loop_status" -ne 0 ] || [ "$goal_status" -ne 0 ] || [ "$context_status" -ne 0 ] || [ "$usage_status" -ne 0 ] || [ "$stream_status" -ne 0 ] || [ "$summary_status" -ne 0 ] || [ "$prompt_status" -ne 0 ] || [ "$file_status" -ne 0 ] || [ "$file_search_status" -ne 0 ] || [ "$temporary_status" -ne 0 ] || [ "$process_status" -ne 0 ] || [ "$root_execution_status" -ne 0 ] || [ "$shell_status" -ne 0 ] || [ "$child_store_status" -ne 0 ] || [ "$subagent_status" -ne 0 ] || [ "$subagent_loop_status" -ne 0 ] || [ "$communication_status" -ne 0 ] || [ "$child_progress_status" -ne 0 ] || [ "$toolkit_status" -ne 0 ] || [ "$embedded_status" -ne 0 ] || [ "$snapshot_status" -ne 0 ] || [ "$ui_status" -ne 0 ] || [ "$panel_status" -ne 0 ] || [ "$paging_status" -ne 0 ] || [ "$recovery_status" -ne 0 ] || [ "$preferences_status" -ne 0 ] || [ "$choices_status" -ne 0 ] || [ "$scroll_status" -ne 0 ]; then exit 1; fi
