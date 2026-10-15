package com.kooo.evdashboard;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Design tokens and view factories for the car UI. Sizes are given in design pixels for the
 * 2560x1600 @160dpi head unit and scaled to the actual window width. Colors follow the system
 * day/night mode.
 */
final class Ui {
    enum Tone {NEUTRAL, ACCENT, OK, WARN, DANGER}
    enum Look {PRIMARY, TINT, DANGER, WARN}

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    final Context context;
    final boolean night, compact;
    final int bg, surface, surface2, line, ink, muted, accent, onAccent, accentTint;
    final int warn, warnBg, warnLine, danger, dangerBg, dangerLine, ok, okBg, segment;
    private final float unit;

    Ui(Context context, Configuration c) {
        this.context = context;
        night = (c.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        compact = c.screenWidthDp < 1200;
        unit = Math.min(1.25f, Math.max(0.45f, c.screenWidthDp / 2560f)) * context.getResources().getDisplayMetrics().density;
        if (night) {
            bg = 0xFF0E1217; surface = 0xFF171D25; surface2 = 0xFF1F2731; line = 0xFF2B3541;
            ink = 0xFFE8EDF3; muted = 0xFF8C98A8; accent = 0xFF5C8DFF; onAccent = 0xFF0B1020; accentTint = 0xFF1D2A45;
            warn = 0xFFFFB454; warnBg = 0xFF2B2212; warnLine = 0xFF5A4520;
            danger = 0xFFFF6B5E; dangerBg = 0xFF2E1816; dangerLine = 0xFF6A2E29;
            ok = 0xFF3DD6A0; okBg = 0xFF11302A; segment = 0xFF1B222B;
        } else {
            bg = 0xFFEDF0F4; surface = 0xFFFFFFFF; surface2 = 0xFFF3F5F8; line = 0xFFE3E8EE;
            ink = 0xFF141A22; muted = 0xFF667385; accent = 0xFF2D6BFF; onAccent = 0xFFFFFFFF; accentTint = 0xFFEAF0FF;
            warn = 0xFFB8650A; warnBg = 0xFFFFF3E0; warnLine = 0xFFF2D2A2;
            danger = 0xFFD6342B; dangerBg = 0xFFFDECEA; dangerLine = 0xFFF3B9B4;
            ok = 0xFF13A06F; okBg = 0xFFE6F6F0; segment = 0xFFDDE3EB;
        }
    }

    int px(float v) {return Math.round(v * unit);}

    int mix(int a, int b, float t) {
        return Color.argb(255, Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t), Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    int toneColor(Tone t) {return switch (t) {case ACCENT -> accent; case OK -> ok; case WARN -> warn; case DANGER -> danger; default -> muted;};}
    private int toneBg(Tone t) {return switch (t) {case ACCENT -> accentTint; case OK -> okBg; case WARN -> warnBg; case DANGER -> dangerBg; default -> surface2;};}
    private int toneLine(Tone t) {return switch (t) {case ACCENT -> mix(accentTint, accent, .3f); case OK -> mix(okBg, ok, .35f); case WARN -> warnLine; case DANGER -> dangerLine; default -> line;};}

    // ---- primitives ----

    LinearLayout vertical() {LinearLayout l = new LinearLayout(context); l.setOrientation(LinearLayout.VERTICAL); return l;}
    LinearLayout horizontal() {LinearLayout l = new LinearLayout(context); l.setOrientation(LinearLayout.HORIZONTAL); l.setGravity(Gravity.CENTER_VERTICAL); return l;}

    LinearLayout.LayoutParams lp(int w, int h) {return new LinearLayout.LayoutParams(w, h);}
    LinearLayout.LayoutParams lp(int w, int h, float weight) {return new LinearLayout.LayoutParams(w, h, weight);}
    LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h); p.setMargins(l, t, r, b); return p;}

