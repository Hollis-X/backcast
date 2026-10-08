import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.SubAgentManager;
import com.mkei.backcast.ui.AgentPanelState;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Runs the production panel renderer against views that record their visible content. */
public final class AgentPanelRegressionTest {
    private static Class<?> panelType;
    private static int passed;
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }
    private static Object get(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static Object invoke(Object target, String name, Object... args) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) if (name.equals(method.getName()) && method.getParameterCount() == args.length) {
                method.setAccessible(true); return method.invoke(target, args);
            }
        }
        throw new NoSuchMethodException(name);
    }
    private static Object panel() throws Exception { return panelType.getDeclaredConstructor().newInstance(); }
    private static String visible(Object panel) throws Exception { return (String) invoke(panel, "visible"); }
    private static SubAgentManager.Record record(String id, String status, String phase) {
        SubAgentManager.Record record = new SubAgentManager.Record();
        record.id = id; record.name = "检查 SVG"; record.parentId = "main";
        record.task = "读取飞机 SVG 文件，核验坐标和标签并报告证据"; record.status = status; record.phase = phase; record.revision = 1;
        return record;
    }
    private static void listShowsCompactTaskStatusWithoutResultsOrJson() throws Exception {
        Object panel = panel();
        SubAgentManager.Record row = record("agent_a", SubAgentManager.RUNNING, "tool");
        row.activeTool = "shell"; row.result = "RESULT_MUST_NOT_BE_IN_LIST"; row.error = "ERROR_MUST_NOT_BE_IN_LIST";
        row.history.put(Message.system("SYSTEM_MUST_NOT_BE_IN_LIST").toCheckpointJson());
        invoke(panel, "renderList", Arrays.asList(row));
        String text = visible(panel);
        check(text.contains("检查 SVG") && text.contains("读取飞机 SVG") && text.contains("执行工具") && text.contains("shell"), "List loses assignment or current work");
        check(!text.contains("RESULT_MUST") && !text.contains("ERROR_MUST") && !text.contains("SYSTEM_MUST") && !text.contains("\"parentId\""), "List includes raw record payload");
        Object body = get(panel, "body");
        List<?> children = (List<?>) get(body, "children");
        invoke(children.get(0), "click");
        AgentPanelState state = (AgentPanelState) get(panel, "state");
        check(state.selectedId.equals(row.id) && state.tab == AgentPanelState.TASK && (Boolean) get(panel, "loadCalled"), "Task row did not open its detail");
        pass("compactListOpensARealAssignmentAndKeepsResultsOutOfRows");
    }
    private static void taskActivityAndResultRemainSeparate() throws Exception {
        Object panel = panel();
        SubAgentManager.Record row = record("agent_a", SubAgentManager.RUNNING, "tool");
        row.activeTool = "shell"; row.progress = "正在读取 SVG 并核验坐标"; row.lastActivityAt = 1801306800000L;
        row.result = "ONLY_RESULT_TAB_SENTINEL"; row.error = "ONLY_ERROR_TAB_SENTINEL";
        row.history.put(Message.assistant("ONLY_ACTIVITY_TAB_SENTINEL", null).toCheckpointJson());
        set(panel, "selected", row);
        invoke(panel, "renderDetail", false);
        String task = visible(panel);
        check(task.contains(row.task) && task.contains(row.progress) && task.contains(row.id) && task.contains("shell"), "Task view does not expose assignment and real progress");
        check(task.contains("最近活动") && !task.contains("ONLY_RESULT_TAB") && !task.contains("ONLY_ACTIVITY_TAB"), "Task tab mixes results or misses time");
        AgentPanelState state = (AgentPanelState) get(panel, "state"); state.tab = AgentPanelState.ACTIVITY;
        invoke(panel, "renderDetail", false);
        check(visible(panel).contains("ONLY_ACTIVITY_TAB") && !visible(panel).contains(row.task) && !visible(panel).contains("ONLY_RESULT_TAB"), "Activity tab is not separate");
        state.tab = 2; invoke(panel, "renderDetail", false);
        check(visible(panel).contains("ONLY_RESULT_TAB") && !visible(panel).contains("ONLY_ERROR_TAB")
                        && visible(panel).contains("本次执行未完成") && !visible(panel).contains("ONLY_ACTIVITY_TAB"),
                "Result view loses evidence, exposes raw errors, or mixes activity");
        pass("taskActivityResultAndErrorHaveDistinctViewsWithActualProgressAndTime");
    }
    private static void preciseStagesDoNotClaimToolsAlreadyExecuted() throws Exception {
        Object panel = panel();
        String[][] stages = {{"generating_tool", "生成工具参数"}, {"tool_ready", "参数已完成"}, {"tool_review", "AI 审查"},
                {"tool_approval", "等待用户确认"}, {"waiting", "等待子任务进度"}, {"children", "等待子任务"}};
        for (String[] stage : stages) {
            SubAgentManager.Record row = record("stages", SubAgentManager.RUNNING, stage[0]); row.activeTool = "shell";
            set(panel, "selected", row); set(panel, "rendered", ""); invoke(panel, "renderDetail", false);
            String shown = visible(panel);
            check(shown.contains(stage[1]) && !shown.contains("执行工具"), "Preview/review/approval/wait stage falsely claims execution: " + shown);
        }
        pass("toolGenerationReviewApprovalAndChildWaitingHaveDistinctVisibleStages");
    }
    private static void failedAssignmentKeepsItsTaskAndManualContinuationRefreshesItsState() throws Exception {
        Object panel = panel(); SubAgentManager.Record row = record("continued", SubAgentManager.RUNNING, "thinking");
        set(panel, "selected", row); invoke(panel, "renderDetail", false);
        check(visible(panel).contains(row.task), "Running assignment disappeared");
        row.status=SubAgentManager.FAILED;row.phase="failed";row.progress="RAW_FAILURE_PROGRESS";row.error="RAW_API_STACK";row.revision++;
        set(panel,"selected",row);invoke(panel,"renderDetail",false);
        check(visible(panel).contains("失败")&&visible(panel).contains(row.task)
                        &&!visible(panel).contains("RAW_FAILURE")&&!visible(panel).contains("RAW_API_STACK"),
                "Failed task became hidden or exposed API failure details");
        invoke(panel, "renderList", Arrays.asList(row));
        check(visible(panel).contains("失败") && !visible(panel).contains("RAW_API_STACK"), "Compact task list hid failure or leaked its private error");
        row.status = SubAgentManager.RUNNING; row.phase = "thinking"; row.progress = "MANUAL_CONTINUATION_PROGRESS";
        row.task = "NEW_USER_ASSIGNMENT"; row.error = ""; row.revision++;
        invoke(panel, "renderDetail", false);
        check(visible(panel).contains(row.task) && visible(panel).contains(row.progress)
                        && !visible(panel).contains("RAW_FAILURE_PROGRESS"), "User continuation retained the previous task failure or old assignment");
        pass("failedAssignmentKeepsItsTaskAndManualContinuationRefreshesItsState");
    }
    private static void activityPagesAreStableWhileNewHistoryArrives() throws Exception {
        Object panel = panel(); SubAgentManager.Record row = record("a", SubAgentManager.RUNNING, "reviewing");
        row.history.put(Message.system("SECRET_SYSTEM_SENTINEL").toCheckpointJson());
        for (int i = 0; i < 100; i++) row.history.put(Message.assistant("EVENT_" + i + "_END", null).toCheckpointJson());
        set(panel, "selected", row); AgentPanelState state = (AgentPanelState) get(panel, "state"); state.tab = AgentPanelState.ACTIVITY;
        invoke(panel, "renderDetail", false);
        check(visible(panel).contains("EVENT_60_END") && visible(panel).contains("EVENT_99_END") && !visible(panel).contains("EVENT_59_END"), "Latest activity page is not 40 rows");
        invoke(panel, "page", false);
        check(visible(panel).contains("EVENT_20_END") && visible(panel).contains("EVENT_59_END") && !visible(panel).contains("EVENT_60_END"), "Older page bounds incorrect");
        row.history.put(Message.assistant("EVENT_100_END", null).toCheckpointJson()); row.revision++;
        invoke(panel, "renderDetail", true);
        check(visible(panel).contains("EVENT_59_END") && !visible(panel).contains("EVENT_100_END"), "Live refresh moved reader away from old page");
        state.historyEnd = -1; invoke(panel, "renderDetail", false);
        check(visible(panel).contains("EVENT_100_END") && !visible(panel).contains("SECRET_SYSTEM"), "Latest navigation failed or exposed system message");
        pass("liveHistoryKeepsTheOlderPageAndLatestNavigationReturnsToNewEvents");
    }
    private static void resultPagingExposesTheFullResultWithoutOneHugeView() throws Exception {
        Object panel = panel(); SubAgentManager.Record row = record("a", SubAgentManager.IDLE, "completed");
        char[] first = new char[8000], second = new char[8000]; Arrays.fill(first, 'a'); Arrays.fill(second, 'b');
        row.result = new String(first) + new String(second) + "LAST_RESULT_PAGE";
        set(panel, "selected", row); AgentPanelState state = (AgentPanelState) get(panel, "state"); state.tab = 2;
        invoke(panel, "renderDetail", false);
        check(visible(panel).contains(new String(first)) && !visible(panel).contains("LAST_RESULT_PAGE"), "First result page isn't bounded");
        invoke(panel, "page", true); check(visible(panel).contains(new String(second)) && !visible(panel).contains("LAST_RESULT_PAGE"), "Second result page lost text");
        invoke(panel, "page", true); check(visible(panel).contains("LAST_RESULT_PAGE"), "Result suffix cannot be read");
        pass("resultPagesMakeAllTextReachableWithoutRenderingItAllAtOnce");
    }
    private static void refreshKeepsScrollAndClosedTasksDisableCommands() throws Exception {
        Object panel = panel(); SubAgentManager.Record row = record("a", SubAgentManager.RUNNING, "model"); set(panel, "selected", row);
        invoke(panel, "renderDetail", false); Object scroll = get(panel, "scroll"); set(scroll, "y", 210);
        row.progress = "继续检查"; row.revision++; invoke(panel, "renderDetail", true);
        check((Integer) get(scroll, "y") == 210, "Refresh jumped away from reading position");
        set(panel, "sending", true); invoke(panel, "renderDetail", true);
        check(!(Boolean) get(get(panel, "send"), "enabled") && (Boolean) get(get(panel, "message"), "enabled"), "Refresh re-enabled send during a pending message");
        set(panel, "sending", false);
        row.status = SubAgentManager.CLOSED; row.phase = SubAgentManager.CLOSED; row.revision++;
        invoke(panel, "renderDetail", true);
        check(!(Boolean) get(get(panel, "send"), "enabled") && !(Boolean) get(get(panel, "stop"), "enabled") && !(Boolean) get(get(panel, "message"), "enabled"), "Closed task still accepts messages or stop commands");
        row.status = SubAgentManager.FAILED; row.phase = "cancelled"; row.revision++; invoke(panel, "renderDetail", true);
        check(visible(panel).contains("已停止"), "Cancelled reusable task mislabeled as active or completed");
        pass("refreshPreservesReadingPositionAndClosedTasksCannotReceiveCommands");
    }
    private static void displayHistoryHidesTransportWrappersAndFormatsTools() throws Exception {
        SubAgentManager.Record row = record("a", SubAgentManager.RUNNING, "tool");
        row.history.put(Message.delegated("检查 SVG", "HIDDEN_FORK_REFERENCE").toCheckpointJson());
        Message coordination = Message.user("transport\n" + new JSONArray().put(new JSONObject().put("from", "main").put("text", "先检查坐标").put("id", "INTERNAL_MAIL_ID").put("usageLease", 7)));
        coordination.coordinationIds = new JSONArray().put("INTERNAL_MAIL_ID"); row.history.put(coordination.toCheckpointJson());
        JSONObject tool = new JSONObject().put("id", "INTERNAL_TOOL_ID").put("function", new JSONObject().put("name", "shell").put("arguments", new JSONObject().put("command", "wc -l plane.svg").toString()));
        row.history.put(Message.assistant("核验文件", new JSONArray().put(tool)).toCheckpointJson());
        List<AgentPanelState.Entry> entries = AgentPanelState.history(row, 0, 3, "", "");
        check(entries.get(0).text.equals("检查 SVG") && !entries.get(0).text.contains("FORK_REFERENCE"), "Displayed delegation leaks quoted context");
        check(entries.get(1).role.equals("message") && entries.get(1).text.contains("main:\n先检查坐标") && !entries.get(1).text.contains("INTERNAL_MAIL_ID"), "Mail is raw transport JSON");
        String command = entries.get(2).tools.get(0);
        check(command.contains("shell\ncommand: wc -l plane.svg") && !command.contains("INTERNAL_TOOL_ID"), "Tool invocation isn't human-readable");
        pass("historyFormatsTaskMailAndToolsWithoutRawTransportPayloads");
    }
    private static void boundedHistoryAndActiveSortUseActualData() throws Exception {
        SubAgentManager.Record idle = record("idle", SubAgentManager.IDLE, "completed"), running = record("run", SubAgentManager.RUNNING, "tool"), root = record("main", SubAgentManager.RUNNING, "tool");
        idle.lastActivityAt = 20; running.lastActivityAt = 10;
        List<SubAgentManager.Record> rows = AgentPanelState.ordered(Arrays.asList(idle, root, running));
        check(rows.size() == 2 && rows.get(0) == running && rows.get(1) == idle, "Active-first sort includes root or loses an idle record");
        char[] chars = new char[20000]; Arrays.fill(chars, 'x');
        for (int i = 0; i < 40; i++) running.history.put(Message.assistant(new String(chars), null).toCheckpointJson());
        int total = 0; for (AgentPanelState.Entry entry : AgentPanelState.history(running, 0, 40, "", "")) total += entry.text.length();
        check(total < 36000, "History page expands unbounded content");
        check(AgentPanelState.active(SubAgentManager.WAITING) && !AgentPanelState.active(SubAgentManager.IDLE) && !AgentPanelState.active(SubAgentManager.FAILED), "Status active state is fabricated");
        pass("historyPayloadIsBoundedAndActiveRecordsSortAheadOfCompletedWork");
    }
    private static void pollingStopsAndStaleLoadsCannotMutateNextVisit() throws Exception {
        AgentPanelState state = new AgentPanelState();
        check(state.beginLoad() < 0, "Hidden panel began a load");
        state.start(); long first = state.beginLoad();
        check(first >= 0 && state.beginLoad() < 0 && !state.mayPoll(true), "Concurrent refreshes can pile up");
        state.stop(); state.start(); long second = state.beginLoad();
        check(!state.finishLoad(first) && !state.mayPoll(true), "Old callback unlocked or changed the new visit");
        check(state.finishLoad(second) && state.mayPoll(true) && !state.mayPoll(false), "Live refresh did not resume after its own load");
        state.stop(); check(!state.mayPoll(true) && !state.finishLoad(second), "Hidden panel continues polling");
        Object panel=panel();invoke(panel,"showReadFailure");invoke(panel,"showReadFailure");
        check(((List<?>)get(panel,"toasts")).size()==1,"A repeated child-panel read failure spammed Toasts");
        set(panel,"readFailureAnnounced",false);invoke(panel,"showReadFailure");
        check(((List<?>)get(panel,"toasts")).size()==2,"A new read failure after recovery could not notify the user");
        pass("pollingAllowsOneLoadAndRejectsCallbacksFromPausedOrDestroyedVisits");
    }
    private static final class Source extends SimpleJavaFileObject {
        final String text;
        Source(String name, String text) { super(URI.create("string:///" + name + ".java"), Kind.SOURCE); this.text = text; }
        @Override public CharSequence getCharContent(boolean ignored) { return text; }
    }
    private static void reportsAndOlderOutcomesRemainVisibleAfterCancellation() throws Exception {
        Object panel = panel(); SubAgentManager.Record row = record("a", SubAgentManager.FAILED, "cancelled");
        row.currentTaskId = "new";
        row.tasks.put(new JSONObject().put("taskId", "old").put("request", "之前的工作"));
        row.tasks.put(new JSONObject().put("taskId", "new").put("request", "本次的工作").put("partial", "PARTIAL_FINDINGS"));
        row.events.put(new JSONObject().put("taskId", "new").put("kind", "message").put("from", "a")
                .put("to", "main").put("text", "REPORTED_EVIDENCE"));
        row.results.put(new JSONObject().put("taskId", "old").put("resultId", "r1").put("content", "OLD_PROOF"));
        row.results.put(new JSONObject().put("taskId", "old-stopped").put("resultId", "r0")
                .put("status", "stopped").put("partial", "OLD_PARTIAL_PROOF"));
        row.results.put(new JSONObject().put("taskId", "new").put("resultId", "r2")
                .put("status", "failed").put("partial", "SAVED_CURRENT_PARTIAL"));
        row.error = "PRIVATE_ERROR_TRACE"; set(panel, "selected", row);
        ((AgentPanelState) get(panel, "state")).tab = 2;
        invoke(panel, "renderDetail", false);
        String visible = visible(panel);
        check(visible.contains("PARTIAL_FINDINGS") && visible.contains("REPORTED_EVIDENCE") && visible.contains("OLD_PROOF")
                && visible.contains("OLD_PARTIAL_PROOF") && visible.contains("SAVED_CURRENT_PARTIAL"),
                "Cancellation hid partial findings, reports, or an earlier task outcome");
        check(!visible.contains("PRIVATE_ERROR_TRACE") && !visible.contains("已停止 · 已停止"),
                "The panel exposed private diagnostics or duplicated the terminal state");
        pass("reportsAndOlderOutcomesRemainVisibleAfterCancellation");
    }
    private static boolean clickReadMore(Object view) throws Exception {
        try {
            if (String.valueOf(get(view, "value")).equals("继续读取全文")) { invoke(view, "click"); return true; }
        } catch (NoSuchFieldException missing) { }
        try {
            List<?> children = new ArrayList<>((List<?>) get(view, "children"));
            for (Object child : children) if (clickReadMore(child)) return true;
        } catch (NoSuchFieldException missing) { }
        return false;
    }
    private static void fullActivityCanBeReadBeyondThePreviewBudget() throws Exception {
        Object panel = panel(); SubAgentManager.Record row = record("a", SubAgentManager.IDLE, "completed");
        char[] prefix = new char[25000]; Arrays.fill(prefix, 'x');
        row.history.put(Message.assistant(new String(prefix) + "ACTIVITY_FINAL_SUFFIX", null).toCheckpointJson());
        set(panel, "selected", row); ((AgentPanelState) get(panel, "state")).tab = AgentPanelState.ACTIVITY;
        invoke(panel, "renderDetail", false);
        check(!visible(panel).contains("ACTIVITY_FINAL_SUFFIX"), "A long activity was eagerly rendered without a preview");
        int clicked = 0;
        while (clickReadMore(get(panel, "body"))) check(++clicked < 20, "Read-more did not terminate");
        check(visible(panel).contains("ACTIVITY_FINAL_SUFFIX"), "The rest of a long activity was discarded");
        row.revision++; invoke(panel, "renderDetail", true);
        check(visible(panel).contains("ACTIVITY_FINAL_SUFFIX"), "A live refresh lost the expanded activity");
        pass("fullActivityCanBeReadBeyondThePreviewBudget");
    }
    private static void resultPagingPreservesUnicodeAtPageBoundaries() {
        char[] prefix = new char[7999]; Arrays.fill(prefix, 'a'); String result = new String(prefix) + "🚀尾部";
        AgentPanelState state = new AgentPanelState(); int[] first = state.resultBounds(result);
        state.resultOffset = AgentPanelState.RESULT_PAGE_SIZE; int[] second = state.resultBounds(result);
        check(first[1] == second[0] && (result.substring(first[0], first[1]) + result.substring(second[0], second[1])).equals(result),
                "Result pages lost, duplicated, or split a Unicode character");
        pass("resultPagingPreservesUnicodeAtPageBoundaries");
    }
    private static String quote(String text) { return JSONObject.quote(text); }
    private static String harness(Path root) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler(); Map<String,String> methods = new HashMap<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, files, null, Arrays.asList("-proc:none"), null,
                    files.getJavaFileObjects(root.resolve("app/src/main/java/com/mkei/backcast/SubAgentsActivity.java").toFile()));
            for (CompilationUnitTree unit : task.parse()) for (Tree member : ((ClassTree) unit.getTypeDecls().get(0)).getMembers())
                if (member instanceof MethodTree) methods.put(((MethodTree) member).getName().toString(), member.toString());
        }
        Map<String,String> strings = new HashMap<>();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        for (String name : Arrays.asList("strings.xml", "agent_panel_strings.xml")) {
            NodeList values = factory.newDocumentBuilder().parse(root.resolve("app/src/main/res/values/" + name).toFile()).getDocumentElement().getChildNodes();
            for (int i = 0; i < values.getLength(); i++) if (values.item(i) instanceof Element && values.item(i).getNodeName().equals("string")) {
                Element value = (Element) values.item(i); strings.put(value.getAttribute("name"), value.getTextContent());
            }
        }
        StringBuilder source = new StringBuilder("import java.util.*; import com.mkei.backcast.agent.*; import com.mkei.backcast.ui.AgentPanelState; public class PanelHarness {");
        source.append("static class R { static class string {"); int id = 1;
        for (String name : strings.keySet()) source.append("static final int ").append(name).append('=').append(id++).append(';');
        source.append("} static class drawable { static final int bg_chip_flat=1; } static class color { static final int transparent=0; }}\n");
        source.append("static Map<Integer,String> labels=new HashMap<Integer,String>(); static {");
        for (Map.Entry<String,String> string : strings.entrySet()) source.append("labels.put(R.string.").append(string.getKey()).append(',').append(quote(string.getValue())).append(");");
        source.append("} String getString(int id,Object...args){String text=labels.get(id); return args.length==0?text:String.format(Locale.ROOT,text,args);}\n");
        source.append("static class View { static final int VISIBLE=0,GONE=8; boolean enabled=true; int visibility; OnClickListener listener; View(){} View(Object owner){} interface OnClickListener {void onClick(View view);} void setVisibility(int value){visibility=value;} void setEnabled(boolean value){enabled=value;} boolean isEnabled(){return enabled;} void setOnClickListener(OnClickListener value){listener=value;} void click(){listener.onClick(this);} void setContentDescription(String value){} void setPadding(int a,int b,int c,int d){} void setBackgroundColor(int color){} void setLayoutParams(Object value){} }\n");
        source.append("static class ViewGroup extends View { static class LayoutParams { static final int MATCH_PARENT=-1,WRAP_CONTENT=-2; LayoutParams(int w,int h){} }}\n");
        source.append("static class TextView extends View { String value=\"\"; TextView(Object owner){} void setText(CharSequence text){value=text.toString();} void setText(int id){value=labels.get(id);} void setTextSize(int size){} void setTextColor(int color){} void setMaxLines(int lines){} void setEllipsize(Object value){} void setTextIsSelectable(boolean value){} void setLineSpacing(int extra,float multiplier){} void setBackgroundResource(int id){} void setTypeface(Object value){} void setHorizontallyScrolling(boolean v){}void setBreakStrategy(int v){}void setHyphenationFrequency(int v){} }\n");
        source.append("static class Layout {static final int BREAK_STRATEGY_SIMPLE=0,HYPHENATION_FREQUENCY_NONE=0;} static class Markdown{static CharSequence render(String text,int bg){return text;}}\n");
        source.append("static class ImageButton extends View {} static class EditText extends TextView {EditText(Object owner){super(owner);}} static class LinearLayout extends View { static final int VERTICAL=1; List<View> children=new ArrayList<View>(); LinearLayout(Object owner){} void setOrientation(int value){} void addView(View child){children.add(child);} void addView(View child,Object params){children.add(child);} void removeAllViews(){children.clear();}void removeView(View child){children.remove(child);} static class LayoutParams extends ViewGroup.LayoutParams {int topMargin,bottomMargin; LayoutParams(int w,int h){super(w,h);}}}\n");
        source.append("static class ScrollView extends View {int y; int getScrollY(){return y;} void scrollTo(int x,int y){this.y=y;} void post(Runnable action){action.run();}} static class TextUtils {static class TruncateAt {static final Object END=new Object();}} static class Icons {static final int CHEVRON_RIGHT=1; static void right(TextView view,int icon,int color,int size){}} static class Typeface {static final Object MONOSPACE=new Object();} static class DateFormat { static String format(String pattern,long at){java.text.SimpleDateFormat format=new java.text.SimpleDateFormat(pattern); format.setTimeZone(java.util.TimeZone.getTimeZone(\"UTC\")); return format.format(new java.util.Date(at));}}\n");
        source.append("static class Settings {String systemPrompt(){return \"\";} String environmentContext(){return \"\";}} AgentPanelState state=new AgentPanelState(); Settings settings=new Settings(); Map<String,Integer>expandedPages=new HashMap<>();String expandedAgent=\"\"; SubAgentManager.Record selected; TextView title=new TextView(this),summary=new TextView(this),pageLabel=new TextView(this); TextView[] tabs={new TextView(this),new TextView(this),new TextView(this)}; LinearLayout body=new LinearLayout(this); ScrollView scroll=new ScrollView(); View tabBar=new View(),pages=new View(),composer=new View(); ImageButton stop=new ImageButton(),send=new ImageButton(),older=new ImageButton(),newer=new ImageButton(),latest=new ImageButton(); EditText message=new EditText(this); String rendered=\"\"; int renderVersion; boolean resumed=true,destroyed,sending,closing,dirty,loadCalled; void load(){loadCalled=true;} int dp(int value){return value;} String redact(String text){return PromptGuard.redact(text,settings.systemPrompt(),settings.environmentContext(),\"\");}\n");
        source.append("void recordUiFailure(Throwable e){}boolean readFailureAnnounced;List<String>toasts=new ArrayList<String>();static class Toast{static final int LENGTH_SHORT=0;PanelHarness owner;String message;static Toast makeText(PanelHarness o,String t,int d){Toast v=new Toast();v.owner=o;v.message=t;return v;}void show(){owner.toasts.add(message);}}\n");
        for (String name : Arrays.asList("renderList", "renderDetail", "page", "status", "statusSummary", "phase", "statusColor", "role", "section", "textPages", "textEnd", "text", "divider", "restoreScroll", "showReadFailure")) {
            String body = methods.get(name); if (body == null) throw new AssertionError("Production method missing: " + name);
            source.append(body.replace("android.text.format.DateFormat", "DateFormat").replace("android.graphics.Typeface", "Typeface").replace("android.text.Layout", "Layout")
                    .replace("android.R.color.transparent", "R.color.transparent").replace("SubAgentsActivity.this", "PanelHarness.this"));
        }
        source.append("LinearLayout textPages(String content){return textPages(content,AgentPanelState.RESULT_PAGE_SIZE);}");
        source.append("String collect(View view){String out=view instanceof TextView?((TextView)view).value+\"\\n\":\"\"; if(view instanceof LinearLayout)for(View child:((LinearLayout)view).children)out+=collect(child); return out;} String visible(){return title.value+\"\\n\"+summary.value+\"\\n\"+pageLabel.value+\"\\n\"+collect(body);} }");
        return source.toString();
    }
    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[0]), output = Files.createTempDirectory("backcast-agent-panel-test-");
        try {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, null)) {
                check(compiler.getTask(null, files, null, Arrays.asList("-proc:none", "-encoding", "UTF-8", "-source", "7", "-target", "7", "-Xlint:-options", "-classpath", System.getProperty("java.class.path"), "-d", output.toString()), null,
                        Arrays.asList(new Source("PanelHarness", harness(root)))).call(), "Panel renderer fixture compile failed");
            }
            try (URLClassLoader loader = new URLClassLoader(new URL[] {output.toUri().toURL()}, AgentPanelRegressionTest.class.getClassLoader())) {
                panelType = loader.loadClass("PanelHarness");
                listShowsCompactTaskStatusWithoutResultsOrJson();
                taskActivityAndResultRemainSeparate();
                preciseStagesDoNotClaimToolsAlreadyExecuted();
                failedAssignmentKeepsItsTaskAndManualContinuationRefreshesItsState();
                activityPagesAreStableWhileNewHistoryArrives();
                resultPagingExposesTheFullResultWithoutOneHugeView();
                refreshKeepsScrollAndClosedTasksDisableCommands();
                displayHistoryHidesTransportWrappersAndFormatsTools();
                boundedHistoryAndActiveSortUseActualData();
                pollingStopsAndStaleLoadsCannotMutateNextVisit();
                reportsAndOlderOutcomesRemainVisibleAfterCancellation();
                fullActivityCanBeReadBeyondThePreviewBudget();
                resultPagingPreservesUnicodeAtPageBoundaries();
            }
            System.out.println("Agent panel regressions: " + passed + " passed");
        } finally {
            try (var paths = Files.walk(output)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
}
