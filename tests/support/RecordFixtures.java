import com.mkei.backcast.agent.SubAgentManager;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Fixtures describe a child compactly with {@code task}, {@code pending} and {@code result}.
 * This expands them into the task/result ledger that production records always carry.
 */
final class RecordFixtures {
    private RecordFixtures() { }

    static SubAgentManager.Record fromJson(JSONObject json) throws Exception {
        return modern(SubAgentManager.Record.fromJson(json));
    }

    static List<SubAgentManager.Record> modern(List<SubAgentManager.Record> records) throws JSONException {
        List<SubAgentManager.Record> result = new ArrayList<SubAgentManager.Record>();
        for (SubAgentManager.Record record : records) result.add(modern(record));
        return result;
    }

    static SubAgentManager.Record modern(SubAgentManager.Record task) throws JSONException {
        if (SubAgentManager.ROOT.equals(task.id) || task.tasks.length() != 0
                || task.task.length() == 0 && task.pending.length() == 0 && task.inFlight.length() == 0) return task;
        task.currentTaskId = "task_" + task.id + "_fixture";
        JSONObject attempt = new JSONObject().put("taskId", task.currentTaskId).put("request", task.task)
                .put("status", task.status).put("phase", task.phase).put("progress", task.progress)
                .put("partial", "").put("error", task.error).put("createdAt", task.lastActivityAt);
        task.tasks.put(attempt);
        for (int i = 0; i < task.pending.length(); i++) {
            JSONObject pending = task.pending.getJSONObject(i);
            boolean firstUnstarted = i == 0 && task.inFlight.length() == 0
                    && task.result.length() == 0 && task.error.length() == 0;
            String pendingId = firstUnstarted ? task.currentTaskId : "task_" + task.id + "_fixture_pending_" + i;
            pending.put("kind", "task").put("taskId", pendingId);
            if (firstUnstarted) attempt.put("request", pending.optString("text", task.task)).put("status", SubAgentManager.QUEUED).put("phase", SubAgentManager.QUEUED);
            else task.tasks.put(new JSONObject().put("taskId", pendingId).put("request", pending.optString("text", ""))
                    .put("status", SubAgentManager.QUEUED).put("phase", SubAgentManager.QUEUED).put("progress", "").put("partial", "")
                    .put("error", "").put("createdAt", task.lastActivityAt));
        }
        if (task.result.length() > 0 || task.error.length() > 0) {
            String resultId = "result_" + task.id + "_fixture";
            attempt.put("resultId", resultId);
            task.results.put(new JSONObject().put("resultId", resultId).put("taskId", task.currentTaskId)
                    .put("status", task.error.length() == 0 ? "completed" : task.status)
                    .put("content", task.result).put("error", task.error).put("partial", "")
                    .put("createdAt", task.lastActivityAt));
        }
        return task;
    }
}
