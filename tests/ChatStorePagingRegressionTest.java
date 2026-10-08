import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.Compactor;
import com.mkei.backcast.mcp.McpSelection;
import com.mkei.backcast.mcp.McpServer;
import com.mkei.backcast.mcp.McpToolInfo;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.json.JSONArray;
import org.json.JSONObject;

/** Executes real ChatStore paging and decoding against an isolated database fixture. */
public final class ChatStorePagingRegressionTest {
    private static Class<?> storeType;
    private static Class<?> databaseType;
    private static Class<?> contextType;
    private static int passed;

    private static final class Source extends SimpleJavaFileObject {
        final String source;
        Source(String name, String source) {
            super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE);
            this.source = source;
        }
        @Override public CharSequence getCharContent(boolean ignoreErrors) { return source; }
    }

    private static void add(List<JavaFileObject> files, String name, String body) {
        int split = name.lastIndexOf('.');
        files.add(new Source(name, "package " + name.substring(0, split) + ";" + body));
    }

    private static void fixtures(List<JavaFileObject> files) {
        add(files, "android.content.Context", "public class Context { public Context getApplicationContext(){return this;} }");
        add(files, "android.content.ContentValues", "public class ContentValues extends java.util.HashMap<String,Object> {} ");
        add(files, "android.database.Cursor",
                "public class Cursor { private final java.util.List<Object[]> rows; private int at=-1;"
                + "public Cursor(java.util.List<Object[]> r){rows=r;} public boolean moveToNext(){return ++at<rows.size();}"
                + "public boolean moveToFirst(){at=0;return !rows.isEmpty();} public String getString(int i){Object v=rows.get(at)[i];return v==null?null:v.toString();}"
                + "public long getLong(int i){Object v=rows.get(at)[i];return v==null?0:((Number)v).longValue();}"
                + "public int getInt(int i){return (int)getLong(i);} public boolean isNull(int i){return rows.get(at)[i]==null;} public void close(){} }");
        add(files, "android.database.sqlite.SQLiteOpenHelper",
                "public abstract class SQLiteOpenHelper { private static final SQLiteDatabase db=new SQLiteDatabase();"
                + "public static int requestedVersion;public SQLiteOpenHelper(android.content.Context c,String n,Object f,int v){requestedVersion=v;}"
                + "public SQLiteDatabase getReadableDatabase(){return db;} public SQLiteDatabase getWritableDatabase(){return db;}"
                + "public abstract void onCreate(SQLiteDatabase db); public abstract void onUpgrade(SQLiteDatabase db,int o,int n); }");
        add(files, "android.database.sqlite.SQLiteDatabase",
                "import java.util.*; import android.database.Cursor; import android.content.ContentValues;"
                + "public class SQLiteDatabase { public static final int CONFLICT_REPLACE=5,CONFLICT_IGNORE=4;"
                + "private static final List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>(); private static long next=1;"
                + "public static final List<String> statements=new ArrayList<String>();public static boolean requestDiagnosticColumn;"
                + "public static final Set<String> runColumns=new HashSet<String>(),messageColumns=new HashSet<String>();"
                + "public static void legacyRuns(int version){runColumns.clear();messageColumns.clear();messageColumns.addAll(Arrays.asList(\"id\",\"session_id\",\"role\",\"content\",\"reasoning\",\"tool_calls\",\"tool_call_id\"));"
                + "if(version>=2)messageColumns.add(\"elapsed_ms\");if(version>=3)messageColumns.add(\"think_ms\");if(version>=7)messageColumns.add(\"display_parts\");if(version>=8)messageColumns.add(\"work_dir\");if(version>=14)messageColumns.add(\"mcp_selection\");if(version<4)return;"
                + "runColumns.addAll(Arrays.asList(\"session_id\",\"running\",\"goal\",\"status\",\"elapsed_ms\"));"
                + "if(version>=5)runColumns.addAll(Arrays.asList(\"turn_at\",\"turn_wall\",\"seen_at\"));"
                + "if(version>=9)runColumns.addAll(Arrays.asList(\"tokens_used\",\"token_budget\"));if(version>=10)runColumns.add(\"budget_wrap_finished\");if(version>=13)runColumns.addAll(Arrays.asList(\"turn_elapsed_ms\",\"turn_think_ms\"));}"
                + "public static void reset(){rows.clear();next=1;statements.clear();requestDiagnosticColumn=false;runColumns.clear();messageColumns.clear();} public void execSQL(String s){statements.add(s);"
                + "if(s.startsWith(\"CREATE TABLE messages (\")&&messageColumns.isEmpty())for(String field:s.substring(s.indexOf('(')+1,s.length()-1).split(\",\"))messageColumns.add(field.trim().split(\" \" )[0]);"
                + "if(s.startsWith(\"ALTER TABLE messages ADD COLUMN \")){String column=s.substring(32).split(\" \" )[0];if(!messageColumns.add(column))throw new AssertionError(\"Duplicate messages column: \"+column);if(column.equals(\"mcp_selection\"))for(Map<String,Object> row:rows)if(row.get(\"table\").equals(\"messages\"))row.put(\"mcp_selection\",\"\");}"
                + "if(s.startsWith(\"CREATE TABLE IF NOT EXISTS runs (\")&&runColumns.isEmpty())"
                + "for(String field:s.substring(s.indexOf('(')+1,s.length()-1).split(\",\"))runColumns.add(field.trim().split(\" \")[0]);"
                + "if(s.startsWith(\"ALTER TABLE runs ADD COLUMN \")){String column=s.substring(28).split(\" \")[0];"
                + "if(!runColumns.add(column))throw new AssertionError(\"Duplicate runs column: \"+column);}"
                + "if(s.startsWith(\"CREATE TABLE IF NOT EXISTS request_events\")&&s.contains(\"diagnostic TEXT\"))requestDiagnosticColumn=true;"
                + "if(s.startsWith(\"ALTER TABLE request_events ADD COLUMN diagnostic\")){if(requestDiagnosticColumn)throw new AssertionError(\"Duplicate diagnostic column\");"
                + "requestDiagnosticColumn=true;for(Map<String,Object> row:rows)if(row.get(\"table\").equals(\"request_events\"))row.put(\"diagnostic\",\"\");}}"
                + "public static List<Map<String,Object>> records(String table,long sid){List<Map<String,Object>> copy=new ArrayList<Map<String,Object>>();"
                + "for(Map<String,Object> row:rows)if(row.get(\"table\").equals(table)&&((Number)row.get(\"session_id\")).longValue()==sid)copy.add(new HashMap<String,Object>(row));return copy;}"
                + "public void execSQL(String s,Object[] a){String table=s.startsWith(\"DELETE FROM request_events WHERE session_id=? AND id NOT IN\")?\"request_events\":"
                + "s.startsWith(\"DELETE FROM diagnostic_errors WHERE session_id=? AND id NOT IN\")?\"diagnostic_errors\":null;"
                + "if(table==null||!s.contains(\"SELECT id FROM \"+table+\" WHERE session_id=?\")||!s.endsWith(\"ORDER BY id DESC LIMIT 200)\"))throw new AssertionError(s);"
                + "long sid=((Number)a[0]).longValue();if(sid!=((Number)a[1]).longValue())throw new AssertionError(\"Retention crossed sessions\");"
                + "List<Map<String,Object>> selected=new ArrayList<Map<String,Object>>();for(Map<String,Object> row:rows)"
                + "if(row.get(\"table\").equals(table)&&((Number)row.get(\"session_id\")).longValue()==sid)selected.add(row);"
                + "while(selected.size()>200)rows.remove(selected.remove(0));}"
                + "public void beginTransaction(){} public void setTransactionSuccessful(){} public void endTransaction(){}"
                + "public long insert(String table,String nullColumn,ContentValues values){Map<String,Object> row=new HashMap<String,Object>(values);"
                + "long id=next++;row.put(\"id\",Long.valueOf(id));row.put(\"table\",table);if(table.equals(\"messages\")||table.equals(\"request_events\")||table.equals(\"diagnostic_errors\")||table.equals(\"runs\")||table.equals(\"context_windows\")||table.equals(\"compaction_events\"))rows.add(row);return id;}"
                + "public long insertWithOnConflict(String t,String n,ContentValues v,int c){if(t.equals(\"runs\")||t.equals(\"context_windows\"))"
                + "delete(t,\"session_id=?\",new String[]{v.get(\"session_id\").toString()});"
                + "if(t.equals(\"compaction_events\")&&c==CONFLICT_IGNORE)for(Map<String,Object> row:rows)"
                + "if(t.equals(row.get(\"table\"))&&v.get(\"session_id\").equals(row.get(\"session_id\"))&&v.get(\"through_id\").equals(row.get(\"through_id\")))return -1;return insert(t,n,v);}"
                + "public int update(String t,ContentValues v,String s,String[] a){if(t.equals(\"sessions\"))return 0;"
                + "boolean clock=s.equals(\"session_id=? AND running=1\");if(!s.equals(\"id=?\")&&!clock)throw new AssertionError(s);"
                + "int changed=0;for(Map<String,Object> row:rows)if(row.get(\"table\").equals(t)"
                + "&&((Number)row.get(clock?\"session_id\":\"id\")).longValue()==Long.parseLong(a[0])"
                + "&&(!clock||((Number)row.get(\"running\")).intValue()==1)){row.putAll(v);changed++;}return changed;}"
                + "public int delete(String t,String s,String[] a){if(s.equals(\"owner=?\"))return 0;if(!s.equals(\"session_id=?\")&&!t.equals(\"sessions\"))throw new AssertionError(s);"
                + "int changed=0;for(Iterator<Map<String,Object>> i=rows.iterator();i.hasNext();){Map<String,Object> row=i.next();"
                + "if(row.get(\"table\").equals(t)&&((Number)row.get(\"session_id\")).longValue()==Long.parseLong(a[0])){i.remove();changed++;}}return changed;}"
                + "public Cursor query(String t,String[] c,String s,String[] a,String g,String h,String o){return query(t,c,s,a,g,h,o,null);}"
                + "public Cursor query(String table,String[] columns,String selection,String[] args,String group,String having,String order,String limit){"
                + "List<Map<String,Object>> selected=new ArrayList<Map<String,Object>>();"
                + "for(Map<String,Object> row:rows){if(!row.get(\"table\").equals(table))continue;boolean include=true;int arg=0;"
                + "for(String condition:selection==null?new String[0]:selection.split(\" AND \")){"
                + "if(condition.equals(\"session_id=?\")){if(((Number)row.get(\"session_id\")).longValue()!=Long.parseLong(args[arg++]))include=false;}"
                + "else if(condition.equals(\"running=1\")){if(((Number)row.get(\"running\")).intValue()!=1)include=false;}"
                + "else if(condition.equals(\"id<?\")){if(((Number)row.get(\"id\")).longValue()>=Long.parseLong(args[arg++]))include=false;}"
                + "else if(condition.equals(\"id>?\")){if(((Number)row.get(\"id\")).longValue()<=Long.parseLong(args[arg++]))include=false;}"
                + "else if(condition.equals(\"through_id>=?\")){if(((Number)row.get(\"through_id\")).longValue()<Long.parseLong(args[arg++]))include=false;}"
                + "else if(condition.equals(\"through_id<=?\")){if(((Number)row.get(\"through_id\")).longValue()>Long.parseLong(args[arg++]))include=false;}"
                + "else if(condition.equals(\"role=?\")){if(!args[arg++].equals(row.get(\"role\")))include=false;}"
                + "else if(condition.equals(\"role<>?\")){if(args[arg++].equals(row.get(\"role\")))include=false;}"
                + "else if(condition.startsWith(\"tool_call_id IN (\")){boolean matches=false;for(int c=0;c<condition.length();c++)"
                + "if(condition.charAt(c)=='?'){if(args[arg++].equals(row.get(\"tool_call_id\")))matches=true;}if(!matches)include=false;}"
                + "else throw new AssertionError(condition);}if(include)selected.add(row);}"
                + "Collections.sort(selected,new Comparator<Map<String,Object>>(){public int compare(Map<String,Object> a,Map<String,Object> b){"
                + "return Long.compare(((Number)a.get(\"id\")).longValue(),((Number)b.get(\"id\")).longValue());}});"
                + "if(order!=null&&order.endsWith(\"DESC\"))Collections.reverse(selected);"
                + "int maximum=limit==null?selected.size():Integer.parseInt(limit);List<Object[]> projected=new ArrayList<Object[]>();"
                + "for(int i=0;i<Math.min(maximum,selected.size());i++){Object[] data=new Object[columns.length];"
                + "for(int j=0;j<columns.length;j++)data[j]=selected.get(i).get(columns[j]);projected.add(data);}return new Cursor(projected);}"
                + "public Cursor rawQuery(String sql,String[] args){if(sql.equals(\"SELECT MAX(id) FROM messages WHERE session_id=?\")){long maximum=0;for(Map<String,Object> row:rows)if(row.get(\"table\").equals(\"messages\")&&((Number)row.get(\"session_id\")).longValue()==Long.parseLong(args[0]))maximum=Math.max(maximum,((Number)row.get(\"id\")).longValue());List<Object[]> out=new ArrayList<Object[]>();out.add(new Object[]{maximum});return new Cursor(out);}if(!sql.startsWith(\"SELECT COUNT(*) FROM messages\"))throw new AssertionError(sql);"
                + "long count=0;for(Map<String,Object> row:rows)if(row.get(\"table\").equals(\"messages\")&&((Number)row.get(\"session_id\")).longValue()==Long.parseLong(args[0])"
                + "&&((Number)row.get(\"id\")).longValue()<Long.parseLong(args[1]))count++;"
                + "List<Object[]> result=new ArrayList<Object[]>();result.add(new Object[]{Long.valueOf(count)});return new Cursor(result);} }");
    }

    private static Object fresh() throws Exception {
        databaseType.getMethod("reset").invoke(null);
        return storeType.getConstructor(contextType).newInstance(contextType.getConstructor().newInstance());
    }
    private static void append(Object store, long sid, Message message) throws Exception {
        storeType.getMethod("append", long.class, Message.class).invoke(store, sid, message);
    }
    private static Object page(Object store, long sid, long before, int size) throws Exception {
        return storeType.getMethod("messagePage", long.class, long.class, int.class).invoke(store, sid, before, size);
    }
    private static Object field(Object target, String name) throws Exception {
        if (target instanceof Map) {
            String column = name.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.US);
            return ((Map<?, ?>) target).get(column);
        }
        return target.getClass().getField(name).get(target);
    }
    private static long number(Object target, String name) throws Exception { return (Long)field(target, name); }
    @SuppressWarnings("unchecked")
    private static List<Message> messages(Object page) throws Exception { return (List<Message>)field(page, "messages"); }
    private static void check(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }

    private static void newestPageIsBoundedAndAscending() throws Exception {
        Object store = fresh();
        for (int i = 0; i < 1000; i++) append(store, 7, Message.user("message " + i));
        append(store, 8, Message.user("other session"));
        Object page = page(store, 7, -1, 48);
        check(messages(page).size() == 48, "Latest query decoded the full transcript");
        check(number(page, "firstId") == 953, "Incorrect page cursor");
        check(number(page, "earlierCount") == 952, "Earlier count included another session");
        for (int i = 0; i < 48; i++) check(messages(page).get(i).content.equals("message " + (952 + i)), "Page is not chronological");
        check("message 951".equals(((Message)field(page, "requestBefore")).content), "Lost user context before page");
    }

    private static void cursorSurvivesNewMessages() throws Exception {
        Object store = fresh();
        for (int i = 0; i < 141; i++) append(store, 7, Message.user("message " + i));
        Object page = page(store, 7, -1, 48);
        Set<String> seen = new HashSet<String>();
        for (Message m : messages(page)) seen.add(m.content);
        append(store, 7, Message.user("new message"));
        append(store, 8, Message.user("another session"));
        while (number(page, "earlierCount") > 0) {
            long before = number(page, "firstId");
            page = page(store, 7, before, 48);
            check(number(page, "firstId") < before, "Cursor boundary was repeated");
            for (Message m : messages(page)) check(seen.add(m.content), "Message loaded twice: " + m.content);
        }
        check(seen.size() == 141 && !seen.contains("new message"), "Appending changed older page boundaries");
    }

    private static void toolPageRetainsOnlyLeadingLabels() throws Exception {
        Object store = fresh();
        append(store, 7, Message.user("inspect files"));
        JSONArray calls = new JSONArray().put(new JSONObject().put("id", "c1").put("function",
                new JSONObject().put("name", "shell").put("arguments", "{}")))
                .put(new JSONObject().put("id", "c2").put("function", new JSONObject().put("name", "read")));
        Message assistant = Message.assistant("already displayed", calls);
        assistant.reasoning = "already displayed reasoning";
        append(store, 7, assistant);
        append(store, 7, Message.toolResult("c2", "already displayed output"));
        append(store, 7, Message.toolResult("c1", "first output"));
        append(store, 7, Message.toolResult("c1", "second output"));
        Object page = page(store, 7, -1, 2);
        Message leading = (Message)field(page, "leadingAssistant");
        check(leading != null && leading.toolCalls.length() == 1 && "c1".equals(leading.toolCalls.getJSONObject(0).getString("id")),
                "Tool results lost labels or included calls outside the page");
        check(leading.content.length() == 0 && leading.reasoning == null && leading.displayParts == null,
                "Leading context would duplicate assistant content");
        check("inspect files".equals(((Message)field(page, "requestBefore")).content), "Tool page lost user request context");
        check(messages(page).size() == 2 && number(page, "firstId") == 4, "Leading assistant altered paging cursor");
        append(store, 7, Message.user("new user"));
        append(store, 7, Message.toolResult("c1", "unrelated result"));
        check(field(page(store, 7, -1, 1), "leadingAssistant") == null, "Labels leaked across a new user turn");
    }

    private static void hugeTurnStillHasHardPageLimit() throws Exception {
        Object store = fresh();
        append(store, 7, Message.user("one long task"));
        for (int i = 0; i < 1000; i++) append(store, 7, Message.assistant("step " + i, null));
        Object page = page(store, 7, -1, Integer.MAX_VALUE);
        check(messages(page).size() == 128, "Page expanded to a whole unbounded turn");
        check(number(page, "earlierCount") == 873, "Hard limit has an incorrect earlier count");
        check("one long task".equals(((Message)field(page, "requestBefore")).content), "Long turn lost original request");
        check(messages(page(store, 7, -1, 0)).size() == 1, "Nonpositive limit did not remain bounded");
    }

    private static void prefixRequestRetainsRetryMetadataWithoutChangingPage() throws Exception {
        Object store = fresh();
        Message previous = Message.user("Earlier request"); previous.workDir = "/earlier";
        append(store, 7L, previous); append(store, 7L, Message.assistant("earlier finished", null));
        Message request = Message.user("Run the selected remote task"); request.workDir = "/original/project";
        request.mcpSelection = selection(); append(store, 7L, request);
        append(store, 8L, Message.user("Other session must not own retry"));
        append(store, 7L, Message.assistant("partial answer", null));
        Object page = page(store, 7L, -1L, 1);
        Message context = (Message)field(page, "requestBefore");
        check(context != null && Message.USER.equals(context.role) && context.content.equals(request.content)
                && context.workDir.equals(request.workDir) && context.mcpSelection != null
                && context.mcpSelection.toJson().similar(request.mcpSelection.toJson()),
                "Assistant page retry context lost the original user text, directory or selected MCP identity");
        check(request.content.equals(field(page, "disclosureBefore")), "Ordinary request lost its disclosure scope");
        check(messages(page).size() == 1 && Message.ASSISTANT.equals(messages(page).get(0).role)
                && number(page, "firstId") == 5L && number(page, "earlierCount") == 3L,
                "Complete retry context changed bounded page contents or cursor");
        append(store, 7L, Message.toolResult("pending", "tool result"));
        page = page(store, 7L, -1L, 1); context = (Message)field(page, "requestBefore");
        check(context != null && context.mcpSelection != null && context.content.equals(request.content),
                "Tool-result page could not bind a retry to its original human request");
        Message next = Message.user("New request"); next.workDir = "/new/project"; append(store, 7L, next);
        append(store, 7L, Message.assistant("new answer", null));
        context = (Message)field(page(store, 7L, -1L, 1), "requestBefore");
        check(context != null && context.content.equals(next.content) && context.workDir.equals(next.workDir)
                && context.mcpSelection == null, "Retry context leaked the prior request selection into the next turn");
    }

    private static void continuationRetryAndDisclosureScopesRemainSeparate() throws Exception {
        Object store = fresh();
        Message request = Message.user("Show the requested private field");
        request.workDir = "/original/project"; request.mcpSelection = selection();
        append(store, 7L, request); append(store, 7L, Message.assistant("first answer", null));
        for (String internal : Arrays.asList("⟦目标续跑⟧", "⟦目标续跑⟧\nContinue the existing goal")) {
            // Current append excludes internal USER rows; simulate a transcript saved by an older version.
            append(store, 7L, Message.user("legacy internal row"));
            List<Map<String, Object>> saved = records("messages", 7L);
            updateRecord(store, "messages", number(saved.get(saved.size() - 1), "id"), "content", internal);
            append(store, 7L, Message.assistant("continued answer", null));
            Object page = page(store, 7L, -1L, 1);
            Message retry = (Message)field(page, "requestBefore");
            check(retry != null && retry.content.equals(request.content) && retry.workDir.equals(request.workDir)
                    && retry.mcpSelection != null && retry.mcpSelection.toJson().similar(request.mcpSelection.toJson()),
                    "Goal continuation lost the original human retry request or its metadata");
            check("".equals(field(page, "disclosureBefore")),
                    "Goal continuation inherited the original request's disclosure scope");
            check(messages(page).size() == 1, "Searching continuation ancestry enlarged the page");
        }
        Message next = Message.user("Describe the summary only"); append(store, 7L, next);
        append(store, 7L, Message.assistant("new request answer", null));
        Object page = page(store, 7L, -1L, 1);
        check(((Message)field(page, "requestBefore")).content.equals(next.content)
                && next.content.equals(field(page, "disclosureBefore")),
                "New human request did not replace both independent page scopes");
        store = fresh(); append(store, 7L, Message.user("legacy internal row"));
        updateRecord(store, "messages", number(records("messages", 7L).get(0), "id"), "content", "⟦目标续跑⟧");
        append(store, 7L, Message.assistant("no original human request", null));
        page = page(store, 7L, -1L, 1);
        check(field(page, "requestBefore") == null && "".equals(field(page, "disclosureBefore")),
                "Internal-only ancestry invented a retry request or disclosure scope");
    }

    private static void prefixRequestCorruptionAndUserlessPageStayExplicit() throws Exception {
        Object store = fresh();
        append(store, 7L, Message.assistant("No human request exists", null));
        check(field(page(store, 7L, -1L, 1), "requestBefore") == null,
                "Userless assistant page invented a retry request");
        store = fresh(); Message request = Message.user("Selected request"); request.mcpSelection = selection();
        append(store, 7L, request); append(store, 7L, Message.assistant("selected answer", null));
        List<Map<String, Object>> rows = (List<Map<String, Object>>)databaseType.getMethod("records", String.class, long.class)
                .invoke(null, "messages", 7L);
        // Update only the off-page original request; page decoding itself remains valid.
        Class<?> valuesType = storeType.getClassLoader().loadClass("android.content.ContentValues");
        Map<String, Object> values = (Map<String, Object>)valuesType.getDeclaredConstructor().newInstance();
        values.put("mcp_selection", "broken selection metadata");
        Object db = storeType.getMethod("getWritableDatabase").invoke(store);
        databaseType.getMethod("update", String.class, valuesType, String.class, String[].class)
                .invoke(db, "messages", values, "id=?", new String[]{String.valueOf(rows.get(0).get("id"))});
        try { page(store, 7L, -1L, 1); throw new AssertionError("Corrupt off-page retry selection was silently discarded"); }
        catch (java.lang.reflect.InvocationTargetException rejected) {
            check(rejected.getCause() instanceof IllegalStateException, "Wrong prefix selection corruption failure");
        }
    }

    private static void messageMetadataSurvivesPaging() throws Exception {
        Object store = fresh();
        Message user = Message.user("hello"); user.workDir = "/project";
        append(store, 7, user);
        Message assistant = Message.assistant("answer", new JSONArray().put(new JSONObject().put("id", "c1")));
        assistant.reasoning = "reason"; assistant.elapsedMs = 2500; assistant.thinkMs = 300;
        assistant.displayParts = new JSONArray().put(new JSONObject().put("type", "body").put("from", 0).put("to", 6));
        append(store, 7, assistant);
        append(store, 7, Message.toolResult("c1", "output"));
        List<Message> result = messages(page(store, 7, -1, 48));
        check("/project".equals(result.get(0).workDir), "User metadata was lost");
        check("reason".equals(result.get(1).reasoning) && result.get(1).elapsedMs == 2500
                && result.get(1).thinkMs == 300 && result.get(1).displayParts.length() == 1
                && result.get(1).toolCalls.length() == 1, "Assistant metadata was decoded at the wrong offset");
        check("c1".equals(result.get(2).toolCallId), "Tool result id was lost");
        @SuppressWarnings("unchecked")
        List<Message> full = (List<Message>)storeType.getMethod("contextMessages", long.class).invoke(store, 7);
        check(full.size() == 3 && "c1".equals(full.get(2).toolCallId) && full.get(1).elapsedMs == 2500,
                "Full model history could not use shared decoding");
    }

    private static McpSelection selection() throws Exception {
        McpServer server = new McpServer("local_tools", "Local tools", "https://private.example/mcp", "private-token", true, 30);
        McpToolInfo tool = new McpToolInfo(new JSONObject().put("name", "inspect_file").put("description", "Inspect a file")
                .put("inputSchema", new JSONObject().put("type", "object").put("properties",
                        new JSONObject().put("path", new JSONObject().put("type", "string")))));
        java.lang.reflect.Constructor<McpSelection> constructor = McpSelection.class.getDeclaredConstructor(McpServer.class, McpToolInfo.class);
        constructor.setAccessible(true);
        return constructor.newInstance(server, tool);
    }

    private static void selectedMcpToolSurvivesTranscriptAndContextCheckpoint() throws Exception {
        Object store = fresh();
        Message selected = Message.user("Inspect this file"); selected.workDir = "/project"; selected.mcpSelection = selection();
        append(store, 7L, selected);
        Message assistant = Message.assistant("Acknowledged", null); assistant.mcpSelection = selected.mcpSelection;
        append(store, 7L, assistant);
        Message tool = Message.toolResult("call", "result"); tool.mcpSelection = selected.mcpSelection; append(store, 7L, tool);
        append(store, 7L, Message.user("Ordinary request"));
        List<Message> page = messages(page(store, 7L, -1, 48));
        check(page.get(0).mcpSelection != null && selected.mcpSelection.toJson().similar(page.get(0).mcpSelection.toJson())
                        && "/project".equals(page.get(0).workDir) && "Inspect this file".equals(page.get(0).content),
                "Selected MCP identity/schema or visible user request was lost during transcript decoding");
        check(page.get(1).mcpSelection == null && page.get(2).mcpSelection == null && page.get(3).mcpSelection == null,
                "Selection metadata leaked to assistant/tool or ordinary user messages");
        check(!assistant.toCheckpointJson().has("mcp_selection") && !tool.toCheckpointJson().has("mcp_selection"),
                "Model checkpoints retained selection metadata on non-user messages");
        String saved = (String) records("messages", 7L).get(0).get("mcp_selection");
        check(!saved.contains("private-token") && !saved.contains("private.example")
                        && new JSONObject(saved).getString("connection_id").length() == 64,
                "Selection storage exposed connection credentials rather than opaque identity");
        storeType.getMethod("replaceAll", long.class, List.class).invoke(store, 7L, Arrays.asList(selected, Message.assistant("Checkpoint", null)));
        append(store, 7L, Message.user("After checkpoint"));
        @SuppressWarnings("unchecked") List<Message> restored = (List<Message>) storeType.getMethod("contextMessages", long.class).invoke(store, 7L);
        check(restored.size() == 3 && restored.get(0).mcpSelection != null
                        && selected.mcpSelection.toJson().similar(restored.get(0).mcpSelection.toJson())
                        && "Checkpoint".equals(restored.get(1).content) && "After checkpoint".equals(restored.get(2).content),
                "Compacted context and later messages did not restore the selected tool exactly once");
    }

    private static void updateRecord(Object store, String table, long id, String key, String value) throws Exception {
        Class<?> valuesType = databaseType.getClassLoader().loadClass("android.content.ContentValues");
        @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) valuesType.getConstructor().newInstance();
        values.put(key, value);
        Object db = storeType.getMethod("getWritableDatabase").invoke(store);
        databaseType.getMethod("update", String.class, valuesType, String.class, String[].class)
                .invoke(db, table, values, "id=?", new String[]{String.valueOf(id)});
    }

    private static void corruptedMcpSelectionCannotSilentlyRestoreAnotherRequest() throws Exception {
        for (String bad : Arrays.asList("not-json", "{}", selection().toJson().put("mapped_name", "different_tool").toString())) {
            Object store = fresh(); Message selected = Message.user("Explicit tool request"); selected.mcpSelection = selection(); append(store, 7L, selected);
            updateRecord(store, "messages", number(records("messages", 7L).get(0), "id"), "mcp_selection", bad);
            for (String method : Arrays.asList("messagePage", "contextMessages")) {
                try {
                    if (method.equals("messagePage")) page(store, 7L, -1, 48);
                    else storeType.getMethod(method, long.class).invoke(store, 7L);
                    throw new AssertionError("Corrupted selection restored without an error through " + method);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    check(failure.getCause() instanceof IllegalStateException && failure.getCause().getMessage().contains("MCP"),
                            "Corrupted selection failed with an unrelated error");
                }
            }
        }
        Object store = fresh(); Message selected = Message.user("Checkpoint request"); selected.mcpSelection = selection(); append(store, 7L, selected);
        storeType.getMethod("replaceAll", long.class, List.class).invoke(store, 7L, Arrays.asList(selected));
        JSONObject broken = selected.toCheckpointJson().put("mcp_selection", new JSONObject());
        updateRecord(store, "context_windows", number(records("context_windows", 7L).get(0), "id"), "window", new JSONArray().put(broken).toString());
        try {
            storeType.getMethod("contextMessages", long.class).invoke(store, 7L);
            throw new AssertionError("Corrupted compacted selection silently restored");
        } catch (java.lang.reflect.InvocationTargetException failure) {
            check(failure.getCause() instanceof IllegalStateException, "Corrupted checkpoint selection failed with an unrelated error");
        }
    }

    private static void emptyPageHasNoContext() throws Exception {
        Object page = page(fresh(), 7, -1, 48);
        check(messages(page).isEmpty() && number(page, "firstId") == 0
                && number(page, "earlierCount") == 0 && field(page, "requestBefore") == null
                && "".equals(field(page, "disclosureBefore"))
                && field(page, "leadingAssistant") == null, "Empty page carried stale cursors or context");
    }

    private static void compactionDividersPersistWithoutEnteringTheModelWindow() throws Exception {
        Object store = fresh();
        Message request = Message.user("inspect project"); request.workDir = "/project";
        append(store, 7L, request); append(store, 7L, Message.assistant("before compact", null));
        long boundary = number(records("messages", 7L).get(1), "id");
        Message summary = Message.user(Compactor.wrap("only a private handoff"));
        storeType.getMethod("replaceAll", long.class, List.class).invoke(store, 7L, Arrays.asList(request, summary));
        storeType.getMethod("replaceAll", long.class, List.class).invoke(store, 7L, Arrays.asList(request, summary));
        append(store, 7L, new Message(Message.COMPACTION, "must not become a transcript message"));
        append(store, 7L, Message.assistant("after compact", null));
        List<Message> decorated = messages(page(store, 7L, -1L, 48));
        check(decorated.size() == 4 && Message.COMPACTION.equals(decorated.get(2).role)
                        && decorated.get(2).content.isEmpty() && "after compact".equals(decorated.get(3).content),
                "Saved divider disappeared, duplicated at the same boundary, leaked its summary or moved to the transcript end");
        check(records("messages", 7L).size() == 3 && records("compaction_events", 7L).size() == 1
                        && number(records("compaction_events", 7L).get(0), "throughId") == boundary,
                "Local event altered append-only model messages or lost its exact boundary");
        @SuppressWarnings("unchecked") List<Message> context = (List<Message>) storeType.getMethod("contextMessages", long.class).invoke(store, 7L);
        check(context.size() == 3 && Compactor.isSummary(context.get(1)) && "after compact".equals(context.get(2).content),
                "Decoration changed the restored model checkpoint");
        for (Message message : context) check(!Message.COMPACTION.equals(message.role), "Local divider entered the model window");
        try { new Message(Message.COMPACTION, "").toJson(); throw new AssertionError("Local divider serialized into an API message"); }
        catch (IllegalStateException expected) { }
        append(store, 8L, Message.user("other session"));
        storeType.getMethod("replaceAll", long.class, List.class).invoke(store, 8L, Arrays.asList(summary));
        storeType.getMethod("delete", long.class).invoke(store, 7L);
        check(records("compaction_events", 7L).isEmpty() && records("compaction_events", 8L).size() == 1,
                "Deleting a conversation orphaned dividers or deleted another conversation's marker");
    }

    private static void compactionDividerPagingPreservesBoundariesAndRequestMetadata() throws Exception {
        Object store = fresh(); Message request = Message.user("selected task"); request.mcpSelection = selection();
        append(store, 7L, request); append(store, 7L, Message.assistant("first answer", null));
        long firstBoundary = number(records("messages", 7L).get(1), "id");
        Message summary = Message.user(Compactor.wrap("handoff"));
        storeType.getMethod("replaceAll", long.class, List.class).invoke(store, 7L, Arrays.asList(request, summary));
        append(store, 7L, Message.assistant("second answer", null));
        long secondBoundary = number(records("messages", 7L).get(2), "id");
        storeType.getMethod("replaceAll", long.class, List.class).invoke(store, 7L, Arrays.asList(request, summary));
        append(store, 7L, Message.assistant("third answer", null));
        Object latest = page(store, 7L, -1L, 1);
        check(messages(latest).size() == 1 && "third answer".equals(messages(latest).get(0).content)
                        && number(latest, "earlierCount") == 3 && ((Message)field(latest, "requestBefore")).mcpSelection != null,
                "Event decorations inflated message counts or changed the original retry request");
        Object middle = page(store, 7L, number(latest, "firstId"), 1);
        check(number(middle, "firstId") == secondBoundary && messages(middle).size() == 2
                        && "second answer".equals(messages(middle).get(0).content)
                        && Message.COMPACTION.equals(messages(middle).get(1).role), "Second boundary was skipped or added to the wrong page");
        Object earlier = page(store, 7L, number(middle, "firstId"), 1);
        check(number(earlier, "firstId") == firstBoundary && messages(earlier).size() == 2
                        && Message.COMPACTION.equals(messages(earlier).get(1).role), "Earlier boundary was skipped or duplicated across pages");
        check(messages(page(store, 8L, -1L, 48)).isEmpty(), "Divider crossed conversation boundaries");
    }

    private static void versionFourteenRestoresOnlyItsKnownCompactionBoundary() throws Exception {
        Object store = fresh(), db = storeType.getMethod("getWritableDatabase").invoke(store);
        append(store, 7L, Message.user("old request")); append(store, 7L, Message.assistant("old answer", null));
        long boundary = number(records("messages", 7L).get(1), "id");
        append(store, 7L, Message.assistant("newer answer", null));
        Class<?> valuesType = databaseType.getClassLoader().loadClass("android.content.ContentValues");
        for (long sid : new long[]{7L, 8L, 9L}) {
            @SuppressWarnings("unchecked") Map<String,Object> checkpoint = (Map<String,Object>) valuesType.getConstructor().newInstance();
            checkpoint.put("session_id", sid); checkpoint.put("through_id", boundary);
            checkpoint.put("window", sid == 7L ? new JSONArray().put(Message.user(Compactor.wrap("legacy handoff")).toCheckpointJson()).toString()
                    : sid == 8L ? new JSONArray().put(Message.user("ordinary checkpoint").toCheckpointJson()).toString() : "broken-json");
            databaseType.getMethod("insert", String.class, String.class, valuesType).invoke(db, "context_windows", null, checkpoint);
        }
        databaseType.getMethod("legacyRuns", int.class).invoke(null, 14);
        storeType.getMethod("onUpgrade", databaseType, int.class, int.class).invoke(store, db, 14, 15);
        List<Message> decorated = messages(page(store, 7L, -1L, 48));
        check(decorated.size() == 4 && Message.COMPACTION.equals(decorated.get(2).role)
                        && "newer answer".equals(decorated.get(3).content) && records("compaction_events", 7L).size() == 1,
                "Legacy migration lost the known boundary or placed it after later messages");
        check(records("compaction_events", 8L).isEmpty() && records("compaction_events", 9L).isEmpty()
                        && "broken-json".equals(records("context_windows", 9L).get(0).get("window")),
                "Migration invented a successful compaction or rewrote invalid recovery evidence");
    }

    private static void stoppedEmptyTurnDoesNotRewritePreviousTurnTime() throws Exception {
        Object store = fresh();
        append(store, 7, Message.user("previous turn"));
        Message previous = Message.assistant("previous answer", null);
        previous.elapsedMs = 3000; previous.thinkMs = 400;
        append(store, 7, previous);
        append(store, 7, Message.user("new turn with no reply yet"));
        storeType.getMethod("markElapsed", long.class, long.class, long.class).invoke(store, 7L, 2684000L, 1000L);
        List<Message> result = messages(page(store, 7, -1, 48));
        check(result.get(1).elapsedMs == 3000 && result.get(1).thinkMs == 400,
                "Stopping a reply-less new turn inflated the previous answer's elapsed time");
        append(store, 8, Message.user("other session"));
        append(store, 8, Message.assistant("other answer", null));
        append(store, 7, Message.assistant("current answer", null));
        storeType.getMethod("markElapsed", long.class, long.class, long.class).invoke(store, 7L, 5000L, 700L);
        storeType.getMethod("markElapsed", long.class, long.class, long.class).invoke(store, 7L, 1000L, 100L);
        result = messages(page(store, 7, -1, 48));
        check(result.get(1).elapsedMs == 3000 && result.get(3).elapsedMs == 5000 && result.get(3).thinkMs == 700,
                "Current turn timing lost monotonic metadata or crossed its user boundary");
        check(messages(page(store, 8, -1, 48)).get(1).elapsedMs == 0, "Elapsed time crossed sessions");
    }

    private static void requestDiagnosticsAreBoundedAndSeparateFromConversation() throws Exception {
        Object store = fresh();
        append(store, 7, Message.user("inspect"));
        MethodAccess.recordRequest(store, 8, "model", 45, "success", "", 0);
        for (int i = 0; i < 205; i++) MethodAccess.recordRequest(store, 7, "model", i,
                i == 204 ? "retryable_error" : "success", i == 204 ? "接口返回 HTTP 503" : "", i == 204 ? 2 : 0);
        List<?> all = requestRecords(7);
        check(all.size() == 200 && number(all.get(0), "elapsedMs") == 204
                        && number(all.get(199), "elapsedMs") == 5,
                "Request history exceeded its cap or discarded the newest attempts");
        check("retryable_error".equals(field(all.get(0), "outcome"))
                        && "接口返回 HTTP 503".equals(field(all.get(0), "reason"))
                        && (Integer) field(all.get(0), "retryCount") == 2,
                "Safe request failure metadata was not preserved");
        check(requestRecords(8).size() == 1, "Retention deleted another conversation's request");
        check(messages(page(store, 7, -1, 48)).size() == 1, "Diagnostics polluted model/transcript history");
        storeType.getMethod("delete", long.class).invoke(store, 7L);
        check(requestRecords(7).isEmpty() && requestRecords(8).size() == 1,
                "Conversation removal left private request logs or removed another conversation's logs");
    }

    private static void legacyDatabaseUpgradeAddsLocalRequestDiagnostics() throws Exception {
        Object store = fresh();
        Object db = databaseType.getConstructor().newInstance();
        check(storeType.getSuperclass().getField("requestedVersion").getInt(null) == 16,
                "Fresh databases do not request the compaction event schema version");
        databaseType.getMethod("legacyRuns", int.class).invoke(null, 10);
        storeType.getMethod("onUpgrade", databaseType, int.class, int.class).invoke(store, db, 10, 16);
        @SuppressWarnings("unchecked")
        List<String> sql = (List<String>) databaseType.getField("statements").get(null);
        check(sql.size() == 11 && sql.get(0).startsWith("CREATE TABLE IF NOT EXISTS request_events")
                        && sql.get(0).contains("diagnostic TEXT NOT NULL DEFAULT ''")
                        && sql.get(1).contains("request_events(session_id,id)")
                        && sql.get(2).startsWith("CREATE TABLE IF NOT EXISTS diagnostic_errors")
                        && sql.get(3).contains("diagnostic_errors(session_id,id)"),
                "Version 10 direct upgrade omitted evidence tables or attempted to add an existing diagnostic column");
        MethodAccess.recordRequest(store, 7, "review", 123, "error", "权限检查失败\n服务暂不可用", 0);
        MethodAccess.recordRequest(store, 7, "compact", 0, "cancelled", "用户停止", 1);
        List<?> events = requestRecords(7);
        check("compact".equals(field(events.get(0), "purpose")) && "cancelled".equals(field(events.get(0), "outcome"))
                        && "review".equals(field(events.get(1), "purpose"))
                        && "权限检查失败 服务暂不可用".equals(field(events.get(1), "reason"))
                        && number(events.get(1), "elapsedMs") == 123 && number(events.get(1), "recordedAt") > 0,
                "Purpose, cancellation, safe reason or request duration was decoded incorrectly");
    }

    private static void versionElevenMigrationPreservesRequestRows() throws Exception {
        Object store = fresh();
        append(store, 7, Message.user("preserved conversation"));
        Object db = databaseType.getConstructor().newInstance();
        Class<?> valuesType = databaseType.getClassLoader().loadClass("android.content.ContentValues");
        @SuppressWarnings("unchecked") Map<String, Object> old = (Map<String, Object>) valuesType.getConstructor().newInstance();
        old.put("session_id", 7L); old.put("recorded_at", 123L); old.put("elapsed_ms", 41L);
        old.put("purpose", "compact"); old.put("outcome", "cancelled"); old.put("reason", "old reason"); old.put("retry_count", 2);
        long oldId = (Long) databaseType.getMethod("insert", String.class, String.class, valuesType)
                .invoke(db, "request_events", null, old);
        databaseType.getMethod("legacyRuns", int.class).invoke(null, 11);
        storeType.getMethod("onUpgrade", databaseType, int.class, int.class).invoke(store, db, 11, 16);
        @SuppressWarnings("unchecked") List<String> sql = (List<String>) databaseType.getField("statements").get(null);
        check(sql.size() == 10 && sql.get(0).equals("ALTER TABLE request_events ADD COLUMN diagnostic TEXT NOT NULL DEFAULT ''")
                        && sql.get(1).startsWith("CREATE TABLE IF NOT EXISTS diagnostic_errors")
                        && sql.get(2).contains("diagnostic_errors(session_id,id)"),
                "Version 11 migration recreated request history or did not apply the additive column default");
        List<?> before = requestRecords(7);
        check(before.size() == 1 && number(before.get(0), "id") == oldId && number(before.get(0), "recordedAt") == 123
                        && number(before.get(0), "elapsedMs") == 41 && "compact".equals(field(before.get(0), "purpose"))
                        && "cancelled".equals(field(before.get(0), "outcome")) && "old reason".equals(field(before.get(0), "reason"))
                        && (Integer) field(before.get(0), "retryCount") == 2 && "".equals(field(before.get(0), "diagnostic")),
                "The migration lost an existing attempt or failed to decode the new empty diagnostic default");
        MethodAccess.recordRequest(store, 7, "model", 3, "success", "", 0, "{\"provider\":\"grok\"}");
        List<?> after = requestRecords(7);
        check(after.size() == 2 && number(after.get(1), "id") == oldId
                        && "grok".equals(new JSONObject((String) field(after.get(0), "diagnostic")).getString("provider"))
                        && "preserved conversation".equals(messages(page(store, 7, -1, 48)).get(0).content),
                "New diagnostic writes after migration changed the old log or conversation");
    }

    private static Object run(Object store, long sid) throws Exception {
        return storeType.getMethod("readRun", long.class).invoke(store, sid);
    }

    private static void saveRun(Object store, long sid, boolean running, long goalMs, long turnMs,
            Long thinkMs) throws Exception {
        storeType.getMethod("saveRun", long.class, boolean.class, String.class, String.class,
                long.class, long.class, Long.class, long.class, long.class, boolean.class)
                .invoke(store, sid, running, "saved goal", "active", goalMs, turnMs, thinkMs, 42L, 200L, true);
    }

    private static void saveClock(Object store, long sid, long goalMs, long turnMs, Long thinkMs) throws Exception {
        storeType.getMethod("saveClock", long.class, long.class, long.class, Long.class)
                .invoke(store, sid, goalMs, turnMs, thinkMs);
    }

    private static void runDurationsRoundTripWithoutLosingNullOrZero() throws Exception {
        Object store = fresh();
        check(field(run(store, 7L), "turnElapsedMs") == null && field(run(store, 7L), "turnThinkMs") == null,
                "Missing runs were mistaken for a new checkpoint");
        saveRun(store, 7L, true, 15000L, 5000L, null);
        Object saved = run(store, 7L);
        check(number(saved, "elapsedMs") == 15000L && number(saved, "turnElapsedMs") == 5000L
                        && field(saved, "turnThinkMs") == null && (Boolean) field(saved, "running")
                        && number(saved, "tokensUsed") == 42L && number(saved, "tokenBudget") == 200L
                        && Boolean.TRUE.equals(field(saved, "budgetWrapFinished"))
                        && "saved goal".equals(field(saved, "goal")) && "active".equals(field(saved, "status")),
                "Durations or run state changed on persistence roundtrip");
        saveRun(store, 7L, true, 0L, 0L, 0L);
        saved = run(store, 7L);
        check(Long.valueOf(0L).equals(field(saved, "turnElapsedMs"))
                        && Long.valueOf(0L).equals(field(saved, "turnThinkMs")) && records("runs", 7L).size() == 1,
                "A real zero checkpoint or zero first event became legacy/null or duplicated its row");
        Map<String, Object> row = records("runs", 7L).get(0);
        check(!row.containsKey("turn_at") && !row.containsKey("turn_wall") && !row.containsKey("seen_at"),
                "New runs persisted obsolete clock anchors");
        saveRun(store, 7L, false, -10L, -20L, -30L);
        saved = run(store, 7L);
        check(number(saved, "elapsedMs") == 0 && number(saved, "turnElapsedMs") == 0
                        && number(saved, "turnThinkMs") == 0 && !(Boolean) field(saved, "running"),
                "Negative checkpoint values were not clamped or final state was lost");
    }

    private static void durationHeartbeatOnlyUpdatesExistingRunningSession() throws Exception {
        Object store = fresh();
        saveRun(store, 7L, true, 15000L, 5000L, null);
        saveRun(store, 8L, false, 6000L, 2000L, 300L);
        saveClock(store, 7L, 17000L, 7000L, 0L);
        Object saved = run(store, 7L);
        check(number(saved, "elapsedMs") == 17000L && number(saved, "turnElapsedMs") == 7000L
                        && Long.valueOf(0L).equals(field(saved, "turnThinkMs"))
                        && (Boolean) field(saved, "running") && number(saved, "tokensUsed") == 42L
                        && number(saved, "tokenBudget") == 200L && Boolean.TRUE.equals(field(saved, "budgetWrapFinished"))
                        && "saved goal".equals(field(saved, "goal")),
                "Heartbeat failed to update durations or overwrote unrelated run state");
        saveClock(store, 8L, 99000L, 88000L, null);
        saveClock(store, 9L, 99000L, 88000L, null);
        saveClock(store, -1L, 99000L, 88000L, null);
        Object stopped = run(store, 8L);
        check(number(stopped, "elapsedMs") == 6000L && number(stopped, "turnElapsedMs") == 2000L
                        && number(stopped, "turnThinkMs") == 300L && !(Boolean) field(stopped, "running")
                        && records("runs", 9L).isEmpty() && records("runs", -1L).isEmpty(),
                "Heartbeat created a row, updated an idle session, or crossed its session boundary");
        saveRun(store, 7L, false, 18000L, 8000L, 0L);
        saveClock(store, 7L, 19000L, 9000L, null);
        saved = run(store, 7L);
        check(number(saved, "turnElapsedMs") == 8000L && Long.valueOf(0L).equals(field(saved, "turnThinkMs")),
                "Late heartbeat rewrote a final stopped checkpoint");
    }

    private static void everyLegacyVersionUpgradesWithNullableDurationColumns() throws Exception {
        for (int version = 1; version <= 15; version++) {
            Object store = fresh(), db = databaseType.getConstructor().newInstance();
            databaseType.getMethod("legacyRuns", int.class).invoke(null, version);
            if (version >= 4) {
                Class<?> valuesType = databaseType.getClassLoader().loadClass("android.content.ContentValues");
                @SuppressWarnings("unchecked") Map<String, Object> legacy = (Map<String, Object>) valuesType.getConstructor().newInstance();
                legacy.put("session_id", 7L); legacy.put("running", 1); legacy.put("goal", "old goal");
                legacy.put("status", "active"); legacy.put("elapsed_ms", 123L);
                if (version >= 5) {
                    legacy.put("turn_at", 10L); legacy.put("turn_wall", 20L); legacy.put("seen_at", 30L);
                }
                if (version >= 13) {
                    legacy.put("turn_elapsed_ms", 900L); legacy.put("turn_think_ms", 0L);
                }
                databaseType.getMethod("insert", String.class, String.class, valuesType).invoke(db, "runs", null, legacy);
            }
            if (version >= 12) databaseType.getField("requestDiagnosticColumn").setBoolean(null, true);
            append(store, 7L, Message.user("preserved legacy request"));
            storeType.getMethod("onUpgrade", databaseType, int.class, int.class).invoke(store, db, version, 16);
            @SuppressWarnings("unchecked") Set<String> columns = (Set<String>) databaseType.getField("runColumns").get(null);
            check(columns.containsAll(Arrays.asList("turn_at", "turn_wall", "seen_at", "turn_elapsed_ms",
                            "turn_think_ms", "tokens_used", "token_budget", "budget_wrap_finished")),
                    "Version " + version + " omitted old compatibility or new duration columns");
            @SuppressWarnings("unchecked") List<String> sql = (List<String>) databaseType.getField("statements").get(null);
            int elapsedAlters = 0, thinkAlters = 0, selectionAlters = 0;
            for (String statement : sql) {
                if (statement.equals("ALTER TABLE runs ADD COLUMN turn_elapsed_ms INTEGER")) elapsedAlters++;
                if (statement.equals("ALTER TABLE runs ADD COLUMN turn_think_ms INTEGER")) thinkAlters++;
                if (statement.equals("ALTER TABLE messages ADD COLUMN mcp_selection TEXT NOT NULL DEFAULT ''")) selectionAlters++;
            }
            check(elapsedAlters == (version >= 4 && version < 13 ? 1 : 0) && thinkAlters == (version >= 4 && version < 13 ? 1 : 0),
                    "Version " + version + " altered duration columns after creating their latest schema");
            check(selectionAlters == (version < 14 ? 1 : 0) && messages(page(store, 7L, -1, 48)).get(0).mcpSelection == null
                            && "preserved legacy request".equals(messages(page(store, 7L, -1, 48)).get(0).content),
                    "Version " + version + " failed to add exactly one empty selection default while preserving legacy requests");
            if (version >= 4) {
                Object restored = run(store, 7L);
                check((version >= 13 ? Long.valueOf(900L).equals(field(restored, "turnElapsedMs"))
                                && Long.valueOf(0L).equals(field(restored, "turnThinkMs"))
                                : field(restored, "turnElapsedMs") == null && field(restored, "turnThinkMs") == null)
                                && number(restored, "elapsedMs") == 123L && (Boolean) field(restored, "running")
                                && "old goal".equals(field(restored, "goal")),
                        "Version " + version + " invented a legacy checkpoint or lost its existing run/checkpoint");
            }
            saveRun(store, 7L, true, 500L, 250L, null);
            check(number(run(store, 7L), "turnElapsedMs") == 250L && field(run(store, 7L), "turnThinkMs") == null,
                    "Version " + version + " could not persist new checkpoint values after migration");
        }
        Object store = fresh(), db = databaseType.getConstructor().newInstance();
        storeType.getMethod("onCreate", databaseType).invoke(store, db);
        @SuppressWarnings("unchecked") List<String> sql = (List<String>) databaseType.getField("statements").get(null);
        boolean nullable = false;
        for (String statement : sql) if (statement.startsWith("CREATE TABLE IF NOT EXISTS runs (")) {
            nullable = statement.contains("turn_elapsed_ms INTEGER,turn_think_ms INTEGER,");
        }
        check(nullable, "Fresh schema did not create both nullable duration checkpoints");
        @SuppressWarnings("unchecked") Set<String> messageColumns = (Set<String>) databaseType.getField("messageColumns").get(null);
        check(messageColumns.contains("mcp_selection") && sql.toString().contains("mcp_selection TEXT NOT NULL DEFAULT ''"),
                "Fresh schema omitted empty-default MCP selection metadata");
    }

    private static String repeated(char value, int count) {
        char[] text = new char[count]; Arrays.fill(text, value); return new String(text);
    }

    private static void structuredRequestDiagnosticsAreRedactedAndBounded() throws Exception {
        Object store = fresh();
        append(store, 7, Message.user("private user request"));
        String evidence = new JSONObject().put("provider", "deepseek").put("status_code", 401)
                .put("api_key", "sensitive-key").put("Authorization", "Bearer opaque-secret")
                .put("messages", new JSONArray().put("private user request"))
                .put("endpoint", "https://user:credential@example.test/v1/chat?api_key=query-secret")
                .put("error", new JSONObject().put("code", "invalid_model").put("message", "model unavailable"))
                .put("response_body", "{\"password\":\"nested-secret\",\"error\":\"bad model\"}").toString();
        MethodAccess.recordRequest(store, 7, "unknown", -10, "unexpected", "Bearer opaque-secret\n" + repeated('x', 250), -2, evidence);
        Object event = requestRecords(7).get(0);
        String detail = (String) field(event, "diagnostic"), reason = (String) field(event, "reason");
        JSONObject parsed = new JSONObject(detail);
        check("deepseek".equals(parsed.getString("provider")) && parsed.getInt("status_code") == 401
                        && "invalid_model".equals(parsed.getJSONObject("error").getString("code"))
                        && "https://example.test/v1/chat".equals(parsed.getString("endpoint")),
                "Structured safe failure evidence or sanitized endpoint was lost");
        for (String secret : Arrays.asList("sensitive-key", "opaque-secret", "private user request", "credential", "query-secret", "nested-secret"))
            check(!detail.contains(secret) && !reason.contains(secret), "Request diagnostics retained confidential input: " + secret);
        check(reason.length() <= 160 && !reason.contains("\n") && "model".equals(field(event, "purpose"))
                        && "error".equals(field(event, "outcome")) && number(event, "elapsedMs") == 0
                        && (Integer) field(event, "retryCount") == 0,
                "Untrusted metadata was not normalized and bounded");
        MethodAccess.recordRequest(store, 7, "review", 1, "error", "", 0,
                new JSONObject().put("error", new JSONObject().put("message", repeated('x', 20000))).toString());
        detail = (String) field(requestRecords(7).get(0), "diagnostic");
        check(detail.length() <= 8192 && new JSONObject(detail).getJSONObject("error").getString("message").length() < 20000,
                "Large failure bodies exceeded the local evidence cap");
        MethodAccess.recordRequest(store, 7, "model", 1, "error", "", 0, "Bearer plaintext-secret");
        detail = (String) field(requestRecords(7).get(0), "diagnostic");
        check(!detail.contains("plaintext-secret") && new JSONObject(detail).has("detail"), "Plain text failure evidence was not structured and scrubbed");
        MethodAccess.recordRequest(store, -1, "model", 1, "error", "", 0, evidence);
        check(requestRecords(-1).isEmpty(), "Global configuration errors became model request history");
        @SuppressWarnings("unchecked") List<Message> history = (List<Message>) storeType.getMethod("contextMessages", long.class).invoke(store, 7L);
        check(history.size() == 1 && "private user request".equals(history.get(0).content), "Request evidence was injected into model context");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(String table, long sid) throws Exception {
        return (List<Map<String, Object>>) databaseType.getMethod("records", String.class, long.class).invoke(null, table, sid);
    }

    private static List<Map<String, Object>> requestRecords(long sid) throws Exception {
        List<Map<String, Object>> rows = records("request_events", sid);
        java.util.Collections.reverse(rows);
        return rows;
    }

    private static void configurationDiagnosticsAreRetainedAndSessionIsolated() throws Exception {
        Object store = fresh();
        append(store, 7, Message.user("keep model history"));
        MethodAccess.recordDiagnostic(store, 8, "toolkit", "other session", "{\"status\":\"failed\"}");
        for (int i = 0; i < 205; i++) {
            MethodAccess.recordDiagnostic(store, -1, "configuration", "global " + i, "{\"number\":" + i + "}");
            MethodAccess.recordDiagnostic(store, 7, "tool", "conversation " + i, "{\"number\":" + i + "}");
        }
        List<Map<String, Object>> global = records("diagnostic_errors", -1), conversation = records("diagnostic_errors", 7);
        check(global.size() == 200 && "global 5".equals(global.get(0).get("summary"))
                        && "global 204".equals(global.get(199).get("summary")) && conversation.size() == 200
                        && "conversation 5".equals(conversation.get(0).get("summary")) && records("diagnostic_errors", 8).size() == 1,
                "Diagnostic retention crossed global/session boundaries or retained stale failures");
        MethodAccess.recordDiagnostic(store, -1, "Bearer source-secret\n" + repeated('s', 100),
                "api_key=summary-secret\r" + repeated('s', 200),
                "{\"password\":\"detail-secret\",\"error\":{\"message\":\"" + repeated('x', 20000) + "\"}}");
        global = records("diagnostic_errors", -1);
        Map<String, Object> last = global.get(global.size() - 1);
        String source = (String) last.get("source"), summary = (String) last.get("summary"), detail = (String) last.get("detail");
        check(source.length() <= 80 && summary.length() <= 160 && detail.length() <= 8192
                        && !source.contains("source-secret") && !summary.contains("summary-secret") && !detail.contains("detail-secret")
                        && !source.contains("\n") && !summary.contains("\r") && ((Number) last.get("recorded_at")).longValue() > 0
                        && new JSONObject(detail).has("error"),
                "Configuration failures lost structured evidence, redaction, timestamps or size limits");
        check(messages(page(store, 7, -1, 48)).size() == 1 && requestRecords(7).isEmpty()
                        && requestRecords(-1).isEmpty(),
                "Configuration/tool errors polluted model transcript or request attempts");
        storeType.getMethod("delete", long.class).invoke(store, 7L);
        check(records("diagnostic_errors", 7).isEmpty() && records("diagnostic_errors", -1).size() == 200
                        && records("diagnostic_errors", 8).size() == 1,
                "Deleting a conversation left its diagnostic errors or removed global/other session failures");
    }

    private static final class MethodAccess {
        static void recordRequest(Object store, long sid, String purpose, long ms, String outcome, String reason, int retry) throws Exception {
            recordRequest(store, sid, purpose, ms, outcome, reason, retry, "");
        }
        static void recordRequest(Object store, long sid, String purpose, long ms, String outcome, String reason, int retry, String diagnostic) throws Exception {
            storeType.getMethod("recordRequest", long.class, String.class, long.class, String.class, String.class, int.class, String.class)
                    .invoke(store, sid, purpose, ms, outcome, reason, retry, diagnostic);
        }
        static void recordDiagnostic(Object store, long sid, String source, String summary, String detail) throws Exception {
            storeType.getMethod("recordDiagnostic", long.class, String.class, String.class, String.class).invoke(store, sid, source, summary, detail);
        }
    }

    @SuppressWarnings("unchecked")
    private static void trailingResultsFinishOnlyTheSameToolBatch() throws Exception {
        Object store = fresh();
        append(store, 7, Message.user("inspect"));
        JSONArray calls = new JSONArray().put(new JSONObject().put("id", "c1"))
                .put(new JSONObject().put("id", "c2")).put(new JSONObject().put("id", "pending"));
        append(store, 7, Message.assistant("tool request", calls));
        append(store, 7, Message.toolResult("c1", "already in page"));
        append(store, 7, Message.toolResult("unrelated", "unrelated result"));
        append(store, 8, Message.toolResult("c2", "other session"));
        append(store, 7, Message.toolResult("c2", "same batch result"));
        append(store, 7, Message.assistant("later response", null));
        append(store, 7, Message.toolResult("pending", "different batch result"));
        Object earlier = page(store, 7, 4, 48);
        List<Message> trailing = (List<Message>)field(earlier, "trailingResults");
        check(messages(earlier).size() == 3 && number(earlier, "firstId") == 1,
                "Context results were added to the visible page or changed the cursor");
        check(trailing.size() == 1 && "c2".equals(trailing.get(0).toolCallId)
                && "same batch result".equals(trailing.get(0).content),
                "Trailing context duplicated in-page results, leaked across sessions/batches, or lost an exact call result");

        store = fresh();
        append(store, 7, Message.assistant("running", new JSONArray().put(new JSONObject().put("id", "running"))));
        earlier = page(store, 7, -1, 48);
        check(((List<Message>)field(earlier, "trailingResults")).isEmpty(), "A real pending tool was marked complete");

        store = fresh();
        calls = new JSONArray();
        for (int i = 0; i < 200; i++) calls.put(new JSONObject().put("id", "c" + i));
        append(store, 7, Message.assistant("many calls", calls));
        for (int i = 0; i < 200; i++) append(store, 7, Message.toolResult("c" + i, "output " + i));
        earlier = page(store, 7, 2, 48);
        check(((List<Message>)field(earlier, "trailingResults")).size() == 128,
                "Trailing context expanded beyond its hard bound");
    }

    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path output = Files.createTempDirectory("backcast-paging-tests-");
        try {
            List<JavaFileObject> files = new ArrayList<JavaFileObject>();
            fixtures(files);
            try (StandardJavaFileManager manager = compiler.getStandardFileManager(null, null, null)) {
                for (JavaFileObject source : manager.getJavaFileObjects(Paths.get(args[0],
                        "app/src/main/java/com/mkei/backcast/ChatStore.java").toFile(), Paths.get(args[0],
                        "app/src/main/java/com/mkei/backcast/agent/Goal.java").toFile(), Paths.get(args[0],
                        "app/src/main/java/com/mkei/backcast/agent/Diagnostics.java").toFile())) files.add(source);
                check(compiler.getTask(null, manager, null, Arrays.asList("-encoding", "UTF-8", "-d", output.toString(),
                        "-source", "8", "-target", "8", "-Xlint:-options", "-classpath", System.getProperty("java.class.path")),
                        null, files).call(), "ChatStore fixture did not compile");
            }
            try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()})) {
                storeType = loader.loadClass("com.mkei.backcast.ChatStore");
                databaseType = loader.loadClass("android.database.sqlite.SQLiteDatabase");
                contextType = loader.loadClass("android.content.Context");
                for (String name : Arrays.asList("newestPageIsBoundedAndAscending", "cursorSurvivesNewMessages",
                        "toolPageRetainsOnlyLeadingLabels", "hugeTurnStillHasHardPageLimit",
                        "prefixRequestRetainsRetryMetadataWithoutChangingPage",
                        "continuationRetryAndDisclosureScopesRemainSeparate",
                        "prefixRequestCorruptionAndUserlessPageStayExplicit", "messageMetadataSurvivesPaging", "selectedMcpToolSurvivesTranscriptAndContextCheckpoint",
                        "corruptedMcpSelectionCannotSilentlyRestoreAnotherRequest", "emptyPageHasNoContext", "trailingResultsFinishOnlyTheSameToolBatch",
                        "compactionDividersPersistWithoutEnteringTheModelWindow", "compactionDividerPagingPreservesBoundariesAndRequestMetadata",
                        "versionFourteenRestoresOnlyItsKnownCompactionBoundary",
                        "stoppedEmptyTurnDoesNotRewritePreviousTurnTime", "requestDiagnosticsAreBoundedAndSeparateFromConversation",
                        "legacyDatabaseUpgradeAddsLocalRequestDiagnostics", "versionElevenMigrationPreservesRequestRows",
                        "runDurationsRoundTripWithoutLosingNullOrZero", "durationHeartbeatOnlyUpdatesExistingRunningSession",
                        "everyLegacyVersionUpgradesWithNullableDurationColumns",
                        "structuredRequestDiagnosticsAreRedactedAndBounded", "configurationDiagnosticsAreRetainedAndSessionIsolated")) {
                    ChatStorePagingRegressionTest.class.getDeclaredMethod(name).invoke(null);
                    System.out.println("PASS " + name);
                    passed++;
                }
                System.out.println(passed + " transcript paging tests passed");
            }
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(output)) {
                for (Path path : (Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
}
