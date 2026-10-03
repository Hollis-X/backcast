package com.mkei.backcast;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.mkei.backcast.agent.AgentLoop;
import com.mkei.backcast.agent.ApprovalGate;
import com.mkei.backcast.agent.Compactor;
import com.mkei.backcast.agent.Goal;
import com.mkei.backcast.agent.LlmClient;
import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.PromptGuard;
import com.mkei.backcast.agent.TokenMeter;
import com.mkei.backcast.agent.ToolRegistry;
import com.mkei.backcast.tool.EditTool;
import com.mkei.backcast.tool.ReadTool;
import com.mkei.backcast.tool.ShellTool;
import com.mkei.backcast.tool.WriteTool;
import com.mkei.backcast.ui.ContextMeter;
import com.mkei.backcast.ui.Icons;
import com.mkei.backcast.ui.Markdown;
import com.mkei.backcast.ui.SlashInput;
import com.mkei.backcast.ui.SweepText;
import com.mkei.backcast.ui.TranscriptScrollView;
import com.mkei.backcast.ui.TurnTrace;
import com.mkei.backcast.ui.WorkTimeline;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：对话流 + agent 循环。
 *
 * 排版原则：助手回复是白底纯文本，不加气泡；只有用户消息用淡紫气泡。
 * 思考与工具输出默认收起成一行小字，点开才展开。
 */
public class MainActivity extends AppCompatActivity implements ApprovalGate {

    /** 用户气泡最大宽度占屏宽比例。 */
    private static final float BUBBLE_MAX_RATIO = 0.82f;

    private TranscriptScrollView scroll;
    private LinearLayout stream;
    private SlashInput prompt;
    private Button send;
    private Button stop;
    private TextView modelChip;
    private View modelChipAnchor;
    private TextView sessionTitle;
    private TextView sessionSub;
    private TextView accessChip;
    private View composerDock;
    private View goalBar;
    private TextView goalLabel;
    private TextView goalTime;
    private ImageView goalEdit;
    /** 目标卡片的预算标签。没设预算时留空。 */
    private TextView goalBudgetLabel;
    private ImageView goalClear;
    private ImageView goalToggle;
    private int goalTick;
    /** 已经提示过的目标状态。同一种停因只弹一次，不反复打扰。 */
    private String announcedGoalStatus = "";
    private final Runnable goalTicker = new Runnable() {
        @Override
        public void run() {
            if (goalBar == null || goalBar.getVisibility() != View.VISIBLE) {
                return;
            }
            int token = goalTick;
            paintGoal();
            if (token == goalTick && goalBar.getVisibility() == View.VISIBLE
                    && loop != null && loop.goalActive() && loop.busy()) {
                goalBar.postDelayed(this, 500);
            }
        }
    };
    private ContextMeter contextMeter;
    private int contextLimit = AgentLoop.DEFAULT_CONTEXT_LIMIT;
    /** 最近一次上报的已用 token，点圆环时用它报精确值。 */
    private int lastContextUsed;
    /** 回放旧消息时暂时改写，避免插到正在进行的对话后面。 */
    private LinearLayout renderHost;
    private static final int HISTORY_PAGE_SIZE = 48;
    private static final int HISTORY_FRAME_SIZE = 4;
    private final ExecutorService historyReader = Executors.newSingleThreadExecutor();
    private final List<Runnable> historyEvents = new LinkedList<Runnable>();
    private int historyToken;
    private long historySequence = -1;
    private boolean sessionOpening;
    private boolean initialHistoryLoading;
    private boolean earlierLoading;
    private boolean historyInserting;
    private long earlierBeforeId;
    private TextView earlierRow;
    private ImageView latestButton;
    private boolean followLatest = true;
    private boolean autoScrollQueued;
    private int scrollActionToken;
    private boolean latestJumpAnimating;
    private final Object approvalLock = new Object();
    private final List<ApprovalRequest> approvals = new LinkedList<ApprovalRequest>();
    private volatile boolean activityDestroyed;

    private static final class ApprovalRequest {
        final AgentLoop source;
        final int generation, token;
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        volatile boolean accepted, completed;
        AlertDialog dialog;
        ApprovalRequest(AgentLoop source) {
            this.source = source;
            generation = source == null ? -1 : source.generation();
            token = source == null ? -1 : source.runToken();
        }
    }

    private static class ReplayCursor {
        TurnTrace turn;
        LinearLayout rows;
        int rendered;
        String request = "";
    }
    private TextView plus;
    private View drawerOverlay;
    private View drawerPanel;
    private PopupWindow modelPopup;
    private PopupWindow slashPopup;

    private Settings settings;
    private ChatStore chatStore;
    private LinearLayout drawerSessions;
    /** 当前会话。-1 表示还没落库的新会话。 */
    private long sessionId = -1;
    private AgentLoop loop;
    private LlmClient client;
    private ToolRegistry registry;

    /** 正在执行的那个工具卡片，onToolEnd 回来时更新它。 */
    private TextView runningToolHeader;
    private TextView runningToolBody;

    /** 等待模型返回时的占位行（靠左的 Thinking）。 */
    private TextView pendingRow;
    private Animation pendingPulse;
    /** 当前这一轮的「工作了」。点它展开下面的思考和命令，不另开一份。 */
    private TextView workHeader;
    private TextView thinkLabel;
    private View turnChevron;
    /** 第一段折叠区，紧贴「工作了」那一行。正文之前的思考和命令排在这里。 */
    private LinearLayout turnRows;
    /** 当前正文段。命令插到正文后面之后，后面的正文明新起一段。 */
    private LinearLayout turnBody;
    /** 这一轮在界面上的落位状态：当前正文段和它后面的折叠段。 */
    private Flow turnFlow;
    /** 本轮开始时段容器里的孩子数。重试时截回到这里，撤掉半截的正文和命令。 */
    private int turnMarkBox = -1;
    private int turnMarkRows = -1;
    /** 本轮开始时刻已经画到第几条。重试就回到这里，不重画已经落地的。 */
    private int turnMarkRendered = -1;
    private LinearLayout turnMarkBody;
    private int turnMarkBodyChildren;

    /** Body blocks and the activity ranges between them stay in arrival order. */
    private static class Flow {
        final LinearLayout box;
        final LinearLayout rows;
        LinearLayout body;
        LinearLayout tail;
        TextView tailTitle;
        boolean bodySeen;
        TurnTrace.Range activeRange;

        Flow(LinearLayout box, LinearLayout rows) {
            this.box = box;
            this.rows = rows;
        }
    }

    /** 压缩单独占一行：进行中显示「压缩中」，结束停在「压缩了 Ns」。 */
    private TextView compactHeader;
    private boolean compactLive;
    private long compactStartedAt;
    private int compactToken;
    /** 正在跑马灯的字。运行中的工作时间、思考、命令才在里面。 */
    private final List<TextView> marquees = new ArrayList<TextView>();
    private boolean marqueeLoop;
    private float marqueePhase;
    /** 正在跑、还没出结果的那条命令。跑完就从跑马灯里拿掉。 */
    private TextView openCommandLabel;
    private TurnTrace.Step openCommandStep;
    private final Runnable marqueeTick = new Runnable() {
        @Override
        public void run() {
            if (!marqueeLoop || marquees.isEmpty()) {
                marqueeLoop = false;
                return;
            }
            marqueePhase += 0.03f;
            if (marqueePhase > 1f) {
                marqueePhase -= 1f;
            }
            for (int i = 0; i < marquees.size(); i++) {
                paintMarquee(marquees.get(i), marqueePhase);
            }
            if (marqueeLoop && stream != null) {
                stream.postDelayed(this, 32);
            }
        }
    };
    private int turnRendered;
    /** 当前这段思考开始的时刻。命令或正文一来就钉死，不跟工作总时长混。 */
    private long thinkOpenAt;
    private TextView openThinkLabel;
    private TurnTrace currentTrace;
    /** 重画时最后一轮。还在跑就接着用它，不再另起一行「工作了」。 */
    private TurnTrace replayTailTrace;
    private LinearLayout replayTailRows;
    /** 界面上的起点。循环里还有更早的起点时以更早的为准，重进不能改成刚刚。 */
    private long turnStartedAt;
    private long firstEventAt;
    /** 新发送的界面只接这一轮的时钟；重进会话时用 -1 接回运行中的轮次。 */
    private int turnUiToken = -1;
    private int sheetToken;
    private int tickToken;
    /** 停止或新发送时加一。旧回调对不上就丢掉，避免停了又把「工作了」拉回来。 */
    private int liveToken;
    private View topBar;
    private View inputBar;
    private ImageView statusFrost;
    private Bitmap frostBitmap;
    private boolean frostQueued;
    private boolean frosting;
    /** 正在往里追加的那一段正文。下一轮模型请求开始时卸掉引用，视图留在对话里。 */
    private TextView liveAnswer;
    private StringBuilder liveAnswerRaw;
    private int liveAnswerRendered;
    /** 这段回复已经在复述内部指令，后面再来的字丢掉。 */
    private boolean secretBlocked;
    private boolean liveFlushQueued;
    /** 打开着的工作面板。只刷新当前这一轮，避免把旧会话的面板写花。 */
    private TurnTrace sheetTrace;
    private TurnTrace.Range sheetRange;
    private WorkTimeline sheetTimeline;
    private WorkTimeline.CommandView sheetCommand;
    private ReasoningNotes reasoningNotes;
    private String reasoningPreference = "";
    private final Runnable sheetRefresh = new Runnable() {
        public void run() {
            if (sheetTrace == null && sheetCommand == null) return;
            syncSheetTools();
            fitActivitySheet();
            View body = findViewById(R.id.sheet_body);
            if (body != null) body.postDelayed(this, 750);
        }
    };
    /**
     * 面板里各段思考的标题。
     *
     * 思考耗时只按整轮记（服务端不按段给时间），所以每段都用同一个数，
     * 和对话里那行「思考了 Ns」对齐。
     */


    private String currentBaseUrl;
    private String currentModel;
    private String currentApiKey;
    private boolean currentUseRoot;
    /** 建工具时用的工作目录；变了要重建，系统提示词里的目录才会跟着更新。 */
    private String currentWorkDir;
    private String currentEffort = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        configureSystemBars();

