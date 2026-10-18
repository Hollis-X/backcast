import com.mkei.backcast.agent.LineDelta;

public final class LineDeltaRegressionTest {
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void same(int[] delta, int added, int removed, String label) {
        check(delta[0] == added && delta[1] == removed, label + ": got +" + delta[0] + " -" + delta[1]);
    }
    public static void main(String[] args) {
        same(LineDelta.count("", "a\nb\nc\n"), 3, 0, "new file");
        same(LineDelta.count("a\nb\nc\n", ""), 0, 3, "emptied file");
        same(LineDelta.count("a\nb\nc\n", "a\nB\nc\n"), 1, 1, "one changed line");
        same(LineDelta.count("a\nb\n", "a\nb\nc\nd\n"), 2, 0, "appended lines");
        same(LineDelta.count("x\nx\nx\n", "x\n"), 0, 2, "duplicate lines removed");
        same(LineDelta.count("a\r\nb\r\n", "a\nb\n"), 0, 0, "line endings only");
        same(LineDelta.count("same", "same"), 0, 0, "no change");
        String result = "已修改 2 处：src/A.java\n" + LineDelta.mark(new int[] { 5, 3 });
        int[] parsed = LineDelta.parse(result);
        check(parsed != null && parsed[0] == 5 && parsed[1] == 3, "Mark did not round-trip");
        check(LineDelta.parse("exit=0\nhello") == null && LineDelta.parse(null) == null, "Text without a mark was parsed");
        check(LineDelta.parse("变更：+1 -2 trailing") == null, "A mark inside other text was parsed");
        check("已修改 2 处：src/A.java".equals(LineDelta.strip(result)), "Strip left the mark: " + LineDelta.strip(result));
        check("exit=0".equals(LineDelta.strip("exit=0")), "Strip changed plain text");
        System.out.println("PASS line delta counting and marks");
    }
}
