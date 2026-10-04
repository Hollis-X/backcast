package com.mkei.backcast;

import android.content.DialogInterface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.Diagnostics;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.PromptGuard;
import com.mkei.backcast.agent.SubAgentManager;
import com.mkei.backcast.ui.AgentPanelState;
import com.mkei.backcast.ui.Icons;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** A session's delegated work, with a separate task, activity and result view. */
public final class SubAgentsActivity extends AppCompatActivity {
    public static final String EXTRA_SESSION_ID = "session_id";
    private final AgentPanelState state = new AgentPanelState();
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private long sessionId;
    private RunHub hub;
    private Settings settings;
    private TextView title, summary, pageLabel;
    private TextView[] tabs;
    private LinearLayout body;
    private ScrollView scroll;
    private View tabBar, pages, composer;
    private ImageButton refresh, stop, send, older, newer, latest;
    private EditText message;
    private SubAgentManager.Record selected;
    private boolean resumed, destroyed, sending, closing, dirty = true;
    private boolean readFailureAnnounced;
    private AlertDialog stopDialog;
    private int renderVersion;
    private String rendered = "";
    private final Runnable poll = new Runnable() {
        @Override public void run() { load(); }
    };

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        setContentView(R.layout.activity_sub_agents);
        sessionId = getIntent().getLongExtra(EXTRA_SESSION_ID, -1L);
        hub = RunHub.get(this); settings = new Settings(this);
        title = (TextView) findViewById(R.id.agent_panel_title);
        summary = (TextView) findViewById(R.id.agent_panel_summary);
        pageLabel = (TextView) findViewById(R.id.agent_panel_page);
        scroll = (ScrollView) findViewById(R.id.agent_panel_scroll);
        body = (LinearLayout) findViewById(R.id.agent_panel_body);
        tabBar = findViewById(R.id.agent_panel_tabs);
        pages = findViewById(R.id.agent_panel_pages);
        composer = findViewById(R.id.agent_panel_composer);
        message = (EditText) findViewById(R.id.agent_panel_message);
        refresh = icon(R.id.agent_panel_refresh, R.drawable.ic_ds_arrow_rotate_clockwise_regular_24);
        stop = icon(R.id.agent_panel_stop, Icons.STOP);
        send = icon(R.id.agent_panel_send, Icons.SEND);
        older = icon(R.id.agent_panel_older, Icons.BACK);
        newer = icon(R.id.agent_panel_newer, R.drawable.ic_ds_arrow_right_lg_regular_24);
        latest = icon(R.id.agent_panel_latest, R.drawable.ic_ds_arrow_down_lg_regular_24);
        icon(R.id.agent_panel_back, Icons.BACK).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { onBackPressed(); }
        });
        tabs = new TextView[] { (TextView) findViewById(R.id.agent_panel_task_tab),
                (TextView) findViewById(R.id.agent_panel_activity_tab),
                (TextView) findViewById(R.id.agent_panel_result_tab) };
        for (int i = 0; i < tabs.length; i++) {
            final int tab = i;
            tabs[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { state.tab = tab; renderDetail(false); }
            });
        }
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { dirty = true; load(); }
        });
        older.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { page(false); }
        });
        newer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { page(true); }
        });
        latest.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { state.historyEnd = -1; renderDetail(false); }
        });
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { sendMessage(); }
        });
        stop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (selected == null || closing || SubAgentManager.CLOSED.equals(selected.status)) return;
                stopDialog = new AlertDialog.Builder(SubAgentsActivity.this).setTitle(R.string.agent_panel_stop)
                        .setMessage(R.string.agent_panel_stop_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.agent_panel_stop, new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) { closeSelected(); }
                        }).show();
            }
        });
        if (savedState != null) {
            state.selectedId = savedState.getString("agent", "");
            state.tab = savedState.getInt("tab", AgentPanelState.TASK);
            state.historyEnd = savedState.getInt("history_end", -1);
            state.resultOffset = savedState.getInt("result_offset", 0);
            message.setText(savedState.getString("message", ""));
        }
        body.addView(text(getString(R.string.agent_panel_loading), 14, 0xFF8E8E93, false));
    }

    @Override protected void onResume() {
        super.onResume(); resumed = true; state.start(); dirty = true; load();
    }
    @Override protected void onPause() {
        resumed = false; state.stop(); body.removeCallbacks(poll); super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true; state.stop(); body.removeCallbacks(poll); reader.shutdownNow();
        if (stopDialog != null) stopDialog.dismiss();
        super.onDestroy();
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("agent", state.selectedId); out.putInt("tab", state.tab);
        out.putInt("history_end", state.historyEnd); out.putInt("result_offset", state.resultOffset);
        out.putString("message", message.getText().toString()); super.onSaveInstanceState(out);
    }
    @Override public void onBackPressed() {
        if (state.selectedId.length() == 0) { super.onBackPressed(); return; }
        state.select(""); selected = null; message.setText(""); rendered = ""; load();
    }

    private SubAgentManager manager() {
        if (sessionId < 0) return null;
        AgentLoop loop = hub.existingSession(sessionId);
        if (loop == null) { hub.prepareSession(sessionId); loop = hub.existingSession(sessionId); }
        return loop == null ? null : hub.subAgents(loop);
    }

    private void load() {
        body.removeCallbacks(poll);
        final long ticket = state.beginLoad();
        if (ticket < 0 || destroyed) return;
        final String target = state.selectedId;
        final SubAgentManager.Record previous = selected;
        final boolean force = dirty;
        dirty = false; refresh.setEnabled(false);
        reader.execute(new Runnable() {
            @Override public void run() {
                final List<SubAgentManager.Record> records = new ArrayList<SubAgentManager.Record>();
                SubAgentManager.Record detail = null;
                String failure = "";
                try {
                    SubAgentManager source = manager();
                    if (source != null) {
                        int cursor = 0;
                        do {
                            JSONObject snapshot = source.list(SubAgentManager.ROOT, cursor);
                            JSONArray agents = snapshot.getJSONArray("agents");
                            for (int i = 0; i < agents.length(); i++) records.add(SubAgentManager.Record.fromJson(agents.getJSONObject(i)));
                            cursor = snapshot.isNull("nextCursor") ? -1 : snapshot.getInt("nextCursor");
                        } while (cursor >= 0);
                        if (target.length() > 0) {
                            for (SubAgentManager.Record row : records) if (target.equals(row.id)) {
                                detail = !force && previous != null && row.revision == previous.revision
                                        ? previous : source.find(target);
                                break;
                            }
                        }
                    }
                } catch (Exception error) { recordUiFailure(error); failure = error.getClass().getSimpleName(); }
                final SubAgentManager.Record result = detail;
                final String error = failure;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (!state.finishLoad(ticket) || destroyed || isFinishing()) return;
                        refresh.setEnabled(true);
                        if (!target.equals(state.selectedId)) { dirty = true; load(); return; }
                        if (error.length() > 0) {
                            showReadFailure();
                        } else if (target.length() > 0 && result != null) {
                            readFailureAnnounced = false;
                            selected = result; renderDetail(true);
                        } else {
                            readFailureAnnounced = false;
                            state.selectedId = ""; selected = null; renderList(records);
                        }
                        boolean active = false;
                        for (SubAgentManager.Record row : records) if (AgentPanelState.active(row.status)) { active = true; break; }
                        AgentLoop root = hub.existingSession(sessionId);
                        active = active || root != null && root.busy();
                        if (dirty) load();
                        else if (state.mayPoll(active)) body.postDelayed(poll, 1000L);
                    }
                });
            }
        });
    }

    private void renderList(List<SubAgentManager.Record> records) {
        title.setText(R.string.agent_panel_title); tabBar.setVisibility(View.GONE);
        pages.setVisibility(View.GONE); composer.setVisibility(View.GONE); stop.setVisibility(View.GONE);
        int active = 0;
        StringBuilder signature = new StringBuilder("list");
        List<SubAgentManager.Record> ordered = AgentPanelState.ordered(records);
        for (SubAgentManager.Record row : ordered) {
            if (AgentPanelState.active(row.status)) active++;
            signature.append(row.id).append(row.status).append(row.phase).append(row.progress).append(row.activeTool).append(row.task);
        }
        summary.setText(getString(R.string.agent_panel_count, ordered.size(), active));
        if (rendered.equals(signature.toString())) return;
        final int scrollY = scroll.getScrollY();
        rendered = signature.toString(); body.removeAllViews();
        if (ordered.isEmpty()) body.addView(text(getString(R.string.agent_panel_empty), 14, 0xFF8E8E93, false));
        for (final SubAgentManager.Record row : ordered) {
            LinearLayout item = new LinearLayout(this); item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(0, dp(12), 0, dp(12));
            TextView name = text(row.name, 15, 0xFF0D0D0D, false); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END);
            Icons.right(name, Icons.CHEVRON_RIGHT, 0xFF8E8E93, dp(16)); item.addView(name);
            item.addView(text(status(row.status, row.phase) + " · " + phase(row.phase)
                    + (row.activeTool.length() == 0 ? "" : " · " + row.activeTool), 12, statusColor(row.status), false));
            TextView task = text(AgentPanelState.shortText(row.task, 220), 13, 0xFF66666C, false);
            task.setMaxLines(3); task.setEllipsize(TextUtils.TruncateAt.END); item.addView(task);
            item.setContentDescription(row.name + ", " + status(row.status, row.phase));
            item.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    state.select(row.id); rendered = ""; selected = null; dirty = true; load();
                }
            });
            body.addView(item); divider();
        }
        restoreScroll(scrollY);
    }

    private void renderDetail(boolean preserveScroll) {
        if (selected == null) return;
        final SubAgentManager.Record row = selected;
        title.setText(row.name); tabBar.setVisibility(View.VISIBLE); composer.setVisibility(View.VISIBLE);
        stop.setVisibility(View.VISIBLE); stop.setEnabled(!closing && !SubAgentManager.CLOSED.equals(row.status));
        boolean open = !SubAgentManager.CLOSED.equals(row.status);
        send.setEnabled(open && !sending && !closing); message.setEnabled(open && !closing);
        summary.setText(status(row.status, row.phase) + " · " + phase(row.phase)
                + (row.activeTool.length() == 0 ? "" : " · " + row.activeTool)
                + (row.lastActivityAt <= 0 ? "" : "\n" + getString(R.string.agent_panel_updated,
                        android.text.format.DateFormat.format("MM-dd HH:mm:ss", row.lastActivityAt))));
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == state.tab ? 0xFF2E6BE6 : 0xFF66666C);
            tabs[i].setBackgroundResource(i == state.tab ? R.drawable.bg_chip_flat : android.R.color.transparent);
        }
        int[] bounds = state.historyBounds(row.history.length());
        String signature = row.id + ":" + row.revision + ":" + state.tab + ":" + bounds[1] + ":" + state.resultOffset;
        if (signature.equals(rendered)) return;
        int scrollY = preserveScroll ? scroll.getScrollY() : 0;
        rendered = signature; body.removeAllViews();
        try {
            if (state.tab == AgentPanelState.TASK) {
                pages.setVisibility(View.GONE);
                if (row.progress.length() > 0 && !SubAgentManager.FAILED.equals(row.status))
                    section(R.string.agent_panel_progress, AgentPanelState.shortText(redact(row.progress), 600));
                section(R.string.agent_panel_assigned, redact(row.task));
                section(R.string.agent_panel_parent, SubAgentManager.ROOT.equals(row.parentId) ? getString(R.string.agent_panel_main) : row.parentId);
                section(R.string.agent_panel_identity, row.id);
                section(R.string.agent_panel_usage, String.valueOf(row.tokensUsed));
            } else if (state.tab == AgentPanelState.ACTIVITY) {
                pages.setVisibility(View.VISIBLE); latest.setVisibility(View.VISIBLE);
                pageLabel.setText(getString(R.string.agent_panel_page, bounds[1] > bounds[0] ? bounds[0] + 1 : 0, bounds[1], row.history.length()));
                older.setEnabled(bounds[0] > 0); newer.setEnabled(bounds[1] < row.history.length()); latest.setEnabled(state.historyEnd >= 0);
                List<AgentPanelState.Entry> entries = AgentPanelState.history(row, bounds[0], bounds[1], settings.systemPrompt(), settings.environmentContext());
                if (entries.isEmpty()) body.addView(text(getString(R.string.agent_panel_no_activity), 14, 0xFF8E8E93, false));
                for (AgentPanelState.Entry entry : entries) {
                    body.addView(text(role(entry.role), 12, 0xFF8E8E93, false));
                    if (entry.text.length() > 0) body.addView(text(entry.text, 14, 0xFF0D0D0D, true));
                    for (String tool : entry.tools) {
                        TextView command = text(tool, 12, 0xFF44444A, true); command.setTypeface(android.graphics.Typeface.MONOSPACE);
                        command.setPadding(dp(10), dp(10), dp(10), dp(10)); command.setBackgroundColor(0xFFF5F5F7); body.addView(command);
                    }
                    divider();
                }
            } else {
                String resultText = redact(row.result);
                int[] result = state.resultBounds(resultText.length());
                pages.setVisibility(resultText.length() > AgentPanelState.RESULT_PAGE_SIZE ? View.VISIBLE : View.GONE);
                latest.setVisibility(View.GONE);
                pageLabel.setText(getString(R.string.agent_panel_result_page, result[1] > result[0] ? result[0] + 1 : 0, result[1], resultText.length()));
                older.setEnabled(result[0] > 0); newer.setEnabled(result[1] < resultText.length());
                if (row.error.length() > 0) {
                    TextView error = text(getString(R.string.agent_panel_failure), 14, 0xFFC0392B, false);
                    body.addView(error); divider();
                }
                body.addView(text(resultText.length() == 0 ? getString(R.string.agent_panel_no_result)
                        : resultText.substring(result[0], result[1]), 14, 0xFF0D0D0D, true));
            }
        } catch (Exception failure) {
            recordUiFailure(failure);
            showReadFailure();
        }
        restoreScroll(scrollY);
    }

    private void page(boolean forward) {
        if (selected == null) return;
        if (state.tab == AgentPanelState.ACTIVITY) {
            if (forward) state.newerHistory(selected.history.length()); else state.olderHistory(selected.history.length());
        } else state.resultOffset = Math.max(0, state.resultOffset + (forward ? 1 : -1) * AgentPanelState.RESULT_PAGE_SIZE);
        renderDetail(false);
    }
    private void sendMessage() {
        final String content = message.getText().toString().trim(), id = state.selectedId;
        if (content.length() == 0 || id.length() == 0 || sending || closing || !send.isEnabled()) return;
        sending = true; send.setEnabled(false);
        reader.execute(new Runnable() {
            @Override public void run() {
                String failure = "";
                try { SubAgentManager source = manager(); if (source == null) throw new IllegalStateException("子任务不存在"); source.sendFromUser(id, content); }
                catch (Exception error) { recordUiFailure(error); failure = error.getClass().getSimpleName(); }
                final String error = failure;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        sending = false;
                        if (destroyed || isFinishing()) return;
                        if (id.equals(state.selectedId) && error.length() == 0 && content.equals(message.getText().toString().trim())) message.setText("");
                        send.setEnabled(!closing && selected != null && !SubAgentManager.CLOSED.equals(selected.status));
                        Toast.makeText(SubAgentsActivity.this, error.length() == 0 ? getString(R.string.agent_panel_message_sent)
                                : "发送失败，详细原因已记录。", Toast.LENGTH_SHORT).show();
                        dirty = true; load();
                    }
                });
            }
        });
    }
    private void closeSelected() {
        if (closing) return;
        final String id = state.selectedId; closing = true; stop.setEnabled(false);
        reader.execute(new Runnable() {
            @Override public void run() {
                String failure = "";
                try { SubAgentManager source = manager(); if (source == null) throw new IllegalStateException("子任务不存在"); source.close(SubAgentManager.ROOT, id); }
                catch (Exception error) { recordUiFailure(error); failure = error.getClass().getSimpleName(); }
                final String error = failure;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        closing = false;
                        if (destroyed || isFinishing()) return;
                        if (error.length() > 0) Toast.makeText(SubAgentsActivity.this, "停止子任务失败，详细原因已记录。", Toast.LENGTH_SHORT).show();
                        dirty = true; load();
                    }
                });
            }
        });
    }
    private String redact(String value) { return PromptGuard.redact(value, settings.systemPrompt(), settings.environmentContext(), ""); }
    private void showReadFailure() {
        if (readFailureAnnounced) return;
        readFailureAnnounced = true;
        Toast.makeText(SubAgentsActivity.this, "读取子任务失败，详细原因已记录。", Toast.LENGTH_SHORT).show();
    }
    private void recordUiFailure(final Throwable failure) {
        final android.content.Context app = getApplicationContext(); final long sid = sessionId;
        new Thread(new Runnable() {
            @Override public void run() {
                ChatStore store = null;
                try {
                    Settings stored = new Settings(app); List<String> secrets = new ArrayList<String>();
                    for (Settings.AiProfile profile : stored.aiProfiles()) secrets.add(profile.apiKey);
                    JSONObject evidence = Diagnostics.failure(failure).put("error", failure.getMessage());
                    store = new ChatStore(app);
                    store.recordDiagnostic(sid, "ui:agents", "子任务界面操作失败", Diagnostics.boundedJson(evidence, secrets.toArray(new String[secrets.size()])));
                } catch (Exception loggingFailure) { }
                finally { if (store != null) try { store.close(); } catch (RuntimeException closeFailure) { } }
            }
        }, "backcast-agent-ui-diagnostic").start();
    }
    private String status(String status, String phase) {
        if (SubAgentManager.IDLE.equals(status) && "completed".equals(phase)) return getString(R.string.sub_agents_phase_completed);
        if (SubAgentManager.QUEUED.equals(status)) return getString(R.string.sub_agents_queued);
        if (SubAgentManager.RUNNING.equals(status)) return getString(R.string.sub_agents_running);
        if (SubAgentManager.WAITING.equals(status)) return getString(R.string.sub_agents_waiting);
        if (SubAgentManager.FAILED.equals(status)) return getString("cancelled".equals(phase) ? R.string.agent_panel_stopped : R.string.sub_agents_failed);
        if (SubAgentManager.IDLE.equals(status)) return getString(R.string.sub_agents_idle);
        return getString(R.string.sub_agents_closed);
    }
    private String phase(String phase) {
        if ("generating_tool".equals(phase)) return getString(R.string.sub_agents_phase_generating);
        if ("tool_ready".equals(phase)) return getString(R.string.sub_agents_phase_tool_ready);
        if ("tool_review".equals(phase)) return getString(R.string.sub_agents_phase_tool_review);
        if ("tool_approval".equals(phase)) return getString(R.string.sub_agents_phase_tool_approval);
        if ("children".equals(phase)) return getString(R.string.sub_agents_phase_children);
        if ("waiting".equals(phase)) return getString(R.string.sub_agents_phase_waiting);
        if ("tool".equals(phase)) return getString(R.string.sub_agents_phase_tool);
        if ("thinking".equals(phase)) return getString(R.string.sub_agents_phase_thinking);
        if ("responding".equals(phase)) return getString(R.string.sub_agents_phase_responding);
        if ("reviewing".equals(phase)) return getString(R.string.sub_agents_phase_reviewing);
        if ("compacting".equals(phase)) return getString(R.string.sub_agents_phase_compacting);
        if ("completed".equals(phase)) return getString(R.string.sub_agents_phase_completed);
        if ("starting".equals(phase)) return getString(R.string.agent_panel_phase_starting);
        if ("model".equals(phase)) return getString(R.string.sub_agents_phase_model);
        return status(phase, phase);
    }
    private int statusColor(String status) {
        return SubAgentManager.FAILED.equals(status) ? 0xFFC0392B : AgentPanelState.active(status) ? 0xFF2E6BE6 : 0xFF8E8E93;
    }
    private String role(String role) {
        return getString(Message.USER.equals(role) ? R.string.agent_panel_role_user : Message.TOOL.equals(role)
                ? R.string.agent_panel_role_tool : "message".equals(role) ? R.string.agent_panel_role_message : R.string.agent_panel_role_assistant);
    }
    private void section(int label, String content) {
        body.addView(text(getString(label), 12, 0xFF8E8E93, false));
        body.addView(text(content, 14, 0xFF0D0D0D, true)); divider();
    }
    private TextView text(String content, int size, int color, boolean selectable) {
        TextView view = new TextView(this); view.setText(content); view.setTextSize(size); view.setTextColor(color);
        view.setTextIsSelectable(selectable); view.setLineSpacing(dp(3), 1f); view.setPadding(0, dp(4), 0, dp(6));
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return view;
    }
    private void divider() {
        View divider = new View(this); divider.setBackgroundColor(0xFFEFEFF1);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        layout.topMargin = dp(12); layout.bottomMargin = dp(12); body.addView(divider, layout);
    }
    private ImageButton icon(int id, int resource) {
        ImageButton button = (ImageButton) findViewById(id);
        button.setImageDrawable(Icons.tinted(this, resource, 0xFF0D0D0D, dp(20))); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void restoreScroll(final int y) {
        final int version = ++renderVersion;
        scroll.post(new Runnable() {
            @Override public void run() { if (resumed && !destroyed && version == renderVersion) scroll.scrollTo(0, y); }
        });
    }
}