        scroll = (TranscriptScrollView) findViewById(R.id.scroll);
        stream = (LinearLayout) findViewById(R.id.stream);
        if (stream != null) {
            stream.setClipChildren(false);
        }
        if (scroll != null) {
            scroll.setClipChildren(false);
        }
        prompt = (SlashInput) findViewById(R.id.prompt);
        send = (Button) findViewById(R.id.send);
        stop = (Button) findViewById(R.id.stop);
        send.setMinimumWidth(0);
        send.setMinimumHeight(0);
        send.setMinWidth(0);
        send.setMinHeight(0);
        stop.setMinimumWidth(0);
        stop.setMinimumHeight(0);
        stop.setMinWidth(0);
        stop.setMinHeight(0);
        modelChip = (TextView) findViewById(R.id.model_chip_text);
        modelChipAnchor = findViewById(R.id.model_chip);
        sessionTitle = (TextView) findViewById(R.id.session_title);
        sessionSub = (TextView) findViewById(R.id.session_sub);
        accessChip = (TextView) findViewById(R.id.access_chip);
        composerDock = findViewById(R.id.composer_dock);
        latestButton = (ImageView) findViewById(R.id.scroll_latest);
        if (latestButton != null) {
            latestButton.setImageDrawable(Icons.tinted(this, Icons.SEND, 0xFF3C3C43, dp(20)));
            latestButton.setRotation(180f);
            latestButton.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { jumpToLatest(); }
            });
        }
        if (scroll != null) {
            scroll.setOnTouchStartListener(new Runnable() {
                @Override public void run() {
                    scrollActionToken++;
                    cancelLatestJumpAnimation();
                }
            });
        }
        goalBar = findViewById(R.id.goal_bar);
        goalLabel = (TextView) findViewById(R.id.goal_label);
        goalTime = (TextView) findViewById(R.id.goal_time);
        goalBudgetLabel = (TextView) findViewById(R.id.goal_budget);
        goalEdit = (ImageView) findViewById(R.id.goal_edit);
        goalClear = (ImageView) findViewById(R.id.goal_clear);
        goalToggle = (ImageView) findViewById(R.id.goal_toggle);
        if (goalClear != null) {
            goalClear.setImageDrawable(Icons.tinted(this, Icons.UNDO, 0xFF3C3C43, dp(16)));
            goalClear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dropGoal();
                }
            });
        }
        if (goalEdit != null) {
            goalEdit.setImageDrawable(Icons.tinted(this, Icons.PENCIL, 0xFF3C3C43, dp(16)));
            goalEdit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editGoal(true);
                }
            });
        }
        if (goalToggle != null) {
            goalToggle.setImageDrawable(Icons.tinted(this, Icons.PLAY, 0xFF3C3C43, dp(16)));
            goalToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleGoalRun();
                }
            });
        }
        if (goalBudgetLabel != null) {
            // 长按预算那一栏改预算：0 或不填表示不设上限，跟 Codex 的可选预算一致。
            goalBudgetLabel.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    editGoalBudget();
                    return true;
                }
            });
        }
        contextMeter = (ContextMeter) findViewById(R.id.context_meter);
        plus = (TextView) findViewById(R.id.action_plus);
        mountIcon((TextView) findViewById(R.id.action_menu), Icons.MENU);
        mountIcon((TextView) findViewById(R.id.action_more), Icons.MORE);
        mountIcon(plus, Icons.PLUS);
        if (modelChip != null) {
            Icons.right(modelChip, Icons.CHEVRON_DOWN, 0xFF8E8E93, dp(13));
        }
        paintStop();
        if (contextMeter != null) {
            contextMeter.setUsage(0f, "0%");
            contextMeter.setContentDescription(getString(R.string.context_hint));
            // 环太小放不下数字，点一下把精确用量说出来。
            contextMeter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toast(getString(R.string.context_used,
                            compactK(lastContextUsed), compactK(contextLimit)));
                }
            });
        }
        if (sessionTitle != null && Build.VERSION.SDK_INT >= 21) {
            sessionTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        drawerOverlay = findViewById(R.id.drawer_overlay);
        drawerPanel = findViewById(R.id.drawer_panel);
        mountIcon((TextView) findViewById(R.id.drawer_search), Icons.SEARCH);
        mountIcon((TextView) findViewById(R.id.drawer_settings), Icons.SETTINGS);
        TextView chatButton = (TextView) findViewById(R.id.drawer_chat);
        if (chatButton != null) {
            Icons.left(chatButton, Icons.PLUS, 0xFFFFFFFF, dp(18));
        }
        bindEdgeToEdge();

        settings = new Settings(this);
        chatStore = new ChatStore(this);
        reasoningNotes = new ReasoningNotes(settings, chatStore);
        drawerSessions = (LinearLayout) findViewById(R.id.drawer_sessions);

        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onSend();
            }
        });

        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                liveToken++;
                if (loop != null) {
                    if (loop.goalActive()) {
                        loop.pauseGoal();
                    }
                    loop.cancel();
                }
                hidePending();
                settleWork();
                if (compactLive) {
                    // 压到一半被停：不要留一行「压缩了 Ns」，它并没有压完。
                    dropCompactRow();
                } else {
                    settleCompact();
                }
                setBusy(false);
                refreshGoal();
            }
        });

        // 模型胶囊：弹出目标图里的白色圆角选择菜单。
        findViewById(R.id.model_chip).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModelPopup();
            }
        });

        // 左侧菜单打开抽屉；抽屉里的设置按钮仍进入完整设置页。
        findViewById(R.id.action_menu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDrawer();
            }
        });
        findViewById(R.id.drawer_scrim).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideDrawer();
            }
        });
        findViewById(R.id.sheet_scrim).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideWorkSheet();
            }
        });
        findViewById(R.id.drawer_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideDrawer();
                openSettings();
            }
        });
        findViewById(R.id.drawer_chat).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideDrawer();
                newChat();
            }
        });

        findViewById(R.id.action_more).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMoreSheet();
            }
        });
        View titleChip = findViewById(R.id.title_chip);
        if (titleChip != null) {
            titleChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDrawer();
                }
            });
        }
        if (accessChip != null) {
            accessChip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showAccessSheet();
                }
            });
        }

        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toast(getString(R.string.plus_hint));
            }
        });

        // 输入框有内容才把发送键点亮成紫色。
        prompt.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (stop.getVisibility() != View.VISIBLE) {
                    tintSend(s != null && s.toString().trim().length() > 0);
                }
                String raw = s == null ? "" : s.toString();
                syncSlashPopup(raw);
                syncSlashChip(raw);
            }
        });
        tintSend(false);
        if (chatStore.runningIds().size() > 0) {
            AgentService.start(this);
        }
        long latest = chatStore.latestId();
        if (latest >= 0) {
            showSession(latest, false);
        } else {
            refreshIdentity();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideKeyboard();
        updateStatus();
        if (!sessionOpening && settings != null && settings.isConfigured()) RunHub.get(this).retargetIfNeeded();
        refreshReasoningPreference();
        applyGate();
        refreshGoal();
        maybeContinue();
    }

    private void refreshReasoningPreference() {
        if (settings == null || reasoningNotes == null) return;
        String preference = settings.reasoningSummary() + "\n" + settings.outputLanguage();
        if (preference.equals(reasoningPreference)) return;
        reasoningPreference = preference;
        refreshReasoningViews(stream);
        applyReasoningPreference(currentTrace);
        if (sheetRange != null && !sheetRange.hasDetail()) hideWorkSheet();
        else {
            syncSheetTools();
            fitActivitySheet();
        }
    }

    private void applyReasoningPreference(TurnTrace trace) {
        if (trace == null) return;
        trace.showReasoning = !"none".equals(settings.reasoningSummary());
        for (TurnTrace.Piece piece : trace.order) reasoningNotes.refreshPreference(piece);
    }

    private void refreshReasoningViews(View view) {
        if (view == null) return;
        Object tag = view.getTag();
        if (tag instanceof TurnTrace) applyReasoningPreference((TurnTrace) tag);
        if (tag instanceof TurnTrace.Range) {
            TurnTrace.Range range = (TurnTrace.Range) tag;
            applyReasoningPreference(range.trace);
            if (view instanceof LinearLayout) {
                refreshFoldResults((LinearLayout) view);
                syncWorkChevron(summaryChevron((LinearLayout) view), range.trace);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) refreshReasoningViews(group.getChildAt(i));
        }
    }

    @Override
    protected void onPause() {
        hideKeyboard();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        cancelApprovals();
        resetHistoryLoading();
        historyReader.shutdownNow();
        if (goalBar != null) {
            goalBar.removeCallbacks(goalTicker);
        }
        try {
            RunHub.get(this).detach(listener);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (sheetOverlayVisible()) {
            hideWorkSheet();
            return;
        }
        if (drawerOverlay != null && drawerOverlay.getVisibility() == View.VISIBLE) {
            hideDrawer();
            return;
        }
        if (modelPopup != null && modelPopup.isShowing()) {
            modelPopup.dismiss();
            return;
        }
        if (slashPopup != null && slashPopup.isShowing()) {
            hideSlashPopup();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public boolean approve(final String toolName, final JSONObject args) {
        final ApprovalRequest request = new ApprovalRequest(AgentLoop.callingApprovalSource());
        if (!approvalCurrent(request)) return false;
        synchronized (approvalLock) { approvals.add(request); }
        boolean displayed = false;
        try {
            while (!request.completed) {
                if (!approvalCurrent(request)) break;
                if (!displayed) {
                    boolean head;
                    synchronized (approvalLock) { head = !approvals.isEmpty() && approvals.get(0) == request; }
                    if (head) {
                        displayed = true;
                        runOnUiThread(new Runnable() {
                            @Override public void run() { showApproval(request, toolName, args); }
                        });
                    }
                }
                request.done.await(250, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            return request.accepted && approvalCurrent(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            request.completed = true;
            request.done.countDown();
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (request.dialog != null && request.dialog.isShowing()) request.dialog.dismiss();
                    synchronized (approvalLock) { approvals.remove(request); }
                }
            });
        }
    }

    private boolean approvalCurrent(ApprovalRequest request) {
        return !activityDestroyed && !isFinishing() && (request.source == null
                || request.source.generation() == request.generation && request.source.runToken() == request.token
                && request.source.approvalGate() == this);
    }

    private void showApproval(final ApprovalRequest request, String toolName, JSONObject args) {
        synchronized (approvalLock) {
            if (request.completed || !approvalCurrent(request) || approvals.isEmpty() || approvals.get(0) != request) {
                request.completed = true; request.done.countDown(); return;
            }
        }
        try {
            String name = request.source == null ? "" : RunHub.get(this).agentName(request.source);
            String title = name.length() == 0 ? toolName : name + " · " + toolName;
            request.dialog = new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.approve_title, title))
                    .setMessage(getString(R.string.approve_body, prettyArgs(args)))
                    .setPositiveButton(R.string.approve_run, new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface dialog, int which) {
                            request.accepted = approvalCurrent(request);
                        }
                    }).setNegativeButton(R.string.approve_deny, null)
                    .setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
                        @Override public void onDismiss(android.content.DialogInterface dialog) {
                            request.completed = true; request.done.countDown();
                        }
                    }).create();
            if (request.completed || !approvalCurrent(request)) {
                request.completed = true; request.done.countDown(); return;
            }
            request.dialog.show();
        } catch (Exception failure) { request.completed = true; request.done.countDown(); }
    }

    private void cancelApprovals() {
        List<ApprovalRequest> waiting;
        synchronized (approvalLock) { waiting = new ArrayList<ApprovalRequest>(approvals); approvals.clear(); }
        for (ApprovalRequest request : waiting) {
            request.accepted = false;
            request.completed = true;
            request.done.countDown();
            if (request.dialog != null && request.dialog.isShowing()) request.dialog.dismiss();
        }
    }

    /** 参数摊平给用户看，命令类参数比较长，超长就截断。 */
    private static String prettyArgs(JSONObject args) {
        if (args == null || args.length() == 0) {
            return "(无参数)";
        }
        String s = args.toString();
        return s.length() > 600 ? s.substring(0, 600) + "…" : s;
    }

    /** 底栏权限文字：完全访问是最宽松的一档，用黄色提醒。 */
    private void refreshAccessChip() {
        if (accessChip == null || settings == null) {
            return;
        }
        String level = settings.accessLevel();
        int res = R.string.access_full;
        if (ApprovalGate.ACCESS_GUARDED.equals(level)) {
            res = R.string.access_guarded;
        } else if (ApprovalGate.ACCESS_STRICT.equals(level)) {
            res = R.string.access_strict;
        }
        accessChip.setText(res);
        // 完全访问下工具会直接执行、不再拦，标黄提醒；另外两档有拦截，保持灰色。
        accessChip.setTextColor(ApprovalGate.ACCESS_FULL.equals(level)
                ? getResources().getColor(R.color.warn_text)
                : getResources().getColor(R.color.text_secondary));
    }

    /** 权限选择：三档，改完立刻保存。 */
    private void showAccessSheet() {
        final String[] levels = new String[]{
                ApprovalGate.ACCESS_FULL,
                ApprovalGate.ACCESS_GUARDED,
                ApprovalGate.ACCESS_STRICT};
        final int[] labels = new int[]{
                R.string.access_full,
                R.string.access_guarded,
                R.string.access_strict};
        final int[] notes = new int[]{
                R.string.access_full_note,
                R.string.access_guarded_note,
                R.string.access_strict_note};

        LinearLayout body = (LinearLayout) findViewById(R.id.sheet_body);
        View panel = findViewById(R.id.sheet_panel);
        View overlay = findViewById(R.id.sheet_overlay);
        View scrim = findViewById(R.id.sheet_scrim);
        if (body == null || panel == null || overlay == null) {
            return;
        }
        body.removeAllViews();
        sheetToken++;

        TextView title = new TextView(this);
        title.setText(R.string.access_title);
        title.setTextSize(20);
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setPadding(0, 0, 0, dp(4));
        body.addView(title, fullWidth());

        String current = settings.accessLevel();
        for (int i = 0; i < levels.length; i++) {
            final String level = levels[i];
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, dp(12), 0, dp(12));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    settings.setAccessLevel(level);
                    applyGate();
                    refreshAccessChip();
                    hideWorkSheet();
                }
            });

            TextView name = new TextView(this);
            name.setText(getString(labels[i]));
            name.setTextSize(16);
            name.setTextColor(getResources().getColor(
                    level.equals(current) ? R.color.accent : R.color.text_primary));
            row.addView(name, fullWidth());

            TextView note = new TextView(this);
            note.setText(getString(notes[i]));
            note.setTextSize(13);
            note.setTextColor(getResources().getColor(R.color.text_secondary));
            note.setLineSpacing(dp(2), 1f);
            row.addView(note, fullWidth());
            body.addView(row, fullWidth());
        }

        showSheet();
    }

    /** 顶栏右侧卡片的更多菜单。 */
    private void showMoreSheet() {
        LinearLayout body = (LinearLayout) findViewById(R.id.sheet_body);
        View panel = findViewById(R.id.sheet_panel);
        View overlay = findViewById(R.id.sheet_overlay);
        View scrim = findViewById(R.id.sheet_scrim);
        if (body == null || panel == null || overlay == null) {
            return;
        }
        body.removeAllViews();
        sheetToken++;

        TextView title = new TextView(this);
        title.setText(R.string.more_title);
        title.setTextSize(20);
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setPadding(0, 0, 0, dp(4));
        body.addView(title, fullWidth());

        body.addView(sheetRow(R.string.more_new_chat, Icons.NEW_CHAT, null,
                new Runnable() {
                    @Override
                    public void run() {
                        hideWorkSheet();
                        newChat();
                    }
                }), fullWidth());
        body.addView(sheetRow(R.string.more_work_dir, Icons.FOLDER, settings.workDirName(),
                new Runnable() {
                    @Override
                    public void run() {
                        showWorkDirSheet();
                    }
                }), fullWidth());
        body.addView(sheetRow(R.string.sub_agents_title, Icons.CHAT, null,
                new Runnable() {
                    @Override
                    public void run() {
                        hideWorkSheet();
                        showSubAgents();
                    }
                }), fullWidth());
        body.addView(sheetRow(R.string.settings_tools_title, Icons.TERMINAL, null,
                new Runnable() {
                    @Override
                    public void run() {
                        hideWorkSheet();
                        showToolkit();
                    }
                }), fullWidth());
        body.addView(sheetRow(R.string.more_settings, Icons.SETTINGS, null,
                new Runnable() {
                    @Override
                    public void run() {
                        hideWorkSheet();
                        openSettings();
                    }
                }), fullWidth());

        showSheet();
    }

    /** 菜单里的一行：左侧图标加标题，右侧显示当前值。 */
    private View sheetRow(int labelRes, int iconRes, String value, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(52));

        TextView label = new TextView(this);
        label.setText(getString(labelRes));
        label.setTextSize(17);
        label.setTextColor(getResources().getColor(R.color.text_primary));
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setMinHeight(dp(52));
        Icons.left(label, iconRes, 0xFF0D0D0D, dp(20));
        row.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (value != null && value.length() > 0) {
            TextView current = new TextView(this);
            current.setText(value);
            current.setTextSize(14);
            current.setTextColor(getResources().getColor(R.color.text_secondary));
            current.setGravity(Gravity.CENTER_VERTICAL);
            current.setMaxLines(1);
            current.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            current.setMaxWidth(dp(150));
            row.addView(current, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (action != null) {
                    action.run();
                }
            }
        });
        return row;
    }

    private String accessLabel() {
        String level = settings.accessLevel();
        if (ApprovalGate.ACCESS_GUARDED.equals(level)) {
            return getString(R.string.access_guarded);
        }
        if (ApprovalGate.ACCESS_STRICT.equals(level)) {
            return getString(R.string.access_strict);
        }
        return getString(R.string.access_full);
    }

    /** 工作文件夹：主目录和附加目录同时授权，历史候选需要明确添加。 */
    private void showWorkDirSheet() {
        LinearLayout body = (LinearLayout) findViewById(R.id.sheet_body);
        View panel = findViewById(R.id.sheet_panel);
        View overlay = findViewById(R.id.sheet_overlay);
        View scrim = findViewById(R.id.sheet_scrim);
        if (body == null || panel == null || overlay == null) {
            return;
        }
        body.removeAllViews();
        sheetToken++;

        TextView title = new TextView(this);
        title.setText(R.string.more_work_dir);
        title.setTextSize(20);
        title.setTextColor(getResources().getColor(R.color.text_primary));
        title.setPadding(0, 0, 0, dp(4));
        body.addView(title, fullWidth());

        TextView scope = new TextView(this);
        scope.setText(R.string.workspace_scope_note);
        scope.setTextSize(13);
        scope.setTextColor(getResources().getColor(R.color.text_secondary));
        scope.setPadding(0, dp(4), 0, dp(12));
        body.addView(scope, fullWidth());

        body.addView(sheetRow(R.string.dir_add, Icons.PLUS, null, new Runnable() {
            @Override
            public void run() {
                askWorkDir();
            }
        }), fullWidth());

        final List<String> dirs = settings.authorizedWorkDirs();
        String current = dirs.get(0);
        for (int i = 0; i < dirs.size(); i++) {
            final String dir = dirs.get(i);
            body.addView(dirRow(dir, dir.equals(current), true), fullWidth());
        }

        boolean historyHeading = false;
        for (String candidate : settings.workDirs()) {
            if (dirs.contains(candidate)) continue;
            if (!historyHeading) {
                TextView heading = new TextView(this);
                heading.setText(R.string.workspace_candidates);
                heading.setTextSize(12);
                heading.setTextColor(getResources().getColor(R.color.text_secondary));
                heading.setPadding(0, dp(20), 0, dp(8));
                body.addView(heading, fullWidth());
                historyHeading = true;
            }
            body.addView(dirRow(candidate, false, false), fullWidth());
        }

        showSheet();
    }

    /** All authorized roots remain active; the primary only selects the relative-path base. */
    private View dirRow(final String dir, boolean current, final boolean authorized) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));

        TextView label = new TextView(this);
        label.setText((authorized ? getString(current ? R.string.workspace_primary : R.string.workspace_additional) + "\n" : "") + dir);
        label.setTextSize(15);
        label.setTextColor(getResources().getColor(
                current ? R.color.accent : R.color.text_primary));
        row.addView(label, fullWidth());
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        if (!current) actions.addView(workspaceAction(authorized ? R.string.workspace_set_primary : R.string.workspace_add_authorized, new Runnable() {
            @Override public void run() {
                if (authorized) settings.setPrimaryWorkDir(dir);
                else settings.addAuthorizedWorkDir(dir);
                applyWorkDir();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (authorized) actions.addView(workspaceAction(R.string.workspace_remove, new Runnable() {
            @Override public void run() {
                if (!settings.removeAuthorizedWorkDir(dir)) { toast(getString(R.string.workspace_keep_one)); return; }
                applyWorkDir();
            }
        }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(actions, fullWidth());
        return row;
    }

    private TextView workspaceAction(int title, final Runnable action) {
        TextView button = new TextView(this);
        button.setText(title); button.setTextSize(13); button.setTextColor(getResources().getColor(R.color.accent));
        button.setGravity(Gravity.CENTER); button.setMinHeight(dp(48));
        button.setBackgroundResource(android.R.drawable.list_selector_background);
        button.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View view) { action.run(); } });
        return button;
    }

    /** 手输或粘贴一个路径加进来。 */
    private void askWorkDir() {
        final EditText input = new EditText(this);
        input.setHint(Settings.DEFAULT_WORK_DIR + "/项目");
        input.setSingleLine(true);
        input.setTextSize(15);
        input.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle(R.string.dir_add)
                .setView(input)
                .setPositiveButton(R.string.workspace_add_authorized,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                String dir = input.getText().toString();
                                if (!Settings.validWorkDir(dir)) {
                                    toast(getString(R.string.workspace_invalid_path));
                                    return;
                                }
                                settings.addAuthorizedWorkDir(dir);
                                applyWorkDir();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 换了工作目录后刷新顶栏卡片与工具，不用重开会话。 */
    private void applyWorkDir() {
        refreshIdentity();
        RunHub.get(this).retargetTools();
        showWorkDirSheet();
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    /** 状态栏和导航栏都透明。内容铺到边缘，图标保持深色。底部小白条不做模糊。 */
    private void configureSystemBars() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 21) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                    | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                    | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.setStatusBarContrastEnforced(false);
            window.setNavigationBarContrastEnforced(false);
        }
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= 26) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    /** 会话铺到状态栏和导航栏下面。顶上留一条轻微模糊，底部只让出小白条。 */
    private void bindEdgeToEdge() {
        topBar = findViewById(R.id.top_bar);
        inputBar = findViewById(R.id.input_bar);
        if (composerDock == null) {
            composerDock = findViewById(R.id.composer_dock);
        }
        statusFrost = (ImageView) findViewById(R.id.status_frost);
        View footer = composerDock != null ? composerDock : inputBar;
        if (footer != null) {
            footer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (bottom - top != oldBottom - oldTop) {
                        pinLastMessage();
                    }
                }
            });
        }
        applyBarInsets(barSize("status_bar_height", 24), barSize("navigation_bar_height", 48));
        if (Build.VERSION.SDK_INT >= 20) {
            final View root = findViewById(R.id.main_root);
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override
                public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                    applyBarInsets(insets.getSystemWindowInsetTop(),
                            insets.getSystemWindowInsetBottom());
                    return insets;
                }
            });
            root.requestApplyInsets();
        }
        if (scroll != null) {
            scroll.getViewTreeObserver().addOnScrollChangedListener(
                    new ViewTreeObserver.OnScrollChangedListener() {
                        @Override
                        public void onScrollChanged() {
                            if (!initialHistoryLoading && !historyInserting) {
                                followLatest = stuckAtEnd();
                            }
                            updateLatestButton();
                            scheduleFrost();
                        }
                    });
        }
        scheduleFrost();
    }

    private int barSize(String name, int fallbackDp) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        if (id > 0) {
            return getResources().getDimensionPixelSize(id);
        }
        return dp(fallbackDp);
    }

    private void applyBarInsets(int top, int bottom) {
        if (top < 0) {
            top = 0;
        }
        if (bottom < 0) {
            bottom = 0;
        }
        if (topBar != null) {
            topBar.setPadding(dp(16), top + dp(8), dp(16), dp(8));
        }
        if (statusFrost != null) {
            ViewGroup.LayoutParams lp = statusFrost.getLayoutParams();
            lp.height = top + dp(28);
            statusFrost.setLayoutParams(lp);
        }
        View footer = composerDock != null ? composerDock : inputBar;
        if (footer != null) {
            ViewGroup.MarginLayoutParams mlp =
                    (ViewGroup.MarginLayoutParams) footer.getLayoutParams();
            mlp.bottomMargin = bottom + dp(10);
            footer.setLayoutParams(mlp);
        }
        if (drawerPanel != null) {
            drawerPanel.setPadding(dp(28), top + dp(16), dp(24), bottom + dp(16));
        }
        View sheet = findViewById(R.id.sheet_panel);
        if (sheet != null) {
            sheet.setPadding(dp(20), sheet.getPaddingTop(), dp(20), bottom + dp(16));
        }
        final int status = top;
        View anchor = topBar != null ? topBar : scroll;
        if (anchor == null) {
            return;
        }
        anchor.post(new Runnable() {
            @Override
            public void run() {
                int header = topBar == null ? status + dp(64) : topBar.getHeight();
                if (scroll != null && scroll.getPaddingTop() != header) {
                    scroll.setPadding(scroll.getPaddingLeft(), header,
                            scroll.getPaddingRight(), scroll.getPaddingBottom());
                }
                // 顶栏可以压在会话上。最后一条必须停在输入条上方。
                pinLastMessage();
                scheduleFrost();
            }
        });
    }

    /**
     * 最后一条停在输入条上方，留一点缝。
     * 再往上滑时，更早的消息仍从半透明输入条后面透出。
     */
    private void pinLastMessage() {
        if (scroll == null) {
            return;
        }
        int footer = dp(120);
        View dock = composerDock != null ? composerDock : inputBar;
        if (dock != null) {
            int bar = dock.getHeight();
            if (bar <= 0) {
                bar = dp(108);
            }
            ViewGroup.MarginLayoutParams mlp =
                    (ViewGroup.MarginLayoutParams) dock.getLayoutParams();
            footer = bar + mlp.bottomMargin + dp(12);
        }
        if (scroll.getPaddingBottom() == footer) {
            positionLatestButton(footer);
            return;
        }
        final boolean follow = stuckAtEnd();
        scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(),
                scroll.getPaddingRight(), footer);
        positionLatestButton(footer);
        if (follow) {
            final int token = historyToken;
            final int action = scrollActionToken;
            scroll.post(new Runnable() {
                @Override
                public void run() {
                    if (token != historyToken || action != scrollActionToken) return;
                    if (followLatest && !historyInserting) scroll.scrollTo(0, latestScrollY());
                    updateLatestButton();
                }
            });
        }
    }

    /** 贴在会话末尾时，补上底部留白后要跟着滚，否则最后一条还是被输入条挡住。 */
    private boolean stuckAtEnd() {
        if (scroll == null) return true;
        View child = scroll.getChildAt(0);
        if (child == null) {
            return true;
        }
        int slack = child.getBottom() + scroll.getPaddingBottom()
                - scroll.getHeight() - scroll.getScrollY();
        return slack < dp(80);
    }

    private int latestScrollY() {
        if (scroll == null || scroll.getChildAt(0) == null) return 0;
        return Math.max(0, scroll.getChildAt(0).getBottom()
                + scroll.getPaddingBottom() - scroll.getHeight());
    }

    private void positionLatestButton(int footer) {
        if (latestButton == null) return;
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) latestButton.getLayoutParams();
        int margin = footer + dp(8);
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin;
            latestButton.setLayoutParams(lp);
        }
        updateLatestButton();
    }

    private void updateLatestButton() {
        if (latestButton == null) return;
        latestButton.setVisibility(!initialHistoryLoading && !stuckAtEnd()
                && stream != null && stream.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private void jumpToLatest() {
        if (scroll == null) return;
        scrollActionToken++;
        int before = scroll.getScrollY();
        scroll.stopScroll();
        followLatest = true;
        scroll.scrollTo(0, latestScrollY());
        updateLatestButton();
        scrollToLatest();
        if (stream != null && !latestJumpAnimating && before != scroll.getScrollY()) {
            cancelLatestJumpAnimation();
            latestJumpAnimating = true;
            stream.setTranslationY(dp(24));
            stream.setAlpha(0.65f);
            stream.animate().translationY(0f).alpha(1f).setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .setListener(new AnimatorListenerAdapter() {
                        @Override public void onAnimationEnd(Animator animation) {
                            latestJumpAnimating = false;
                            stream.animate().setListener(null);
                        }
                    }).start();
        }
    }

    private void cancelLatestJumpAnimation() {
        latestJumpAnimating = false;
        if (stream == null) return;
        stream.animate().setListener(null).cancel();
        stream.setAlpha(1f);
        stream.setTranslationY(0f);
    }

    private void scheduleFrost() {
        if (statusFrost == null || scroll == null || frostQueued) {
            return;
        }
        frostQueued = true;
        statusFrost.postDelayed(new Runnable() {
            @Override
            public void run() {
                frostQueued = false;
                renderFrost();
            }
        }, 48);
    }

    /** 只模糊状态栏那一条，并向下淡出。底部不走这里。 */
    private void renderFrost() {
        if (frosting || statusFrost == null || scroll == null) {
            return;
        }
        frosting = true;
        try {
            drawFrost();
        } finally {
            frosting = false;
        }
    }

    private void drawFrost() {
        if (statusFrost == null || scroll == null) {
            return;
        }
        int w = statusFrost.getWidth();
        int h = statusFrost.getHeight();
        if (w < 8 || h < 8) {
            return;
        }
        int bw = Math.max(12, w / 7);
        int bh = Math.max(6, h / 7);
        try {
            Bitmap bmp = frostBitmap;
            if (bmp == null || bmp.getWidth() != bw || bmp.getHeight() != bh) {
                bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                frostBitmap = bmp;
            }
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(0xFFFFFFFF);
            canvas.save();
            canvas.scale(bw / (float) w, bh / (float) h);
            canvas.clipRect(0, 0, w, h);
            canvas.translate(scroll.getLeft() - statusFrost.getLeft(),
                    scroll.getTop() - statusFrost.getTop());
            scroll.draw(canvas);
            canvas.restore();
            softenFrost(bmp);
            statusFrost.setBackgroundColor(Color.TRANSPARENT);
            statusFrost.setImageBitmap(bmp);
        } catch (Exception ignored) {
        }
    }

    private static void softenFrost(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        boxBlur(px, w, h, 1);
        boxBlur(px, w, h, 1);
        int last = Math.max(1, h - 1);
        for (int y = 0; y < h; y++) {
            float fade = 1f - (y / (float) last);
            fade = fade * fade;
            int veil = (int) (196f * fade);
            int keep = (int) (230f * fade);
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int c = px[row + x];
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                r = (r * (255 - veil) + 255 * veil) / 255;
                g = (g * (255 - veil) + 255 * veil) / 255;
                b = (b * (255 - veil) + 255 * veil) / 255;
                px[row + x] = (keep << 24) | (r << 16) | (g << 8) | b;
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void boxBlur(int[] px, int w, int h, int radius) {
        int[] tmp = new int[px.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                int n = 0;
                for (int k = -radius; k <= radius; k++) {
                    int xx = x + k;
                    if (xx < 0 || xx >= w) {
                        continue;
                    }
                    int c = px[row + xx];
                    a += (c >>> 24) & 0xff;
                    r += (c >> 16) & 0xff;
                    g += (c >> 8) & 0xff;
                    b += c & 0xff;
                    n++;
                }
                tmp[row + x] = ((a / n) << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                int n = 0;
                for (int k = -radius; k <= radius; k++) {
                    int yy = y + k;
                    if (yy < 0 || yy >= h) {
                        continue;
                    }
                    int c = tmp[yy * w + x];
                    a += (c >>> 24) & 0xff;
                    r += (c >> 16) & 0xff;
                    g += (c >> 8) & 0xff;
                    b += c & 0xff;
                    n++;
                }
                px[y * w + x] = ((a / n) << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }
    }

    /** 抽屉开关的动画计时，避免连点时旧的延迟任务把新开的抽屉藏掉。 */
    private int drawerToken;

    private void showDrawer() {
        renderSessions();
        drawerToken++;
        if (drawerPanel != null) {
            ViewGroup.LayoutParams lp = drawerPanel.getLayoutParams();
            int screen = getResources().getDisplayMetrics().widthPixels;
            int peek = dp(56);
            lp.width = Math.max(screen - peek, (int) (screen * 0.78f));
            drawerPanel.setLayoutParams(lp);
            drawerPanel.startAnimation(AnimationUtils.loadAnimation(
                    this, R.anim.drawer_in));
        }
        View scrim = findViewById(R.id.drawer_scrim);
        if (scrim != null) {
            scrim.startAnimation(AnimationUtils.loadAnimation(this, R.anim.scrim_in));
        }
        drawerOverlay.setVisibility(View.VISIBLE);
    }

    private void hideDrawer() {
        if (drawerOverlay == null || drawerOverlay.getVisibility() != View.VISIBLE) {
            return;
        }
        final int token = ++drawerToken;
        if (drawerPanel != null) {
            drawerPanel.startAnimation(AnimationUtils.loadAnimation(
                    this, R.anim.drawer_out));
        }
        View scrim = findViewById(R.id.drawer_scrim);
        if (scrim != null) {
            scrim.startAnimation(AnimationUtils.loadAnimation(this, R.anim.scrim_out));
        }
        // 动画结束后再隐藏；期间若又打开抽屉，这次回调作废。
        drawerOverlay.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (token == drawerToken) {
                    drawerOverlay.setVisibility(View.GONE);
                }
            }
        }, 220);
    }

    /**
     * 顶部模型胶囊的弹出菜单。
     * 思考强度与工具权限。
     */
    private void showModelPopup() {
        if (modelPopup != null && modelPopup.isShowing()) {
            modelPopup.dismiss();
            return;
        }

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_popup);
        card.setPadding(dp(24), dp(22), dp(18), dp(18));

        TextView title = popupText(getString(R.string.popup_model), 20,
                R.color.text_primary);
        card.addView(title, wrapParams());

        TextView current = popupText(displayModelName(settings.model()), 16,
                R.color.text_secondary);
        current.setPadding(0, dp(4), 0, dp(12));
        card.addView(current, wrapParams());

        TextView reasoningTitle = popupText(getString(R.string.popup_reasoning), 18,
                R.color.text_primary);
        reasoningTitle.setPadding(0, dp(12), 0, dp(4));
        card.addView(reasoningTitle, wrapParams());

        addEffortOption(card, "关闭", Settings.EFFORT_OFF);
        addEffortOption(card, "低", Settings.EFFORT_LOW);
        addEffortOption(card, "中", Settings.EFFORT_MEDIUM);
        addEffortOption(card, "高", Settings.EFFORT_HIGH);
        addEffortOption(card, "最大 (max)", Settings.EFFORT_MAX);
        addEffortOption(card, "Ultra (ultra)", Settings.EFFORT_ULTRA);
        TextView permissions = popupText(getString(R.string.more_access) + " · " + accessLabel(), 16, R.color.text_primary);
        permissions.setMinHeight(dp(48));
        permissions.setGravity(Gravity.CENTER_VERTICAL);
        permissions.setPadding(dp(12), dp(8), dp(12), 0);
        permissions.setCompoundDrawables(Icons.tinted(this, Icons.SHIELD, 0xFF3C3C43, dp(20)), null, null, null);
        permissions.setCompoundDrawablePadding(dp(8));
        permissions.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (modelPopup != null) modelPopup.dismiss();
                showAccessSheet();
            }
        });
        card.addView(permissions, wrapParams());

        card.measure(View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = Math.min(card.getMeasuredHeight(), getResources().getDisplayMetrics().heightPixels - dp(64));
        ScrollView menu = new ScrollView(this);
        menu.addView(card);
        modelPopup = new PopupWindow(menu, dp(280), Math.max(dp(120), height), true);
        modelPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        modelPopup.setOutsideTouchable(true);
        // 不用 elevation：部分机型会渲染成硬边灰块，改用 drawable 自绘阴影。
        card.startAnimation(AnimationUtils.loadAnimation(this, R.anim.popup_in));
        View anchor = modelChipAnchor != null ? modelChipAnchor : modelChip;
        showAbove(modelPopup, anchor, dp(280));
    }

    private void showToolkit() {
        startActivity(new Intent(this, ToolConfigActivity.class));
    }

    private void showSubAgents() {
        Intent intent = new Intent(this, SubAgentsActivity.class);
        intent.putExtra(SubAgentsActivity.EXTRA_SESSION_ID, sessionId);
        startActivity(intent);
    }


    private static String shortChildText(String text, int limit) {
        if (text == null) return "";
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }

    /** 芯片在底栏，菜单往上弹，避免掉到屏幕外。 */
    private void showAbove(PopupWindow popup, View anchor, int width) {
        if (popup == null || anchor == null) {
            return;
        }
        View content = popup.getContentView();
        int w = width > 0 ? width : dp(280);
        content.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int height = popup.getHeight() > 0 ? popup.getHeight() : content.getMeasuredHeight();
        int y = loc[1] - height - dp(8);
        if (y < dp(8)) {
            y = dp(8);
        }
        if (popup.isShowing()) {
            popup.update(loc[0], y, w, height);
        } else {
            popup.setWidth(w);
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, loc[0], y);
        }
    }

    private void addEffortOption(LinearLayout card, String label, final String effort) {
        final TextView row = popupText(label, 17, R.color.text_primary);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(50));
        row.setPadding(dp(12), 0, dp(12), 0);
        if (effort.equals(settings.effectiveReasoningEffort())) {
            row.setBackgroundResource(R.drawable.bg_popup_selected);
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings.setReasoningEffort(effort);
                updateStatus();
                if (modelPopup != null) {
                    modelPopup.dismiss();
                }
            }
        });
        card.addView(row, wrapParams());
    }

    private TextView popupText(String text, int size, int colorRes) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(size);
        tv.setTextColor(getResources().getColor(colorRes));
        return tv;
    }

    private LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private String displayModelName(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return getString(R.string.status_no_model);
        }
        String s = raw.trim();
        if ("deepseek-v4.1-flash".equalsIgnoreCase(s)) {
            return "DeepSeek V4.1 Flash";
        }
        if ("gpt-6-astra".equalsIgnoreCase(s)) {
            return "GPT 6 Astra";
        }
        String[] parts = s.replace('_', '-').split("-");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.length() == 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.toString();
    }

    /** 发送键：有字是黑圆，没字是浅灰圆。箭头用图标，不用字符。 */
    private void tintSend(boolean active) {
        paintRound(send, active ? 0xFF111111 : 0xFFF2F2F2,
                active ? 0xFFFFFFFF : 0xFFB4B4B8, Icons.SEND, dp(6));
    }

    /** 停止是黑圆里的白方块，不用圆环图标。 */
    private void paintStop() {
        if (stop == null) {
            return;
        }
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(0xFF111111);
        GradientDrawable square = new GradientDrawable();
        square.setShape(GradientDrawable.RECTANGLE);
        square.setCornerRadius(dp(2));
        square.setColor(0xFFFFFFFF);
        int inset = dp(9);
        LayerDrawable layer = new LayerDrawable(new Drawable[]{
                circle, new InsetDrawable(square, inset, inset, inset, inset)});
        stop.setText("");
        stop.setBackgroundDrawable(layer);
    }

    private void paintRound(Button button, int fill, int glyph, int kind, int inset) {
        if (button == null) {
            return;
        }
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(fill);
        // 图标尺寸要扣掉四周内缩，否则会被裁掉。
        int side = Math.max(dp(12), dp(28) - inset * 2);
        Drawable icon = Icons.tinted(this, kind, glyph, side);
        LayerDrawable layer = new LayerDrawable(new Drawable[]{
                circle, new InsetDrawable(icon, inset, inset, inset, inset)});
        button.setText("");
        button.setBackgroundDrawable(layer);
    }

    /** 顶栏和加号只放图标，不套白圆。 */
    private void mountIcon(TextView tv, int res) {
        if (tv == null) {
            return;
        }
        tv.setText("");
        tv.setBackgroundColor(Color.TRANSPARENT);
        int inset = dp(9);
        Drawable icon = Icons.tinted(this, res, 0xFF0D0D0D, dp(22));
        tv.setBackgroundDrawable(new InsetDrawable(icon, inset, inset, inset, inset));
    }

    /** 新开会话：旧的留在库里，侧边栏还能点回去。 */
    private void newChat() {
        hideKeyboard();
        resetHistoryLoading();
        hideWorkSheet();
        sessionId = -1;
        releaseLiveViews();
        stream.removeAllViews();
        hidePending();
        runningToolHeader = null;
        runningToolBody = null;
        workHeader = null;
        thinkLabel = null;
        turnChevron = null;
        currentTrace = null;
        turnStartedAt = 0;
        firstEventAt = 0;
        setBusy(false);
        send.setEnabled(true);
        stop.setEnabled(true);
        loop = RunHub.get(this).freshDraft(listener);
        applyGate();
        updateStatus();
        refreshIdentity();
        refreshContextMeter();
        refreshGoal();
        updateLatestButton();
    }

    private void showSession(final long id, boolean closeDrawer) {
        hideKeyboard();
        resetHistoryLoading();
        // 换会话时把上一轮的面板收掉，否则屏幕上留着旧一轮的内容。
        hideWorkSheet();
        sessionId = id;
        RunHub.get(this).detach(listener);
        loop = null;
        releaseLiveViews();
        stream.removeAllViews();
        hidePending();
        runningToolHeader = null;
        runningToolBody = null;
        workHeader = null;
        thinkLabel = null;
        turnChevron = null;
        currentTrace = null;
        turnStartedAt = 0;
        firstEventAt = 0;
        sessionOpening = true;
        setBusy(false);
        send.setEnabled(false);
        stop.setEnabled(false);
        sessionTitle.setText(R.string.history_loading);
        if (goalBar != null) goalBar.setVisibility(View.GONE);
        if (closeDrawer) {
            hideDrawer();
        }
        final int token = historyToken;
        final RunHub hub = RunHub.get(this);
        historyReader.execute(new Runnable() {
            @Override public void run() {
                try {
                    hub.prepareSession(id);
                    ui(new Runnable() {
                        @Override public void run() {
                            if (token != historyToken || id != sessionId || isFinishing()) return;
                            final AgentLoop source = hub.bind(id, null);
                            loop = source;
                            applyGate();
                            setBusy(loop != null && loop.busy());
                            stop.setEnabled(true);
                            initialHistoryLoading = true;
                            historyReader.execute(new Runnable() {
                                @Override public void run() {
                                    try {
                                        final AgentLoop.UiSnapshot<ChatStore.MessagePage> snapshot = source.snapshotUi(
                                                new AgentLoop.UiSnapshotReader<ChatStore.MessagePage>() {
                                                    @Override public ChatStore.MessagePage read() {
                                                        return chatStore.messagePage(id, 0, HISTORY_PAGE_SIZE);
                                                    }
                                                }, listener);
                                        ui(new Runnable() {
                                            @Override public void run() {
                                                if (token != historyToken || loop != source || isFinishing()) return;
                                                sessionOpening = false;
                                                historySequence = snapshot.sequence;
                                                loadLatestHistory(snapshot.data, new Runnable() {
                                                    @Override public void run() { source.replayUiSnapshot(snapshot, listener); }
                                                });
                                                refreshIdentity();
                                                refreshContextMeter();
                                                refreshGoal();
                                                maybeContinue();
                                            }
                                        });
                                    } catch (Exception error) {
                                        ui(new Runnable() {
                                            @Override public void run() {
                                                if (token != historyToken || loop != source || isFinishing()) return;
                                                sessionOpening = false;
                                                initialHistoryLoading = false;
                                                hub.bind(id, listener);
                                                historyEvents.clear();
                                                addErrorText(getString(R.string.history_load_failed));
                                                send.setEnabled(true);
                                                refreshIdentity();
                                                refreshGoal();
                                            }
                                        });
                                    }
                                }
                            });
                        }
                    });
                } catch (Exception error) {
                    ui(new Runnable() {
                        @Override public void run() {
                            if (token != historyToken || id != sessionId || isFinishing()) return;
                            sessionOpening = false;
                            addErrorText(getString(R.string.history_load_failed));
                            send.setEnabled(true);
                            stop.setEnabled(true);
                        }
                    });
                }
            }
        });
    }

    private long ensureSession(String text) {
        if (sessionId < 0) {
            sessionId = chatStore.create(titleOf(text));
            if (loop != null) {
                RunHub.get(this).adopt(loop, sessionId);
            }
            refreshIdentity();
        }
        return sessionId;
    }

    /** 顶栏是会话身份，不是模型。模型在底栏芯片。 */
    private void refreshIdentity() {
        if (sessionOpening) return;
        if (sessionTitle == null) {
            return;
        }
        String title = getString(R.string.session_new);
        String sub = getString(R.string.app_name);
        if (sessionId >= 0 && chatStore != null) {
            String stored = chatStore.title(sessionId);
            if (stored.length() > 0) {
                title = stored;
            }
        }
        // 卡片上行是会话名，下行是工作目录名，两者都要一眼看到。
        if (settings != null) {
            sub = settings.workDirName();
            int count = settings.authorizedWorkDirs().size();
            if (count > 1) sub = getString(R.string.workspace_count, sub, count);
        }
        sessionTitle.setText(title);
        if (sessionSub != null) {
            sessionSub.setText(sub);
        }
    }

    private static String titleOf(String text) {
        String s = text == null ? "" : text.replace('\n', ' ').trim();
        if (s.length() > 24) {
            return s.substring(0, 24) + "…";
        }
        return s.length() == 0 ? "新会话" : s;
    }

    private void renderSessions() {
        if (drawerSessions == null) {
            return;
        }
        drawerSessions.removeAllViews();
        List<ChatStore.Session> list = chatStore.sessions();
        if (list.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.drawer_empty);
            empty.setTextSize(15);
            empty.setTextColor(getResources().getColor(R.color.text_secondary));
            empty.setPadding(0, dp(12), 0, 0);
            drawerSessions.addView(empty);
            return;
        }
        for (int i = 0; i < list.size(); i++) {
            final ChatStore.Session session = list.get(i);
            TextView row = new TextView(this);
            row.setText(session.title);
            row.setTextSize(15);
            row.setMaxLines(1);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setTextColor(getResources().getColor(R.color.text_primary));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            if (session.id == sessionId) {
                row.setBackgroundResource(R.drawable.bg_popup_selected);
            }
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (session.id == sessionId) {
                        hideDrawer();
                        return;
                    }
                    showSession(session.id, true);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    confirmDelete(session);
                    return true;
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            drawerSessions.addView(row, lp);
        }
    }

    private void confirmDelete(final ChatStore.Session session) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.drawer_delete)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                RunHub.get(MainActivity.this).drop(session.id);
                                chatStore.delete(session.id);
                                if (session.id == sessionId) {
                                    newChat();
                                }
                                renderSessions();
                            }
                        })
                .show();
    }

    private void updateStatus() {
        if (modelChip != null) {
            if (!settings.isConfigured()) {
                modelChip.setText(R.string.status_no_model);
            } else {
                String effort = effortLabel(settings.effectiveReasoningEffort());
                String name = displayModelName(settings.model());
                modelChip.setText(effort.length() == 0 ? name : name + "  " + effort);
            }
        }
        if (accessChip != null && settings != null) {
            refreshAccessChip();
            refreshIdentity();
        }
    }

    private static String effortLabel(String effort) {
        if (Settings.EFFORT_OFF.equals(effort)) {
            return "关";
        }
        if (Settings.EFFORT_LOW.equals(effort)) {
            return "低";
        }
        if (Settings.EFFORT_MEDIUM.equals(effort)) {
            return "中";
        }
        if (Settings.EFFORT_HIGH.equals(effort)) {
            return "高";
        }
        if (Settings.EFFORT_MAX.equals(effort)) {
            return "max";
        }
        if (Settings.EFFORT_ULTRA.equals(effort)) {
            return "ultra";
        }
        return "";
    }

    /** 每次发送前按最新配置接上这个会话自己的循环。别的会话正在跑的不会被换掉。 */
    private boolean prepareEngine() {
        if (sessionOpening || initialHistoryLoading) return false;
        if (!settings.isConfigured()) {
            toast(getString(R.string.toast_need_config));
            return false;
        }
        RunHub hub = RunHub.get(this);
        hub.retargetIfNeeded();
        if (loop == null
                || (sessionId >= 0 && loop.sessionKey() != sessionId)
                || (sessionId < 0 && loop.sessionKey() >= 0)) {
            loop = sessionId >= 0
                    ? hub.bind(sessionId, listener)
                    : hub.freshDraft(listener);
        } else {
            loop.setListener(listener);
        }
        applyGate();
        contextLimit = AgentLoop.DEFAULT_CONTEXT_LIMIT;
        if (contextMeter != null) {
            contextMeter.setWarnRatio(settings.compactRatio());
        }
        refreshAccessChip();
        return true;
    }

    private void applyGate() {
        if (settings == null) {
            return;
        }
        RunHub.get(this).broadcastAccess(settings.accessLevel(), this);
    }

    /** 输入框开头是 / 时弹出的一条指令。 */
    private static final class SlashCmd {
        final String name;
        final String detail;

        SlashCmd(String name, String detail) {
            this.name = name;
            this.detail = detail;
        }
    }

    private SlashCmd[] slashCmds() {
        return new SlashCmd[] {
                new SlashCmd("compact", getString(R.string.slash_compact_detail)),
                new SlashCmd("goal", getString(R.string.slash_goal_detail))
        };
    }

    /** 唯一前缀也算命中，方便只打出 /comp 就执行。 */
    private SlashCmd matchSlash(String text) {
        if (text == null || !text.startsWith("/")) {
            return null;
        }
        String name = text.substring(1).trim().toLowerCase();
        if (name.length() == 0) {
            return null;
        }
        SlashCmd[] cmds = slashCmds();
        SlashCmd hit = null;
        int hits = 0;
        for (int i = 0; i < cmds.length; i++) {
            if (cmds[i].name.toLowerCase().startsWith(name)) {
                hit = cmds[i];
                hits++;
            }
        }
        return hits == 1 ? hit : null;
    }

    private void syncSlashPopup(String raw) {
        if (stop.getVisibility() == View.VISIBLE || raw == null || !raw.startsWith("/")) {
            hideSlashPopup();
            return;
        }
        if (raw.indexOf('\n') >= 0 || raw.indexOf(' ') >= 0) {
            hideSlashPopup();
            return;
        }
        String prefix = raw.substring(1).toLowerCase();
        // 已经打出完整指令名就不再挡着正文，让它变成输入框里的一整块。
        if (isWholeCmd(raw)) {
            hideSlashPopup();
            return;
        }
        SlashCmd[] cmds = slashCmds();
        List<SlashCmd> hits = new ArrayList<SlashCmd>();
        for (int i = 0; i < cmds.length; i++) {
            if (cmds[i].name.toLowerCase().startsWith(prefix)) {
                hits.add(cmds[i]);
            }
        }
        if (hits.isEmpty()) {
            hideSlashPopup();
            return;
        }
        showSlashPopup(hits);
    }

    /** 输入框里正好打出完整指令名时，收掉弹层并标成一块。目标后面的需求留着。 */
    private void syncSlashChip(String raw) {
        if (prompt == null) {
            return;
        }
        if (isWholeCmd(raw)) {
            prompt.setChipped(true, isGoalWord(raw));
            return;
        }
        if (raw != null && raw.length() > 5
                && raw.regionMatches(true, 0, "/goal", 0, 5)
                && Character.isWhitespace(raw.charAt(5))) {
            prompt.holdChip(5);
            return;
        }
        prompt.setChipped(false);
    }

    private boolean isGoalWord(String raw) {
        return raw != null && raw.length() == 5
                && raw.regionMatches(true, 0, "/goal", 0, 5);
    }

    /** 整段文字就是一条指令本身（不含空格和换行）。 */
    private boolean isWholeCmd(String raw) {
        if (raw == null || !raw.startsWith("/")) {
            return false;
        }
        String name = raw.substring(1);
        SlashCmd[] cmds = slashCmds();
        for (int i = 0; i < cmds.length; i++) {
            if (cmds[i].name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void showSlashPopup(List<SlashCmd> hits) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_slash_card);
        card.setPadding(dp(8), dp(8), dp(8), dp(8));

        TextView title = popupText(getString(R.string.slash_title), 13, R.color.text_secondary);
        title.setPadding(dp(10), dp(4), dp(10), dp(6));
        card.addView(title, wrapParams());

        for (int i = 0; i < hits.size(); i++) {
            final SlashCmd cmd = hits.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setBackgroundResource(R.drawable.bg_slash_item);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            row.setClickable(true);
            TextView name = popupText("/" + cmd.name, 15, R.color.text_primary);
            row.addView(name);
            TextView detail = popupText(cmd.detail, 12, R.color.text_secondary);
            detail.setPadding(0, dp(2), 0, 0);
            row.addView(detail);
            LinearLayout.LayoutParams rowLp = wrapParams();
            rowLp.bottomMargin = i == hits.size() - 1 ? 0 : dp(6);
            // 点一下只填进输入框，发不发由用户按发送键决定。
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideSlashPopup();
                    prompt.setText("/" + cmd.name);
                    prompt.setSelection(prompt.getText().length());
                    prompt.setChipped(true, "goal".equals(cmd.name));
                }
            });
            card.addView(row, rowLp);
        }

        View anchor = inputBar != null ? inputBar : prompt;
        int width = anchor.getWidth() > 0 ? anchor.getWidth() : dp(280);
        if (slashPopup == null) {
            slashPopup = new PopupWindow(card, width,
                    ViewGroup.LayoutParams.WRAP_CONTENT, false);
            slashPopup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            // 点屏幕别处不收起：误触一下就把面板弄没，还得重新打一个 /。
            // 输入框里的内容不再匹配指令时，syncSlashPopup 会自己收掉。
            slashPopup.setOutsideTouchable(false);
            slashPopup.setFocusable(false);
            if (Build.VERSION.SDK_INT >= 21) {
                // 不吃外面的触摸，点输入框仍然能正常聚焦打字。
                slashPopup.setTouchModal(false);
            }
            slashPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
            slashPopup.setAnimationStyle(R.style.SlashPopupAnimation);
        } else {
            slashPopup.setContentView(card);
            slashPopup.setWidth(width);
        }
        showAbove(slashPopup, anchor, width);
    }

    private void hideSlashPopup() {
        if (slashPopup != null && slashPopup.isShowing()) {
            slashPopup.dismiss();
        }
    }

    private void runSlash(SlashCmd cmd) {
        if (cmd == null) {
            return;
        }
        if ("compact".equals(cmd.name)) {
            runCompact();
        } else if ("goal".equals(cmd.name)) {
            editGoal(false);
        }
    }

    /** 手动压缩：不把 /compact 当成用户消息，压完回到空闲。 */
    private void runCompact() {
        if (!prepareEngine()) {
            return;
        }
        if (sessionId < 0 || loop == null) {
            toast(getString(R.string.compact_empty));
            return;
        }
        final AgentLoop target = loop;
        final long sid = sessionId;
        final int gen = target.generation();
        liveToken++;
        final int token = liveToken;
        turnUiToken = token;
        turnStartedAt = SystemClock.elapsedRealtime();
        firstEventAt = 0;
        setBusy(true);
        // 压缩自己那一行由 onCompactStart 建，不借用「工作了」。
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (token != liveToken) {
                    return;
                }
                target.compactNow(sid, gen, token);
            }
        }).start();
    }

    /**
     * 手动压缩后按新窗口重画对话。
     *
     * 压缩已把库和内存都换成「摘要 + 最后一个用户轮」，这里照着重画一次，
     * 界面才不会继续摆着已经被压掉的旧消息。
     */
    

    private void onSend() {
        final String text = prompt.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return;
        }
        hideSlashPopup();
        if (isGoalCommand(text)) {
            prompt.setText("");
            String rest = text.substring("/goal".length()).trim();
            if (rest.length() == 0) {
                editGoal(false);
            } else {
                commitGoal(rest, false);
            }
            return;
        }
        if (text.startsWith("/") && text.indexOf(' ') < 0 && text.indexOf('\n') < 0) {
            if (text.length() == 1) {
                return;
            }
            SlashCmd cmd = matchSlash(text);
            if (cmd == null) {
                toast(getString(R.string.slash_unknown));
                return;
            }
            prompt.setText("");
            runSlash(cmd);
            return;
        }
        startText(text, false);
    }

    private void startText(final String text, boolean asGoal) {
        if (!prepareEngine()) return;
        final AgentLoop target = loop;
        final long sid = ensureSession(text);
        final int gen = target.generation();
        final int token = ++liveToken;
        prompt.setText("");
        sealLiveAnswer();
        sealCurrentTurn();
        settleCompact();
        addUserBubble(text, settings.workDir());
        if (asGoal) target.setGoal(text);
        turnUiToken = token;
        turnStartedAt = SystemClock.elapsedRealtime();
        firstEventAt = 0;
        beginWorkRow();
        setBusy(true);
        refreshGoal();
        new Thread(new Runnable() {
            @Override public void run() {
                if (token == liveToken) target.submit(text, sid, gen, token);
            }
        }).start();
    }

    private void setBusy(boolean busy) {
        send.setVisibility(busy ? View.GONE : View.VISIBLE);
        stop.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (!busy) {
            tintSend(prompt.getText().toString().trim().length() > 0);
        }
    }

    private void uiLive(final int gen, final Runnable r) {
        final AgentLoop source = AgentLoop.callingUiSource();
        final int token = source == null ? -1 : source.callingToken();
        final long sequence = source == null ? -1 : source.callingUiSequence();
        final boolean replaying = source != null && source.isReplayingUiSnapshot();
        final Runnable event = new Runnable() {
            @Override
            public void run() {
                if (source == null || loop != source || !source.accepts(gen, token)
                        || (turnUiToken >= 0 && turnUiToken != token)
                        || (!replaying && sequence >= 0 && sequence <= historySequence)) {
                    return;
                }
                r.run();
            }
        };
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (source == null || source != loop) return;
                if (initialHistoryLoading && !replaying) historyEvents.add(event);
                else event.run();
            }
        });
    }

    private final AgentLoop.Listener listener = new AgentLoop.Listener() {
        @Override
        public void onRequestStart(final int gen) {
            uiLive(gen, new Runnable() {
                @Override
                public void run() {
                    setBusy(true);
                    sealOpenThink();
                    sealLiveAnswer();
                    showPending();
                    refreshGoal();
                    syncTurnFold();
                    if (currentTrace != null) {
                        currentTrace.beginRound();
                        markTurn();
                    }
                }
            });
        }

        @Override
        public void onAssistantText(final int gen, final String text) {
            uiLive(gen, new Runnable() {
                @Override
                public void run() {
                    hidePending();
                    noteFirstEvent();
                    appendAgentDelta(text);
                }
            });
        }

        @Override
        public void onReasoning(final int gen, final String text) {
            uiLive(gen, new Runnable() {
                @Override
                public void run() {
                    hidePending();
                    noteFirstEvent();
                    attachReasoning(text);
                }
            });
        }

        @Override
        public void onToolPreview(final int gen, final int index, final String id,
                final String name, final String args) {
            uiLive(gen, new Runnable() {
                @Override
                public void run() {
                    hidePending();
                    noteFirstEvent();
                    if (currentTrace == null) {
                        beginWorkRow();
                    }
                    if (currentTrace != null) {
                        // 命令一出，这一轮的正文明起一段，命令留在它后面。
                        sealOpenThink();
                        sealLiveAnswer();
                        currentTrace.previewStep(index, id, name, args);
                        // 先把这一段折叠摆好，后面再来的正文明起一段。
                        syncTurnFold();
                        scheduleLiveFlush();
                    }
                }
            });
        }

        @Override
        public void onToolStart(final int gen, final String name, final String args) {
            uiLive(gen, new Runnable() {
                @Override
                public void run() {
                    hidePending();
                    noteFirstEvent();
                    if (currentTrace == null) {
                        beginWorkRow();
                    }
                    if (currentTrace != null) {
                        // 命令一出，这一轮的正文明起一段，命令留在它后面。
                        sealOpenThink();
                        sealLiveAnswer();
                        currentTrace.startStep(name, args);
                    }
                    refreshTurnChrome();
                    syncTurnFold();
                    syncSheetTools();
                }
            });
        }

        @Override
        public void onToolEnd(final int gen, final String name, final String result) {
            uiLive(gen, new Runnable() {
                @Override
                public void run() {
                    if (currentTrace != null) {
                        currentTrace.fillResult("", name, result);
                        refreshTurnChrome();
                        refreshFoldResults(turnRows);
                        refreshAllFolds(turnFlow);
                        syncSheetTools();
                    }
                }
            });
        }

        @Override
        public void onError(final int gen, final String message) {
            uiLive(gen, new Runnable() {
                @Override
                public void run() {
                    hidePending();
                    sealLiveAnswer();
                    settleWork();
                    if (compactLive) {
                        // 压缩失败不留一行假的「压缩了 Ns」。
                        dropCompactRow();
                    } else {
                        settleCompact();
                    }
                    addErrorText(message);
                    refreshGoal();
                }
            });
        }

        @Override
        public void onContextUsage(final int gen, final int used, final int limit) {
            uiLive(gen, new Runnable() {
                @Override
                public void run() {
                    showContextUsage(used, limit);
                }
            });
        }

        @Override
        public void onCompactStart(final int gen) {
            uiLive(gen, new Runnable() {
                @Override
                public void run() {
                    beginCompactRow();
                }
            });
        }

        @Override
        public void onCompacted(final int gen, final boolean followup) {
            uiLive(gen, new Runnable() {
                @Override
                public void run() {
                    finishCompaction(followup);
                }
            });
        }

        @Override
        public void onFinish(final int gen) {
            uiLive(gen, new Runnable() {
                @Override
                public void run() {
                    hidePending();
                    sealLiveAnswer();
                    settleWork();
                    settleCompact();
                    setBusy(false);
                    refreshGoal();
                }
            });
        }

        @Override
        public void onRetry(final int gen) {
            uiLive(gen, new Runnable() {
                @Override
                public void run() {
                    rewindLiveRound();
                }
            });
        }

        @Override
        public void onSteer(final int gen) {
            uiLive(gen, new Runnable() {
                @Override
                public void run() {
                    showSteerBreak();
                }
            });
        }
    };

    // ---------- 视图构建 ----------

