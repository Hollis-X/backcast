package com.mkei.backcast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.app.Dialog;
import com.mkei.backcast.agent.QuestionRequest;
import com.mkei.backcast.ui.QuestionDialog;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class QuestionDialogTest {
    private static QuestionRequest sample() throws Exception {
        JSONArray questions = new JSONArray()
                .put(new JSONObject().put("question", "先改哪一块？").put("header", "范围").put("multiSelect", false)
                        .put("options", new JSONArray()
                                .put(new JSONObject().put("label", "先改工具层（推荐）").put("description", "改动小，容易回退"))
                                .put(new JSONObject().put("label", "先改界面").put("description", "能马上看到效果"))))
                .put(new JSONObject().put("question", "要补哪些测试？").put("header", "测试").put("multiSelect", true)
                        .put("options", new JSONArray()
                                .put(new JSONObject().put("label", "单元测试").put("description", "快"))
                                .put(new JSONObject().put("label", "界面测试").put("description", "覆盖更全"))
                                .put(new JSONObject().put("label", "回归测试").put("description", "")))); 
        return new QuestionRequest(questions);
    }

    private static Activity open() {
        Activity activity = Robolectric.buildActivity(McpConfigActivity.class,
                new Intent(RuntimeEnvironment.getApplication(), McpConfigActivity.class)).setup().get();
        ShadowLooper.idleMainLooper();
        return activity;
    }

    private static List<CompoundButton> buttons(View root) {
        List<CompoundButton> found = new ArrayList<CompoundButton>();
        if (root instanceof CompoundButton) found.add((CompoundButton) root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) found.addAll(buttons(group.getChildAt(i)));
        }
        return found;
    }

    private static List<EditText> inputs(View root) {
        List<EditText> found = new ArrayList<EditText>();
        if (root instanceof EditText) found.add((EditText) root);
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) found.addAll(inputs(group.getChildAt(i)));
        }
        return found;
    }

    private static void tap(CompoundButton button) { ((View) button.getParent()).performClick(); }

    private static View tagged(Dialog dialog, String tag) {
        java.util.ArrayList<View> found = new java.util.ArrayList<View>();
        dialog.getWindow().getDecorView().findViewsWithText(found, tag, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        return found.get(0);
    }

    @Test
    public void answersNeedEveryQuestionAndComeBackAsLabels() throws Exception {
        QuestionRequest request = sample();
        QuestionDialog.show(open(), request); ShadowLooper.idleMainLooper();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        View root = dialog.getWindow().getDecorView();
        List<CompoundButton> options = buttons(root);
        assertEquals(5, options.size());
        assertEquals("1/2", ((android.widget.TextView) ((ViewGroup) tagged(dialog, QuestionDialog.PREV).getParent()).getChildAt(2)).getText().toString());
        assertFalse("OK must wait for every answer", tagged(dialog, QuestionDialog.OK).isEnabled());
        tap(options.get(1));                       // single choice: second option
        tap(options.get(0));                       // switching replaces the first choice
        assertTrue(options.get(0).isChecked() && !options.get(1).isChecked());
        assertFalse(tagged(dialog, QuestionDialog.OK).isEnabled());
        tap(options.get(2)); tap(options.get(4));  // multi choice: two of three
        assertTrue(tagged(dialog, QuestionDialog.OK).isEnabled());
        tagged(dialog, QuestionDialog.OK).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(request.finished());
        JSONObject answers = request.await();
        assertEquals("先改工具层（推荐）", answers.getString("先改哪一块？"));
        assertEquals("单元测试、回归测试", answers.getString("要补哪些测试？"));
        assertFalse(dialog.isShowing());
    }

    @Test
    public void typedAnswerReplacesTheSingleChoiceAndSkipCancels() throws Exception {
        QuestionRequest request = sample();
        QuestionDialog.show(open(), request); ShadowLooper.idleMainLooper();
        Dialog dialog = ShadowDialog.getLatestDialog();
        View root = dialog.getWindow().getDecorView();
        List<CompoundButton> options = buttons(root);
        List<EditText> typed = inputs(root);
        tap(options.get(0));
        typed.get(0).setText("两边一起改");
        assertFalse("typing replaces the chosen option", options.get(0).isChecked());
        typed.get(1).setText("只要冒烟测试");
        assertTrue(tagged(dialog, QuestionDialog.OK).isEnabled());
        tagged(dialog, QuestionDialog.OK).performClick();
        JSONObject answers = request.await();
        assertEquals("两边一起改", answers.getString("先改哪一块？"));
        assertEquals("只要冒烟测试", answers.getString("要补哪些测试？"));

        QuestionRequest skipped = sample();
        QuestionDialog.show(open(), skipped); ShadowLooper.idleMainLooper();
        Dialog second = ShadowDialog.getLatestDialog();
        tagged(second, QuestionDialog.SKIP).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(skipped.cancelled());
        assertNull(skipped.await());
        assertFalse(second.isShowing());
    }

    @Test
    public void cancellingTheRequestClosesTheDialog() throws Exception {
        QuestionRequest request = sample();
        QuestionDialog.show(open(), request); ShadowLooper.idleMainLooper();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        request.cancel();           // what the stop button does to a waiting tool
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
    }

    /** Renders the dialog for visual review when BACKCAST_SCREENSHOTS names a directory. */
    @Test
    public void rendersForReview() throws Exception {
        String dir = System.getenv("BACKCAST_SCREENSHOTS");
        if (dir == null || dir.length() == 0) return;
        QuestionRequest request = sample();
        QuestionDialog.show(open(), request); ShadowLooper.idleMainLooper();
        Dialog dialog = ShadowDialog.getLatestDialog();
        List<CompoundButton> options = buttons(dialog.getWindow().getDecorView());
        tap(options.get(0)); tap(options.get(3));
        ShadowLooper.idleMainLooper();
        tagged(dialog, QuestionDialog.PREV).performClick();
        ShadowLooper.idleMainLooper();
        View decor = dialog.getWindow().getDecorView();
        int width = Math.round(380 * RuntimeEnvironment.getApplication().getResources().getDisplayMetrics().density);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(decor.getMeasuredWidth(), decor.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        decor.draw(canvas);
        File out = new File(dir);
        out.mkdirs();
        try (FileOutputStream stream = new FileOutputStream(new File(out, "16-question-dialog.png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
    }
}
