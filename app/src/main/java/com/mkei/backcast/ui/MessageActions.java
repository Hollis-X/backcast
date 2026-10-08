package com.mkei.backcast.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.mkei.backcast.R;

/** Message actions are always explicit user gestures. */
public final class MessageActions {
    private MessageActions() { }

    public static void install(final Context context, final TextView text, final Runnable retry) {
        text.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View view) {
                new AlertDialog.Builder(context).setItems(retry == null
                        ? new String[]{"复制"} : new String[]{"复制", "重试"}, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int item) {
                        if (item == 1 && retry != null) { retry.run(); return; }
                        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText("backcast", text.getText()));
                            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show();
                        }
                    }
                }).show();
                return true;
            }
        });
    }
}