/**
      * 这一轮还在跑。「工作了」从发出起就一直跑马灯，直到这一轮收尾。
      */
    private void showPending() {
        adoptLoopClock();
        if (workHeader == null) {
            beginWorkRow();
        }
        syncMarquee();
    }

    private long loopTurnStart() {
        if (loop == null) {
            return 0L;
        }
        return turnUiToken < 0 ? loop.activeTurnStart()
                : loop.activeTurnStart(loop.generation(), turnUiToken);
    }

    private long loopFirstEvent() {
        if (loop == null) {
            return 0L;
        }
        return turnUiToken < 0 ? loop.activeFirstEvent()
                : loop.activeFirstEvent(loop.generation(), turnUiToken);
    }

    /** Adopt an earlier clock only from the current turn; re-entry accepts the running turn. */
    private void adoptLoopClock() {
        long loopAt = loopTurnStart();
        if (loopAt > 0 && (turnStartedAt <= 0 || loopAt < turnStartedAt)) {
            turnStartedAt = loopAt;
        }
        long loopFirst = loopAt > 0 ? loopFirstEvent() : 0L;
        if (loopFirst > turnStartedAt && (firstEventAt == 0 || loopFirst < firstEventAt)) {
            firstEventAt = loopFirst;
        }
        if (turnStartedAt == 0) {
            turnStartedAt = SystemClock.elapsedRealtime();
        }
    }

    /** 界面和循环各有一个起点时，用更早的那个。答完后循环不再对外报起点。 */
    private long liveOrigin() {
        long loopAt = loopTurnStart();
        if (loopAt > 0 && turnStartedAt > 0) {
            return Math.min(loopAt, turnStartedAt);
        }
        return loopAt > 0 ? loopAt : turnStartedAt;
    }

    /** 第一次有内容的时刻。没有就返回 0，调用方按「还在等」处理。 */
    private long liveFirst() {
        long origin = liveOrigin();
        if (origin <= 0) {
            return 0L;
        }
        long loopFirst = loopFirstEvent();
        long first = 0L;
        if (loopFirst > origin) {
            first = loopFirst;
        }
        if (firstEventAt > origin && (first == 0L || firstEventAt < first)) {
            first = firstEventAt;
        }
        return first;
    }

    private void noteFirstEvent() {
        if (firstEventAt == 0) {
            firstEventAt = SystemClock.elapsedRealtime();
        }
    }

    /**
     * 重进时库里已经有这一轮。接着用这一行，不要再画一行新的「工作了」。
     */
    private boolean adoptRunningTurn() {
        TurnTrace trace = replayTailTrace;
        LinearLayout rows = replayTailRows;
        if (trace == null || rows == null || !(rows.getParent() instanceof LinearLayout)) {
            return false;
        }
        LinearLayout box = (LinearLayout) rows.getParent();
        View top = box.getChildCount() == 0 ? null : box.getChildAt(0);
        TextView headerView = null;
        View chevronView = null;
        if (top instanceof TextView) {
            headerView = (TextView) top;
        } else if (top instanceof LinearLayout
                && ((LinearLayout) top).getChildCount() > 0
                && ((LinearLayout) top).getChildAt(0) instanceof TextView) {
            headerView = (TextView) ((LinearLayout) top).getChildAt(0);
            if (((LinearLayout) top).getChildCount() > 1) {
                chevronView = ((LinearLayout) top).getChildAt(1);
            }
        }
        if (headerView == null) {
            return false;
        }
        long already = trace.elapsedMs;
        trace.elapsedMs = 0;
        currentTrace = trace;
        workHeader = headerView;
        thinkLabel = null;
        turnChevron = chevronView;
        turnRows = rows;
        turnFlow = flowOf(rows);
        restoreFlow(turnFlow);
        turnBody = turnFlow != null ? turnFlow.body : null;
        turnRendered = trace.order.size();
        openThinkLabel = null;
        openCommandLabel = null;
        openCommandStep = null;
        long loopAt = loopTurnStart();
        if (loopAt > 0 && (turnStartedAt <= 0 || loopAt < turnStartedAt)) {
            turnStartedAt = loopAt;
            long loopFirst = loopFirstEvent();
            firstEventAt = loopFirst > loopAt ? loopFirst : 0L;
        } else if (turnStartedAt <= 0) {
            // 库里这条已经带了耗时，就从那个耗时往回推，不要从现在重计。
            long now = SystemClock.elapsedRealtime();
            turnStartedAt = already > 0 ? now - already : now;
            firstEventAt = 0L;
        }
        trace.beginRound();
        markTurn();
        startTick();
        // 回放这一行只接管一次。之后再来一轮续跑时不再认它。
        replayTailTrace = null;
        replayTailRows = null;
        return true;
    }

    /** 重进后接着跑：按已经画出来的段恢复落位状态，新命令才不会落错地方。 */
    private void restoreFlow(Flow flow) {
        if (flow == null) return;
        flow.body = null;
        flow.bodySeen = false;
        flow.tail = null;
        flow.activeRange = null;
        for (int i = 0; i < flow.box.getChildCount(); i++) {
            View child = flow.box.getChildAt(i);
            if (!(child instanceof LinearLayout)) continue;
            if ("body".equals(child.getContentDescription())) {
                flow.body = (LinearLayout) child;
                flow.bodySeen = true;
                flow.activeRange = null;
                flow.tail = null;
            } else if ("activity".equals(child.getContentDescription())) {
                flow.tail = (LinearLayout) child;
                flow.activeRange = (TurnTrace.Range) child.getTag();
                flow.body = null;
            }
        }
        refreshAllFolds(flow);
    }

    /** 记下这一轮开始时的位置。重试时截回这里，不把上半轮画两遍。 */
    private void markTurn() {
        turnMarkBody = null;
        turnMarkBodyChildren = 0;
        if (turnFlow == null) {
            turnMarkBox = -1;
            turnMarkRows = -1;
            turnMarkRendered = -1;
            return;
        }
        turnMarkBox = turnFlow.box.getChildCount();
        turnMarkRows = turnFlow.rows.getChildCount();
        turnMarkRendered = turnRendered;
        turnMarkBody = turnFlow.body;
        turnMarkBodyChildren = turnMarkBody == null ? 0 : turnMarkBody.getChildCount();
    }

    /** 这次请求要重试。截回本轮起点，撤掉没写进历史的半截。 */
    private void rewindLiveRound() {
        if (currentTrace != null) {
            currentTrace.dropIncompleteRound();
        }
        if (liveAnswer != null) {
            ViewParent parent = liveAnswer.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(liveAnswer);
            }
            liveAnswer = null;
            liveAnswerRaw = null;
        }
        if (turnFlow != null) {
            if (turnMarkBody != null) {
                while (turnMarkBody.getChildCount() > turnMarkBodyChildren)
                    turnMarkBody.removeViewAt(turnMarkBody.getChildCount() - 1);
            }
            while (turnMarkRows >= 0 && turnFlow.rows.getChildCount() > turnMarkRows) {
                turnFlow.rows.removeViewAt(turnFlow.rows.getChildCount() - 1);
            }
            while (turnMarkBox >= 0 && turnFlow.box.getChildCount() > turnMarkBox) {
                turnFlow.box.removeViewAt(turnFlow.box.getChildCount() - 1);
            }
            // 截断后重算段落状态：起点没有正文，新正文明起一段。
            turnFlow.body = null;
            turnFlow.tail = null;
            turnFlow.tailTitle = null;
            turnFlow.bodySeen = false;
            restoreFlow(turnFlow);
        }
        turnBody = turnFlow != null ? turnFlow.body : null;
        openThinkLabel = null;
        openCommandLabel = null;
        openCommandStep = null;
        thinkOpenAt = 0;
        if (turnMarkBox >= 0 && currentTrace != null) {
            // 截回本轮起点后，已经落地的那些还画着，接着往后补就行。
            turnRendered = turnMarkRendered >= 0 ? turnMarkRendered : 0;
        } else {
            turnRendered = 0;
        }
        if (turnRows != null && currentTrace != null) {
            turnRendered = appendFoldRows(turnRows, currentTrace, turnRendered);
        }
        if (sheetTrace == currentTrace && sheetRange != null && !sheetRange.hasDetail()) hideWorkSheet();
        syncSheetTools();
        refreshTurnChrome();
        showPending();
    }

    /** 用户消息正下方插入摘要。思考和工具不写在对话里。 */
    private void beginWorkRow() {
        if (workHeader != null) {
            return;
        }
        currentTrace = new TurnTrace();
        addTurnSummary(currentTrace, true);
        markTurn();
        startTick();
    }

    /**
     * 压缩那一行。
     *
     * 压缩不是「工作」：它只产生摘要、不产生命令和思考，所以要单独一行，
     * 不能借「工作了」。收尾时停在「压缩了 Ns」。
     */
    private void beginCompactRow() {
        if (compactHeader != null) {
            return;
        }
        if (compactStartedAt == 0) {
            compactStartedAt = SystemClock.elapsedRealtime();
        }
        compactLive = true;
        SweepText header = new SweepText(this);
        header.setTextSize(15);
        header.setIncludeFontPadding(false);
        header.setTextColor(0xFF6E6E76);
        header.setPadding(0, dp(4), 0, dp(2));
        header.setText(getString(R.string.compacting));
        compactHeader = header;
        host().addView(header, fullWidth());
        startCompactTick();
        syncMarquee();
        autoScroll();
    }

    /** 压缩那一行的秒数还在走，按秒刷新。用单独的记号，不打断「工作了」的计时。 */
    private void startCompactTick() {
        final int token = ++compactToken;
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (token != compactToken || !compactLive || compactHeader == null) {
                    return;
                }
                bindCompact(compactHeader);
                compactHeader.postDelayed(this, 500);
            }
        };
        if (compactHeader != null) {
            compactHeader.post(tick);
        }
    }

    /** 压缩结束：把秒数钉死，行留在对话里。 */
    private void settleCompact() {
        compactLive = false;
        compactToken++;
        if (compactHeader != null) {
            clearMarquee(compactHeader);
            bindCompact(compactHeader);
        }
        compactHeader = null;
        compactStartedAt = 0;
        syncMarquee();
    }

    private void bindCompact(TextView header) {
        if (header == null) {
            return;
        }
        long ms = compactStartedAt > 0
                ? SystemClock.elapsedRealtime() - compactStartedAt : 0;
        header.setText(getString(R.string.compacted_ns, Integer.valueOf(seconds(ms))));
    }

    /** 压缩没成：把那一行整条撤掉，不留一条假的「压缩了 1s」。 */
    private void dropCompactRow() {
        compactLive = false;
        compactToken++;
        if (compactHeader != null) {
            clearMarquee(compactHeader);
            if (compactHeader.getParent() instanceof ViewGroup) {
                ((ViewGroup) compactHeader.getParent()).removeView(compactHeader);
            }
        }
        compactHeader = null;
        compactStartedAt = 0;
        syncMarquee();
    }

    private void startTick() {
        tickToken++;
        final int token = tickToken;
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (token != tickToken || currentTrace == null || workHeader == null) {
                    return;
                }
                refreshTurnChrome();
                workHeader.postDelayed(this, 1000);
            }
        };
        if (workHeader != null) {
            workHeader.post(tick);
        }
    }

    private void attachReasoning(String text) {
        if (text == null || text.trim().length() == 0) return;
        if (liveAnswer != null) sealLiveAnswer();
        if (currentTrace == null) beginWorkRow();
        if (currentTrace == null) return;
        if (PromptGuard.REFUSAL.equals(text)) {
            currentTrace.reasoning.setLength(0);
            currentTrace.reasoning.append(text);
            TurnTrace.Piece piece = openThinkPiece();
            if (piece == null) currentTrace.appendThink(text);
            else {
                piece.think.setLength(0); piece.think.append(text);
                piece.summaryVersion++; piece.summaryPending = false;
                piece.summary = ""; piece.summaryError = text;
            }
        } else {
            boolean fresh = currentTrace.openThink() == null || openThinkPiece().sealed;
            currentTrace.appendThink(text);
            if (fresh) thinkOpenAt = SystemClock.elapsedRealtime();
        }
        scheduleLiveFlush();
    }

    /** 流式正文追加到同一段，不每来一个字就新开一块。 */
    private void appendAgentDelta(String text) {
        if (text == null || text.length() == 0 || secretBlocked) {
            return;
        }
        if (liveAnswer == null) {
            sealOpenThink();
            syncTurnFold();
            // 第一次出正文时记下 order 长度，思考的续接靠它区分正文前后。
            if (currentTrace != null && currentTrace.bodyAt < 0) {
                currentTrace.bodyAt = currentTrace.order.size();
            }
            liveAnswer = new TextView(this);
            liveAnswer.setTextSize(16);
            liveAnswer.setTextColor(getResources().getColor(R.color.text_primary));
            liveAnswer.setLineSpacing(dp(5), 1f);
            liveAnswer.setPadding(0, dp(4), 0, dp(8));
            enableCopy(liveAnswer);
            liveAnswerRaw = new StringBuilder();
            liveAnswerRendered = 0;
            // 正文落到当前那一段里。上一段正文后面如果已经排了命令，
            // 这里会另起一段，命令就留在它出现的位置，不会被顶到最后。
            LinearLayout slot = bodySlot(turnFlow);
            turnBody = slot;
            ViewGroup parent = slot != null ? slot : stream;
            parent.addView(liveAnswer, fullWidth());
        }
        liveAnswerRaw.append(text);
        if (PromptGuard.REFUSAL.equals(text)) {
            liveAnswerRaw.setLength(0); liveAnswerRaw.append(text); secretBlocked = true;
            liveAnswer.setText(""); liveAnswerRendered = 0;
        }
        scheduleLiveFlush();
    }

    /** 正文或思考里出现了内部指令原文，就整段换成拒绝。 */
    

    /** 这一段正文结束。视图留在对话里，下一轮再新建。 */
    private void sealLiveAnswer() {
        flushLiveAnswer();
        if (liveAnswer != null && liveAnswerRaw != null)
            liveAnswer.setText(Markdown.render(liveAnswerRaw.toString(), getResources().getColor(R.color.code_bg)));
        liveAnswer = null; liveAnswerRaw = null; liveAnswerRendered = 0; secretBlocked = false;
    }

    private void releaseLiveViews() {
        resetSheetDetails();
        turnMarkBody = null;
        turnUiToken = -1;
        liveAnswer = null; liveAnswerRaw = null; liveAnswerRendered = 0;
        secretBlocked = false; liveFlushQueued = false;
        View body = findViewById(R.id.sheet_body);
        if (body != null) body.removeCallbacks(sheetRefresh);
        sheetTrace = null; sheetTimeline = null; sheetRange = null;
        turnRows = null; turnBody = null; turnFlow = null; turnRendered = 0;
        thinkOpenAt = 0; compactHeader = null; compactLive = false; compactStartedAt = 0;
        stopMarquee();
    }

    /** 换会话或新开会话后，圆环按当前历史重算一次。 */
    private void refreshContextMeter() {
        if (contextMeter == null) {
            return;
        }
        if (loop != null) {
            showContextUsage(loop.contextUsed(), loop.contextLimit());
            return;
        }
        // 还没发过消息时 loop 尚未建立，此时按库里的历史先算一次，
        // 否则首次打开会话圆环一直停在 0%。
        int used = 0;
        if (sessionId >= 0 && chatStore != null) {
            used = TokenMeter.of(chatStore.contextMessages(sessionId))
                    + TokenMeter.of(settings.fullSystemPrompt());
        }
        showContextUsage(used, contextLimit);
    }

    private void scheduleLiveFlush() {
        if (liveFlushQueued || stream == null) return;
        liveFlushQueued = true;
        final int owner = liveToken;
        stream.postDelayed(new Runnable() {
            public void run() {
                liveFlushQueued = false;
                if (owner != liveToken) return;
                flushLiveAnswer();
                syncTurnFold();
                refreshTurnChrome();
                autoScroll();
            }
        }, 100);
    }

    private void flushLiveAnswer() {
        if (liveAnswer == null || liveAnswerRaw == null) return;
        int end = liveAnswerRaw.length();
        if (end == liveAnswerRendered) return;
        if (end < liveAnswerRendered) { liveAnswer.setText(""); liveAnswerRendered = 0; }
        liveAnswer.append(liveAnswerRaw.substring(liveAnswerRendered, end));
        liveAnswerRendered = end;
    }

    /** 面板按发生顺序补块。思考出现在工具后面时，就画在那条工具后面。 */
    private void syncSheetTools() {
        if (sheetCommand != null) sheetCommand.bind();
        if (sheetTrace != null && sheetTimeline != null && sheetRange != null)
            sheetTimeline.bind(sheetRange, sheetTrace == currentTrace && sheetTrace.elapsedMs <= 0);
    }

    /** 把当前这一轮的秒数钉死。新开一轮、出错、结束都走这里，避免留下没有秒数的行。 */
    private void sealCurrentTurn() {
        if (currentTrace == null) {
            tickToken++;
            workHeader = null;
            thinkLabel = null;
            turnChevron = null;
            turnStartedAt = 0;
            firstEventAt = 0;
            turnUiToken = -1;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long origin = liveOrigin();
        if (currentTrace.elapsedMs <= 0 && origin > 0) {
            currentTrace.elapsedMs = Math.max(1L, now - origin);
        }
        if (currentTrace.thinkMs <= 0) {
            long first = liveFirst();
            long end = first > origin ? first : now;
            long think = origin > 0 ? end - origin : currentTrace.elapsedMs;
            currentTrace.thinkMs = Math.max(1L, think);
        }
        if (currentTrace.elapsedMs <= 0) {
            currentTrace.elapsedMs = Math.max(1L, currentTrace.thinkMs);
        }
        if (workHeader != null) {
            workHeader.clearAnimation();
        }
        pendingPulse = null;
        sealOpenThink();
        currentTrace.stampThinkIfMissing(currentTrace.thinkMs);
        refreshTurnChrome();
        refreshFoldResults(turnRows);
        refreshAllFolds(turnFlow);
        if (sessionId >= 0) {
            chatStore.markElapsed(sessionId, currentTrace.elapsedMs, currentTrace.thinkMs);
        }
        tickToken++;
        workHeader = null;
        thinkLabel = null;
        turnChevron = null;
        turnRows = null;
        turnBody = null;
        turnFlow = null;
        turnRendered = 0;
        openThinkLabel = null;
        openCommandLabel = null;
        openCommandStep = null;
        stopMarquee();
        currentTrace = null;
        turnStartedAt = 0;
        firstEventAt = 0;
        turnUiToken = -1;
    }

    /** 这一轮结束，写成「工作了 Ns」和「思考了 Ns」。 */
    private void settleWork() {
        sealCurrentTurn();
    }

    /** 移除占位行。 */
    private void hidePending() {
        if (pendingRow == null) {
            return;
        }
        pendingRow.clearAnimation();
        pendingPulse = null;
        stream.removeView(pendingRow);
        pendingRow = null;
    }

    /** 助手回复：白底正文，Markdown 渲染成加粗 / 代码 / 列表 / 表格。 */
    private void addAgentText(String text) {
        TextView tv = new TextView(this);
        tv.setText(Markdown.render(visibleText(text),
                getResources().getColor(R.color.code_bg)));
        tv.setTextSize(16);
        tv.setTextColor(getResources().getColor(R.color.text_primary));
        tv.setLineSpacing(dp(5), 1f);
        tv.setPadding(0, dp(4), 0, dp(8));
        enableCopy(tv);

        host().addView(tv, fullWidth());
        autoScroll();
    }

    /** 顶栏圆环：上下文用量。到九成会换色，那时自动压缩。 */
    private void showContextUsage(int used, int limit) {
        if (contextMeter == null) {
            return;
        }
        if (limit > 0) {
            contextLimit = limit;
        }
        lastContextUsed = used;
        float ratio = contextLimit <= 0 ? 0f : (float) used / (float) contextLimit;
        // 环内只放得下百分比，精确用量放进无障碍描述和点按提示。
        int pct = Math.round(ratio * 100f);
        contextMeter.setUsage(ratio, pct + "%");
        contextMeter.setContentDescription(getString(R.string.context_used,
                compactK(used), compactK(contextLimit)));
    }

    /** 圆环里的字：按 k 显示，太长的数字塞不进环。 */
    private static String compactK(int tokens) {
        if (tokens >= 1000000) {
            return trimZero(tokens / 1000000f) + "M";
        }
        if (tokens >= 1000) {
            return trimZero(tokens / 1000f) + "k";
        }
        return String.valueOf(tokens);
    }

    private static String trimZero(float v) {
        String s = String.format(java.util.Locale.US, "%.1f", Float.valueOf(v));
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
/**
     * 压缩只换模型窗口，不结束这一轮工作。
     *
     * 自动压缩，或目标还在跑，都接着原来的「工作了」。
     * 先封口再新开一行，会多出一段工作时间。
     */
    private void finishCompaction(boolean followup) {
        long ms = compactStartedAt > 0
                ? SystemClock.elapsedRealtime() - compactStartedAt : 0;
        sealLiveAnswer();
        boolean continuing = followup || (loop != null && loop.goalActive());
        if (!continuing) {
            settleWork();
            settleCompact();
            refreshContextMeter();
            addContextNote(getString(R.string.context_compacted));
            addCompactNote(ms);
            setBusy(false);
            return;
        }
        settleCompact();
        addContextNote(getString(R.string.context_compacted));
        if (!followup) {
            refreshContextMeter();
            addCompactNote(ms);
        }
        if (workHeader == null) {
            beginWorkRow();
        }
        if (!followup) {
            showPending();
        }
    }
    /** 自动续跑仍属于同一条用户请求，不再向聊天插入分隔或重开工作卡片。 */
    private void showSteerBreak() {
        if (loop == null || !loop.goalActive()) return;
        hidePending();
        sealLiveAnswer();
        sealOpenThink();
        if (currentTrace == null) beginWorkRow();
        syncTurnFold();
        setBusy(true);
        refreshGoal();
    }


    /** 压缩提示：单独一行小字，和正文区分开。 */
    private void addContextNote(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(0xFF8E8E93);
        tv.setPadding(0, dp(8), 0, dp(4));
        host().addView(tv, fullWidth());
        autoScroll();
    }

    /** 手动压缩结束后那一行「压缩了 Ns」。它是结果，不参与跑马灯。 */
    private void addCompactNote(long ms) {
        TextView tv = new TextView(this);
        tv.setText(getString(R.string.compacted_ns, Integer.valueOf(seconds(ms))));
        tv.setTextSize(15);
        tv.setIncludeFontPadding(false);
        tv.setTextColor(0xFF6E6E76);
        tv.setPadding(0, dp(4), 0, dp(2));
        host().addView(tv, fullWidth());
        autoScroll();
    }

    private void addErrorText(String message) {
        TextView tv = new TextView(this);
        tv.setText(message);
        tv.setTextSize(14);
        tv.setTextColor(getResources().getColor(R.color.error_text));
        tv.setLineSpacing(dp(3), 1f);
        tv.setPadding(0, dp(6), 0, dp(10));
        Icons.left(tv, Icons.WARNING, getResources().getColor(R.color.error_text), dp(16));
        enableCopy(tv);

        host().addView(tv, fullWidth());
        autoScroll();
    }

    /** 用户消息只显示正文；目录仍保存在会话记录中供工具解析。 */
    private void addUserBubble(String text, String workDir) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextColor(getResources().getColor(R.color.text_primary));
        tv.setLineSpacing(dp(2), 1f);
        tv.setBackgroundResource(R.drawable.bg_bubble_user);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        tv.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels
                * BUBBLE_MAX_RATIO));
        enableCopy(tv);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.RIGHT);
        column.addView(tv);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.RIGHT);
        row.setPadding(0, dp(8), 0, dp(8));
        row.addView(column);

        host().addView(row, fullWidth());
        autoScroll();
    }

    private static String dirName(String dir) {
        String t = dir == null ? "" : dir.trim();
        while (t.length() > 1 && t.endsWith("/")) t = t.substring(0, t.length() - 1);
        int cut = t.lastIndexOf('/');
        return cut < 0 || cut == t.length() - 1 ? t : t.substring(cut + 1);
    }

    /**
     * 工作时间单独一行。里面有思考或命令才出现箭头，点这一行才展开。
     *
     * 思考和命令默认合着，进行中也不自己摊开。
     * 正文在折叠外面，收起时还在。
     */
    private LinearLayout addTurnSummary(final TurnTrace trace, boolean live) {
        applyReasoningPreference(trace);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(4), 0, dp(2));
        box.setContentDescription("turn");
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setTag(trace);
        SweepText header = new SweepText(this);
        header.setTextSize(14);
        header.setTextColor(0xFF6E6E76);
        header.setIncludeFontPadding(false);
        header.setTag(trace);
        head.addView(header, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final View chevron = makeChevron();
        head.addView(chevron);
        final LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        final TurnTrace.Range beforeBody = new TurnTrace.Range(trace, 0);
        rows.setTag(beforeBody);
        TextView activity = new TextView(this);
        activity.setTextSize(13);
        activity.setTextColor(0xFF8E8E93);
        activity.setPadding(0, dp(3), 0, dp(8));
        rows.addView(activity, fullWidth());
        View.OnClickListener details = new View.OnClickListener() {
            public void onClick(View view) { showActivitySheet(beforeBody); }
        };
        head.setOnClickListener(details);
        activity.setOnClickListener(details);
        box.addView(head, fullWidth());
        box.addView(rows, fullWidth());
        Flow flow = new Flow(box, rows);
        box.setTag(flow);
        host().addView(box, fullWidth());
        if (live) {
            workHeader = header; thinkLabel = null; turnChevron = chevron;
            turnRows = rows; turnFlow = flow; turnBody = null; turnRendered = 0;
        }
        bindSummary(header, trace);
        refreshFoldResults(rows);
        syncWorkChevron(chevron, trace);
        autoScroll();
        return rows;
    }

    /** 点「工作了」展开或收起。先把还没画上的思考补进这一行，避免点了没反应。 */
    

    private void refreshTurnChrome() {
        if (currentTrace == null) return;
        adoptLoopClock();
        bindSummary(workHeader, currentTrace);
        syncWorkChevron(turnChevron, currentTrace);
        refreshAllFolds(turnFlow);
        syncMarquee();
    }

    /**
     * 运行中的「工作了」、还没钉死的思考、还没出结果的命令，字上走一道扫光。
     * 这几个字都很短，系统跑马灯要文字比控件宽才会滚，所以这里自己扫。
     */
    private void syncMarquee() {
        ArrayList<TextView> next = new ArrayList<TextView>();
        if (workHeader != null) next.add(workHeader);
        if (compactLive && compactHeader != null) next.add(compactHeader);
        for (TextView old : marquees) if (!next.contains(old)) clearMarquee(old);
        marquees.clear(); marquees.addAll(next);
        if (next.isEmpty() || stream == null) {
            marqueeLoop = false;
            if (stream != null) stream.removeCallbacks(marqueeTick);
        } else if (!marqueeLoop) {
            marqueeLoop = true; marqueePhase = 0f;
            stream.removeCallbacks(marqueeTick); stream.post(marqueeTick);
        }
    }

    private void stopMarquee() {
        marqueeLoop = false;
        if (stream != null) {
            stream.removeCallbacks(marqueeTick);
        }
        for (int i = 0; i < marquees.size(); i++) {
            clearMarquee(marquees.get(i));
        }
        marquees.clear();
    }

    private void clearMarquee(TextView tv) {
        if (tv instanceof SweepText) {
            ((SweepText) tv).setPhase(-1f);
        }
    }

    private void paintMarquee(TextView tv, float phase) {
        if (tv instanceof SweepText) {
            ((SweepText) tv).setPhase(phase);
        }
    }

    /** 只刷新工作时间。思考秒数在各自的折叠行上，不挂在这一行后面。 */
    private void bindSummary(TextView header, TurnTrace trace) {
        if (header == null || trace == null) {
            return;
        }
        long ms = trace.elapsedMs > 0 ? trace.elapsedMs
                : (trace == currentTrace ? displayElapsed(trace) : 0L);
        if (ms <= 0) {
            header.setText(getString(R.string.thinking));
            return;
        }
        header.setText(getString(R.string.worked, Integer.valueOf(seconds(ms))));
    }

    /** 这段思考结束。秒数钉在这段上，不跟后面的命令混。 */
    private void sealOpenThink() {
        TurnTrace.Piece piece = openThinkPiece();
        if (piece != null && !piece.sealed) {
            piece.sealed = true;
            if (thinkOpenAt > 0 && piece.thinkMs <= 0)
                piece.thinkMs = Math.max(1L, SystemClock.elapsedRealtime() - thinkOpenAt);
        }
        thinkOpenAt = 0;
        syncSheetTools();
    }

    

    

    private TurnTrace.Piece openThinkPiece() {
        if (currentTrace == null || currentTrace.order.isEmpty()) {
            return null;
        }
        TurnTrace.Piece last = currentTrace.order.get(currentTrace.order.size() - 1);
        return last.think != null ? last : null;
    }

    private void syncTurnFold() {
        if (currentTrace == null || turnRows == null) {
            return;
        }
        turnRendered = appendFoldRows(turnRows, currentTrace, turnRendered);
        syncWorkChevron(turnChevron, currentTrace);
        syncMarquee();
    }

    /**
     * 正文下面的那一行。
     * 第一条是命令就写「执行了命令」，箭头贴到屏幕右缘，内边距和命令行对齐。
     * 第一条是思考则把时间放在最右，再加箭头。
     */
    

    /**
     * 正文之后那段折叠的标题。
     * 段里第一条是命令就写「执行了命令」，是思考就把耗时放最右。
     */
    

    

    private int sidePad() {
        return scroll != null && scroll.getPaddingRight() > 0
                ? scroll.getPaddingRight() : dp(18);
    }

    private Flow flowOf(LinearLayout rows) {
        if (rows == null || !(rows.getParent() instanceof LinearLayout)) {
            return null;
        }
        Object tag = ((LinearLayout) rows.getParent()).getTag();
        return tag instanceof Flow ? (Flow) tag : null;
    }

    

    

    /**
     * 按发生顺序补折叠行。
     * 已经画过的行不搬；正文出现后再来的思考和命令排在新的一段里，
     * 位置就在那段正文后面，不会被后面的正文顶到最后。
     */
    private int appendFoldRows(LinearLayout rows, TurnTrace trace, int rendered) {
        if (rows == null || trace == null) return rendered;
        Flow flow = flowOf(rows);
        if (flow == null) return rendered;
        TurnTrace.Range first = (TurnTrace.Range) rows.getTag();
        while (rendered < trace.order.size()) {
            if (!flow.bodySeen) {
                first.end = rendered + 1;
            } else {
                if (flow.activeRange == null) {
                    flow.activeRange = new TurnTrace.Range(trace, rendered);
                    flow.tail = activityRow(flow.activeRange);
                    flow.box.addView(flow.tail, fullWidth());
                }
                flow.activeRange.end = rendered + 1;
                flow.body = null;
            }
            rendered++;
        }
        refreshAllFolds(flow);
        syncWorkChevron(summaryChevron(rows), trace);
        return rendered;
    }

    private WorkTimeline.Actions activityActions() {
        return new WorkTimeline.Actions() {
            public void summarize(final TurnTrace.Piece piece, boolean retry) {
                if (retry) { piece.summaryError = ""; piece.requestedChars = 0; }
                reasoningNotes.request(piece, new Runnable() {
                    public void run() { syncSheetTools(); fitActivitySheet(); }
                });
            }
            public void command(TurnTrace.Step step) { showCommandScreen(step); }
        };
    }

    private LinearLayout activityRow(final TurnTrace.Range range) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setContentDescription("activity");
        row.setTag(range);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setMinimumHeight(dp(40));
        TextView caption = new TextView(this);
        caption.setTextSize(14);
        caption.setTextColor(0xFF6E6E76);
        caption.setPadding(0, dp(8), dp(8), dp(8));
        head.addView(caption, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(makeChevron());
        head.setOnClickListener(new View.OnClickListener() {
            public void onClick(View view) { showActivitySheet(range); }
        });
        row.addView(head, fullWidth());
        return row;
    }

    private void refreshAllFolds(Flow flow) {
        if (flow == null) return;
        refreshFoldResults(flow.rows);
        for (int i = 0; i < flow.box.getChildCount(); i++) {
            View child = flow.box.getChildAt(i);
            if (child instanceof LinearLayout && "activity".equals(child.getContentDescription()))
                refreshFoldResults((LinearLayout) child);
        }
    }

    /** 正文落到位：命令已经插在上一段正文后面，这段正文明新起一段。 */
    private LinearLayout bodySlot(Flow flow) {
        if (flow == null) {
            return null;
        }
        if (flow.body == null) {
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setContentDescription("body");
            flow.box.addView(body, fullWidth());
            flow.body = body;
            flow.bodySeen = true;
            // 这一段正文之后的命令另开一段，不能落回上一段里。
            flow.tail = null;
            flow.tailTitle = null;
            flow.activeRange = null;
        }
        return flow.body;
    }

    /**
     * 这一轮的命令或思考要落位。
     * 正文已经画过，就把当前正文段封口，后面再来的正文明起一段；
     * 命令因此停在它真正发生的位置，不会被后面的正文顶到最后。
     */
    

    /** 折叠段那一行的标题。跑马灯要拿它，段一建出来就记住。 */
    

    

    

    

    

    private void refreshFoldResults(LinearLayout rows) {
        if (rows == null || rows.getChildCount() == 0 || !(rows.getTag() instanceof TurnTrace.Range)) return;
        TurnTrace.Range range = (TurnTrace.Range) rows.getTag();
        range.end = Math.min(range.end, range.trace.order.size());
        View first = rows.getChildAt(0);
        TextView caption = first instanceof LinearLayout
                ? (TextView) ((LinearLayout) first).getChildAt(0) : (TextView) first;
        String next = range.caption();
        if (!next.contentEquals(caption.getText())) caption.setText(next);
        rows.setVisibility(range.hasDetail() ? View.VISIBLE : View.GONE);
    }

    /** 会话里只留几行。整段命令和结果要点「执行结果」才打开。 */
    

    

    /** 展开和收起都走高度，不一下子把后面的正文顶走。 */
    

    

    /** 折叠块原先是 GONE，直接量经常得到 0。不够高时改把可见的子项加起来。 */
    

    

    /** 整条命令和结果。只从「执行结果」进来，不摊在会话里。 */
    private void showCommandScreen(TurnTrace.Step step) {
        LinearLayout panel = (LinearLayout) findViewById(R.id.sheet_panel);
        if (step == null || panel == null) return;
        showSheet();
        findViewById(R.id.sheet_scroll).setVisibility(View.GONE);
        sheetCommand = new WorkTimeline.CommandView(this, step);
        panel.addView(sheetCommand, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        lp.height = (int) (getResources().getDisplayMetrics().heightPixels * .72f);
        panel.setLayoutParams(lp);
        findViewById(R.id.sheet_body).postDelayed(sheetRefresh, 750);
    }

    

    

    /** 同一个箭头。收起时尖头朝右，展开转到朝下。 */
    private View makeChevron() {
        ImageView chevron = new ImageView(this);
        int size = dp(16);
        chevron.setImageDrawable(
                Icons.tinted(this, Icons.CHEVRON_RIGHT, 0xFF6E6E76, size));
        chevron.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER_VERTICAL;
        lp.rightMargin = dp(3);
        chevron.setLayoutParams(lp);
        return chevron;
    }

    private void spinChevron(final View chevron, final boolean expanded, final boolean animate) {
        chevron.post(new Runnable() {
            @Override
            public void run() {
                if (chevron.getWidth() == 0) {
                    if (chevron.getVisibility() != View.GONE) {
                        chevron.post(this);
                    }
                    chevron.setRotation(expanded ? 90f : 0f);
                    return;
                }
                chevron.setPivotX(chevron.getWidth() / 2f);
                chevron.setPivotY(chevron.getHeight() / 2f);
                float to = expanded ? 90f : 0f;
                if (!animate && Math.abs(chevron.getRotation() - to) < 1f) {
                    return;
                }
                if (animate) {
                    chevron.animate().rotation(to).setDuration(180).start();
                } else {
                    chevron.animate().cancel();
                    chevron.setRotation(to);
                }
            }
        });
    }

    private long displayElapsed(TurnTrace trace) {
        if (trace == currentTrace) {
            long origin = liveOrigin();
            if (origin > 0) {
                return Math.max(1L, SystemClock.elapsedRealtime() - origin);
            }
        }
        if (trace.elapsedMs > 0) {
            return trace.elapsedMs;
        }
        return 1000L;
    }

    private long displayThink(TurnTrace trace) {
        if (trace == currentTrace) {
            long origin = liveOrigin();
            if (origin > 0) {
                long first = liveFirst();
                long end = first > origin ? first : SystemClock.elapsedRealtime();
                return Math.max(1L, end - origin);
            }
        }
        if (trace.thinkMs > 0) {
            return trace.thinkMs;
        }
        if (trace.elapsedMs > 0) {
            return trace.elapsedMs;
        }
        return 1000L;
    }

    private int seconds(long ms) {
        return (int) Math.max(1L, (ms + 999) / 1000);
    }

    /** 展开块超过 maxPx 时改成定高，内部自己滚。 */
    

    private void resetHistoryLoading() {
        if (loop != null && (sessionOpening || initialHistoryLoading)) {
            loop.setListener(new AgentLoop.Quiet());
        }
        historyToken++;
        scrollActionToken++;
        if (scroll != null) scroll.stopScroll();
        initialHistoryLoading = false;
        sessionOpening = false;
        earlierLoading = false;
        historyInserting = false;
        historyEvents.clear();
        historySequence = -1;
        earlierRow = null;
        earlierBeforeId = 0;
        renderHost = null;
        followLatest = true;
        cancelLatestJumpAnimation();
    }

    private void loadLatestHistory(final ChatStore.MessagePage page, final Runnable pendingReplay) {
        replayTailTrace = null;
        replayTailRows = null;
        initialHistoryLoading = true;
        final int token = historyToken;
        earlierBeforeId = page.firstId;
        if (page.earlierCount > 0) addEarlierRow(page.earlierCount);
        final LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        renderPage(page, block, token, new Runnable() {
            @Override public void run() {
                stream.addView(block, fullWidth());
                if (loop != null && (loop.busy() || !historyEvents.isEmpty())) {
                    if (!adoptRunningTurn()) beginWorkRow();
                    showPending();
                }
                pendingReplay.run();
                scrollToLatest();
                drainHistoryEvents(token);
                updateLatestButton();
            }
        }, true);
    }

    private void drainHistoryEvents(final int token) {
        if (token != historyToken) return;
        long start = SystemClock.elapsedRealtime();
        int count = 0;
        while (!historyEvents.isEmpty() && count < 16) {
            historyEvents.remove(0).run();
            count++;
            if (SystemClock.elapsedRealtime() - start >= 6) break;
        }
        if (!historyEvents.isEmpty()) {
            stream.postOnAnimation(new Runnable() {
                @Override public void run() { drainHistoryEvents(token); }
            });
        } else {
            initialHistoryLoading = false;
            setBusy(loop != null && loop.busy());
            send.setEnabled(true);
            updateLatestButton();
            maybeContinue();
        }
    }

    private void renderPage(final ChatStore.MessagePage page, final LinearLayout block,
            final int token, final Runnable complete, final boolean latest) {
        final List<Message> messages = stripCompactionAsks(page.messages);
        final ReplayCursor cursor = new ReplayCursor();
        cursor.request = page.requestBefore;
        final Runnable frame = new Runnable() {
            int next;
            @Override public void run() {
                if (token != historyToken || isFinishing()) return;
                LinearLayout previous = renderHost;
                renderHost = block;
                try {
                    if (next == 0 && page.leadingAssistant != null) {
                        seedReplayTools(cursor, page.leadingAssistant);
                    }
                    long started = SystemClock.elapsedRealtime();
                    int count = 0;
                    while (next < messages.size() && count < HISTORY_FRAME_SIZE) {
                        renderSlice(messages, next, next + 1, cursor);
                        next++;
                        count++;
                        if (SystemClock.elapsedRealtime() - started >= 6) break;
                    }
                    if (next == messages.size()) {
                        fillReplayResults(cursor, page.trailingResults);
                        closeReplayTurn(cursor.turn, cursor.rows);
                    }
                } finally {
                    renderHost = previous;
                }
                if (next < messages.size()) stream.postOnAnimation(this);
                else {
                    if (latest) {
                        replayTailTrace = cursor.turn;
                        replayTailRows = cursor.rows;
                    }
                    complete.run();
                }
            }
        };
        stream.postOnAnimation(frame);
    }

    private void seedReplayTools(ReplayCursor cursor, Message leading) {
        if (leading.toolCalls == null || PromptGuard.requestsDisclosure(cursor.request)) return;
        cursor.turn = new TurnTrace();
        cursor.rows = addTurnSummary(cursor.turn, false);
        for (int i = 0; i < leading.toolCalls.length(); i++) {
            JSONObject call = leading.toolCalls.optJSONObject(i);
            if (call == null) continue;
            JSONObject fn = call.optJSONObject("function");
            cursor.turn.addStep(call.optString("id", ""),
                    fn == null ? "tool" : fn.optString("name", "tool"),
                    fn == null ? "" : fn.optString("arguments", ""));
        }
        cursor.rendered = appendFoldRows(cursor.rows, cursor.turn, 0);
    }

    private void fillReplayResults(ReplayCursor cursor, List<Message> results) {
        if (cursor.turn == null || results == null || PromptGuard.requestsDisclosure(cursor.request)) return;
        for (Message result : results) {
            for (TurnTrace.Step step : cursor.turn.steps) {
                if (result.toolCallId != null && result.toolCallId.equals(step.id) && !step.done) {
                    cursor.turn.fillResult(result.toolCallId, "", result.content == null ? "" : result.content);
                    break;
                }
            }
        }
        refreshFoldResults(cursor.rows);
        refreshAllFolds(flowOf(cursor.rows));
    }

    private void addEarlierRow(long count) {
        final TextView row = new TextView(this);
        earlierRow = row;
        row.setText(getString(R.string.earlier_messages, Long.valueOf(count)));
        row.setTextSize(14);
        row.setTextColor(0xFFAEAEB2);
        row.setPadding(0, dp(10), 0, dp(8));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadEarlierPage(row);
            }
        });
        stream.addView(row, fullWidth());
    }

    private void loadEarlierPage(final TextView row) {
        if (earlierLoading || initialHistoryLoading || earlierBeforeId <= 0
                || row != earlierRow || stream.indexOfChild(row) < 0) return;
        earlierLoading = true;
        row.setEnabled(false);
        row.setText(R.string.history_loading);
        final long sid = sessionId;
        final long before = earlierBeforeId;
        final int token = historyToken;
        historyReader.execute(new Runnable() {
            @Override public void run() {
                try {
                    final ChatStore.MessagePage page = chatStore.messagePage(sid, before, HISTORY_PAGE_SIZE);
                    ui(new Runnable() {
                        @Override public void run() {
                            if (token != historyToken || sid != sessionId || row != earlierRow) return;
                            final LinearLayout block = new LinearLayout(MainActivity.this);
                            block.setOrientation(LinearLayout.VERTICAL);
                            renderPage(page, block, token, new Runnable() {
                                @Override public void run() {
                                    insertEarlierPage(row, block, page);
                                }
                            }, false);
                        }
                    });
                } catch (Exception error) {
                    ui(new Runnable() {
                        @Override public void run() {
                            if (token != historyToken || row != earlierRow) return;
                            earlierLoading = false;
                            row.setEnabled(true);
                            row.setText(R.string.history_retry);
                        }
                    });
                }
            }
        });
    }

    /** Prepending changes content height; restore the same view at the same screen offset. */
    private void insertEarlierPage(final TextView row, LinearLayout block, ChatStore.MessagePage page) {
        int index = stream.indexOfChild(row);
        if (index < 0) return;
        final View anchor = stream.getChildCount() > index + 1 ? stream.getChildAt(index + 1) : row;
        final int offset = anchor.getTop() - scroll.getScrollY();
        final int token = historyToken;
        final int action = scrollActionToken;
        historyInserting = true;
        stream.addView(block, index + 1, fullWidth());
        earlierBeforeId = page.firstId;
        if (page.earlierCount > 0) {
            row.setText(getString(R.string.earlier_messages, Long.valueOf(page.earlierCount)));
        } else {
            stream.removeView(row);
            earlierRow = null;
        }
        scroll.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                scroll.getViewTreeObserver().removeOnPreDrawListener(this);
                if (token != historyToken) return true;
                if (action == scrollActionToken && anchor.getParent() == stream) {
                    scroll.scrollTo(0, Math.max(0, anchor.getTop() - offset));
                }
                historyInserting = false;
                earlierLoading = false;
                row.setEnabled(true);
                followLatest = stuckAtEnd();
                updateLatestButton();
                scheduleFrost();
                return true;
            }
        });
    }

    private LinearLayout host() {
        return renderHost != null ? renderHost : stream;
    }

    private void renderRange(List<Message> messages, int from, int to) {
        if (messages == null) {
            return;
        }
        ReplayCursor cursor = new ReplayCursor();
        int start = Math.max(0, from);
        int end = Math.min(to, messages.size());
        for (int i = 0; i < Math.min(start, end); i++) {
            Message earlier = messages.get(i);
            if (earlier != null && Message.USER.equals(earlier.role)) {
                cursor.request = Goal.isSteer(earlier.content) || Goal.isNote(earlier.content)
                        ? "" : earlier.content;
            }
        }
        renderSlice(messages, start, end, cursor);
        closeReplayTurn(cursor.turn, cursor.rows);
        replayTailTrace = cursor.turn;
        replayTailRows = cursor.rows;
    }

    private void renderSlice(List<Message> messages, int start, int end, ReplayCursor cursor) {
        TurnTrace turn = cursor.turn;
        LinearLayout rows = cursor.rows;
        int rendered = cursor.rendered;
        String request = cursor.request;
        for (int i = start; i < end; i++) {
            Message m = messages.get(i);
            if (m == null) {
                continue;
            }
            if (Message.USER.equals(m.role)) {
                if (Goal.isSteer(m.content) || Goal.isNote(m.content)) {
                    // 续跑不是用户新说的话：不画进对话，也不继承上一句的披露请求。
                    request = "";
                    continue;
                }
                closeReplayTurn(turn, rows);
                turn = null;
                rows = null;
                rendered = 0;
                request = m.content;
                addUserBubble(m.content == null ? "" : m.content, m.workDir);
            } else if (Message.ASSISTANT.equals(m.role)) {
                String content = PromptGuard.redact(m.content, settings.systemPrompt(),
                        settings.environmentContext(), Compactor.PROMPT, request);
                String reasoning = PromptGuard.redact(m.reasoning, settings.systemPrompt(),
                        settings.environmentContext(), Compactor.PROMPT, request);
                JSONArray calls = PromptGuard.requestsDisclosure(request) ? null : m.toolCalls;
                boolean detail = reasoning.trim().length() > 0
                        || (calls != null && calls.length() > 0)
                        || m.elapsedMs > 0 || m.displayParts != null;
                if (detail && turn == null) {
                    turn = new TurnTrace();
                    rows = addTurnSummary(turn, false);
                    rendered = 0;
                }
                if (turn != null) {
                    if (m.elapsedMs > turn.elapsedMs) {
                        turn.elapsedMs = m.elapsedMs;
                    }
                    if (m.thinkMs > turn.thinkMs) {
                        turn.thinkMs = m.thinkMs;
                    }
                    if (m.hasDisplayParts(content, reasoning, calls)) {
                        rendered = renderDisplayParts(m, content, reasoning, calls, turn, rows, rendered);
                        continue;
                    }
                    if (reasoning.trim().length() > 0) {
                        turn.appendThink(reasoning);
                        if (m.thinkMs > 0) {
                            TurnTrace.Piece last = turn.order.get(turn.order.size() - 1);
                            if (last.think != null && last.thinkMs <= 0) last.thinkMs = m.thinkMs;
                        }
                        turn.sealThink();
                    }
                    rendered = appendFoldRows(rows, turn, rendered);
                }
                // 一次回复里正文先落位，命令排在正文后面。顺序错了命令会跑到前面。
                if (content.length() > 0) {
                    if (rows != null) {
                        addBodyInto(rows, content);
                    } else {
                        addAgentText(content);
                    }
                }
                if (turn != null) {
                    if (calls != null) {
                        for (int c = 0; c < calls.length(); c++) {
                            JSONObject call = calls.optJSONObject(c);
                            if (call == null) {
                                continue;
                            }
                            JSONObject fn = call.optJSONObject("function");
                            String name = fn == null ? "tool" : fn.optString("name", "tool");
                            String args = fn == null ? "" : fn.optString("arguments", "");
                            turn.addStep(call.optString("id", ""), name, args);
                        }
                    }
                    rendered = appendFoldRows(rows, turn, rendered);
                }
            } else if (Message.TOOL.equals(m.role)) {
                if (PromptGuard.requestsDisclosure(request)) {
                    continue;
                }
                if (turn == null) {
                    turn = new TurnTrace();
                    rows = addTurnSummary(turn, false);
                    rendered = 0;
                }
                turn.fillResult(m.toolCallId, "", m.content == null ? "" : m.content);
                refreshFoldResults(rows);
                refreshAllFolds(flowOf(rows));
            }
        }
        cursor.turn = turn;
        cursor.rows = rows;
        cursor.rendered = rendered;
        cursor.request = request;
    }

    private int renderDisplayParts(Message message, String content, String reasoning,
            JSONArray calls, TurnTrace turn, LinearLayout rows, int rendered) {
        for (int i = 0; i < message.displayParts.length(); i++) {
            JSONObject part = message.displayParts.optJSONObject(i);
            String type = part.optString("type");
            if ("think".equals(type)) {
                turn.appendThink(reasoning.substring(part.optInt("from"), part.optInt("to")));
                turn.sealThink();
                rendered = appendFoldRows(rows, turn, rendered);
            } else if ("body".equals(type)) {
                turn.sealThink();
                rendered = appendFoldRows(rows, turn, rendered);
                addBodyInto(rows, content.substring(part.optInt("from"), part.optInt("to")));
            } else if ("tool".equals(type)) {
                JSONObject call = calls.optJSONObject(part.optInt("index"));
                JSONObject fn = call == null ? null : call.optJSONObject("function");
                if (call != null) turn.addStep(call.optString("id", ""),
                        fn == null ? "tool" : fn.optString("name", "tool"),
                        fn == null ? "" : fn.optString("arguments", ""));
                rendered = appendFoldRows(rows, turn, rendered);
            }
        }
        turn.sealThink();
        return rendered;
    }

    private void closeReplayTurn(TurnTrace turn, LinearLayout rows) {
        if (turn == null) return;
        turn.stampThinkIfMissing(turn.thinkMs);
        for (TurnTrace.Piece piece : turn.order) if (piece.think != null) piece.sealed = true;
        refreshFoldResults(rows);
        bindSummary(summaryHeader(rows), turn);
        syncWorkChevron(summaryChevron(rows), turn);
    }

    /** 盒子里第 index 个孩子是折叠段就返回它，否则 null。 */
    

    /** 工作时间那一行的字。外层是横条，字在横条里面，不在盒子的第一个子视图上。 */
    private TextView summaryHeader(LinearLayout rows) {
        if (rows == null || !(rows.getParent() instanceof LinearLayout)) {
            return null;
        }
        View head = ((LinearLayout) rows.getParent()).getChildAt(0);
        if (head instanceof TextView) {
            return (TextView) head;
        }
        if (head instanceof LinearLayout && ((LinearLayout) head).getChildCount() > 0) {
            View text = ((LinearLayout) head).getChildAt(0);
            if (text instanceof TextView) {
                return (TextView) text;
            }
        }
        return null;
    }

    private View summaryChevron(LinearLayout rows) {
        if (rows == null || !(rows.getParent() instanceof LinearLayout)) {
            return null;
        }
        View head = ((LinearLayout) rows.getParent()).getChildAt(0);
        if (!(head instanceof LinearLayout) || ((LinearLayout) head).getChildCount() < 2) {
            return null;
        }
        return ((LinearLayout) head).getChildAt(1);
    }

    /** 折叠里没有思考也没有命令时不画箭头。空壳点开什么都没有。 */
    private void syncWorkChevron(View chevron, TurnTrace trace) {
        if (chevron == null) return;
        int end = trace == null ? 0 : trace.bodyAt < 0 ? trace.order.size() : trace.bodyAt;
        TurnTrace.Range range = trace == null ? null : new TurnTrace.Range(trace, 0);
        if (range != null) range.end = end;
        chevron.setVisibility(range != null && range.hasDetail() ? View.VISIBLE : View.GONE);
    }

    

    /** 正文不进折叠。按发生顺序落到这一段正文里；命令之后新来的正文明起一段。 */
    private void addBodyInto(LinearLayout rows, String text) {
        TextView tv = new TextView(this);
        tv.setText(Markdown.render(visibleText(text),
                getResources().getColor(R.color.code_bg)));
        tv.setTextSize(16);
        tv.setTextColor(getResources().getColor(R.color.text_primary));
        tv.setLineSpacing(dp(5), 1f);
        tv.setPadding(0, dp(4), 0, dp(8));
        enableCopy(tv);
        Flow flow = flowOf(rows);
        LinearLayout slot = flow != null ? bodySlot(flow) : rows;
        if (slot == null) {
            slot = rows;
        }
        TurnTrace trace = traceOf(rows);
        if (trace != null && trace.bodyAt < 0) {
            trace.bodyAt = trace.order.size();
        }
        if (rows == turnRows) {
            turnBody = slot;
        }
        slot.addView(tv, fullWidth());
    }

    private TurnTrace traceOf(LinearLayout rows) {
        if (rows == null || !(rows.getParent() instanceof LinearLayout)) {
            return null;
        }
        View header = ((LinearLayout) rows.getParent()).getChildAt(0);
        if (header != null && header.getTag() instanceof TurnTrace) {
            return (TurnTrace) header.getTag();
        }
        return null;
    }

    /**
     * 显示用的文本。
     *
     * 交接摘要开头的固定前缀是发给模型看的，摊在对话里就是一大段英文，
     * 还容易被当成「旧上下文又回来了」。显示时只留摘要本身。
     */
    private static String visibleText(String text) {
        if (text == null || !text.startsWith(Compactor.SUMMARY_PREFIX)) {
            return text;
        }
        String body = text.substring(Compactor.SUMMARY_PREFIX.length()).trim();
        return body.length() == 0 ? text : body;
    }

    private boolean sheetOverlayVisible() {
        View overlay = findViewById(R.id.sheet_overlay);
        return overlay != null && overlay.getVisibility() == View.VISIBLE;
    }

    /**
     * 显示面板。
     *
     * 高度不设死：wrap_content 让面板只占内容那么高，工具详情那类长内容
     * 由内部 ScrollView 自己滚动。之前给面板写死屏高，收起后没还原，
     * 后面每个面板都跟着变高，底下留一大片空白。
     */
    private void showSheet() {
        resetSheetDetails();
        View panel = findViewById(R.id.sheet_panel);
        View overlay = findViewById(R.id.sheet_overlay);
        View scrim = findViewById(R.id.sheet_scrim);
        if (panel == null || overlay == null) {
            return;
        }
        // 弹出面板时收起键盘，否则面板会被键盘顶掉一半。
        hideKeyboard();
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        panel.setLayoutParams(lp);
        sheetToken++;
        if (Build.VERSION.SDK_INT >= 21) getWindow().setNavigationBarColor(Color.WHITE);
        overlay.setVisibility(View.VISIBLE);
        panel.startAnimation(AnimationUtils.loadAnimation(this, R.anim.sheet_in));
        if (scrim != null) {
            scrim.startAnimation(AnimationUtils.loadAnimation(this, R.anim.scrim_in));
        }
    }

    /** 收起软键盘。面板要占满底部时，键盘不该跟着一起顶上来。 */
    private void hideKeyboard() {
        View focus = getCurrentFocus();
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            View target = focus != null ? focus : prompt;
            if (target != null) imm.hideSoftInputFromWindow(target.getWindowToken(), 0);
        }
        if (focus != null) focus.clearFocus();
        View root = findViewById(R.id.main_root);
        if (root != null) root.requestFocus();
    }

    private void resetSheetDetails() {
        View body = findViewById(R.id.sheet_body);
        if (body != null) body.removeCallbacks(sheetRefresh);
        LinearLayout panel = (LinearLayout) findViewById(R.id.sheet_panel);
        if (panel != null) {
            for (int i = panel.getChildCount() - 1; i >= 0; i--)
                if (panel.getChildAt(i) instanceof WorkTimeline.CommandView) panel.removeViewAt(i);
        }
        sheetCommand = null;
        sheetTrace = null;
        sheetRange = null;
        sheetTimeline = null;
        View scrollView = findViewById(R.id.sheet_scroll);
        if (scrollView != null) scrollView.setVisibility(View.VISIBLE);
    }

    private void fitActivitySheet() {
        if (sheetTimeline == null) return;
        View panel = findViewById(R.id.sheet_panel);
        int width = getResources().getDisplayMetrics().widthPixels - panel.getPaddingLeft() - panel.getPaddingRight();
        sheetTimeline.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, width), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = sheetTimeline.getMeasuredHeight() + panel.getPaddingTop() + panel.getPaddingBottom() + dp(40);
        height = Math.min(height, (int) (getResources().getDisplayMetrics().heightPixels * .72f));
        ViewGroup.LayoutParams lp = panel.getLayoutParams();
        if (lp.height != height) { lp.height = height; panel.setLayoutParams(lp); }
    }

    private void showActivitySheet(final TurnTrace.Range range) {
        if (range == null || !range.hasDetail()) return;
        LinearLayout body = (LinearLayout) findViewById(R.id.sheet_body);
        if (body == null) return;
        showSheet();
        body.removeAllViews();
        sheetTrace = range.trace;
        sheetRange = range;
        sheetTimeline = new WorkTimeline(this, activityActions());
        body.addView(sheetTimeline, fullWidth());
        syncSheetTools();
        fitActivitySheet();
        body.postDelayed(sheetRefresh, 750);
    }

    private void hideWorkSheet() {
        final View overlay = findViewById(R.id.sheet_overlay);
        View panel = findViewById(R.id.sheet_panel);
        View scrim = findViewById(R.id.sheet_scrim);
        View body = findViewById(R.id.sheet_body);
        if (body != null) body.removeCallbacks(sheetRefresh);
        sheetTrace = null; sheetTimeline = null; sheetRange = null;
        if (overlay == null || overlay.getVisibility() != View.VISIBLE) return;
        sheetToken++;
        final int token = sheetToken;
        if (panel != null) panel.startAnimation(AnimationUtils.loadAnimation(this, R.anim.sheet_out));
        if (scrim != null) scrim.startAnimation(AnimationUtils.loadAnimation(this, R.anim.scrim_out));
        overlay.postDelayed(new Runnable() {
            public void run() {
                if (token != sheetToken) return;
                overlay.setVisibility(View.GONE);
                resetSheetDetails();
                if (Build.VERSION.SDK_INT >= 21) getWindow().setNavigationBarColor(Color.TRANSPARENT);
            }
        }, 220);
    }
