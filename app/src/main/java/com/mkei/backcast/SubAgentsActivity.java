package com.mkei.backcast;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.SubAgentManager;
import com.mkei.backcast.ui.AgentPanelState;
import com.mkei.backcast.ui.Icons;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** 本会话的子代理：只列名字和图标，点进去就是和主会话一样的会话页。 */
public final class SubAgentsActivity extends AppCompatActivity {
    public static final String EXTRA_SESSION_ID = "session_id";
    /** 图标用彩色表情，按名字固定取一个，比几何符号更像「图标」，也更好区分。 */
    private static final String[] GLYPHS = { "\uD83E\uDD8A", "\uD83D\uDC19", "\uD83E\uDD89", "\uD83D\uDC1D", "\uD83D\uDC22",
            "\uD83E\uDD84", "\uD83D\uDC33", "\uD83D\uDC27", "\uD83E\uDD8B", "\uD83C\uDF35", "\uD83D\uDC3C", "\uD83E\uDD80",
            "\uD83D\uDC28", "\uD83E\uDD94", "\uD83D\uDC38", "\uD83E\uDD9C" };
    private static final int[] TONES = { 0xFFE3ECFD, 0xFFE4F5EA, 0xFFFDECDD, 0xFFF3E6FB, 0xFFFCE4E8, 0xFFE2F4F6, 0xFFF8F1D8, 0xFFECECEF };
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
        setContentView(R.layout.activity_sub_agents);
        sessionId = getIntent().getLongExtra(EXTRA_SESSION_ID, -1L);
        body = (LinearLayout) findViewById(R.id.agent_panel_body);
        ImageButton back = (ImageButton) findViewById(R.id.agent_panel_back);
        back.setImageDrawable(Icons.tinted(this, Icons.BACK, 0xFF0D0D0D, dp(20)));
        back.setOnClickListener(new View.OnClickListener() {
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
            empty.setTextColor(0xFF8E8E93);
            empty.setPadding(dp(12), dp(16), dp(12), dp(16));
            body.addView(empty);
            return;
        }
        for (final SubAgentManager.Record row : rows) body.addView(item(row));
    }

    private View item(final SubAgentManager.Record row) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(12), dp(10), dp(12), dp(10));
        item.setMinimumHeight(dp(56));
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        item.setBackgroundResource(ripple.resourceId);
        int seed = (row.name.length() == 0 ? row.id : row.name).hashCode() & 0x7fffffff;
        int tone = TONES[seed % TONES.length];
        TextView icon = new TextView(this);
        icon.setText(GLYPHS[(Integer.reverse(seed) >>> 1) % GLYPHS.length]);
        icon.setTextSize(18);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(tone);
        icon.setBackground(circle);
        item.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView name = new TextView(this);
        name.setText(row.name);
        name.setTextSize(16);
        name.setTextColor(0xFF0D0D0D);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(14);
        item.addView(name, lp);
        item.setContentDescription(row.name);
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
