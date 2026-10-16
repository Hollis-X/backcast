package com.mkei.backcast.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;
import com.mkei.backcast.R;
import com.mkei.backcast.agent.QuestionRequest;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 模型提的选择题，用和「用量」「工作文件夹」一样的底部面板展示。
 * 多道题时一次只显示一题：左右滑动或点「‹ 1/3 ›」切换；单选题选完自动翻到下一题。
 * 每题几个选项加一个自己写的输入框，全部答完才能确定。
 */
public final class QuestionDialog {
    public static final String OK = "question_ok", SKIP = "question_skip", PREV = "question_prev", NEXT = "question_next";

    private QuestionDialog() { }

    private static final class Ask {
        String question;
        boolean multi;
        final List<String> labels = new ArrayList<String>();
        final List<CompoundButton> buttons = new ArrayList<CompoundButton>();
        EditText custom;
        View page;

        boolean answered() {
            if (custom.getText().toString().trim().length() > 0) return true;
            for (CompoundButton button : buttons) if (button.isChecked()) return true;
            return false;
        }

        /** 自己写的内容优先；多选时选项和自己写的合在一起。 */
        String answer() {
            String typed = custom.getText().toString().trim();
            StringBuilder picked = new StringBuilder();
            for (int i = 0; i < buttons.size(); i++) if (buttons.get(i).isChecked())
                picked.append(picked.length() == 0 ? "" : "、").append(labels.get(i));
            if (typed.length() == 0) return picked.toString();
            return multi && picked.length() > 0 ? picked + "、" + typed : typed;
        }
    }