/**
     * 一段思考。出现在哪次工具调用后面，就画在那条工具后面。
     *
     * 标题按这一轮的思考耗时写成「思考了 Ns」，和对话里那行摘要的口径一致。
     */
    

    

    

    

    

    

    /** 压缩指令只该出现在压缩请求里，回放会话时剔除。 */
    private static List<Message> stripCompactionAsks(List<Message> raw) {
        List<Message> out = new ArrayList<Message>();
        if (raw == null) {
            return out;
        }
        for (int i = 0; i < raw.size(); i++) {
            Message m = raw.get(i);
            if (m != null && Message.USER.equals(m.role)
                    && Compactor.PROMPT.equals(m.content)) {
                continue;
            }
            out.add(m);
        }
        return out;
    }

    /** 中断的工具调用补一条失败结果，避免下次请求因缺 tool 消息被拒。 */
    private List<Message> withToolResults(List<Message> raw) {
        List<Message> out = new ArrayList<Message>();
        if (raw == null) {
            return out;
        }
        for (int i = 0; i < raw.size(); i++) {
            Message m = raw.get(i);
            out.add(m);
            if (m == null || !Message.ASSISTANT.equals(m.role) || m.toolCalls == null) {
                continue;
            }
            for (int c = 0; c < m.toolCalls.length(); c++) {
                JSONObject call = m.toolCalls.optJSONObject(c);
                if (call == null) {
                    continue;
                }
                String id = call.optString("id", "");
                if (!hasToolResult(raw, i + 1, id)) {
                    out.add(Message.toolResult(id, AgentLoop.FAIL_PREFIX + "会话中断，没有结果。"));
                }
            }
        }
        return out;
    }

    private boolean hasToolResult(List<Message> messages, int from, String id) {
        for (int i = from; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m == null) {
                continue;
            }
            if (Message.USER.equals(m.role) || Message.ASSISTANT.equals(m.role)) {
                return false;
            }
            String callId = m.toolCallId == null ? "" : m.toolCallId;
            if (Message.TOOL.equals(m.role) && id.equals(callId)) {
                return true;
            }
        }
        return false;
    }

    /** 工具调用：一行小字，点开看命令与输出。 */
    

    

    /** 工具行标题：小箭头 + 名称 + 状态，箭头用图标。 */
    

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void enableCopy(final TextView tv) {
        tv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                ClipboardManager cm = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("backcast", tv.getText()));
                    toast(getString(R.string.copied));
                }
                return true;
            }
        });
    }

    /** 工具结果是否算失败：异常前缀、显式错误、或命令超时。 */
    private static boolean isFailure(String result) {
        if (result == null) {
            return false;
        }
        return result.startsWith(AgentLoop.FAIL_PREFIX)
                || result.startsWith("错误：")
                || result.indexOf("命令超时") >= 0;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private static String trim(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 4000 ? s : s.substring(0, 4000) + "\n…（已截断）";
    }

    private void ui(Runnable r) {
        runOnUiThread(r);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 压缩重画之后停在最新一条，不回到摘要开头。 */
    private void scrollToLatest() {
        if (scroll == null) {
            return;
        }
        followLatest = true;
        final int token = historyToken;
        final int action = scrollActionToken;
        scroll.post(new Runnable() {
            @Override
            public void run() {
                if (token != historyToken || action != scrollActionToken || !followLatest) return;
                scroll.scrollTo(0, latestScrollY());
                updateLatestButton();
            }
        });
    }

    private boolean isGoalCommand(String text) {
        if (text == null || text.length() < 5
                || !text.regionMatches(true, 0, "/goal", 0, 5)) {
            return false;
        }
        if (text.length() == 5) {
            return true;
        }
        return Character.isWhitespace(text.charAt(5));
    }
    /**
     * 目标因为预算用尽或原地打转停下时，给用户一句明确交代。
     *
     * 同一种状态只提示一次；用户点 ▶ 继续后状态变回 active，之后还能再提示。
     */
    private void announceGoalStop() {
        if (loop == null) {
            return;
        }
        String status = loop.goalStatus();
        if (status.equals(announcedGoalStatus)) {
            return;
        }
        announcedGoalStatus = status;
        if (Goal.BUDGET_LIMITED.equals(status)) {
            toast(getString(R.string.goal_budget_limited));
        }
    }
    /** 目标卡片的预算标签：没设预算就不占位置。 */
    private String budgetText() {
        if (loop == null) {
            return "";
        }
        long used = loop.goalTokensUsed();
        long budget = loop.goalTokenBudget();
        if (budget > 0) {
            return used + " / " + budget;
        }
        return used > 0 ? String.valueOf(used) : "";
    }
    /**
     * 长按预算那一栏改预算。
     *
     * 留空或 0 表示不设上限，跟 Codex 的可选预算一致：
     * 不设就一直跑到模型自己完成；设了就按 token 记账，到顶软收尾。
     */
    private void editGoalBudget() {
        if (!prepareEngine() || loop == null || loop.goalText().length() == 0) {
            return;
        }
        final EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.goal_budget_hint);
        long budget = loop.goalTokenBudget();
        input.setText(budget > 0 ? String.valueOf(budget) : "");
        input.setTextSize(15);
        new AlertDialog.Builder(this)
                .setTitle(R.string.goal_budget_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                long tokens = 0;
                                try {
                                    tokens = Long.parseLong(input.getText().toString().trim());
                                } catch (Exception invalid) {
                                    tokens = 0;
                                }
                                loop.setGoalBudget(tokens);
                                refreshGoal();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
    /** 卡片上的编辑只改文字。斜杠设下的新目标会开始跑。 */
    private void editGoal(final boolean renameOnly) {
        if (!prepareEngine()) {
            return;
        }
        final EditText input = new EditText(this);
        input.setText(loop == null ? "" : loop.goalText());
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setTextSize(15);
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(R.string.goal_edit)
                .setView(input)
                .setPositiveButton(android.R.string.ok,
                        new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                boolean rename = renameOnly && loop != null
                                        && loop.goalText().length() > 0;
                                commitGoal(input.getText().toString().trim(), rename);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void commitGoal(String text, boolean renameOnly) {
        if (!prepareEngine() || loop == null) return;
        if (text.length() == 0) {
            loop.clearGoal();
            refreshGoal();
        } else if (renameOnly) {
            loop.renameGoal(text);
            refreshGoal();
        } else {
            startText(text, true);
        }
    }

    /** 清掉这一次目标，并停掉它还在跑的那一轮。不回滚文件。 */
    private void dropGoal() {
        if (loop == null || loop.goalText().length() == 0) {
            return;
        }
        liveToken++;
        boolean busy = loop.busy();
        loop.clearGoal();
        if (busy) {
            loop.cancel();
            hidePending();
            settleWork();
            if (compactLive) {
                dropCompactRow();
            } else {
                settleCompact();
            }
            setBusy(false);
        }
        refreshGoal();
    }

    private void toggleGoalRun() {
        if (loop == null || loop.goalText().length() == 0) {
            editGoal(false);
            return;
        }
        if (loop.goalActive() && loop.busy()) {
            liveToken++;
            loop.pauseGoal();
            loop.cancel();
            hidePending();
            settleWork();
            setBusy(false);
            refreshGoal();
            return;
        }
        if (!prepareEngine()) {
            return;
        }
        loop.markGoalActive();
        kick(loop);
    }

    private long continueSid = Long.MIN_VALUE;
    private long continueKickAt;

    /**
     * 进程被杀掉之后，库里还记着在跑，这边的循环却已经没了。
     * 回到这个会话就接上。正在跑的不会从这里再开一轮。
     */
    private void maybeContinue() {
        if (sessionOpening || initialHistoryLoading || loop == null || loop.busy() || sessionId < 0 || chatStore == null
                || settings == null || !settings.isConfigured()) {
            return;
        }
        if (Goal.PAUSED.equals(loop.goalStatus())) {
            return;
        }
        ChatStore.Run run = chatStore.readRun(sessionId);
        if (run == null || !run.running || !loop.needsResume()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (continueSid == sessionId && now - continueKickAt < 1500L) {
            return;
        }
        continueSid = sessionId;
        continueKickAt = now;
        kick(loop);
    }

    private void kick(final AgentLoop target) {
        if (target == null) {
            return;
        }
        final long sid = target.sessionKey();
        liveToken++;
        final int token = liveToken;
        if (target == loop) {
            settleCompact();
            turnUiToken = -1;
            // 续跑是同一条用户请求的下一段：接着回放画出的那一行走。
            // 接不上才另起，避免重进后多出一行「工作了」。
            if (workHeader == null && !adoptRunningTurn()) {
                adoptLoopClock();
                beginWorkRow();
            }
            setBusy(true);
            refreshGoal();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (token != liveToken && target == loop) {
                    return;
                }
                target.resume(sid, token);
            }
        }).start();
    }

    private void refreshGoal() {
        if (goalBar == null) {
            return;
        }
        boolean show = loop != null && loop.goalOpen();
        goalBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            announceGoalStop();
        }
        if (!show) {
            goalTick++;
            goalBar.removeCallbacks(goalTicker);
            return;
        }
        paintGoal();
        goalBar.removeCallbacks(goalTicker);
        if (loop.goalActive() && loop.busy()) {
            goalBar.postDelayed(goalTicker, 500);
        }
    }

    private void paintGoal() {
        if (goalLabel == null || loop == null) {
            return;
        }
        goalLabel.setText(loop.goalText());
        if (goalTime != null) {
            goalTime.setText(loop.goalElapsed() <= 0 ? "" : Goal.clock(loop.goalElapsed()));
        }
        if (goalBudgetLabel != null) {
            goalBudgetLabel.setText(budgetText());
        }
        if (goalToggle != null) {
            boolean running = loop.goalActive() && loop.busy();
            goalToggle.setImageDrawable(Icons.tinted(this,
                    running ? Icons.PAUSE : Icons.PLAY, 0xFF3C3C43, dp(16)));
        }
    }

    /** 用户已经往上翻时不打断他；只在贴近底部时自动跟随。 */
    private void autoScroll() {
        if (scroll == null || renderHost != null || historyInserting || autoScrollQueued) return;
        autoScrollQueued = true;
        final int token = historyToken;
        final int action = scrollActionToken;
        scroll.postOnAnimation(new Runnable() {
            @Override
            public void run() {
                autoScrollQueued = false;
                if (token != historyToken || action != scrollActionToken) return;
                if (followLatest && !initialHistoryLoading && !historyInserting)
                    scroll.scrollTo(0, latestScrollY());
                updateLatestButton();
                scheduleFrost();
            }
        });
    }
}
