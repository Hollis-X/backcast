import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** Exercises the real stopScroll method with the API 16 native touch/animation contract. */
public final class TranscriptScrollRegressionTest {
    private static int passed;
    private static Class<?> scrollType;
    private static Class<?> eventType;
    private static Class<?> contextType;

    private static final class Source extends SimpleJavaFileObject {
        final String text;
        Source(String name, String text) {
            super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE);
            this.text = text;
        }
        @Override public CharSequence getCharContent(boolean ignored) { return text; }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }
    private static Object field(Object target, String name) throws Exception {
        return target.getClass().getField(name).get(target);
    }
    private static void field(Object target, String name, Object value) throws Exception {
        target.getClass().getField(name).set(target, value);
    }
    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, args);
    }
    private static Object invoke(Object target, String name) throws Exception {
        return invoke(target, name, new Class<?>[0]);
    }
    private static Object newScroll() throws Exception {
        return scrollType.getConstructor(contextType).newInstance(contextType.getConstructor().newInstance());
    }
    private static void touch(Object scroll, int action, float y) throws Exception {
        Object event = eventType.getMethod("obtain", long.class, long.class, int.class,
                float.class, float.class, int.class).invoke(null, 1L, 2L, action, 0f, y, 0);
        invoke(scroll, "onTouchEvent", new Class<?>[]{eventType}, event);
        invoke(event, "recycle");
    }

    private static void compile(Path root, Path build) throws Exception {
        List<JavaFileObject> sources = new ArrayList<>();
        sources.add(new Source("android.content.Context", "package android.content; public class Context {}"));
        sources.add(new Source("android.util.AttributeSet", "package android.util; public interface AttributeSet {}"));
        sources.add(new Source("android.os.SystemClock", "package android.os; public class SystemClock {"
                + "public static long uptimeMillis(){return 10L;} }"));
        sources.add(new Source("android.view.ViewParent", "package android.view; public interface ViewParent {"
                + "void requestDisallowInterceptTouchEvent(boolean disallow); }"));
        sources.add(new Source("android.view.MotionEvent", "package android.view; public class MotionEvent {"
                + "public static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3;"
                + "public static int obtained,recycled; public int action;public float y;"
                + "public static MotionEvent obtain(long d,long t,int a,float x,float y,int m){"
                + "MotionEvent e=new MotionEvent();e.action=a;e.y=y;obtained++;return e;}"
                + "public int getActionMasked(){return action;}public float getY(){return y;}"
                + "public void recycle(){recycled++;} }"));
        // Like AOSP, DOWN aborts the scroller and CANCEL can spring back if still overscrolled.
        sources.add(new Source("android.widget.ScrollView", "package android.widget;"
                + "import android.content.*;import android.util.*;import android.view.*;"
                + "public class ScrollView {"
                + "public int x,y,range=1000,children=1,nextY,aborts,frames,pointer=-1,tracker;"
                + "public boolean animating,dragging;public float lastY;public Parent parent=new Parent();"
                + "public static class Parent implements ViewParent {public boolean disallow;"
                + "public void requestDisallowInterceptTouchEvent(boolean value){disallow=value;} }"
                + "public ScrollView(Context c){}public ScrollView(Context c,AttributeSet a){}"
                + "public ScrollView(Context c,AttributeSet a,int s){}"
                + "public int getScrollX(){return x;}public int getScrollY(){return y;}"
                + "public ViewParent getParent(){return parent;}"
                + "public void scrollTo(int a,int b){if(children>0){x=a;y=Math.max(0,Math.min(range,b));}}"
                + "public void fling(int v){animating=true;nextY=y+v;}"
                + "public void smoothScrollTo(int a,int b){animating=true;nextY=b;}"
                + "public void computeScroll(){frames++;if(animating)y=nextY;}"
                + "public boolean dispatchTouchEvent(MotionEvent e){return onTouchEvent(e);}"
                + "private void springBack(){if(y<0||y>range){animating=true;nextY=Math.max(0,Math.min(range,y));}}"
                + "public boolean onTouchEvent(MotionEvent e){tracker=1;switch(e.action){"
                + "case 0:if(children==0)return false;dragging=animating;"
                + "if(dragging)parent.requestDisallowInterceptTouchEvent(true);"
                + "if(animating){animating=false;aborts++;}lastY=e.y;pointer=0;break;"
                + "case 2:if(pointer<0)throw new IllegalStateException(\"No active pointer\");"
                + "if(Math.abs(lastY-e.y)>5)dragging=true;if(dragging)scrollTo(x,y+(int)(lastY-e.y));lastY=e.y;break;"
                + "case 1:if(dragging){fling(40);pointer=-1;dragging=false;tracker=0;}break;"
                + "case 3:if(dragging&&children>0){springBack();pointer=-1;dragging=false;tracker=0;}break;"
                + "}return true;}"
                + "public boolean onInterceptTouchEvent(MotionEvent e){if(e.action==3||e.action==1){"
                + "dragging=false;pointer=-1;tracker=0;springBack();}return dragging;} }"));
        try (StandardJavaFileManager fm = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            for (JavaFileObject source : fm.getJavaFileObjects(root.resolve(
                    "app/src/main/java/com/mkei/backcast/ui/TranscriptScrollView.java").toFile())) sources.add(source);
            check(ToolProvider.getSystemJavaCompiler().getTask(null, fm, null,
                    Arrays.asList("-proc:none", "-encoding", "UTF-8", "-source", "7", "-target", "7",
                            "-Xlint:-options", "-d", build.toString()), null, sources).call(),
                    "TranscriptScrollView fixture compilation failed");
        }
    }

    private static void activeFlingStopsBeforeJump() throws Exception {
        Object scroll = newScroll();
        field(scroll, "y", 400);
        invoke(scroll, "fling", new Class<?>[]{int.class}, -200);
        invoke(scroll, "stopScroll");
        invoke(scroll, "scrollTo", new Class<?>[]{int.class, int.class}, 0, 1000);
        for (int i=0;i<4;i++) invoke(scroll, "computeScroll");
        check((Integer) field(scroll, "y") == 1000 && !(Boolean) field(scroll, "animating")
                && (Integer) field(scroll, "aborts") == 1, "Scheduled fling moved the view after the jump");
        check(!(Boolean) field(field(scroll, "parent"), "disallow"), "Stop retained parent interception");
        pass("nativeFlingCannotOverwriteAnExplicitBottomJump");
    }
    private static void smoothScrollAlsoStops() throws Exception {
        Object scroll = newScroll();
        field(scroll, "y", 500);
        invoke(scroll, "smoothScrollTo", new Class<?>[]{int.class, int.class}, 0, 100);
        invoke(scroll, "stopScroll");
        invoke(scroll, "computeScroll");
        check((Integer) field(scroll, "y") == 500 && !(Boolean) field(scroll, "animating"),
                "Stopping the transcript left native smooth scrolling running");
        pass("nativeSmoothScrollStopsWithoutChangingTheReadingPosition");
    }
    private static void overscrollDoesNotStartAnotherAnimation() throws Exception {
        for (int y : new int[]{-25,1025}) {
            Object scroll = newScroll();
            field(scroll, "y", y);
            invoke(scroll, "fling", new Class<?>[]{int.class}, 30);
            invoke(scroll, "stopScroll");
            check(!(Boolean) field(scroll, "animating"), "CANCEL started a spring-back during a jump");
            invoke(scroll, "scrollTo", new Class<?>[]{int.class, int.class}, 0, 1000);
            invoke(scroll, "computeScroll");
            check((Integer) field(scroll, "y") == 1000, "Old overscroll replaced the latest position");
        }
        pass("stopClampsBothEdgesBeforeCancelCanStartSpringBack");
    }
    private static void repeatIdleAndEmptyStopsReleasePointers() throws Exception {
        for (int children : new int[]{0,1}) {
            Object scroll = newScroll();
            field(scroll, "children", children);
            for (int i=0;i<3;i++) invoke(scroll, "stopScroll");
            check(!(Boolean) field(scroll, "animating") && !(Boolean) field(scroll, "dragging")
                    && (Integer) field(scroll, "pointer") == -1 && (Integer) field(scroll, "tracker") == 0,
                    "An idle/empty stop retained a synthetic gesture");
        }
        Field obtained=eventType.getField("obtained"),recycled=eventType.getField("recycled");
        check(obtained.getInt(null)==recycled.getInt(null), "Stop leaked synthetic MotionEvents");
        pass("repeatedIdleAndEmptyStopsReleaseNativePointersAndEvents");
    }
    private static void nextUserGestureStillScrollsAndFlings() throws Exception {
        Object scroll = newScroll();
        field(scroll, "y", 300);
        invoke(scroll, "fling", new Class<?>[]{int.class}, 50);
        invoke(scroll, "stopScroll");
        touch(scroll, 0, 100f);
        touch(scroll, 2, 70f);
        check((Integer) field(scroll, "y") == 330, "The next user's drag stopped working");
        touch(scroll, 1, 70f);
        check((Boolean) field(scroll, "animating"), "The next user's fling stopped working");
        pass("stoppingTheOldFlingPreservesTheNextNativeDragAndFling");
    }
    private static void onlyRealTouchDispatchCancelsUiWork() throws Exception {
        Object scroll = newScroll();
        final int[] callbacks = {0};
        invoke(scroll, "setOnTouchStartListener", new Class<?>[]{Runnable.class},
                new Runnable() { @Override public void run() { callbacks[0]++; } });
        invoke(scroll, "stopScroll");
        check(callbacks[0]==0, "Synthetic cancellation invalidated the explicit jump's UI ownership");
        Object event=eventType.getMethod("obtain",long.class,long.class,int.class,float.class,float.class,int.class)
                .invoke(null,1L,2L,0,0f,80f,0);
        invoke(scroll,"dispatchTouchEvent",new Class<?>[]{eventType},event);
        invoke(event,"recycle");
        check(callbacks[0]==1, "A dispatched touch-down did not cancel outstanding UI work");
        invoke(scroll, "stopScroll");
        check(callbacks[0]==1, "A second synthetic cancellation looked like another real touch");
        pass("onlyRealTouchDispatchNotSyntheticCancellationInvalidatesUiWork");
    }
    private static void layoutUsesStoppableTranscript(Path root) throws Exception {
        NodeList nodes=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(root.resolve(
                "app/src/main/res/layout/activity_main.xml").toFile()).getElementsByTagName(
                "com.mkei.backcast.ui.TranscriptScrollView");
        check(nodes.getLength()==1 && "@+id/scroll".equals(((Element)nodes.item(0)).getAttribute("android:id")),
                "The main transcript does not use the stoppable ScrollView");
        pass("transcriptLayoutInflatesTheScrollViewWithExplicitCancellation");
    }

    public static void main(String[] args) throws Exception {
        Path root=Paths.get(args[0]),build=Files.createTempDirectory("backcast-scroll-tests-");
        try {
            compile(root,build);
            try (URLClassLoader loader=new URLClassLoader(new URL[]{build.toUri().toURL()},null)) {
                scrollType=loader.loadClass("com.mkei.backcast.ui.TranscriptScrollView");
                contextType=loader.loadClass("android.content.Context");
                eventType=loader.loadClass("android.view.MotionEvent");
                activeFlingStopsBeforeJump();
                smoothScrollAlsoStops();
                overscrollDoesNotStartAnotherAnimation();
                repeatIdleAndEmptyStopsReleasePointers();
                nextUserGestureStillScrollsAndFlings();
                onlyRealTouchDispatchCancelsUiWork();
                layoutUsesStoppableTranscript(root);
            }
            System.out.println(passed+" transcript scroll tests passed");
        } finally {
            try (var paths=Files.walk(build)) {
                for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
