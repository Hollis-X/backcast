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
                + "public SQLiteOpenHelper(android.content.Context c,String n,Object f,int v){}"
                + "public SQLiteDatabase getReadableDatabase(){return db;} public SQLiteDatabase getWritableDatabase(){return db;}"
                + "public abstract void onCreate(SQLiteDatabase db); public abstract void onUpgrade(SQLiteDatabase db,int o,int n); }");
        add(files, "android.database.sqlite.SQLiteDatabase",
                "import java.util.*; import android.database.Cursor; import android.content.ContentValues;"
                + "public class SQLiteDatabase { public static final int CONFLICT_REPLACE=5;"
                + "private static final List<Map<String,Object>> rows=new ArrayList<Map<String,Object>>(); private static long next=1;"
                + "public static void reset(){rows.clear();next=1;} public void execSQL(String s){} public void execSQL(String s,Object[] a){}"
                + "public void beginTransaction(){} public void setTransactionSuccessful(){} public void endTransaction(){}"
                + "public long insert(String table,String nullColumn,ContentValues values){Map<String,Object> row=new HashMap<String,Object>(values);"
                + "long id=next++;row.put(\"id\",Long.valueOf(id));if(table.equals(\"messages\"))rows.add(row);return id;}"
                + "public long insertWithOnConflict(String t,String n,ContentValues v,int c){return insert(t,n,v);}"
                + "public int update(String t,ContentValues v,String s,String[] a){return 0;} public int delete(String t,String s,String[] a){return 0;}"
                + "public Cursor query(String t,String[] c,String s,String[] a,String g,String h,String o){return query(t,c,s,a,g,h,o,null);}"
                + "public Cursor query(String table,String[] columns,String selection,String[] args,String group,String having,String order,String limit){"
                + "List<Map<String,Object>> selected=new ArrayList<Map<String,Object>>();"
                + "for(Map<String,Object> row:rows){boolean include=true;int arg=0;"
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
                + "long count=0;for(Map<String,Object> row:rows)if(((Number)row.get(\"session_id\")).longValue()==Long.parseLong(args[0])"
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
                        "app/src/main/java/com/mkei/backcast/agent/Goal.java").toFile())) files.add(source);
                check(compiler.getTask(null, manager, null, Arrays.asList("-encoding", "UTF-8", "-d", output.toString(),
                        "-source", "7", "-target", "7", "-Xlint:-options", "-classpath", System.getProperty("java.class.path")),
                        null, files).call(), "ChatStore fixture did not compile");
            }
            try (URLClassLoader loader = new URLClassLoader(new URL[]{output.toUri().toURL()})) {
                storeType = loader.loadClass("com.mkei.backcast.ChatStore");
                databaseType = loader.loadClass("android.database.sqlite.SQLiteDatabase");
                contextType = loader.loadClass("android.content.Context");
                for (String name : Arrays.asList("newestPageIsBoundedAndAscending", "cursorSurvivesNewMessages",
                        "toolPageRetainsOnlyLeadingLabels", "hugeTurnStillHasHardPageLimit",
                        "messageMetadataSurvivesPaging", "emptyPageHasNoContext", "trailingResultsFinishOnlyTheSameToolBatch")) {
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