    GradientDrawable rect(int fill, float radius, int stroke, float strokeWidth) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(px(radius));
        if (stroke != 0) d.setStroke(Math.max(1, px(strokeWidth)), stroke);
        return d;
    }

    /** Fill with pressed shade and a visible focus ring so TAB / ENTER navigation stays usable. */
    Drawable interactive(int fill, float radius, int stroke) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rect(mix(fill == 0 ? surface : fill, ink, .12f), radius, stroke, 2));
        s.addState(new int[]{android.R.attr.state_focused}, rect(fill, radius, accent, 4));
        s.addState(new int[]{}, rect(fill, radius, stroke, 2));
        return s;
    }

    TextView text(CharSequence s, float size, int color, boolean bold) {
        TextView t = new TextView(context);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setLineSpacing(0, 1.18f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    TextView mono(CharSequence s, float size, int color) {
        TextView t = text(s, size, color, false);
        t.setTypeface(Typeface.MONOSPACE);
        return t;
    }

    ImageView icon(int res, int tint, float size) {
        ImageView v = new ImageView(context);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setLayoutParams(lp(px(size), px(size)));
        return v;
    }

    // ---- components ----

    TextView chip(String label, Tone tone) {
        TextView t = text(label, 24, toneColor(tone), true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine();
        t.setPadding(px(22), 0, px(22), 0);
        t.setBackground(rect(tone == Tone.NEUTRAL ? 0 : toneBg(tone), 100, toneLine(tone), 2));
        t.setLayoutParams(lp(WRAP, px(52)));
        return t;
    }

    void setChip(TextView chip, String label, Tone tone) {
        chip.setText(label);
        chip.setTextColor(toneColor(tone));
        chip.setBackground(rect(tone == Tone.NEUTRAL ? 0 : toneBg(tone), 100, toneLine(tone), 2));
    }

    TextView callout(String lead, String body, Tone tone) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (!lead.isEmpty()) {
            b.append(lead);
            b.setSpan(new StyleSpan(Typeface.BOLD), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            b.setSpan(new ForegroundColorSpan(toneColor(tone)), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        b.append(body);
        TextView t = text(b, 26, ink, false);
        t.setPadding(px(26), px(20), px(26), px(20));
        t.setBackground(rect(toneBg(tone), 24, toneLine(tone), 2));
        return t;
    }

    android.widget.Button button(String label, Look look, View.OnClickListener click) {
        android.widget.Button b = new android.widget.Button(context);
        int fill, color, stroke;
        switch (look) {
            case PRIMARY -> {fill = accent; color = onAccent; stroke = 0;}
            case DANGER -> {fill = dangerBg; color = danger; stroke = dangerLine;}
            case WARN -> {fill = warnBg; color = warn; stroke = warnLine;}
            default -> {fill = accentTint; color = accent; stroke = 0;}
        }
        b.setText(label);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30));
        b.setTextColor(color);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(px(34), 0, px(34), 0);
        b.setBackground(interactive(fill, 30, stroke));
        b.setOnClickListener(click);
        b.setLayoutParams(lp(WRAP, px(look == Look.PRIMARY ? 108 : 96)));
        return b;
    }

    /** Rounded container whose children are separated by hairlines. */
    LinearLayout group() {
        LinearLayout g = vertical();
        g.setBackground(rect(surface2, 30, 0, 0));
        return g;
    }

    void divide(LinearLayout group) {
        if (group.getChildCount() == 0) return;
        View d = new View(context);
        d.setBackgroundColor(line);
        group.addView(d, lp(MATCH, Math.max(1, px(2)), px(32), 0, 0, 0));
    }

    void addRow(LinearLayout group, View row) {
        divide(group);
        group.addView(row, lp(MATCH, WRAP));
    }

    private LinearLayout rowBase() {
        LinearLayout r = horizontal();
        r.setMinimumHeight(px(100));
        r.setPadding(px(32), px(14), px(32), px(14));
        r.setClickable(true);
        r.setFocusable(true);
        r.setBackground(interactive(0, 0, 0));
        return r;
    }

    /** A tappable row with a trailing chevron. */
    LinearLayout actionRow(String label, Tone tone, View.OnClickListener click) {
        LinearLayout r = rowBase();
        int color = tone == Tone.NEUTRAL ? ink : toneColor(tone);
        r.addView(text(label, 30, color, true), lp(0, WRAP, 1));
        r.addView(text("›", 44, muted, false));
        r.setOnClickListener(click);
        return r;
    }

    LinearLayout infoRow(View content) {
        LinearLayout r = horizontal();
        r.setPadding(px(32), px(18), px(32), px(18));
        r.addView(content, lp(0, WRAP, 1));
        return r;
    }

    static final class SwitchRow {LinearLayout view; Toggle toggle; TextView hint;}

    SwitchRow switchRow(String label, String hint, boolean on) {
        SwitchRow s = new SwitchRow();
        LinearLayout r = horizontal();
        r.setMinimumHeight(px(108));
        r.setPadding(px(32), px(12), px(32), px(12));
        LinearLayout t = vertical();
        t.addView(text(label, 30, ink, true));
        s.hint = text(hint, 24, muted, false);
        s.hint.setVisibility(hint.isEmpty() ? View.GONE : View.VISIBLE);
        t.addView(s.hint);
        r.addView(t, lp(0, WRAP, 1));
        s.toggle = new Toggle(this, false);
        s.toggle.setChecked(on);
        r.addView(s.toggle, lp(WRAP, WRAP, px(24), 0, 0, 0));
        r.setClickable(true);
        r.setOnClickListener(v -> s.toggle.toggle());
        s.view = r;
        return s;
    }

    /** Checkbox row for confirmations; the whole row is tappable. */
    Toggle checkRow(ViewGroup parent, String label, boolean on) {
        LinearLayout r = horizontal();
        r.setPadding(0, px(10), 0, px(10));
        Toggle t = new Toggle(this, true);
        t.setChecked(on);
        r.addView(t, lp(WRAP, WRAP, 0, 0, px(24), 0));
        r.addView(text(label, 28, ink, false), lp(0, WRAP, 1));
        r.setClickable(true);
        r.setOnClickListener(v -> t.toggle());
        parent.addView(r, lp(MATCH, WRAP));
        return t;
    }

    /** Labelled outlined input; the box shows a focus ring while editing. */
    EditText field(ViewGroup parent, String label, String initial, int inputType, String placeholder, String unitLabel, float weight) {
        LinearLayout box = horizontal();
        box.setPadding(px(28), 0, px(28), 0);
        box.setBackground(rect(surface, 24, line, 2));
        EditText e = new EditText(context);
        e.setBackground(null);
        e.setPadding(0, 0, 0, 0);
        e.setSingleLine();
        e.setInputType(inputType);
        e.setText(initial);
        e.setHint(placeholder);
        e.setHintTextColor(mix(muted, surface, .35f));
        e.setTextColor(ink);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32));
        if ((inputType & InputType.TYPE_CLASS_NUMBER) != 0) e.setTypeface(Typeface.MONOSPACE);
        e.setOnFocusChangeListener((v, focused) -> box.setBackground(focused ? rect(surface, 24, accent, 4) : rect(surface, 24, line, 2)));
        box.addView(e, lp(0, MATCH, 1));
        if (!unitLabel.isEmpty()) box.addView(text(unitLabel, 26, muted, false));
        LinearLayout holder = vertical();
        holder.setPadding(px(32), px(14), px(32), px(20));
        holder.addView(text(label, 24, muted, false), lp(WRAP, WRAP, 0, 0, 0, px(8)));
        holder.addView(box, lp(MATCH, px(96)));
        parent.addView(holder, weight > 0 ? lp(0, WRAP, weight) : lp(MATCH, WRAP));
        return e;
    }

    /** Round radio marker used by the image list. */
    View radio(boolean selected) {
        View v = new View(context);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(selected ? accent : 0);
        d.setStroke(px(4), selected ? accent : muted);
        v.setBackground(d);
        v.setLayoutParams(lp(px(40), px(40)));
        return v;
    }

    /** Switch (default) or square checkbox with its own drawing, focus ring and CompoundButton behaviour. */
    @android.annotation.SuppressLint("ViewConstructor")
    static final class Toggle extends CompoundButton {
        private final Ui ui;
        private final boolean box;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick = new Path();
        private final RectF bounds = new RectF();

        Toggle(Ui ui, boolean box) {
            super(ui.context);
            this.ui = ui;
            this.box = box;
            setFocusable(true);
            setClickable(true);
        }

        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(box ? ui.px(56) : ui.px(104), box ? ui.px(56) : ui.px(60));
        }

        @Override protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            RectF r = bounds;
            r.set(0, 0, w, h);
            if (box) {
                float radius = ui.px(14), stroke = ui.px(4);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(isChecked() ? ui.accent : 0);
                c.drawRoundRect(r, radius, radius, paint);
                if (!isChecked()) {
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(stroke);
                    paint.setColor(ui.muted);
                    r.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2);
                    c.drawRoundRect(r, radius, radius, paint);
                } else {
                    tick.reset();
                    tick.moveTo(w * .24f, h * .52f);
                    tick.lineTo(w * .44f, h * .72f);
                    tick.lineTo(w * .78f, h * .30f);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(ui.px(6));
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeJoin(Paint.Join.ROUND);
                    paint.setColor(ui.onAccent);
                    c.drawPath(tick, paint);
                }
            } else {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(isChecked() ? ui.accent : ui.night ? 0xFF3A4654 : 0xFFCBD3DD);
                c.drawRoundRect(r, h / 2f, h / 2f, paint);
                float pad = ui.px(6), d = h - 2 * pad;
                paint.setColor(Color.WHITE);
                float cx = isChecked() ? w - pad - d / 2 : pad + d / 2;
                c.drawCircle(cx, h / 2f, d / 2, paint);
            }
            if (isFocused()) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.px(4));
                paint.setColor(ui.accent);
                float inset = -ui.px(5), ring = box ? ui.px(18) : h / 2f + ui.px(5);
                r.set(inset, inset, w - inset, h - inset);
                c.drawRoundRect(r, ring, ring, paint);
            }
        }
    }
}
