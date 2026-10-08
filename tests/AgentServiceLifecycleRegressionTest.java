import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Runs the real service with queued main callbacks and blocking recovery, not an Android runtime. */
public final class AgentServiceLifecycleRegressionTest {
    static void add(List<JavaFileObject> files, String name, String code) {
        files.add(new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return code; }
        });
    }
    static void fixtures(List<JavaFileObject> files) {
        add(files, "android.content.SharedPreferences", """
            package android.content; public class SharedPreferences {
              public final java.util.Map<String,Long> values=new java.util.concurrent.ConcurrentHashMap<>();
              public long getLong(String name,long fallback){return values.getOrDefault(name,fallback);}
              public Editor edit(){return new Editor();} public class Editor{
                public Editor putLong(String name,long value){values.put(name,value);return this;} public boolean commit(){return true;}
              }
            }
            """);
        add(files, "android.content.Context", """
            package android.content; public class Context {
              public static final String NOTIFICATION_SERVICE="notification",POWER_SERVICE="power",ACTIVITY_SERVICE="activity";
              public static final int MODE_PRIVATE=0;
              public android.app.NotificationManager notifications=new android.app.NotificationManager();
              public android.os.PowerManager power=new android.os.PowerManager();
              public android.app.ActivityManager activities=new android.app.ActivityManager();
              public SharedPreferences preferences=new SharedPreferences();public RuntimeException startError;public int starts;
              public Context getApplicationContext(){return this;}public String getPackageName(){return "com.mkei.backcast";}
              public Object getSystemService(String name){return name.equals(NOTIFICATION_SERVICE)?notifications:name.equals(POWER_SERVICE)?power:activities;}
              public SharedPreferences getSharedPreferences(String name,int mode){return preferences;}
              public Intent startForegroundService(Intent i){starts++;if(startError!=null)throw startError;return i;}
              public String getString(int id){return "Backcast";}
            }
            """);
        add(files, "android.content.Intent", "package android.content; public class Intent {public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_SINGLE_TOP=2;public Intent(Context c,Class<?> type){}public void setFlags(int f){}}");
        add(files, "android.os.IBinder", "package android.os;public interface IBinder{}");
        add(files, "android.os.Looper", "package android.os;public class Looper{public static Looper getMainLooper(){return new Looper();}}");
        add(files, "android.os.Handler", """
            package android.os;public class Handler{
              static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> queue=new java.util.concurrent.ConcurrentLinkedQueue<>();
              public Handler(Looper l){}public boolean post(Runnable r){queue.add(r);return true;}
              public static boolean queued(){return !queue.isEmpty();}public static void drain(){Runnable r;while((r=queue.poll())!=null)r.run();}
            }
            """);
        add(files, "android.os.Process", "package android.os;public class Process{public static int myPid(){return 700;}}");
        add(files, "android.os.Build", "package android.os;public class Build{public static class VERSION{public static int SDK_INT=30;}}");
        add(files, "android.os.PowerManager", """
            package android.os;public class PowerManager{
              public static final int PARTIAL_WAKE_LOCK=1;public RuntimeException error;public WakeLock last;
              public WakeLock newWakeLock(int flags,String name){last=new WakeLock(this);return last;}
              public static class WakeLock{final PowerManager parent;boolean held;public int acquires,releases;
                WakeLock(PowerManager p){parent=p;}public void setReferenceCounted(boolean b){}public boolean isHeld(){return held;}
                public void acquire(){if(parent.error!=null)throw parent.error;held=true;acquires++;}public void release(){held=false;releases++;}
              }
            }
            """);
        add(files, "android.app.Service", """
            package android.app;public class Service extends android.content.Context{
              public static final int STOP_FOREGROUND_REMOVE=1,START_NOT_STICKY=2,START_STICKY=1;
              public int promotions,demotions,stopAttempts,stops,newestStart;public boolean destroyed;public RuntimeException foregroundError;
              public android.os.IBinder onBind(android.content.Intent i){return null;}public int onStartCommand(android.content.Intent i,int f,int id){return 0;}
              public void onDestroy(){destroyed=true;}public void onTaskRemoved(android.content.Intent i){}
              public void startForeground(int id,Notification n){if(foregroundError!=null)throw foregroundError;promotions++;}
              public void stopForeground(int flags){demotions++;}public boolean stopSelfResult(int id){stopAttempts++;if(id!=newestStart)return false;stops++;return true;}
            }
            """);
        add(files, "android.app.Notification", """
            package android.app;public class Notification{public String text;public static class Builder{
              final Notification value=new Notification();public Builder(android.content.Context c,String channel){}
              public Builder setSmallIcon(int v){return this;}public Builder setContentTitle(String v){return this;}
              public Builder setContentText(String v){value.text=v;return this;}public Builder setOngoing(boolean v){return this;}
              public Builder setContentIntent(PendingIntent v){return this;}public Notification getNotification(){return value;}
            }}
            """);
        add(files, "android.app.NotificationChannel", "package android.app;public class NotificationChannel{public NotificationChannel(String id,String name,int priority){}public void setShowBadge(boolean b){}}");
        add(files, "android.app.NotificationManager", "package android.app;public class NotificationManager{public static final int IMPORTANCE_LOW=1;public int updates;public Notification last;public void createNotificationChannel(NotificationChannel c){}public void notify(int id,Notification n){updates++;last=n;}}");
        add(files, "android.app.PendingIntent", "package android.app;public class PendingIntent{public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2;public static PendingIntent getActivity(android.content.Context c,int id,android.content.Intent i,int flags){return new PendingIntent();}}");
        add(files, "android.app.ApplicationExitInfo", """
            package android.app;public class ApplicationExitInfo{
              public int pid,reason,status,importance;public long timestamp;public String process;
              public ApplicationExitInfo(int p,String n,long t,int r){pid=p;process=n;timestamp=t;reason=r;status=9;importance=200;}
              public int getPid(){return pid;}public String getProcessName(){return process;}public long getTimestamp(){return timestamp;}
              public int getReason(){return reason;}public int getStatus(){return status;}public int getImportance(){return importance;}
            }
            """);
        add(files, "android.app.ActivityManager", """
            package android.app;public class ActivityManager{
              public java.util.List<ApplicationExitInfo> exits=new java.util.ArrayList<>();public volatile int reads;public String requestedPackage;public int requestedPid;
              public java.util.concurrent.CountDownLatch gate;
              public java.util.List<ApplicationExitInfo> getHistoricalProcessExitReasons(String p,int pid,int count){reads++;requestedPackage=p;requestedPid=pid;if(gate!=null)try{gate.await();}catch(InterruptedException e){throw new RuntimeException(e);}return exits;}
            }
            """);
        add(files, "android.util.Log", "package android.util;public class Log{public static int w(String tag,String text){return 0;}}");
        add(files, "android.R", "package android;public class R{public static class drawable{public static int stat_notify_sync=1;}}");
        add(files, "com.mkei.backcast.R", "package com.mkei.backcast;public class R{public static class string{public static int app_name=1;}}");
        add(files, "com.mkei.backcast.MainActivity", "package com.mkei.backcast;public class MainActivity{}");
        add(files, "com.mkei.backcast.ChatStore", """
            package com.mkei.backcast;public class ChatStore implements AutoCloseable{
              public static final java.util.List<Long> running=new java.util.concurrent.CopyOnWriteArrayList<>();
              public static final java.util.List<org.json.JSONObject> events=new java.util.concurrent.CopyOnWriteArrayList<>();public static boolean fail;
              public ChatStore(android.content.Context c){if(fail)throw new IllegalStateException("DB unavailable");}
              public java.util.List<Long> runningIds(){return new java.util.ArrayList<>(running);}
              public void recordDiagnostic(long sid,String source,String summary,String detail){try{events.add(new org.json.JSONObject().put("sid",sid).put("source",source).put("summary",summary).put("detail",new org.json.JSONObject(detail)));}catch(Exception e){throw new RuntimeException(e);}}
              public void close(){}
            }
            """);
        add(files, "com.mkei.backcast.RunHub", """
            package com.mkei.backcast;public class RunHub{
              public static final RunHub INSTANCE=new RunHub();public volatile boolean active=true;public volatile int recovers,reads;
              public volatile java.util.concurrent.CountDownLatch entered,gate;public volatile RuntimeException recoveryError;
              public static RunHub get(android.content.Context c){return INSTANCE;}
              public void recover(){recovers++;if(entered!=null)entered.countDown();if(gate!=null)try{gate.await();}catch(InterruptedException e){throw new RuntimeException(e);}if(recoveryError!=null)throw recoveryError;}
              public boolean hasWork(){reads++;return active;}public String noteText(){return "Active task";}
            }
            """);
        add(files, "com.mkei.backcast.ServiceChecks", checks());
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]), build=Files.createTempDirectory("backcast-service-lifecycle-");
        List<JavaFileObject> files=new ArrayList<>();fixtures(files);
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(null,null,null)){
            for(JavaFileObject file:manager.getJavaFileObjects(root.resolve("app/src/main/java/com/mkei/backcast/AgentService.java").toFile(),
                    root.resolve("app/src/main/java/com/mkei/backcast/agent/Diagnostics.java").toFile()))files.add(file);
            boolean ok=compiler.getTask(null,manager,null,List.of("-proc:none","-classpath",System.getProperty("java.class.path"),"-d",build.toString()),null,files).call();
            if(!ok)throw new AssertionError("AgentService fixture compilation failed");
            try(URLClassLoader loader=new URLClassLoader(new URL[]{build.toUri().toURL()})){
                loader.loadClass("com.mkei.backcast.ServiceChecks").getMethod("run").invoke(null);
            }
        }finally{try(var filesInBuild=Files.walk(build)){for(Path file:(Iterable<Path>)filesInBuild.sorted(Comparator.reverseOrder())::iterator)Files.delete(file);}}
    }
    static String checks(){return """
        package com.mkei.backcast;
        import android.app.*;import android.content.*;import android.os.*;import org.json.*;
        import java.util.*;import java.util.concurrent.*;import java.util.function.BooleanSupplier;
        public class ServiceChecks{
          static int passed;static RunHub hub=RunHub.INSTANCE;
          static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
          static void pass(String text){passed++;System.out.println("PASS "+text);}
          static void await(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+3_000_000_000L;while(System.nanoTime()<end){Handler.drain();if(condition.getAsBoolean())return;Thread.sleep(5);}throw new AssertionError("Timed out");}
          static void waitWithoutDraining(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+3_000_000_000L;while(System.nanoTime()<end){if(condition.getAsBoolean())return;Thread.sleep(5);}throw new AssertionError("Timed out queued callback");}
          static void reset(){Handler.drain();ChatStore.running.clear();ChatStore.events.clear();ChatStore.fail=false;hub.active=true;hub.recovers=0;hub.reads=0;hub.gate=null;hub.entered=null;hub.recoveryError=null;Build.VERSION.SDK_INT=30;}
          static int start(AgentService service,int id){service.newestStart=id;return service.onStartCommand(new Intent(service,AgentService.class),0,id);}
          static boolean event(String name){return ChatStore.events.stream().anyMatch(v->v.getJSONObject("detail").optString("event").equals(name));}
          static long eventCount(String name){return ChatStore.events.stream().filter(v->v.getJSONObject("detail").optString("event").equals(name)).count();}
          static void close(AgentService service)throws Exception{hub.active=false;service.onDestroy();Thread.sleep(10);Handler.drain();}
          static void recoveryLeavesMainResponsive()throws Exception{
            reset();AgentService service=new AgentService();hub.entered=new CountDownLatch(1);hub.gate=new CountDownLatch(1);
            try{int result=start(service,1);check(result==Service.START_STICKY&&service.promotions==1&&service.power.last.isHeld(),"Active service did not promote/hold wake immediately");
              check(hub.entered.await(1,TimeUnit.SECONDS),"Background recovery never began");start(service,2);
              check(hub.recovers==1&&service.stops==0,"Repeated start duplicated blocked recovery or stopped work");
              hub.gate.countDown();await(()->service.notifications.updates==1);check(service.notifications.last.text.equals("Active task"),"Restored active notification not published");
            }finally{hub.gate.countDown();close(service);}pass("foregroundAndWakePrecedeBackgroundRecoveryAndStartsCoalesce");
          }
          static void noWorkStopsOnlyOwnStart()throws Exception{
            reset();hub.active=false;AgentService service=new AgentService();try{start(service,1);await(()->service.stops==1);
              check(service.demotions==1&&!service.power.last.isHeld(),"Idle stop leaked foreground or wake lock");service.onDestroy();Thread.sleep(20);check(!event("service_destroyed_with_work"),"Expected idle stop was diagnosed as active interruption");
            }finally{close(service);}pass("idleRecoveryStopsServiceAndReleasesWake");
          }
          static void oldIdleCallbackCannotStopNewWork()throws Exception{
            reset();hub.active=false;AgentService service=new AgentService();try{start(service,1);waitWithoutDraining(Handler::queued);hub.active=true;start(service,2);Handler.drain();
              await(()->service.notifications.updates==1);check(service.stops==0&&service.demotions==0&&service.power.last.isHeld()&&hub.recovers==2,"Old no-work result stopped a newer active start");
            }finally{close(service);}pass("staleIdleSnapshotRechecksNewStartInsteadOfStoppingIt");
          }
          static void pendingAndroidStartCannotBeStopped()throws Exception{
            reset();hub.active=false;AgentService service=new AgentService();try{start(service,1);waitWithoutDraining(Handler::queued);service.newestStart=2;Handler.drain();
              check(service.stopAttempts==1&&service.stops==0&&service.demotions==0&&service.power.last.isHeld(),"stopSelfResult rejection still removed protection");
              hub.active=true;start(service,2);await(()->service.notifications.updates==1);
            }finally{close(service);}pass("platformPendingStartRetainsForegroundAndWakeUntilNewCallback");
          }
          static void deniedStartIsRecorded()throws Exception{
            reset();ChatStore.running.add(31L);Context app=new Context();app.startError=new IllegalStateException("Bearer fixture-secret");AgentService.start(app);
            await(()->eventCount("foreground_start_rejected")==2);check(app.starts==1,"Rejected foreground start retried automatically");
            check(ChatStore.events.stream().map(v->v.optLong("sid")).collect(java.util.stream.Collectors.toSet()).equals(Set.of(-1L,31L)),"Service denial not associated with pending conversation");
            check(!ChatStore.events.toString().contains("fixture-secret"),"Raw start error credential leaked");pass("foregroundStartDenialIsBoundedPrivateEvidenceAndDoesNotRetry");
          }
          static void promotionAndWakeFailureStayTraceable()throws Exception{
            reset();AgentService service=new AgentService();service.foregroundError=new SecurityException("forbidden");try{
              check(start(service,1)==Service.START_NOT_STICKY&&service.stops==1&&hub.recovers==0,"Promotion failure attempted unprotected recovery");await(()->event("foreground_setup_failed"));
            }finally{close(service);}
            reset();service=new AgentService();AgentService current=service;service.power.error=new SecurityException("wake denied");try{
              start(service,1);await(()->event("wake_lock_failed")&&current.notifications.updates==1);check(service.stops==0&&hub.recovers==1,"Wake failure stopped live work");
            }finally{close(service);}pass("promotionAndWakeFailureRecordExactProtectionFailure");
          }
          static void recoverFailureDoesNotCancelWork()throws Exception{
            reset();ChatStore.running.add(32L);hub.recoveryError=new IllegalStateException("Bearer fixture-secret");AgentService service=new AgentService();try{
              start(service,1);await(()->service.notifications.updates==1&&event("recovery_failed"));
              check(service.stops==0&&service.power.last.isHeld()&&!ChatStore.events.toString().contains("fixture-secret"),"Recovery failure stopped task/protection or exposed raw error");
            }finally{close(service);}pass("recoveryFailureIsRecordedWhileExistingWorkKeepsProtection");
          }
          static void destructionRejectsLateCallbacks()throws Exception{
            reset();ChatStore.running.add(33L);hub.entered=new CountDownLatch(1);hub.gate=new CountDownLatch(1);AgentService service=new AgentService();try{
              start(service,1);check(hub.entered.await(1,TimeUnit.SECONDS),"Recovery not blocked");service.onDestroy();await(()->event("service_destroyed_with_work"));
              check(!service.power.last.isHeld(),"Destroy leaked wake lock");hub.gate.countDown();waitWithoutDraining(Handler::queued);Handler.drain();
              check(service.notifications.updates==0&&service.stops==0,"Late recovery callback changed a destroyed service");
            }finally{hub.gate.countDown();close(service);}pass("activeDestroyRecordsObservedLossAndRejectsLateRecoveryCallback");
          }
          static void taskRemovalDoesNotCancelService()throws Exception{
            reset();AgentService service=new AgentService();try{start(service,1);await(()->service.notifications.updates==1);service.onTaskRemoved(new Intent(service,AgentService.class));await(()->event("task_removed"));
              check(service.stops==0&&service.power.last.isHeld(),"Task removal stopped the independently running service");
            }finally{close(service);}pass("taskRemovalIsEvidenceWithoutCancellingActiveWork");
          }
          static void destroyedServiceDoesNotBeginRecoveryAfterExitInspection()throws Exception{
            reset();AgentService service=new AgentService();service.activities.gate=new CountDownLatch(1);try{
              start(service,1);waitWithoutDraining(()->service.activities.reads==1);service.onDestroy();service.activities.gate.countDown();
              Thread.sleep(30);Handler.drain();check(hub.recovers==0&&service.notifications.updates==0&&!service.power.last.isHeld(),"Destroyed service began recovery after blocked exit inspection");
            }finally{service.activities.gate.countDown();close(service);}pass("destroyedServiceNeverBeginsDelayedRecovery");
          }
          static void fatalExceptionEvidencePrecedesExit(){
            reset();ChatStore.running.add(34L);AgentService.recordUncaughtException(new Context(),Thread.currentThread(),new IllegalStateException("Bearer fixture-secret"));
            check(eventCount("uncaught_exception")==2&&!ChatStore.events.toString().contains("fixture-secret"),"Fatal exception diagnostics were asynchronous/missing/unredacted");
            JSONObject detail=ChatStore.events.get(0).getJSONObject("detail");check(detail.has("thread_id")&&detail.getJSONObject("failure").getJSONArray("exceptions").length()>0,"Fatal thread/failure evidence missing");
            pass("uncaughtExceptionWritesPrivatePendingSessionEvidenceSynchronously");
          }
          static void previousExitUsesOnlyOwnProcessAndDeduplicates(){
            reset();ChatStore.running.add(35L);Context app=new Context();app.activities.exits.add(new ApplicationExitInfo(900,"other.package",6000,1));
            app.activities.exits.add(new ApplicationExitInfo(700,"com.mkei.backcast",5000,5));app.activities.exits.add(new ApplicationExitInfo(800,"com.mkei.backcast:remote",4000,3));
            app.activities.exits.add(new ApplicationExitInfo(101,"com.mkei.backcast",1000,2));app.activities.exits.add(new ApplicationExitInfo(102,"com.mkei.backcast",2000,6));
            AgentService.recordPreviousExit(app);AgentService.recordPreviousExit(app);check(eventCount("previous_process_exit")==2,"Historical exit recorded twice or lost per-session association");
            JSONObject detail=ChatStore.events.get(0).getJSONObject("detail");check(detail.getInt("exit_pid")==102&&detail.getLong("exit_timestamp")==2000&&detail.getInt("exit_reason")==6,"Wrong process/current PID/history chosen");
            check(app.activities.requestedPackage.equals(app.getPackageName())&&app.activities.requestedPid==0,"Historical lookup exceeded application scope");
            app.activities.exits.add(new ApplicationExitInfo(103,"com.mkei.backcast",3000,1));AgentService.recordPreviousExit(app);check(eventCount("previous_process_exit")==4,"New exit record hidden by deduplication");
            pass("systemExitEvidenceFiltersProcessAndPidAndDeduplicatesTimestamp");
          }
          static void oldSdkAndFailedEvidenceDoNotFabricateCause(){
            reset();Context app=new Context();Build.VERSION.SDK_INT=29;AgentService.recordPreviousExit(app);check(app.activities.reads==0&&ChatStore.events.isEmpty(),"Old SDK queried unavailable exit API");
            Build.VERSION.SDK_INT=30;app.activities.exits.add(new ApplicationExitInfo(104,"com.mkei.backcast",4000,0));ChatStore.fail=true;AgentService.recordPreviousExit(app);
            check(app.preferences.getLong("exit_timestamp",0)==0&&ChatStore.events.isEmpty(),"Unavailable diagnostic was marked recorded");
            ChatStore.fail=false;AgentService.recordPreviousExit(app);check(eventCount("previous_process_exit")==1&&ChatStore.events.get(0).getJSONObject("detail").getInt("exit_reason")==0,"Unknown exit reason was fabricated or not retried on future inspection");
            pass("unsupportedSdkAndUnavailableEvidenceRemainExplicit");
          }
          public static void run()throws Exception{
            recoveryLeavesMainResponsive();noWorkStopsOnlyOwnStart();oldIdleCallbackCannotStopNewWork();pendingAndroidStartCannotBeStopped();deniedStartIsRecorded();
            promotionAndWakeFailureStayTraceable();recoverFailureDoesNotCancelWork();destructionRejectsLateCallbacks();taskRemovalDoesNotCancelService();destroyedServiceDoesNotBeginRecoveryAfterExitInspection();fatalExceptionEvidencePrecedesExit();
            previousExitUsesOnlyOwnProcessAndDeduplicates();oldSdkAndFailedEvidenceDoNotFabricateCause();System.out.println(passed+" service lifecycle tests passed");
          }
        }
        """;}
}
