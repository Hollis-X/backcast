import com.mkei.backcast.agent.Message;
import com.mkei.backcast.agent.SubAgentManager;
import java.io.File;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.json.*;

/** Production checkpoints run against real SQLite, including transaction rollback and old-file migration. */
public final class SQLiteSubAgentStoreRegressionTest {
    private static Path project, output, files;
    private static Class<?> storeType, childType, databaseType, helperType, contextType;
    private static Object database, store;
    private static int passed;
    private static void check(boolean value,String detail){if(!value)throw new AssertionError(detail);}
    private static Object invoke(Object object,String name,Class<?>[] types,Object... args)throws Exception{
        try{return object.getClass().getMethod(name,types).invoke(object,args);}
        catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof Exception)throw (Exception)cause;throw failure;}
    }
    private static void fresh()throws Exception{
        if(database!=null)invoke(database,"close",new Class<?>[0]);
        helperType.getField("shared").set(null,null);databaseType.getField("failRecordWrite").setInt(null,0);
        databaseType.getField("refuseInsertTable").set(null,null);
        store=storeType.getConstructor(contextType).newInstance(contextType.getConstructor().newInstance());
        database=invoke(store,"getWritableDatabase",new Class<?>[0]);
    }
    private static Object child(long sid)throws Exception{return childType.getConstructor(storeType,long.class).newInstance(store,sid);}
    private static SubAgentManager.Record record(String id,long revision,String status)throws Exception{
        SubAgentManager.Record record=new SubAgentManager.Record();record.id=id;record.parentId="main";record.name=id;
        record.task="Inspect original";record.status=status;record.sessionId=8;record.revision=revision;
        record.result="completed artifact";record.tokensUsed=123;record.acknowledgedRevision=revision;
        record.history.put(Message.user("original task").toCheckpointJson());
        record.history.put(Message.assistant("stored answer",null).toCheckpointJson());
        record.pending.put(new JSONObject().put("id","mail-stable").put("from","main").put("text","next"));
        record.acknowledgedChildren.put("agent_done",revision);
        record.currentTaskId="task-stable";
        record.tasks.put(new JSONObject().put("taskId","task-stable").put("request","Inspect original")
                .put("status","completed").put("partial","original partial").put("resultId","result-stable"));
        record.results.put(new JSONObject().put("resultId","result-stable").put("taskId","task-stable")
                .put("status","completed").put("content","independent artifact"));
        record.events.put(new JSONObject().put("eventId","event-stable").put("type","progress").put("text","report"));
        record.forkHistory.put(Message.user("forked original").toCheckpointJson());
        record.forkConfig.put("client",new JSONObject().put("model","captured-model"));
        record.acknowledgedResults.put("result-stable",4);record.deliveredResults.put("result-stable",8);
        record.rootWakeAllowed=true;
        return record;
    }
    private static void save(Object child,SubAgentManager.Record record)throws Exception{invoke(child,"save",new Class<?>[]{SubAgentManager.Record.class},record);}
    @SuppressWarnings("unchecked") private static List<SubAgentManager.Record> load(Object child)throws Exception{return (List<SubAgentManager.Record>)invoke(child,"load",new Class<?>[0]);}
    private static void roundTripKeepsStatusesHistoryMailResultsAndAck()throws Exception{
        fresh();Object first=child(7);SubAgentManager.Record record=record("agent_fixture",3,SubAgentManager.IDLE);
        record.toJson();save(first,record);SubAgentManager.Record restored=load(child(7)).get(0);
        check(restored.toJson().toString().equals(record.toJson().toString()),"Record fields changed during SQLite round trip");
        check(restored.history.length()==2&&restored.pending.getJSONObject(0).getString("id").equals("mail-stable")
                &&restored.acknowledgedChildren.getLong("agent_done")==3&&restored.status.equals(SubAgentManager.IDLE),"Lost completed task/history/mail/ack");
        record.status=SubAgentManager.CLOSED;record.revision++;save(first,record);
        check(load(child(7)).get(0).status.equals(SubAgentManager.CLOSED),"Closed checkpoint became runnable");
    }
    private static void staleAndEqualRevisionsCannotOverwriteNewState()throws Exception{
        fresh();Object child=child(7);SubAgentManager.Record record=record("agent_fixture",5,SubAgentManager.CLOSED);save(child,record);
        record.status=SubAgentManager.QUEUED;record.task="stale work";record.revision=3;save(child,record);
        record.revision=5;save(child,record);
        check(load(child).get(0).status.equals(SubAgentManager.CLOSED)&&load(child).get(0).task.equals("Inspect original"),"Stale checkpoint revived stopped work");
    }
    private static void parentSessionsStayIsolatedAndDeleteBlocksLateWriters()throws Exception{
        fresh();Object first=child(7),other=child(8),late=child(7);
        save(first,record("agent_fixture",3,SubAgentManager.IDLE));save(other,record("agent_fixture",4,SubAgentManager.CLOSED));
        invoke(store,"delete",new Class<?>[]{long.class},7L);
        boolean blocked=false;try{save(late,record("agent_fixture",9,SubAgentManager.RUNNING));}catch(IllegalStateException expected){blocked=true;}
        check(blocked&&load(other).size()==1,"Deletion revived its session or removed a sibling session");
    }
    private static void draftAdoptionIsTransactionalAndTombstonesOldOwner()throws Exception{
        fresh();Object draft=child(-1);save(draft,record("agent_fixture",3,SubAgentManager.IDLE));
        Field owner=childType.getDeclaredField("owner");owner.setAccessible(true);String old=(String)owner.get(draft);
        invoke(draft,"bindSession",new Class<?>[]{long.class},7L);
        check(load(child(7)).size()==1,"Adoption lost child records");
        boolean blocked=false;try{invoke(store,"saveSubAgentRecord",new Class<?>[]{String.class,JSONObject.class},old,record("agent_fixture",9,SubAgentManager.RUNNING).toJson());}catch(IllegalStateException expected){blocked=true;}
        check(blocked,"Late draft write recreated the adopted owner");
    }
    @SuppressWarnings("unchecked") private static void recoveryIndexRequiresDurableRootPermission()throws Exception{
        fresh();Object child=child(7);SubAgentManager.Record root=record("main",3,SubAgentManager.IDLE),pending=record("agent_fixture",4,SubAgentManager.QUEUED);
        root.rootWakeAllowed=false;save(child,root);save(child,pending);
        check(((List<Long>)invoke(store,"subAgentWorkSessionIds",new Class<?>[0])).isEmpty(),"Unarmed/failed parent became automatically resumable");
        root.revision++;root.rootWakeAllowed=true;save(child,root);
        check(((List<Long>)invoke(store,"subAgentWorkSessionIds",new Class<?>[0])).equals(Arrays.asList(7L)),"Idle parent with durable child work was omitted");
        root.revision++;root.managerCancelled=true;save(child,root);
        check(((List<Long>)invoke(store,"subAgentWorkSessionIds",new Class<?>[0])).isEmpty(),"Stopped parent queue became resumable");
    }
    @SuppressWarnings("unchecked") private static void coordinationEventsSurviveTranscriptAndContextRecovery()throws Exception{
        fresh();
        JSONObject batch=new JSONObject().put("agents",new JSONArray().put(new JSONObject()
                .put("resultId","artifact-stable").put("offset",8000).put("endOffset",12000)))
                .put("inbox",new JSONArray().put(new JSONObject().put("id","mail-stable")));
        Message event=Message.user(Message.COORDINATION_PREFIX+"Inspect child data.\n"+batch);
        invoke(store,"append",new Class<?>[]{long.class,Message.class},7L,event);
        invoke(store,"append",new Class<?>[]{long.class,Message.class},7L,Message.user(com.mkei.backcast.agent.Goal.STEER_PREFIX+"ephemeral"));
        List<Message> raw=(List<Message>)invoke(store,"contextMessages",new Class<?>[]{long.class},7L);
        check(raw.size()==1&&raw.get(0).coordinationIds.toString().equals(new JSONArray()
                .put("mail-stable").put("result:artifact-stable:8000:12000").toString()),"Raw recovery dropped event identities or persisted ordinary steer");
        event.restoreCoordinationIds();
        invoke(store,"replaceAll",new Class<?>[]{long.class,List.class},7L,Arrays.asList(event,Message.user(com.mkei.backcast.agent.Goal.STEER_PREFIX+"ephemeral")));
        List<Message> checkpoint=(List<Message>)invoke(store,"contextMessages",new Class<?>[]{long.class},7L);
        check(checkpoint.size()==1&&checkpoint.get(0).content.equals(event.content)
                &&checkpoint.get(0).coordinationIds.toString().equals(raw.get(0).coordinationIds.toString()),"Checkpoint recovery lost child event or chunk dedup identities");
    }
    @SuppressWarnings("unchecked") private static void rejectedTranscriptInsertCannotConfirmChildEvent()throws Exception{
        fresh();Message event=Message.user(Message.COORDINATION_PREFIX+"Inspect child data.\n"
                +new JSONObject().put("messages",new JSONArray().put(new JSONObject().put("id","mail-stable"))));
        databaseType.getField("refuseInsertTable").set(null,"messages");boolean failed=false;
        try{invoke(store,"append",new Class<?>[]{long.class,Message.class},7L,event);}catch(IllegalStateException expected){failed=true;}
        check(failed&&((List<Message>)invoke(store,"contextMessages",new Class<?>[]{long.class},7L)).isEmpty(),"Rejected message write appeared durable");
        databaseType.getField("refuseInsertTable").set(null,"context_windows");failed=false;
        try{invoke(store,"replaceAll",new Class<?>[]{long.class,List.class},7L,Arrays.asList(event));}catch(IllegalStateException expected){failed=true;}
        check(failed&&((List<Message>)invoke(store,"contextMessages",new Class<?>[]{long.class},7L)).isEmpty(),"Rejected checkpoint write appeared durable");
    }
    @SuppressWarnings("unchecked") private static void explicitChildRestartIndexRequiresPendingAuthorizedTask()throws Exception{
        fresh();Object child=child(7);
        SubAgentManager.Record root=record("main",3,SubAgentManager.IDLE);root.rootWakeAllowed=false;root.userRestartOnly=true;
        SubAgentManager.Record selected=record("agent_selected",4,SubAgentManager.QUEUED);
        selected.tasks.getJSONObject(0).remove("resultId");selected.tasks.getJSONObject(0).put("userRestartOnly",true).put("status","queued");
        selected.pending.getJSONObject(0).put("kind","task").put("taskId","task-stable").put("userRestartOnly",true);
        save(child,root);save(child,selected);save(child,record("agent_old",4,SubAgentManager.QUEUED));
        check(((List<Long>)invoke(store,"subAgentWorkSessionIds",new Class<?>[0])).equals(Arrays.asList(7L))
                &&(Boolean)invoke(store,"subAgentUserRestartOnly",new Class<?>[]{long.class},7L),"Explicit selected child was omitted from detached recovery");
        selected.revision++;selected.tasks.getJSONObject(0).put("resultId","new-result");selected.pending=new JSONArray();save(child,selected);
        check(((List<Long>)invoke(store,"subAgentWorkSessionIds",new Class<?>[0])).isEmpty(),"Unmarked old sibling queue revived after authorized task completed");
        selected.revision++;selected.tasks.getJSONObject(0).remove("resultId");save(child,selected);
        root.revision++;root.managerCancelled=true;save(child,root);
        check(((List<Long>)invoke(store,"subAgentWorkSessionIds",new Class<?>[0])).isEmpty()
                &&!(Boolean)invoke(store,"subAgentUserRestartOnly",new Class<?>[]{long.class},7L),"A later stop did not revoke explicit child recovery");
    }
    public static void main(String[] args)throws Exception{
        project=Paths.get(args[0]);output=Files.createTempDirectory("backcast-sqlite-children-");files=output.resolve("files");Files.createDirectories(files);
        System.setProperty("backcast.sqlite.bridge",project.resolve("tests/support/sqlite/sqlite_bridge.py").toString());
        List<File> sources=new ArrayList<File>();
        try(java.util.stream.Stream<Path> paths=Files.walk(project.resolve("tests/support/sqlite"))){paths.filter(p->p.toString().endsWith(".java")).forEach(p->sources.add(p.toFile()));}
        sources.add(project.resolve("app/src/main/java/com/mkei/backcast/ChatStore.java").toFile());sources.add(project.resolve("app/src/main/java/com/mkei/backcast/SQLiteSubAgentStore.java").toFile());
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(null,null,null)){
            check(compiler.getTask(null,manager,null,Arrays.asList("-proc:none","-encoding","UTF-8","-source","8","-target","8","-Xlint:-options","-classpath",System.getProperty("java.class.path"),"-d",output.toString()),null,manager.getJavaFileObjectsFromFiles(sources)).call(),"SQLite child fixture compilation failed");
        }
        try(URLClassLoader loader=new URLClassLoader(new URL[]{output.toUri().toURL()},SQLiteSubAgentStoreRegressionTest.class.getClassLoader())){
            contextType=loader.loadClass("android.content.Context");storeType=loader.loadClass("com.mkei.backcast.ChatStore");childType=loader.loadClass("com.mkei.backcast.SQLiteSubAgentStore");databaseType=loader.loadClass("android.database.sqlite.SQLiteDatabase");helperType=loader.loadClass("android.database.sqlite.SQLiteOpenHelper");
            for(String name:Arrays.asList("roundTripKeepsStatusesHistoryMailResultsAndAck","staleAndEqualRevisionsCannotOverwriteNewState","parentSessionsStayIsolatedAndDeleteBlocksLateWriters","draftAdoptionIsTransactionalAndTombstonesOldOwner","recoveryIndexRequiresDurableRootPermission","coordinationEventsSurviveTranscriptAndContextRecovery","rejectedTranscriptInsertCannotConfirmChildEvent","explicitChildRestartIndexRequiresPendingAuthorizedTask")){
                try{SQLiteSubAgentStoreRegressionTest.class.getDeclaredMethod(name).invoke(null);}catch(InvocationTargetException failure){throw new AssertionError(name,failure.getCause());}
                passed++;System.out.println("PASS "+name);
            }
            System.out.println(passed+" SQLite child checkpoint tests passed");
        }finally{
            if(database!=null)invoke(database,"close",new Class<?>[0]);
            try(java.util.stream.Stream<Path> paths=Files.walk(output)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.delete(path);}
        }
    }
}
