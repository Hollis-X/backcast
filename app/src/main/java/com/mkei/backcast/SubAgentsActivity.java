package com.mkei.backcast;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.SubAgentManager;
import com.mkei.backcast.ui.AgentComms;
import com.mkei.backcast.ui.AgentPanelState;
import com.mkei.backcast.ui.Icons;
import com.mkei.backcast.ui.M3;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 本会话的子代理：每个一张 M3 卡片，头像、名字和路径、状态标签、当前进度或任务、通信条数和最近活动时间；
 * 点进去就是和主会话一样的会话页。
 */
public final class SubAgentsActivity extends AppCompatActivity {
    public static final String EXTRA_SESSION_ID = "session_id";
    /** 头像底色和图标色：三组 M3 容器色，按名字固定取一组，随深浅色变化，所以每次现取。 */
    private static int[][] tones() {
        return new int[][] { { M3.primaryContainer, M3.onPrimaryContainer },
                { M3.secondaryContainer, M3.onSecondaryContainer }, { M3.tertiaryContainer, M3.onTertiaryContainer } };
    }
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private long sessionId;
    private LinearLayout body;
    private String rendered = "";
    private boolean resumed, destroyed;
    private final Runnable poll = new Runnable() {
        @Override public void run() { load(); }
    };

    @Override protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        M3.load(this);
        setContentView(R.layout.activity_sub_agents);
        sessionId = getIntent().getLongExtra(EXTRA_SESSION_ID, -1L);
        body = (LinearLayout) findViewById(R.id.agent_panel_body);
        androidx.appcompat.widget.Toolbar toolbar = (androidx.appcompat.widget.Toolbar) findViewById(R.id.agent_panel_toolbar);
        toolbar.setNavigationIcon(Icons.tinted(this, Icons.BACK, M3.onSurface, dp(24)));
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
    }

    @Override protected void onResume() { super.onResume(); resumed = true; load(); }
    @Override protected void onPause() { resumed = false; body.removeCallbacks(poll); super.onPause(); }
    @Override protected void onDestroy() {
        destroyed = true; body.removeCallbacks(poll); reader.shutdownNow(); super.onDestroy();
    }

    private void load() {
        body.removeCallbacks(poll);
        if (destroyed) return;
        final RunHub hub = RunHub.get(this);
        reader.execute(new Runnable() {
            @Override public void run() {
                final List<SubAgentManager.Record> records = new ArrayList<SubAgentManager.Record>();
                final java.util.Map<String, Integer> comms = new java.util.HashMap<String, Integer>();
                boolean active = false;
                try {
                    AgentLoop root = sessionId < 0 ? null : hub.existingSession(sessionId);
                    if (root == null && sessionId >= 0) { hub.prepareSession(sessionId); root = hub.existingSession(sessionId); }
                    SubAgentManager manager = root == null ? null : hub.subAgents(root);
                    if (manager != null) {
                        int cursor = 0;
                        do {
                            JSONObject snapshot = manager.list(SubAgentManager.ROOT, cursor);
                            JSONArray agents = snapshot.getJSONArray("agents");
                            for (int i = 0; i < agents.length(); i++) records.add(SubAgentManager.Record.fromJson(agents.getJSONObject(i)));
                            cursor = snapshot.isNull("nextCursor") ? -1 : snapshot.getInt("nextCursor");
                        } while (cursor >= 0);
                    }
                    for (SubAgentManager.Record row : records) {
                        if (AgentPanelState.active(row.status)) active = true;
                        if (manager != null) comms.put(row.id, Integer.valueOf(manager.communications(row.id).length()));
                    }
                } catch (Exception ignored) {
                    // 读不到就显示空列表，下次进来再读。
                }
                final boolean polling = active;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (destroyed || isFinishing()) return;
                        render(AgentPanelState.ordered(records), comms);
                        // 运行中的子代理可能刚派出来新的，列表跟着刷新。
                        if (resumed && polling) body.postDelayed(poll, 1500L);
                    }
                });
            }
        });
    }

    private void render(List<SubAgentManager.Record> rows, java.util.Map<String, Integer> comms) {
        StringBuilder signature = new StringBuilder();
        for (SubAgentManager.Record row : rows) signature.append(row.id).append('\n').append(row.name).append('\n')
                .append(row.status).append(row.phase).append('\n').append(row.progress).append('\n')
                .append(row.lastActivityAt).append(':').append(comms.get(row.id)).append('\n');
        if (signature.toString().equals(rendered) && body.getChildCount() > 0) return;
        rendered = signature.toString();
        body.removeAllViews();
        if (rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.agent_panel_empty);
            empty.setTextSize(14);
            empty.setTextColor(M3.onSurfaceVariant);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(48), dp(16), dp(16));
            body.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }
        int running = 0;
        for (SubAgentManager.Record row : rows) if (AgentPanelState.active(row.status)) running++;
        TextView summary = new TextView(this);
        summary.setText(getString(R.string.agent_panel_summary, rows.size(), running));
        summary.setTextSize(14);
        summary.setTextColor(M3.primary);
        summary.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        summary.setPadding(dp(4), dp(8), dp(4), dp(12));
        body.addView(summary, new LinearLayout.LayoutParams(-1, -2));
        for (SubAgentManager.Record row : rows) {
            Integer count = comms.get(row.id);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            body.addView(item(row, count == null ? 0 : count.intValue()), lp);
        }
    }

    /** 一张子代理卡片：surfaceContainer 填充、16dp 圆角，按下出现状态层。 */
    private View item(final SubAgentManager.Record row, int comms) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(14));
        card.setBackground(M3.ripple(M3.fill(M3.container, dp(M3.L)), M3.onSurface, dp(M3.L)));
        card.setClickable(true);
        card.setContentDescription(row.name);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        int seed = (row.name.length() == 0 ? row.id : row.name).hashCode() & 0x7fffffff;
        int[] tone = tones()[seed % tones().length];
        ImageView avatar = new ImageView(this);
        avatar.setImageDrawable(Icons.tinted(this, R.drawable.ic_agent, tone[1], dp(22)));
        avatar.setScaleType(ImageView.ScaleType.CENTER);
        avatar.setBackground(M3.fill(tone[0], dp(M3.M)));
        head.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(row.name);
        name.setTextSize(16);
        name.setTextColor(M3.onSurface);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        names.addView(name);
        if (row.path.length() > 0) {
            TextView path = new TextView(this);
            path.setText(row.path);
            path.setTextSize(12);
            path.setTextColor(M3.onSurfaceVariant);
            path.setSingleLine(true);
            path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            path.setPadding(0, dp(2), 0, 0);
            names.addView(path);
        }
        LinearLayout.LayoutParams namesLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        namesLp.leftMargin = dp(16);
        namesLp.rightMargin = dp(8);
        head.addView(names, namesLp);
        head.addView(AgentComms.chip(this, AgentComms.stateColor(row.status, row.phase),
                getString(AgentComms.stateLabel(row.status, row.phase)), SubAgentManager.RUNNING.equals(row.status)));
        card.addView(head);

        // 运行中写当前进度，其余写分到的任务；都没有就不占这一行。
        String detail = AgentPanelState.active(row.status) && row.progress.trim().length() > 0 ? row.progress : row.task;
        detail = detail == null ? "" : detail.trim().replace('\n', ' ');
        if (detail.length() > 0) {
            TextView task = new TextView(this);
            task.setText(detail);
            task.setTextSize(14);
            task.setTextColor(M3.onSurfaceVariant);
            task.setLineSpacing(dp(2), 1f);
            task.setMaxLines(2);
            task.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(12);
            card.addView(task, lp);
        }

        View line = new View(this);
        line.setBackgroundColor(M3.outlineVariant);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(-1, 1);
        lineLp.topMargin = dp(12);
        card.addView(line, lineLp);
        LinearLayout meta = new LinearLayout(this);
        meta.setOrientation(LinearLayout.HORIZONTAL);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mail = new ImageView(this);
        mail.setImageDrawable(Icons.tinted(this, R.drawable.ic_mail, M3.onSurfaceVariant, dp(16)));
        meta.addView(mail, new LinearLayout.LayoutParams(dp(16), dp(16)));
        TextView count = new TextView(this);
        count.setText(comms == 0 ? getString(R.string.agent_meta_no_comms) : getString(R.string.agent_meta_comms, comms));
        count.setTextSize(12);
        count.setTextColor(M3.onSurfaceVariant);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        countLp.leftMargin = dp(6);
        meta.addView(count, countLp);
        if (row.lastActivityAt > 0) {
            TextView when = new TextView(this);
            when.setText(AgentComms.ago(this, row.lastActivityAt));
            when.setTextSize(12);
            when.setTextColor(M3.onSurfaceVariant);
            meta.addView(when);
        }
        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(Icons.tinted(this, Icons.CHEVRON_RIGHT, M3.onSurfaceVariant, dp(18)));
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        chevronLp.leftMargin = dp(4);
        meta.addView(chevron, chevronLp);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(-1, -2);
        metaLp.topMargin = dp(10);
        card.addView(meta, metaLp);

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent intent = new Intent(SubAgentsActivity.this, SubAgentChatActivity.class);
                intent.putExtra(MainActivity.EXTRA_AGENT_ROOT, sessionId);
                intent.putExtra(MainActivity.EXTRA_AGENT_ID, row.id);
                startActivity(intent);
            }
        });
        return card;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
