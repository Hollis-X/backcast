import com.mkei.backcast.agent.Message;
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
                + "public class SQLiteDatabase { public static final int CONFLICT_REPLACE=5;"
                + "private static final List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>(); private static long next=1;"
                + "public static final List<String> statements=new ArrayList<String>();public static boolean requestDiagnosticColumn;"
                + "public static void reset(){rows.clear();next=1;statements.clear();requestDiagnosticColumn=false;} public void execSQL(String s){statements.add(s);"
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
                + "long id=next++;row.put(\"id\",Long.valueOf(id));row.put(\"table\",table);if(table.equals(\"messages\")||table.equals(\"request_events\")||table.equals(\"diagnostic_errors\"))rows.add(row);return id;}"
                + "public long insertWithOnConflict(String t,String n,ContentValues v,int c){return insert(t,n,v);}"
                + "public int update(String t,ContentValues v,String s,String[] a){if(t.equals(\"sessions\"))return 0;"
                + "if(!s.equals(\"id=?\"))throw new AssertionError(s);int changed=0;for(Map<String,Object> row:rows)"
                + "if(row.get(\"table\").equals(t)&&((Number)row.get(\"id\")).longValue()==Long.parseLong(a[0])){row.putAll(v);changed++;}return changed;}"
                + "public int delete(String t,String s,String[] a){if(!s.equals(\"session_id=?\")&&!t.equals(\"sessions\"))throw new AssertionError(s);"
                + "int changed=0;for(Iterator<Map<String,Object>> i=rows.iterator();i.hasNext();){Map<String,Object> row=i.next();"
                + "if(row.get(\"table\").equals(t)&&((Number)row.get(\"session_id\")).longValue()==Long.parseLong(a[0])){i.remove();changed++;}}return changed;}"
                + "public Cursor query(String t,String[] c,String s,String[] a,String g,String h,String o){return query(t,c,s,a,g,h,o,null);}"
                + "public Cursor query(String table,String[] columns,String selection,String[] args,String group,String having,String order,String limit){"
                + "List<Map<String,Object>> selected=new ArrayList<Map<String,Object>>();"
                + "for(Map<String,Object> row:rows){if(!row.get(\"table\").equals(table))continue;boolean include=true;int arg=0;"
                + "for(String condition:selection.split(\" AND \")){"
                + "if(condition.equals(\"session_id=?\")){if(((Number)row.get(\"session_id\")).longValue()!=Long.parseLong(args[arg++]))include=false;}"
                + "else if(condition.equals(\"id<?\")){if(((Number)row.get(\"id\")).longValue()>=Long.parseLong(args[arg++]))include=false;}"
                + "else if(condition.equals(\"id>?\")){if(((Number)row.get(\"id\")).longValue()<=Long.parseLong(args[arg++]))include=false;}"
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
                + "public Cursor rawQuery(String sql,String[] args){if(!sql.startsWith(\"SELECT COUNT(*) FROM messages\"))throw new AssertionError(sql);"
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
        check(number(page, "firstId") == 953 && number(page, "lastId") == 1000, "Incorrect page cursors");
        check(number(page, "earlierCount") == 952, "Earlier count included another session");
        for (int i = 0; i < 48; i++) check(messages(page).get(i).content.equals("message " + (952 + i)), "Page is not chronological");
        check("message 951".equals(field(page, "requestBefore")), "Lost user context before page");
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
            check(number(page, "lastId") < before, "Cursor boundary was repeated");
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
        check("inspect files".equals(field(page, "requestBefore")), "Tool page lost user request context");
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
        check("one long task".equals(field(page, "requestBefore")), "Long turn lost original request");
        check(messages(page(store, 7, -1, 0)).size() == 1, "Nonpositive limit did not remain bounded");
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
        List<Message> full = (List<Message>)storeType.getMethod("messages", long.class).invoke(store, 7);
        check(full.size() == 3 && "c1".equals(full.get(2).toolCallId) && full.get(1).elapsedMs == 2500,
                "Full model history could not use shared decoding");
    }

    private static void emptyPageHasNoContext() throws Exception {
        Object page = page(fresh(), 7, -1, 48);
        check(messages(page).isEmpty() && number(page, "firstId") == 0 && number(page, "lastId") == 0
                && number(page, "earlierCount") == 0 && "".equals(field(page, "requestBefore"))
                && field(page, "leadingAssistant") == null, "Empty page carried stale cursors or context");
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
        List<?> all = MethodAccess.requestEvents(store, 7, Integer.MAX_VALUE);
        check(all.size() == 200 && number(all.get(0), "elapsedMs") == 204
                        && number(all.get(199), "elapsedMs") == 5,
                "Request history exceeded its cap or discarded the newest attempts");
        check(MethodAccess.requestEvents(store, 7, 20).size() == 20 && MethodAccess.requestEvents(store, 7, 0).size() == 1,
                "Diagnostic reads were unbounded");
        check("retryable_error".equals(field(all.get(0), "outcome"))
                        && "接口返回 HTTP 503".equals(field(all.get(0), "reason"))
                        && (Integer) field(all.get(0), "retryCount") == 2,
                "Safe request failure metadata was not preserved");
        check(MethodAccess.requestEvents(store, 8, 200).size() == 1, "Retention deleted another conversation's request");
        check(messages(page(store, 7, -1, 48)).size() == 1, "Diagnostics polluted model/transcript history");
        storeType.getMethod("delete", long.class).invoke(store, 7L);
        check(MethodAccess.requestEvents(store, 7, 200).isEmpty() && MethodAccess.requestEvents(store, 8, 200).size() == 1,
                "Conversation removal left private request logs or removed another conversation's logs");
    }

    private static void legacyDatabaseUpgradeAddsLocalRequestDiagnostics() throws Exception {
        Object store = fresh();
        Object db = databaseType.getConstructor().newInstance();
        check(storeType.getSuperclass().getField("requestedVersion").getInt(null) == 12,
                "Fresh databases do not request the diagnostics schema version");
        storeType.getMethod("onUpgrade", databaseType, int.class, int.class).invoke(store, db, 10, 12);
        @SuppressWarnings("unchecked")
        List<String> sql = (List<String>) databaseType.getField("statements").get(null);
        check(sql.size() == 4 && sql.get(0).startsWith("CREATE TABLE IF NOT EXISTS request_events")
                        && sql.get(0).contains("diagnostic TEXT NOT NULL DEFAULT ''")
                        && sql.get(1).contains("request_events(session_id,id)")
                        && sql.get(2).startsWith("CREATE TABLE IF NOT EXISTS diagnostic_errors")
                        && sql.get(3).contains("diagnostic_errors(session_id,id)"),
                "Version 10 direct upgrade omitted evidence tables or attempted to add an existing diagnostic column");
        MethodAccess.recordRequest(store, 7, "review", 123, "error", "权限检查失败\n服务暂不可用", 0);
        MethodAccess.recordRequest(store, 7, "compact", 0, "cancelled", "用户停止", 1);
        List<?> events = MethodAccess.requestEvents(store, 7, 20);
        check("compact".equals(field(events.get(0), "purpose")) && "cancelled".equals(field(events.get(0), "outcome"))
                        && "review".equals(field(events.get(1), "purpose"))
                        && "权限检查失败 服务暂不可用".equals(field(events.get(1), "reason"))
                        && number(events.get(1), "elapsedMs") == 123 && number(events.get(1), "recordedAt") > 0,
                "Purpose, cancellation, safe reason or request duration was decoded incorrectly");
        try { ((List) events).clear(); throw new AssertionError("Diagnostics were mutable"); }
        catch (UnsupportedOperationException expected) { }
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
        storeType.getMethod("onUpgrade", databaseType, int.class, int.class).invoke(store, db, 11, 12);
        @SuppressWarnings("unchecked") List<String> sql = (List<String>) databaseType.getField("statements").get(null);
        check(sql.size() == 3 && sql.get(0).equals("ALTER TABLE request_events ADD COLUMN diagnostic TEXT NOT NULL DEFAULT ''")
                        && sql.get(1).startsWith("CREATE TABLE IF NOT EXISTS diagnostic_errors")
                        && sql.get(2).contains("diagnostic_errors(session_id,id)"),
                "Version 11 migration recreated request history or did not apply the additive column default");
        List<?> before = MethodAccess.requestEvents(store, 7, 20);
        check(before.size() == 1 && number(before.get(0), "id") == oldId && number(before.get(0), "recordedAt") == 123
                        && number(before.get(0), "elapsedMs") == 41 && "compact".equals(field(before.get(0), "purpose"))
                        && "cancelled".equals(field(before.get(0), "outcome")) && "old reason".equals(field(before.get(0), "reason"))
                        && (Integer) field(before.get(0), "retryCount") == 2 && "".equals(field(before.get(0), "diagnostic")),
                "The migration lost an existing attempt or failed to decode the new empty diagnostic default");
        MethodAccess.recordRequest(store, 7, "model", 3, "success", "", 0, "{\"provider\":\"grok\"}");
        List<?> after = MethodAccess.requestEvents(store, 7, 20);
        check(after.size() == 2 && number(after.get(1), "id") == oldId
                        && "grok".equals(new JSONObject((String) field(after.get(0), "diagnostic")).getString("provider"))
                        && "preserved conversation".equals(messages(page(store, 7, -1, 48)).get(0).content),
                "New diagnostic writes after migration changed the old log or conversation");
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
        Object event = MethodAccess.requestEvents(store, 7, 20).get(0);
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
        detail = (String) field(MethodAccess.requestEvents(store, 7, 20).get(0), "diagnostic");
        check(detail.length() <= 8192 && new JSONObject(detail).getJSONObject("error").getString("message").length() < 20000,
                "Large failure bodies exceeded the local evidence cap");
        MethodAccess.recordRequest(store, 7, "model", 1, "error", "", 0, "Bearer plaintext-secret");
        detail = (String) field(MethodAccess.requestEvents(store, 7, 20).get(0), "diagnostic");
        check(!detail.contains("plaintext-secret") && new JSONObject(detail).has("detail"), "Plain text failure evidence was not structured and scrubbed");
        MethodAccess.recordRequest(store, -1, "model", 1, "error", "", 0, evidence);
        check(MethodAccess.requestEvents(store, -1, 20).isEmpty(), "Global configuration errors became model request history");
        @SuppressWarnings("unchecked") List<Message> history = (List<Message>) storeType.getMethod("contextMessages", long.class).invoke(store, 7L);
        check(history.size() == 1 && "private user request".equals(history.get(0).content), "Request evidence was injected into model context");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> records(String table, long sid) throws Exception {
        return (List<Map<String, Object>>) databaseType.getMethod("records", String.class, long.class).invoke(null, table, sid);
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
        check(messages(page(store, 7, -1, 48)).size() == 1 && MethodAccess.requestEvents(store, 7, 20).isEmpty()
                        && MethodAccess.requestEvents(store, -1, 20).isEmpty(),
                "Configuration/tool errors polluted model transcript or request attempts");
        storeType.getMethod("delete", long.class).invoke(store, 7L);
        check(records("diagnostic_errors", 7).isEmpty() && records("diagnostic_errors", -1).size() == 200
                        && records("diagnostic_errors", 8).size() == 1,
                "Deleting a conversation left its diagnostic errors or removed global/other session failures");
    }

    private static final class MethodAccess {
        static void recordRequest(Object store, long sid, String purpose, long ms, String outcome, String reason, int retry) throws Exception {
            storeType.getMethod("recordRequest", long.class, String.class, long.class, String.class, String.class, int.class)
                    .invoke(store, sid, purpose, ms, outcome, reason, retry);
        }
        static void recordRequest(Object store, long sid, String purpose, long ms, String outcome, String reason, int retry, String diagnostic) throws Exception {
            storeType.getMethod("recordRequest", long.class, String.class, long.class, String.class, String.class, int.class, String.class)
                    .invoke(store, sid, purpose, ms, outcome, reason, retry, diagnostic);
        }
        static void recordDiagnostic(Object store, long sid, String source, String summary, String detail) throws Exception {
            storeType.getMethod("recordDiagnostic", long.class, String.class, String.class, String.class).invoke(store, sid, source, summary, detail);
        }
        static List<?> requestEvents(Object store, long sid, int limit) throws Exception {
            return (List<?>) storeType.getMethod("requestEvents", long.class, int.class).invoke(store, sid, limit);
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
        check(messages(earlier).size() == 3 && number(earlier, "lastId") == 3,
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
                        "messageMetadataSurvivesPaging", "emptyPageHasNoContext", "trailingResultsFinishOnlyTheSameToolBatch",
                        "stoppedEmptyTurnDoesNotRewritePreviousTurnTime", "requestDiagnosticsAreBoundedAndSeparateFromConversation",
                        "legacyDatabaseUpgradeAddsLocalRequestDiagnostics", "versionElevenMigrationPreservesRequestRows",
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
