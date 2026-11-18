package com.topherdophe.neon;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * View factory shared by every screen. Text is sized in sp so it follows the phone's font size;
 * symbol-only buttons (▶, ⚙ and friends) and the big clock are sized in dp so large system fonts
 * can't push them off the screen.
 */
final class Ui {
    static final float OFF = .35f;

    interface Toggle { void set(boolean on); }
    interface IntChoice { void pick(int value); }
    interface Labeler { String label(int value); }

    abstract static class SeekAdapter implements SeekBar.OnSeekBarChangeListener {
        @Override public void onProgressChanged(SeekBar sb, int value, boolean fromUser) { }
        @Override public void onStartTrackingTouch(SeekBar sb) { }
        @Override public void onStopTrackingTouch(SeekBar sb) { }
    }

    final Context ctx;
    private final AppSettings settings;

    Ui(Context ctx, AppSettings settings) {
        this.ctx = ctx;
        this.settings = settings;
    }

    int accent() { return settings.accent(); }
    int accent2() { return settings.accent2(); }

    int dp(float d) { return Math.round(d * ctx.getResources().getDisplayMetrics().density); }
    float dpf(float d) { return d * ctx.getResources().getDisplayMetrics().density; }
    static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }

    static LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }

    LinearLayout vertical() {
        LinearLayout l = new LinearLayout(ctx);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        return l;
    }

    TextView text(String s, int sizeSp, boolean bold) {
        TextView v = new TextView(ctx);
        v.setText(s);
        v.setTextSize(sizeSp);
        v.setGravity(Gravity.CENTER);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tint(v, accent(), accent2());
        return v;
    }

    /** Sets a size in dp: it ignores the system font scale. Only for symbols and the clock. */
    static void fixedSize(TextView v, float sizeDp) { v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeDp); }

    /**
     * Colours a TextView with a solid colour, or a left-to-right gradient across the text itself.
     * The colours live in the view's tag so re-tinting replaces them instead of stacking listeners.
     */
    void tint(TextView v, int c1, int c2) {
        boolean first = v.getTag() == null;
        v.setTag(new int[]{c1, c2});
        v.setTextColor(c1);
        if (first) {
            v.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or2, ob) -> applyShader(v));
            v.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
                @Override public void afterTextChanged(Editable s) { v.post(() -> applyShader(v)); }
            });
        }
        applyShader(v);
    }

    private static void applyShader(TextView v) {
        Object tag = v.getTag();
        if (!(tag instanceof int[])) return;
        int[] c = (int[]) tag;
        if (c[0] == c[1]) {
            if (v.getPaint().getShader() != null) { v.getPaint().setShader(null); v.invalidate(); }
            return;
        }
        int inner = v.getWidth() - v.getTotalPaddingLeft() - v.getTotalPaddingRight();
        if (inner <= 0) return;
        float textWidth = 0;
        for (String line : v.getText().toString().split("\n")) textWidth = Math.max(textWidth, v.getPaint().measureText(line));
        textWidth = Math.max(1, Math.min(textWidth, inner));
        boolean centred = (v.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL;
        float start = v.getTotalPaddingLeft() + (centred ? (inner - textWidth) / 2f : 0);
        v.getPaint().setShader(new LinearGradient(start, 0, start + textWidth, 0, c[0], c[1], Shader.TileMode.CLAMP));
        v.invalidate();
    }

    /** Lets screen-reader users jump between headings (Android 9+). */
    static void markHeading(View v) {
        if (Build.VERSION.SDK_INT >= 28) v.setAccessibilityHeading(true);
    }

    /** What a screen reader says for a control's current value (Android 11+). */
    static void describeState(View v, String state) {
        if (Build.VERSION.SDK_INT >= 30) v.setStateDescription(state);
    }

    static TextView oneLine(TextView v) {
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    private RippleDrawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(accent(), 0x40)), null, new ColorDrawable(Color.WHITE));
    }

    /** A tappable label with a ripple. A null action means the caller sets its own listener. */
    TextView action(String s, int sizeSp, Runnable r) {
        TextView v = text(s, sizeSp, false);
        v.setPadding(dp(12), dp(12), dp(12), dp(12));
        v.setMinHeight(dp(48));
        v.setBackground(ripple());
        if (r != null) v.setOnClickListener(x -> r.run());
        return v;
    }

    /** A symbol-only button: fixed size, always with a spoken label for screen readers. */
    TextView glyph(String symbol, float sizeDp, String description, Runnable r) {
        TextView v = action(symbol, 16, r);
        fixedSize(v, sizeDp);
        v.setContentDescription(description);
        v.setMinWidth(dp(48));
        return v;
    }

    /** Top-bar button. */
    TextView icon(String symbol, String description, Runnable r) {
        TextView v = glyph(symbol, 26, description, r);
        v.setMinWidth(dp(44));
        v.setPadding(dp(8), dp(8), dp(8), dp(8));
        return v;
    }

    /**
     * A list entry: a title with a smaller, dimmed subtitle underneath, and an optional "+" (add to queue)
     * button on the right. A null onTap leaves the row's click handling to the caller.
     */
    LinearLayout listRow(String titleText, String sub, int titleSize, Runnable onTap, Runnable onQueue) {
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ripple());
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(12), dp(8), dp(4), dp(8));
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = oneLine(text(titleText, titleSize, false));
        t.setGravity(Gravity.START);
        texts.addView(t, matchWrap());
        boolean hasSub = sub != null && !sub.isEmpty();
        if (hasSub) {
            TextView s = oneLine(text(sub, Math.max(12, titleSize - 4), false));
            s.setGravity(Gravity.START);
            s.setAlpha(.6f);
            texts.addView(s, matchWrap());
        }
        // Read the row as one item ("Song, Artist") instead of two separate texts.
        row.setContentDescription(hasSub ? titleText + ", " + sub : titleText);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        if (onQueue != null) row.addView(glyph("+", 26, "Add " + titleText + " to queue", onQueue));
        if (onTap != null) row.setOnClickListener(v -> onTap.run());
        return row;
    }

    EditText input(String hint) {
        EditText e = new EditText(ctx);
        e.setSingleLine(true);
        e.setTextColor(accent());
        e.setHintTextColor(withAlpha(accent(), 0x80));
        e.setHint(hint);
        e.setBackgroundTintList(ColorStateList.valueOf(accent()));
        return e;
    }

    SeekBar seekBar() {
        SeekBar s = new SeekBar(ctx);
        s.setProgressTintList(ColorStateList.valueOf(accent()));
        s.setThumbTintList(ColorStateList.valueOf(accent2()));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(withAlpha(accent(), 0x40)));
        s.setMinimumHeight(dp(40));
        return s;
    }

    TextView heading(String s) {
        TextView h = oneLine(text(s, 22, true));
        h.setPadding(0, dp(4), 0, dp(12));
        markHeading(h);
        return h;
    }

    void section(LinearLayout list, String s) {
        TextView h = text(s.toUpperCase(Locale.ROOT), 13, true);
        h.setAlpha(.6f);
        h.setPadding(0, dp(24), 0, dp(4));
        markHeading(h);
        list.addView(h, matchWrap());
    }

    /** Adds a setting followed by a thin divider in a colour that complements the highlight colour. */
    void setting(LinearLayout list, View v) {
        list.addView(v, matchWrap());
        View line = new View(ctx);
        // Hairline that fades out at both ends.
        int c = complement();
        line.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{withAlpha(c, 0), withAlpha(c, 0xB0), withAlpha(c, 0)}));
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1)));
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(6);
        list.addView(line, lp);
    }

    /** Gradients use their second colour; solid colours use a neighbouring hue; white uses grey. */
    int complement() {
        if (accent() != accent2()) return accent2();
        float[] hsv = new float[3];
        Color.colorToHSV(accent(), hsv);
        if (hsv[1] < 0.1f) return 0xFF777777;
        hsv[0] = (hsv[0] + 50f) % 360f;
        return Color.HSVToColor(hsv);
    }

    TextView label(String s) {
        TextView l = text(s, 14, false);
        l.setAlpha(.8f);
        l.setPadding(0, dp(10), 0, 0);
        return l;
    }

    /**
     * A horizontally scrolling row of options; the selected one is bright, the rest faded.
     * Screen readers hear "name, group: option, selected".
     */
    HorizontalScrollView choices(String group, String[] options, int selected, IntChoice onPick) {
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setFillViewport(true);
        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER);
        for (int i = 0; i < options.length; i++) {
            int index = i;
            TextView chip = action(options[i], 15, null);
            chip.setPadding(dp(10), dp(10), dp(10), dp(10));
            chip.setContentDescription(group + ": " + options[i]);
            mark(chip, i == selected);
            chip.setOnClickListener(v -> {
                for (int j = 0; j < row.getChildCount(); j++) mark(row.getChildAt(j), j == index);
                onPick.pick(index);
            });
            row.addView(chip);
        }
        hs.addView(row, new FrameLayout.LayoutParams(-1, -2));
        return hs;
    }

    private static void mark(View chip, boolean on) {
        chip.setAlpha(on ? 1f : OFF);
        chip.setSelected(on);
    }

    TextView switchRow(String label, boolean initial, Toggle onChange) {
        TextView v = action("", 16, null);
        boolean[] state = {initial};
        Runnable show = () -> {
            v.setText(label + ":  " + (state[0] ? "ON" : "OFF"));
            v.setAlpha(state[0] ? 1f : .6f);
            v.setContentDescription(label + ", " + (state[0] ? "on" : "off"));
        };
        show.run();
        v.setOnClickListener(x -> {
            state[0] = !state[0];
            show.run();
            onChange.set(state[0]);
        });
        return v;
    }

    LinearLayout slider(String name, int min, int value, Labeler fmt, IntChoice onMove, Runnable onDone) {
        LinearLayout box = vertical();
        box.setPadding(0, dp(10), 0, dp(4));
        TextView l = text(name + ": " + fmt.label(value), 14, false);
        l.setAlpha(.8f);
        box.addView(l, matchWrap());
        SeekBar s = seekBar();
        s.setMax(100 - min);
        s.setProgress(value - min);
        s.setContentDescription(name);
        describeState(s, fmt.label(value));
        s.setOnSeekBarChangeListener(new SeekAdapter() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int v = p + min;
                l.setText(name + ": " + fmt.label(v));
                describeState(sb, fmt.label(v));
                if (fromUser) onMove.pick(v);
            }
            @Override public void onStopTrackingTouch(SeekBar sb) { onDone.run(); }
        });
        box.addView(s, matchWrap());
        return box;
    }

    /** A vertical list inside a ScrollView that fills the remaining height of parent. */
    LinearLayout scrollList(ViewGroup parent) {
        ScrollView scroll = new ScrollView(ctx);
        LinearLayout list = vertical();
        scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
        parent.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        return list;
    }
}