    /** ViewPager 默认不随内容定高；这里取所有题里最高的一页，切换时面板不跳动。 */
    private static final class WrapPager extends ViewPager {
        WrapPager(Activity activity) { super(activity); }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int tallest = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                tallest = Math.max(tallest, child.getMeasuredHeight());
            }
            super.onMeasure(widthSpec, tallest > 0 ? MeasureSpec.makeMeasureSpec(tallest, MeasureSpec.EXACTLY) : heightSpec);
        }
    }

    public static void show(final Activity activity, final QuestionRequest request) {
        final float density = activity.getResources().getDisplayMetrics().density;
        final int accent = activity.getResources().getColor(R.color.accent);
        final int primary = activity.getResources().getColor(R.color.text_primary);
        final int secondary = activity.getResources().getColor(R.color.text_secondary);
        final List<Ask> asks = new ArrayList<Ask>();
        final Runnable[] refresh = new Runnable[1];
        final ViewPager[] pagerRef = new ViewPager[1];
        try {
            JSONArray questions = request.questions;
            for (int i = 0; i < questions.length(); i++) {
                JSONObject item = questions.getJSONObject(i);
                final Ask ask = new Ask();
                ask.question = item.getString("question"); ask.multi = item.optBoolean("multiSelect", false);
                LinearLayout page = new LinearLayout(activity);
                page.setOrientation(LinearLayout.VERTICAL);
                page.setPadding(dp(density, 2), 0, dp(density, 2), 0);
                String header = item.optString("header", "");
                if (header.length() > 0) {
                    TextView chip = text(activity, header + (ask.multi ? " · 可多选" : ""), 12, secondary);
                    chip.setPadding(0, 0, 0, dp(density, 2));
                    page.addView(chip);
                } else if (ask.multi) {
                    page.addView(text(activity, "可多选", 12, secondary));
                }
                TextView title = text(activity, ask.question, 17, primary);
                title.setPadding(0, dp(density, 2), 0, dp(density, 8));
                page.addView(title);
                JSONArray options = item.getJSONArray("options");
                for (int j = 0; j < options.length(); j++) {
                    JSONObject option = options.getJSONObject(j);
                    final CompoundButton button = ask.multi ? new CheckBox(activity) : new RadioButton(activity);
                    button.setButtonTintList(ColorStateList.valueOf(accent));
                    button.setClickable(false);
                    ask.labels.add(option.getString("label")); ask.buttons.add(button);
                    LinearLayout label = new LinearLayout(activity);
                    label.setOrientation(LinearLayout.VERTICAL);
                    label.addView(text(activity, option.getString("label"), 15, primary));
                    String detail = option.optString("description", "");
                    if (detail.length() > 0) label.addView(text(activity, detail, 13, secondary));
                    final LinearLayout row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(density, 6), dp(density, 8), dp(density, 12), dp(density, 8));
                    row.setBackground(choiceBackground(density, false));
                    row.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            if (ask.multi) button.setChecked(!button.isChecked());
                            else { for (CompoundButton other : ask.buttons) other.setChecked(other == button); ask.custom.setText(""); }
                            refresh[0].run();
                            // 单选题选完就翻到下一题，像 Claude 的提问一样一路点下去。
                            if (!ask.multi && pagerRef[0] != null) {
                                final int index = asks.indexOf(ask);
                                if (index >= 0 && index < asks.size() - 1) pagerRef[0].postDelayed(new Runnable() {
                                    @Override public void run() {
                                        if (pagerRef[0].getCurrentItem() == index) pagerRef[0].setCurrentItem(index + 1, true);
                                    }
                                }, 220);
                            }
                        }
                    });
                    LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowLp.topMargin = dp(density, 6);
                    page.addView(row, rowLp);
                }
                ask.custom = new EditText(activity);
                ask.custom.setHint("其他（直接写你的答案）"); ask.custom.setTextSize(14); ask.custom.setSingleLine(false); ask.custom.setMaxLines(3);
                ask.custom.setBackgroundResource(R.drawable.bg_input);
                ask.custom.setPadding(dp(density, 14), dp(density, 10), dp(density, 14), dp(density, 10));
                ask.custom.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (!ask.multi && s.length() > 0) for (CompoundButton other : ask.buttons) other.setChecked(false);
                    }
                    @Override public void afterTextChanged(Editable s) { refresh[0].run(); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(density, 10);
                page.addView(ask.custom, lp);
                ask.page = page;
                asks.add(ask);
            }
            if (asks.isEmpty()) throw new IllegalArgumentException("no questions");
        } catch (Exception malformed) { request.cancel(); return; }

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundResource(R.drawable.bg_sheet);
        sheet.setPadding(dp(density, 20), 0, dp(density, 20), dp(density, 20));
        View handle = new View(activity);
        handle.setBackgroundResource(R.drawable.bg_sheet_handle);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(density, 36), dp(density, 4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.topMargin = dp(density, 10); handleLp.bottomMargin = dp(density, 14);
        sheet.addView(handle, handleLp);

        // 多题时顶部一行「‹ 1/3 ›」，两边按钮平滑切换。
        final TextView counter = text(activity, "", 14, secondary);
        final TextView prev = navButton(activity, density, "‹", PREV);
        final TextView next = navButton(activity, density, "›", NEXT);
        if (asks.size() > 1) {
            LinearLayout nav = new LinearLayout(activity);
            nav.setOrientation(LinearLayout.HORIZONTAL);
            nav.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            TextView label = text(activity, "需要你选择", 14, secondary);
            nav.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            nav.addView(prev);
            counter.setGravity(Gravity.CENTER);
            nav.addView(counter, new LinearLayout.LayoutParams(dp(density, 44), ViewGroup.LayoutParams.WRAP_CONTENT));
            nav.addView(next);
            LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            navLp.bottomMargin = dp(density, 6);
            sheet.addView(nav, navLp);
        }

        final WrapPager pager = new WrapPager(activity);
        pager.setId(View.generateViewId());
        pager.setOffscreenPageLimit(Math.max(1, asks.size() - 1));
        pager.setAdapter(new PagerAdapter() {
            @Override public int getCount() { return asks.size(); }
            @Override public boolean isViewFromObject(View view, Object object) { return view == object; }
            @Override public Object instantiateItem(ViewGroup container, int position) {
                View page = asks.get(position).page;
                if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
                container.addView(page);
                return page;
            }
            @Override public void destroyItem(ViewGroup container, int position, Object object) { container.removeView((View) object); }
        });
        pagerRef[0] = pager;
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(pager, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // 题目再长也不顶满屏幕，留出上面的会话。
        int maxHeight = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.6f);
        sheet.addView(new MaxHeightFrame(activity, maxHeight, scroll), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(density, 16), 0, 0);
        final TextView skip = pill(activity, density, "跳过", false, SKIP);
        final TextView ok = pill(activity, density, "确定", true, OK);
        actions.addView(skip, new LinearLayout.LayoutParams(0, dp(density, 46), 1f));
        View gap = new View(activity);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(density, 10), 1));
        actions.addView(ok, new LinearLayout.LayoutParams(0, dp(density, 46), 1f));
        sheet.addView(actions);

        // 焦点先放在面板上，打开时不让「其他」输入框抢焦点弹出键盘。
        sheet.setFocusableInTouchMode(true);
        sheet.requestFocus();
        dialog.setContentView(sheet);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setWindowAnimations(android.R.style.Animation_InputMethod);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> request.cancel());
        dialog.setOnDismissListener(d -> request.cancel());

        refresh[0] = new Runnable() {
            @Override public void run() {
                boolean all = true;
                for (Ask ask : asks) {
                    all &= ask.answered();
                    for (CompoundButton button : ask.buttons)
                        ((View) button.getParent()).setBackground(choiceBackground(density, button.isChecked()));
                }
                ok.setEnabled(all);
                ok.setAlpha(all ? 1f : 0.4f);
                int at = pager.getCurrentItem();
                counter.setText((at + 1) + "/" + asks.size());
                prev.setEnabled(at > 0); prev.setAlpha(at > 0 ? 1f : 0.3f);
                next.setEnabled(at < asks.size() - 1); next.setAlpha(at < asks.size() - 1 ? 1f : 0.3f);
            }
        };
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override public void onPageSelected(int position) { refresh[0].run(); }
        });
        prev.setOnClickListener(v -> pager.setCurrentItem(pager.getCurrentItem() - 1, true));
        next.setOnClickListener(v -> pager.setCurrentItem(pager.getCurrentItem() + 1, true));
        skip.setOnClickListener(v -> { request.cancel(); dialog.dismiss(); });
        ok.setOnClickListener(v -> {
            JSONObject answers = new JSONObject();
            try { for (Ask ask : asks) answers.put(ask.question, ask.answer()); }
            catch (Exception invalid) { request.cancel(); return; }
            request.answer(answers);
            dialog.dismiss();
        });
        request.setOnCancel(() -> activity.runOnUiThread(() -> { if (dialog.isShowing()) dialog.dismiss(); }));
        try { dialog.show(); } catch (RuntimeException unavailable) { request.cancel(); return; }
        refresh[0].run();
    }

    /** 外层限制最大高度，超出部分在里面滚动。 */
    private static final class MaxHeightFrame extends android.widget.FrameLayout {
        private final int max;
        MaxHeightFrame(Activity activity, int max, View child) {
            super(activity);
            this.max = max;
            addView(child, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }

    private static GradientDrawable choiceBackground(float density, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFFE3ECFD : 0xFFF5F5F7);
        bg.setCornerRadius(12 * density);
        return bg;
    }

    private static TextView navButton(Activity activity, float density, String glyph, String tag) {
        TextView button = text(activity, glyph, 22, activity.getResources().getColor(R.color.text_primary));
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(tag);
        button.setMinWidth(dp(density, 40)); button.setMinHeight(dp(density, 40));
        return button;
    }

    private static TextView pill(Activity activity, float density, String label, boolean primary, String tag) {
        TextView button = text(activity, label, 16, primary ? Color.WHITE : activity.getResources().getColor(R.color.text_primary));
        button.setGravity(Gravity.CENTER);
        button.setContentDescription(tag);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? activity.getResources().getColor(R.color.accent) : 0xFFF0F0F3);
        bg.setCornerRadius(23 * density);
        button.setBackground(bg);
        return button;
    }

    private static int dp(float density, int value) { return Math.round(value * density); }

    private static TextView text(Activity activity, String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(3 * activity.getResources().getDisplayMetrics().density, 1f);
        return view;
    }
}
