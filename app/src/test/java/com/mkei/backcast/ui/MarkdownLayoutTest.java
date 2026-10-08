package com.mkei.backcast.ui;

import android.text.Layout;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Android's native text layout must wrap code without rewriting the copied text. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MarkdownLayoutTest {
    private void wrapped(String source, String expected, int width, float size) {
        CharSequence text = Markdown.render(source, 0xFFEFEFF1);
        assertEquals(expected, text.toString());
        assertEquals(0, ((Spanned) text).getSpans(0, text.length(), ReplacementSpan.class).length);
        TextPaint paint = new TextPaint();
        paint.setTextSize(size);
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE).build();
        assertTrue("Long code stayed on a single clipped line", layout.getLineCount() > 1);
        int next = 0;
        for (int line = 0; line < layout.getLineCount(); line++) {
            assertEquals(next, layout.getLineStart(line));
            next = layout.getLineEnd(line);
            assertTrue("A line extends beyond the viewport", layout.getLineWidth(line) <= width + 1f);
            if (next < text.length() && next > 0) {
                assertFalse("Layout split a Unicode surrogate pair", Character.isHighSurrogate(text.charAt(next - 1))
                        && Character.isLowSurrogate(text.charAt(next)));
            }
        }
        assertEquals("The end of the message was clipped", text.length(), next);
    }

    @Test public void longPathsWrapAtNarrowWidthsAndLargeFont() {
        String path = "/storage/emulated/0/wh/mcp/s0165/很长的目录🚀/进房.md";
        for (int width : new int[]{180, 280, 360})
            for (float font : new float[]{18f, 32f}) wrapped("文档：`" + path + "`", "文档：" + path, width, font);
    }

    @Test public void unbrokenIdentifiersAndMobileTablesKeepEveryCharacter() {
        String symbol = "AccountJoinRandomFriendPreviousGameWithoutAnyWhitespace";
        wrapped("`" + symbol + "`", symbol, 180, 28f);
        wrapped("| 名称 |\n| --- |\n| `" + symbol + "` |", "名称  " + symbol, 200, 28f);
    }

    @Test public void streamedFenceAndInlineCodeUseTheSameBreakableLayout() {
        String path = "/storage/emulated/0/wh/mcp/s0165/a_long_unbroken_file_name.md";
        wrapped("```\n" + path, path, 180, 24f);
        wrapped("```\n" + path + "\n```", path, 180, 24f);
        wrapped("`" + path + "`", path, 180, 24f);
    }
}
