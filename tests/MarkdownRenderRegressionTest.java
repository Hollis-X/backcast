import com.mkei.backcast.ui.MarkdownRenderQueue;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Real renderer spans plus controlled worker/UI frames; no Android View is touched by the worker. */
public final class MarkdownRenderRegressionTest {
    private static int passed;
    private static Method render;
    private static final class Source extends SimpleJavaFileObject {
        private final String text;
        Source(String name, String text) {
            super(URI.create("string:///" + name.replace('.', '/') + ".java"), Kind.SOURCE);
            this.text = text;
        }
        @Override public CharSequence getCharContent(boolean ignored) { return text; }
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "Worker did not reach the expected point"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }
    private static void pass(String name) { passed++; System.out.println("PASS " + name); }
    private static CharSequence markdown(String source, int background) {
        try { return (CharSequence) render.invoke(null, source, background); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    @SuppressWarnings("unchecked")
    private static List<Object[]> spans(CharSequence text) throws Exception {
        return (List<Object[]>) text.getClass().getField("spans").get(text);
    }
    private static boolean span(CharSequence text, String className) throws Exception {
        for (Object[] item : spans(text)) if (item[0].getClass().getSimpleName().equals(className)) return true;
        return false;
    }
    private static final class Frames implements Executor {
        final List<Runnable> tasks = new ArrayList<Runnable>();
        @Override public synchronized void execute(Runnable task) { tasks.add(task); notifyAll(); }
        synchronized void awaitFrame() throws Exception {
            long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (tasks.isEmpty() && System.nanoTime() < limit) wait(100);
            check(tasks.size() == 1, "Rendering flooded the UI with " + tasks.size() + " pending frame results");
        }
        void frame() throws Exception {
            awaitFrame(); Runnable task;
            synchronized (this) { task = tasks.remove(0); }
            task.run();
        }
    }
    private static MarkdownRenderQueue queue(ExecutorService worker, Frames frames) {
        return new MarkdownRenderQueue(worker, frames, new MarkdownRenderQueue.Renderer() {
            @Override public CharSequence render(String source, int background) { return markdown(source, background); }
        });
    }
    private static void streamingFormatsBeforeTheReplyEndsOnAWorker() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(); Frames frames = new Frames();
        List<CharSequence> applied = new ArrayList<CharSequence>(); Thread ui = Thread.currentThread();
        final boolean[] backgroundThread = { false };
        MarkdownRenderQueue queue = new MarkdownRenderQueue(worker, frames, (source, bg) -> {
            backgroundThread[0] = Thread.currentThread() != ui; return markdown(source, bg);
        });
        try {
            queue.submit(new Object(), "## 中文标题\n**已经输出** `abc`", 0xFFE0E0E0, true, applied::add);
            frames.awaitFrame(); check(applied.isEmpty(), "Worker changed presentation before a UI frame");
            frames.frame();
            check(backgroundThread[0] && applied.size() == 1, "Live Markdown waited for completion or parsed on the UI thread");
            check(applied.get(0).toString().equals("中文标题\n已经输出 abc")
                    && span(applied.get(0), "StyleSpan") && span(applied.get(0), "TypefaceSpan")
                    && span(applied.get(0), "BackgroundColorSpan"),
                    "Live headings/bold/inline code did not use the actual Markdown parser");
        } finally { queue.close(); worker.shutdownNow(); }
        pass("streamingFormatsBeforeTheReplyEndsOnAWorker");
    }
    private static void updatesCoalesceAndOnlyTheFinalVersionCanApply() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(); Frames frames = new Frames();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger parsed = new AtomicInteger(); List<CharSequence> applied = new ArrayList<CharSequence>();
        MarkdownRenderQueue queue = new MarkdownRenderQueue(worker, frames, (source, bg) -> {
            if (parsed.incrementAndGet() == 1) { entered.countDown(); await(release); }
            return markdown(source, bg);
        });
        Object paragraph = new Object();
        try {
            queue.submit(paragraph, "**开", 10, true, applied::add); await(entered);
            for (int i = 0; i < 100; i++) queue.submit(paragraph, "**更新" + i + "**", 10, true, applied::add);
            String finalText = "## 完成\n```java\n第一行();\n  第二行();\n```";
            queue.submit(paragraph, finalText, 10, true, applied::add);
            release.countDown(); frames.frame(); check(applied.isEmpty(), "An obsolete in-flight result overwrote the final reply");
            frames.frame();
            check(parsed.get() == 2 && applied.size() == 1 && applied.get(0).toString().equals("完成\n第一行();\n  第二行();"),
                    "Every token was rendered separately or the final reply lost code indentation/newlines");
            check(span(applied.get(0), "TypefaceSpan") && span(applied.get(0), "BackgroundColorSpan")
                    && !span(applied.get(0), "CodeSpan"), "A multiline code block is still a single unbreakable replacement");
        } finally { release.countDown(); queue.close(); worker.shutdownNow(); }
        pass("updatesCoalesceAndOnlyTheFinalVersionCanApply");
    }
    private static void sessionAndDestroyInvalidateAlreadyPostedResults() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(); Frames frames = new Frames();
        MarkdownRenderQueue queue = queue(worker, frames); List<CharSequence> applied = new ArrayList<CharSequence>();
        try {
            Object target = new Object(); queue.submit(target, "旧会话", 1, false, applied::add);
            frames.awaitFrame(); queue.cancelAll(); frames.frame(); check(applied.isEmpty(), "Session replacement applied a stale posted result");
            queue.submit(target, "新会话", 1, true, applied::add); frames.frame();
            check(applied.size() == 1 && applied.get(0).toString().equals("新会话"), "Cancellation prevented the new session from rendering");
            queue.submit(target, "销毁后", 1, false, applied::add); frames.awaitFrame(); queue.close(); frames.frame();
            queue.submit(target, "不能复活", 1, true, applied::add);
            check(applied.size() == 1 && frames.tasks.isEmpty(), "A destroyed render queue retained visible work");
        } finally { queue.close(); worker.shutdownNow(); }
        pass("sessionAndDestroyInvalidateAlreadyPostedResults");
    }
    private static void liveReplyTakesPriorityAndEachResultNeedsItsOwnFrame() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(); Frames frames = new Frames();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger parsed = new AtomicInteger(); List<String> applied = new ArrayList<String>();
        MarkdownRenderQueue queue = new MarkdownRenderQueue(worker, frames, (source, bg) -> {
            if (parsed.incrementAndGet() == 1) { entered.countDown(); await(release); }
            return markdown(source, bg);
        });
        try {
            queue.submit(new Object(), "历史1", 1, false, v -> applied.add(v.toString())); await(entered);
            queue.submit(new Object(), "历史2", 1, false, v -> applied.add(v.toString()));
            queue.submit(new Object(), "历史3", 1, false, v -> applied.add(v.toString()));
            queue.submit(new Object(), "流式正文", 1, true, v -> applied.add(v.toString()));
            release.countDown(); frames.awaitFrame(); check(parsed.get() == 1, "History parsing ran ahead of UI frame delivery");
            frames.frame(); frames.frame();
            check(applied.equals(Arrays.asList("历史1", "流式正文")), "A live reply waited behind the entire history page");
            frames.frame(); frames.frame(); check(applied.size() == 4, "History was lost after the live reply took priority");
        } finally { release.countDown(); queue.close(); worker.shutdownNow(); }
        pass("liveReplyTakesPriorityAndEachResultNeedsItsOwnFrame");
    }
    private static void renderFailureFallsBackAndDoesNotBlockOtherMessages() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor(); Frames frames = new Frames();
        List<CharSequence> applied = new ArrayList<CharSequence>();
        MarkdownRenderQueue queue = new MarkdownRenderQueue(worker, frames, (source, bg) -> {
            if (source.equals("broken")) throw new IllegalArgumentException("invalid markup");
            return markdown(source, bg);
        });
        try {
            queue.submit(new Object(), "broken", 1, false, applied::add);
            queue.submit(new Object(), "**后续**", 1, false, applied::add); frames.frame(); frames.frame();
            check(applied.size() == 2 && applied.get(0).toString().equals("broken") && applied.get(1).toString().equals("后续"),
                    "A failed Markdown parse discarded content or stopped following messages");
        } finally { queue.close(); worker.shutdownNow(); }
        pass("renderFailureFallsBackAndDoesNotBlockOtherMessages");
    }
    private static void largeCodeAndMobileTablesPreserveReadableCopyText() throws Exception {
        StringBuilder source = new StringBuilder("```python\n");
        for (int i = 0; i < 4000; i++) source.append("  print('中文").append(i).append("')\n");
        source.append("```\n\n| 名称 | 状态 |\n| --- | --- |\n| 工作 | **完成** |\n");
        CharSequence result = markdown(source.toString(), 11);
        check(result.toString().startsWith("  print('中文0')\n") && result.toString().contains("  print('中文3999')\n")
                && result.toString().endsWith("名称  工作\n状态  完成"), "Long code or table copy text was truncated or reordered");
        check(span(result, "TypefaceSpan") && !span(result, "CodeSpan"), "Long code still uses a monolithic ReplacementSpan");
        CharSequence incomplete = markdown("```java\n第一行\n第二行", 11);
        check(incomplete.toString().equals("第一行\n第二行") && span(incomplete, "TypefaceSpan"),
                "An unfinished streamed fence cannot render before the closing marker arrives");
        pass("largeCodeAndMobileTablesPreserveReadableCopyText");
    }
    private static void compileRenderer(Path root, Path build) throws Exception {
        List<JavaFileObject> sources = new ArrayList<JavaFileObject>();
        sources.add(new Source("android.graphics.Typeface", "package android.graphics;public class Typeface{public static final int BOLD=1;public static final Typeface MONOSPACE=new Typeface();}"));
        sources.add(new Source("android.graphics.Paint", "package android.graphics;public class Paint{public static final int ANTI_ALIAS_FLAG=1;public static class FontMetricsInt{}"
                + "public Paint(){}public Paint(int v){}public Typeface getTypeface(){return null;}public Typeface setTypeface(Typeface t){return t;}"
                + "public float measureText(CharSequence s,int a,int b){return b-a;}public void setColor(int c){}public float ascent(){return -1;}public float descent(){return 1;}}"));
        sources.add(new Source("android.graphics.Canvas", "package android.graphics;public class Canvas{public void drawRoundRect(float a,float b,float c,float d,float e,float f,Paint p){}"
                + "public void drawText(CharSequence s,int a,int b,float x,float y,Paint p){}}"));
        sources.add(new Source("android.text.Spanned", "package android.text;public interface Spanned extends CharSequence{int SPAN_EXCLUSIVE_EXCLUSIVE=33;}"));
        sources.add(new Source("android.text.SpannableStringBuilder", "package android.text;import java.util.*;public class SpannableStringBuilder implements Spanned{"
                + "public List<Object[]> spans=new ArrayList<Object[]>();private StringBuilder text=new StringBuilder();public int length(){return text.length();}"
                + "public char charAt(int p){return text.charAt(p);}public CharSequence subSequence(int a,int b){return text.subSequence(a,b);}"
                + "public SpannableStringBuilder append(CharSequence s){text.append(s);return this;}public SpannableStringBuilder append(char c){text.append(c);return this;}"
                + "public void setSpan(Object span,int a,int b,int flags){spans.add(new Object[]{span,a,b,flags});}public SpannableStringBuilder delete(int a,int b){text.delete(a,b);return this;}"
                + "public String toString(){return text.toString();}}"));
        sources.add(new Source("android.text.style.ReplacementSpan", "package android.text.style;import android.graphics.*;public abstract class ReplacementSpan{"
                + "public abstract int getSize(Paint p,CharSequence s,int a,int b,Paint.FontMetricsInt fm);public abstract void draw(Canvas c,CharSequence s,int a,int b,float x,int t,int y,int z,Paint p);}"));
        for (String name : Arrays.asList("StyleSpan", "RelativeSizeSpan", "TypefaceSpan", "BackgroundColorSpan")) {
            String type = name.equals("TypefaceSpan") ? "String" : name.equals("RelativeSizeSpan") ? "float" : "int";
            sources.add(new Source("android.text.style." + name, "package android.text.style;public class " + name + "{public final " + type + " value;public " + name + "(" + type + " v){value=v;}}"));
        }
        try (StandardJavaFileManager manager = ToolProvider.getSystemJavaCompiler().getStandardFileManager(null, null, null)) {
            for (JavaFileObject file : manager.getJavaFileObjects(root.resolve("app/src/main/java/com/mkei/backcast/ui/Markdown.java").toFile())) sources.add(file);
            check(ToolProvider.getSystemJavaCompiler().getTask(null, manager, null,
                    Arrays.asList("-encoding", "UTF-8", "-source", "8", "-target", "8", "-Xlint:-options", "-d", build.toString()),
                    null, sources).call(), "Actual Markdown parser did not compile");
        }
    }
    private static void inlineCodePreservesLongPathsAndUnicode() throws Exception {
        String path = "/storage/emulated/0/wh/mcp/s0165/很长的目录🚀/进房.md";
        String identifier = "AccountJoinRandomFriendPreviousGameWithoutAnyWhitespace";
        CharSequence rendered = markdown("文件：`" + path + "`\n调用：`" + identifier + "`", 11);
        check(rendered.toString().equals("文件：" + path + "\n调用：" + identifier),
                "Inline code changed copy text or dropped the end of a path");
        check(span(rendered, "TypefaceSpan") && span(rendered, "BackgroundColorSpan")
                && !span(rendered, "CodeSpan"), "Inline code cannot wrap as native text");
        CharSequence table = markdown("| 名称 |\n| --- |\n| `" + path + "` |", 11);
        check(table.toString().equals("名称  " + path), "A table clipped a long inline path");
        pass("inlineCodePreservesLongPathsAndUnicode");
    }
    public static void main(String[] args) throws Exception {
        Path build = Files.createTempDirectory("backcast-markdown-test-");
        try {
            compileRenderer(Paths.get(args[0]), build);
            try (URLClassLoader loader = new URLClassLoader(new URL[]{build.toUri().toURL()}, null)) {
                render = loader.loadClass("com.mkei.backcast.ui.Markdown").getMethod("render", String.class, int.class);
                streamingFormatsBeforeTheReplyEndsOnAWorker();
                updatesCoalesceAndOnlyTheFinalVersionCanApply();
                sessionAndDestroyInvalidateAlreadyPostedResults();
                liveReplyTakesPriorityAndEachResultNeedsItsOwnFrame();
                renderFailureFallsBackAndDoesNotBlockOtherMessages();
                largeCodeAndMobileTablesPreserveReadableCopyText();
                inlineCodePreservesLongPathsAndUnicode();
            }
            System.out.println("Markdown rendering regressions: " + passed + " passed");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(build)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }
}
