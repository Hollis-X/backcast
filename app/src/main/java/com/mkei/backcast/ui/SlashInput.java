package com.mkei.backcast.ui;

import android.content.Context;
import android.text.Editable;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;

/**
 * 输入框。指令名是一块，不按字母拆。
 *
 * 没有后文的指令，退格或继续打字都会整块清掉。
 * 后面还能跟需求的指令，块只盖住指令名，需求留在块后面。
 * 没按发送，它就只是一个还没提交的输入。
 */
public class SlashInput extends EditText {

    /** 块的底色。浅灰，和输入栏里的其它元素同一档。 */
    private static final int CHIP_BG = 0xFFEFEFF2;

    private boolean chipped;
    /** 块结束的位置。后面的字不进块。-1 表示当前没有块。 */
    private int chipEnd = -1;
    /** 为真时块后面可以继续写。压缩这种不带参数的指令为假。 */
    private boolean chipKeepsTail;

    public SlashInput(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * 文本被清空时顺手复位整块状态。
     *
     * 不然发送完再打新字，旧的块标记还在，第一个字会被当成「替换整块」清掉。
     */
    @Override
    public void setText(CharSequence text, android.widget.TextView.BufferType type) {
        // 先复位：super.setText 会清掉旧的 span，并在内部触发 TextWatcher，
        // 那个回调用 setChipped 重新铺底色，所以标记必须在调用前就归零。
        chipped = false;
        chipEnd = -1;
        chipKeepsTail = false;
        super.setText(text, type);
    }

    /** 把当前内容标成一整块。keepTail 为真时，后面打出来的字留在块后面。 */
    public void setChipped(boolean value) {
        setChipped(value, false);
    }

    public void setChipped(boolean value, boolean keepTail) {
        if (!value) {
            if (chipEnd < 0) {
                return;
            }
            chipEnd = -1;
            chipped = false;
            chipKeepsTail = false;
            clearChipSpans();
            return;
        }
        int end = length();
        if (end <= 0) {
            setChipped(false);
            return;
        }
        if (chipped && chipEnd == end && chipKeepsTail == keepTail) {
            return;
        }
        chipKeepsTail = keepTail;
        chipEnd = end;
        chipped = true;
        paintChip();
    }

    /** 只把开头的指令名留成块，后面已经打出来的需求不动。 */
    public void holdChip(int end) {
        int len = length();
        if (end <= 0 || end > len) {
            setChipped(false);
            return;
        }
        if (chipped && chipKeepsTail && chipEnd == end) {
            return;
        }
        chipKeepsTail = true;
        chipEnd = end;
        chipped = true;
        paintChip();
    }

    private void paintChip() {
        CharSequence text = getText();
        if (!(text instanceof Spannable) || chipEnd <= 0 || chipEnd > text.length()) {
            return;
        }
        clearChipSpans();
        ((Spannable) text).setSpan(new ChipSpan(), 0, chipEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private void clearChipSpans() {
        CharSequence text = getText();
        if (!(text instanceof Spannable)) {
            return;
        }
        Spannable sp = (Spannable) text;
        ChipSpan[] old = sp.getSpans(0, sp.length(), ChipSpan.class);
        for (int i = 0; i < old.length; i++) {
            sp.removeSpan(old[i]);
        }
    }

    /** 光标若停在块里面，打字前挪到整段末尾，避免把指令名拆开。 */
    private void placeCaretAfterChip() {
        int len = length();
        int start = getSelectionStart();
        int end = getSelectionEnd();
        if (start < 0 || end < 0) {
            setSelection(len);
            return;
        }
        if (start > end) {
            int swap = start;
            start = end;
            end = swap;
        }
        if (start < chipEnd) {
            setSelection(len);
        }
    }

    /** 退格打进指令名时整块删掉。后面还有需求就只删指令名。 */
    private boolean onDelete(int before) {
        if (chipEnd <= 0 || before <= 0) {
            return false;
        }
        int start = getSelectionStart();
        int end = getSelectionEnd();
        int len = length();
        if (start < 0 || end < 0) {
            return eatWhole();
        }
        if (start > end) {
            int swap = start;
            start = end;
            end = swap;
        }
        if (!chipKeepsTail || len <= chipEnd || (start == 0 && end >= len)) {
            return eatWhole();
        }
        if (start < chipEnd) {
            return start == end ? eatChipKeepTail() : eatWhole();
        }
        if (start == end && start - before < chipEnd) {
            return eatChipKeepTail();
        }
        return false;
    }

    private boolean eatWhole() {
        if (chipEnd <= 0) {
            return false;
        }
        chipEnd = -1;
        chipped = false;
        chipKeepsTail = false;
        setText("");
        return true;
    }

    private boolean eatChipKeepTail() {
        Editable text = getText();
        if (text == null || chipEnd <= 0 || chipEnd > text.length()) {
            return eatWhole();
        }
        int cut = chipEnd;
        if (cut < text.length() && text.charAt(cut) == ' ') {
            cut++;
        }
        String tail = text.subSequence(cut, text.length()).toString();
        chipEnd = -1;
        chipped = false;
        chipKeepsTail = false;
        setText(tail);
        setSelection(0);
        return true;
    }

    /** 继续打字。不带参数的指令整块换掉；带需求的指令把字接到块后面。 */
    private CharSequence textAfterChip(CharSequence text) {
        if (chipEnd <= 0 || text == null || text.length() == 0) {
            return text;
        }
        if (!chipKeepsTail) {
            eatWhole();
            return text;
        }
        placeCaretAfterChip();
        if (length() == chipEnd && !Character.isWhitespace(text.charAt(0))) {
            return " " + text;
        }
        return text;
    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_DEL
                && event.getAction() == KeyEvent.ACTION_DOWN
                && onDelete(1)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * 输入法的退格大多不走按键，而是走连接的 deleteSurroundingText，
     * 所以这里包一层，把整块的删除拦下来。
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        final InputConnection base = super.onCreateInputConnection(outAttrs);
        if (base == null) {
            return null;
        }
        return new InputConnectionWrapper(base, true) {
            @Override
            public boolean deleteSurroundingText(int before, int after) {
                if (onDelete(before)) {
                    return true;
                }
                return super.deleteSurroundingText(before, after);
            }

            @Override
            public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                // 部分输入法走它删字，
                // 不拦的话整块会被拆成一个个字符删掉。
                if (onDelete(before)) {
                    return true;
                }
                return super.deleteSurroundingTextInCodePoints(before, after);
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_DEL
                        && event.getAction() == KeyEvent.ACTION_DOWN
                        && onDelete(1)) {
                    return true;
                }
                return super.sendKeyEvent(event);
            }

            @Override
            public boolean commitText(CharSequence text, int newCursorPosition) {
                CharSequence next = textAfterChip(text);
                boolean ok = super.commitText(next, newCursorPosition);
                if (chipEnd > 0) {
                    if (chipEnd > length()) {
                        setChipped(false);
                    } else {
                        paintChip();
                    }
                }
                return ok;
            }

            @Override
            public boolean setComposingText(CharSequence text, int newCursorPosition) {
                // 中文输入法先走组字，不拦的话组字会把指令名整段换掉。
                if (chipEnd > 0 && text != null && text.length() > 0 && !chipKeepsTail) {
                    eatWhole();
                } else if (chipEnd > 0 && text != null && text.length() > 0) {
                    placeCaretAfterChip();
                    if (length() == chipEnd && !Character.isWhitespace(text.charAt(0))) {
                        super.commitText(" ", 1);
                    }
                }
                return super.setComposingText(text, newCursorPosition);
            }
        };
    }

    /**
     * 整段文字铺一层灰底。
     *
     * 用 CharacterStyle 而不是 ReplacementSpan：后者会替换掉文字的测量，
     * 在可编辑的输入框里遇到光标移动和输入法组合文字会出错。
     */
    private static final class ChipSpan extends CharacterStyle {

        @Override
        public void updateDrawState(TextPaint tp) {
            tp.bgColor = CHIP_BG;
        }
    }
}
