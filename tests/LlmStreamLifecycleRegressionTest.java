import com.mkei.backcast.agent.LlmClient;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Stream ownership and SSE termination through the production parser. */
public final class LlmStreamLifecycleRegressionTest {
    private static final class Tracked extends InputStream {
        final byte[] body;
        int position, reads;
        boolean closed, failRead, failClose, failAtEof;
        Tracked(String body) throws Exception { this.body = body.getBytes("UTF-8"); }
        @Override public int read() throws IOException {
            byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public int read(byte[] buffer, int offset, int size) throws IOException {
            reads++;
            if (failRead || (failAtEof && position == body.length)) throw new IOException("fixture read failure");
            if (position == body.length) return -1;
            int count = 0;
            while (count < size && position < body.length) {
                byte value = body[position++]; buffer[offset + count++] = value;
                if (value == '\n') break;
            }
            return count;
        }
        @Override public void close() throws IOException {
            closed = true; if (failClose) throw new IOException("fixture close failure");
        }
    }
    private static final String SSE = "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"}}]}\n\n"
            + "data: [DONE]\n";
    private static Object attempt(boolean cancelled) throws Exception {
        Class<?> type = Class.forName("com.mkei.backcast.agent.LlmClient$Attempt");
        Constructor<?> ctor = type.getDeclaredConstructor(); ctor.setAccessible(true); Object value = ctor.newInstance();
        java.lang.reflect.Field dead = type.getDeclaredField("dead"); dead.setAccessible(true); dead.setBoolean(value, cancelled);
        return value;
    }
    private static LlmClient.Reply stream(Tracked input, boolean cancelled) throws Exception {
        Object attempt = attempt(cancelled);
        Method read = LlmClient.class.getDeclaredMethod("readStream", InputStream.class, LlmClient.Reply.class,
                attempt.getClass(), LlmClient.Sink.class, long.class);
        read.setAccessible(true); LlmClient.Reply reply = new LlmClient.Reply();
        read.invoke(null, input, reply, attempt, null, 1000L); return reply;
    }
    private static String all(Tracked input) throws Exception {
        Method read = LlmClient.class.getDeclaredMethod("readAll", InputStream.class); read.setAccessible(true);
        return (String) read.invoke(null, input);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void sseDoneClosesWithoutDrainingOpenStream() throws Exception {
        Tracked input = new Tracked(SSE); input.failAtEof = true;
        check("answer".equals(stream(input, false).content) && input.closed, "SSE DONE did not close cleanly");
    }
    private static void firstDoneDoesNotWaitForAnotherNetworkRead() throws Exception {
        Tracked input = new Tracked("data: [DONE]\n"); input.failAtEof = true;
        check(stream(input, false).error == null && input.closed && input.reads == 1, "First DONE kept reading the connection");
    }
    private static void malformedSseStillClosesStream() throws Exception {
        Tracked input = new Tracked("data: invalid-json\n"); boolean failed = false;
        try { stream(input, false); } catch (InvocationTargetException expected) { failed = true; }
        check(failed && input.closed, "Parsing error leaked response stream");
    }
    private static void networkReadFailureStillClosesStream() throws Exception {
        Tracked input = new Tracked(""); input.failRead = true; boolean failed = false;
        try { stream(input, false); } catch (InvocationTargetException expected) { failed = expected.getCause() instanceof IOException; }
        check(failed && input.closed, "Read failure leaked response stream");
    }
    private static void ordinaryBodyReadsToEofAndCloses() throws Exception {
        Tracked input = new Tracked("response\n"); check("response\n".equals(all(input)) && input.closed, "Ordinary body was not closed after EOF");
    }
    private static void ordinaryBodyFailureClosesAndPreservesCause() throws Exception {
        Tracked input = new Tracked(""); input.failRead = true; input.failClose = true; boolean failed = false;
        try { all(input); } catch (InvocationTargetException expected) { failed = "fixture read failure".equals(expected.getCause().getMessage()); }
        check(failed && input.closed, "Error body cleanup leaked or masked read failure");
    }
    private static void closeFailureDoesNotDiscardCompleteReply() throws Exception {
        Tracked input = new Tracked(SSE); input.failClose = true;
        check("answer".equals(stream(input, false).content) && input.closed, "Close failure discarded received content");
    }
    private static void cancelledAttemptClosesWithoutReading() throws Exception {
        Tracked input = new Tracked(SSE);
        check(stream(input, true).content.length() == 0 && input.closed && input.reads == 0, "Cancelled parser read or leaked response");
    }
    public static void main(String[] args) throws Exception {
        String[] cases = {"sseDoneClosesWithoutDrainingOpenStream", "firstDoneDoesNotWaitForAnotherNetworkRead",
                "malformedSseStillClosesStream", "networkReadFailureStillClosesStream", "ordinaryBodyReadsToEofAndCloses",
                "ordinaryBodyFailureClosesAndPreservesCause", "closeFailureDoesNotDiscardCompleteReply", "cancelledAttemptClosesWithoutReading"};
        for (String name : cases) { LlmStreamLifecycleRegressionTest.class.getDeclaredMethod(name).invoke(null); System.out.println("PASS " + name); }
        System.out.println(cases.length + " stream lifecycle tests passed");
    }
}
