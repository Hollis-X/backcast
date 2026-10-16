package com.mkei.backcast;

import android.content.Intent;
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
 * 本会话的子代理：只列图标和名字（M3 分段列表）。状态、任务和通信都在点进去的会话页里看，
 * 那里和主会话一样的界面，收发的消息按时间穿插在对话中。
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
                    for (SubAgentManager.Record row : records) if (AgentPanelState.active(row.status)) active = true;
                } catch (Exception ignored) {
                    // 读不到就显示空列表，下次进来再读。
                }
                final boolean polling = active;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (destroyed || isFinishing()) return;
                        render(AgentPanelState.ordered(records));
                        // 运行中的子代理可能刚派出来新的，列表跟着刷新。
                        if (resumed && polling) body.postDelayed(poll, 1500L);
                    }
                });
            }
        });
    }

    private void render(List<SubAgentManager.Record> rows) {
        StringBuilder signature = new StringBuilder();
        for (SubAgentManager.Record row : rows) signature.append(row.id).append('\n').append(row.name).append('\n');
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
        for (int i = 0; i < rows.size(); i++) {
            // M3 分段列表：首尾大圆角，中间小圆角，行间 2dp 缝。
            float top = dp(i == 0 ? M3.L : M3.XS), bottom = dp(i == rows.size() - 1 ? M3.L : M3.XS);
            View item = item(rows.get(i), new float[] { top, top, top, top, bottom, bottom, bottom, bottom });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(2);
            body.addView(item, lp);
        }
    }

    /** 一行：圆角方形头像（统一的矢量图标，底色按名字固定）加名字。 */
    private View item(final SubAgentManager.Record row, float[] corners) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(16), dp(12), dp(16), dp(12));
        item.setMinimumHeight(dp(64));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(M3.container);
        shape.setCornerRadii(corners);
        android.graphics.drawable.GradientDrawable mask = new android.graphics.drawable.GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadii(corners);
        item.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(M3.alpha(M3.onSurface, 0.1f)), shape, mask));
        item.setClickable(true);
        item.setContentDescription(row.name);
        int seed = (row.name.length() == 0 ? row.id : row.name).hashCode() & 0x7fffffff;
        int[] tone = tones()[seed % tones().length];
        ImageView avatar = new ImageView(this);
        avatar.setImageDrawable(Icons.tinted(this, R.drawable.ic_agent, tone[1], dp(22)));
        avatar.setScaleType(ImageView.ScaleType.CENTER);
        avatar.setBackground(M3.fill(tone[0], dp(M3.M)));
        item.addView(avatar, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView name = new TextView(this);
        name.setText(row.name);
        name.setTextSize(16);
        name.setTextColor(M3.onSurface);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(16);
        item.addView(name, lp);
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent intent = new Intent(SubAgentsActivity.this, SubAgentChatActivity.class);
                intent.putExtra(MainActivity.EXTRA_AGENT_ROOT, sessionId);
                intent.putExtra(MainActivity.EXTRA_AGENT_ID, row.id);
                startActivity(intent);
            }
        });
        return item;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
