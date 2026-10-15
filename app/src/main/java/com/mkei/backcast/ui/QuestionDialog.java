package com.mkei.backcast.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.mkei.backcast.R;
import com.mkei.backcast.agent.QuestionRequest;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** 模型提的选择题：每题几个选项加一个自己写的输入框，全部答完才能确定。 */
public final class QuestionDialog {
    private QuestionDialog() { }

    private static final class Ask {
        String question;
        boolean multi;
        final List<String> labels = new ArrayList<String>();
        final List<CompoundButton> buttons = new ArrayList<CompoundButton>();
        EditText custom;

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

    public static void show(final Activity activity, final QuestionRequest request) {
        final float density = activity.getResources().getDisplayMetrics().density;
        final int accent = activity.getResources().getColor(R.color.accent);
        final List<Ask> asks = new ArrayList<Ask>();
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Math.round(22 * density), Math.round(8 * density), Math.round(22 * density), Math.round(4 * density));
        final AlertDialog[] dialog = new AlertDialog[1];
        final Runnable[] refresh = new Runnable[1];
        try {
            JSONArray questions = request.questions;
            for (int i = 0; i < questions.length(); i++) {
                JSONObject item = questions.getJSONObject(i);
                final Ask ask = new Ask();
                ask.question = item.getString("question"); ask.multi = item.optBoolean("multiSelect", false);
                String header = item.optString("header", "");
                if (header.length() > 0) {
                    TextView chip = text(activity, header, 12, activity.getResources().getColor(R.color.text_secondary));
                    chip.setPadding(0, Math.round((i == 0 ? 0 : 18) * density), 0, 0);
                    body.addView(chip);
                }
                TextView title = text(activity, ask.question, 16, activity.getResources().getColor(R.color.text_primary));
                title.setPadding(0, Math.round(4 * density), 0, Math.round(6 * density));
                body.addView(title);
                JSONArray options = item.getJSONArray("options");
                for (int j = 0; j < options.length(); j++) {
                    JSONObject option = options.getJSONObject(j);
                    final CompoundButton button = ask.multi ? new CheckBox(activity) : new RadioButton(activity);
                    button.setButtonTintList(ColorStateList.valueOf(accent));
                    ask.labels.add(option.getString("label")); ask.buttons.add(button);
                    LinearLayout text = new LinearLayout(activity);
                    text.setOrientation(LinearLayout.VERTICAL);
                    text.addView(text(activity, option.getString("label"), 15, activity.getResources().getColor(R.color.text_primary)));
                    String detail = option.optString("description", "");
                    if (detail.length() > 0) text.addView(text(activity, detail, 13, activity.getResources().getColor(R.color.text_secondary)));
                    LinearLayout row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setPadding(0, Math.round(6 * density), 0, Math.round(6 * density));
                    row.addView(button, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    button.setClickable(false);
                    row.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View view) {
                            if (ask.multi) button.setChecked(!button.isChecked());
                            else { for (CompoundButton other : ask.buttons) other.setChecked(other == button); ask.custom.setText(""); }
                            refresh[0].run();
                        }
                    });
                    body.addView(row);
                }
                ask.custom = new EditText(activity);
                ask.custom.setHint("其他（直接写你的答案）"); ask.custom.setTextSize(14); ask.custom.setSingleLine(false); ask.custom.setMaxLines(3);
                ask.custom.setBackgroundResource(R.drawable.bg_input);
                ask.custom.setPadding(Math.round(14 * density), Math.round(10 * density), Math.round(14 * density), Math.round(10 * density));
                ask.custom.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                        if (!ask.multi && s.length() > 0) for (CompoundButton other : ask.buttons) other.setChecked(false);
                    }
                    @Override public void afterTextChanged(Editable s) { refresh[0].run(); }
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = Math.round(6 * density);
                body.addView(ask.custom, lp);
                asks.add(ask);
            }
        } catch (Exception malformed) { request.cancel(); return; }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        dialog[0] = new AlertDialog.Builder(activity).setView(scroll)
                .setPositiveButton("确定", null).setNegativeButton("跳过", null).create();
        dialog[0].setCanceledOnTouchOutside(false);
        dialog[0].setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
            @Override public void onCancel(android.content.DialogInterface d) { request.cancel(); }
        });
        dialog[0].setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) { request.cancel(); }
        });
        refresh[0] = new Runnable() {
            @Override public void run() {
                boolean all = true;
                for (Ask ask : asks) all &= ask.answered();
                android.widget.Button ok = dialog[0].getButton(AlertDialog.BUTTON_POSITIVE);
                if (ok != null) ok.setEnabled(all);
            }
        };
        request.setOnCancel(new Runnable() {
            @Override public void run() {
                activity.runOnUiThread(new Runnable() { @Override public void run() { if (dialog[0].isShowing()) dialog[0].dismiss(); } });
            }
        });
        try { dialog[0].show(); } catch (RuntimeException unavailable) { request.cancel(); return; }
        dialog[0].getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { request.cancel(); }
        });
        dialog[0].getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                JSONObject answers = new JSONObject();
                try { for (Ask ask : asks) answers.put(ask.question, ask.answer()); }
                catch (Exception invalid) { request.cancel(); return; }
                request.answer(answers);
                dialog[0].dismiss();
            }
        });
        refresh[0].run();
    }

    private static TextView text(Activity activity, String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setLineSpacing(3 * activity.getResources().getDisplayMetrics().density, 1f);
        return view;
    }
}
