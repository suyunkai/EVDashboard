package com.kooo.evdashboard;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.function.IntConsumer;

/** Card-style dialog built from {@link Ui} tokens; replaces AlertDialog so text and buttons are legible on the head unit. */
final class UiDialog {
    private final Activity activity;
    private final Ui ui;
    private final Dialog dialog;
    private final LinearLayout card, body, buttons;
    private final TextView title;
    private boolean compact;
    private int maxWidth = 1500;
    private Button positive;
    private Runnable onCancel;

    UiDialog(Activity activity, Ui ui, String title) {
        this.activity = activity;
        this.ui = ui;
        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        card = ui.vertical();
        card.setBackground(ui.rect(ui.surface, 44, ui.line, 2));
        card.setPadding(ui.px(48), ui.px(40), ui.px(48), ui.px(36));
        TextView t = ui.text(title, 40, ui.ink, true);
        this.title = t;
        card.addView(t, ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(16)));
        body = ui.vertical();
        MaxScroll scroll = new MaxScroll(activity, (int) (activity.getResources().getDisplayMetrics().heightPixels * .62f));
        scroll.addView(body);
        card.addView(scroll, ui.lp(Ui.MATCH, Ui.WRAP));
        buttons = ui.horizontal();
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        card.addView(buttons, ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(28), 0, 0));
        dialog.setContentView(card);
        dialog.setOnCancelListener(d -> {if (onCancel != null) onCancel.run();});
    }

    UiDialog message(CharSequence text) {
        body.addView(ui.text(text, 30, ui.ink, false), ui.lp(Ui.MATCH, Ui.WRAP, 0, 0, 0, ui.px(8)));
        return this;
    }

    UiDialog heading(String text, int color) {
        body.addView(ui.text(text, 34, color, true), ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(8), 0, ui.px(6)));
        return this;
    }

    UiDialog view(View v) {
        body.addView(v, ui.lp(Ui.MATCH, Ui.WRAP));
        return this;
    }

    UiDialog items(String[] labels, IntConsumer pick) {
        LinearLayout list = ui.group();
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            ui.addRow(list, ui.actionRow(labels[i], Ui.Tone.NEUTRAL, v -> {dialog.dismiss(); pick.accept(index);}));
        }
        return view(list);
    }

    UiDialog onCancel(Runnable r) {onCancel = r; return this;}
    UiDialog onDismiss(Runnable r) {dialog.setOnDismissListener(d -> r.run()); return this;}
    UiDialog compact(int width) {
        compact = true; maxWidth = width;
        card.setPadding(ui.px(36), ui.px(28), ui.px(36), ui.px(28));
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.px(36));
        buttons.setLayoutParams(ui.lp(Ui.MATCH, Ui.WRAP, 0, ui.px(18), 0, 0));
        return this;
    }
    void dismiss() {dialog.dismiss();}
    boolean isShowing() {return dialog.isShowing();}

    private Button add(String label, Ui.Look look, Runnable action) {
        Button b = ui.button(label, look, v -> {dialog.dismiss(); if (action != null) action.run();});
        if (compact) {b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, ui.px(28)); b.setPadding(ui.px(24), 0, ui.px(24), 0);}
        int height = compact ? Math.max(ui.px(88), Math.round(48 * activity.getResources().getDisplayMetrics().density)) : ui.px(look == Ui.Look.PRIMARY ? 108 : 96);
        buttons.addView(b, ui.lp(Ui.WRAP, height, ui.px(compact ? 12 : 16), 0, 0, 0));
        return b;
    }

    UiDialog negative(String label, Runnable action) {add(label, Ui.Look.TINT, action); return this;}
    UiDialog neutral(String label, Runnable action) {add(label, Ui.Look.TINT, action); return this;}

    UiDialog positive(String label, Ui.Look look, Runnable action) {
        positive = add(label, look, action);
        return this;
    }

    void positiveEnabled(boolean on) {
        if (positive == null) return;
        positive.setEnabled(on);
        positive.setAlpha(on ? 1f : .4f);
    }

    UiDialog show() {
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            DisplayMetrics m = activity.getResources().getDisplayMetrics();
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(Math.min(ui.px(maxWidth), (int) (m.widthPixels * .92f)), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setDimAmount(.55f);
        }
        return this;
    }

    private static final class MaxScroll extends ScrollView {
        private final int max;

        MaxScroll(Activity a, int max) {super(a); this.max = max;}

        @Override protected void onMeasure(int w, int h) {
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
        }
    }
}
