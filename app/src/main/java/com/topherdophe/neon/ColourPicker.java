package com.topherdophe.neon;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.content.res.ColorStateList;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Highlight colour picker pop-up. One tone shows one picker; gradient shows two (start and end).
 * Each picker has a colour slider (the rainbow) and a white slider, with a live preview at the top.
 */
final class ColourPicker {
    interface Done { void pick(int color1, int color2); }

    private static final int[] RAINBOW = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
            0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};

    private ColourPicker() { }

    static void show(MainActivity app, boolean gradient, int start1, int start2, Done onDone, Runnable onCancel) {
        Ui ui = app.ui;
        Ui.Popup popup = ui.popup(gradient ? "Gradient" : "One tone");
        int[] colors = {start1, gradient ? start2 : start1};

        TextView preview = ui.text("Neon Controller", 22, true);
        preview.setPadding(0, ui.dp(4), 0, ui.dp(8));
        popup.list.addView(preview, Ui.matchWrap());
        Runnable refresh = () -> ui.tint(preview, colors[0], gradient ? colors[1] : colors[0]);
        refresh.run();

        addPicker(ui, popup.list, gradient ? "Start colour" : null, colors[0], c -> {
            colors[0] = c;
            if (!gradient) colors[1] = c;
            refresh.run();
        });
        if (gradient) addPicker(ui, popup.list, "End colour", colors[1], c -> { colors[1] = c; refresh.run(); });

        boolean[] saved = {false};
        LinearLayout buttons = new LinearLayout(app);
        buttons.setGravity(android.view.Gravity.CENTER);
        buttons.addView(ui.action("CANCEL", 17, popup::dismiss));
        buttons.addView(ui.action("DONE", 17, () -> {
            saved[0] = true;
            popup.dismiss();
            onDone.pick(colors[0], colors[1]);
        }));
        popup.list.addView(buttons, Ui.matchWrap());
        popup.dialog.setOnDismissListener(d -> { if (!saved[0]) onCancel.run(); });
        popup.show();
    }

    private interface Change { void set(int color); }

    private static void addPicker(Ui ui, LinearLayout list, String label, int start, Change onChange) {
        if (label != null) list.addView(ui.label(label), Ui.matchWrap());
        float[] hsv = new float[3];
        Color.colorToHSV(start, hsv);
        hsv[2] = 1f; // full brightness: dark colours vanish on black

        GradientDrawable whiteTrack = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.HSVToColor(new float[]{hsv[0], 1f, 1f}), Color.WHITE});
        Runnable update = () -> {
            whiteTrack.setColors(new int[]{Color.HSVToColor(new float[]{hsv[0], 1f, 1f}), Color.WHITE});
            onChange.set(Color.HSVToColor(hsv));
        };

        SeekBar hue = slider(ui, new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, RAINBOW),
                Math.round(hsv[0]), 359, "Colour");
        hue.setOnSeekBarChangeListener(new Ui.SeekAdapter() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                if (!fromUser) return;
                hsv[0] = v;
                update.run();
            }
        });
        list.addView(hue, Ui.matchWrap());

        // Left = full colour, right = white.
        SeekBar white = slider(ui, whiteTrack, Math.round((1f - hsv[1]) * 100), 100, "Whiteness");
        white.setOnSeekBarChangeListener(new Ui.SeekAdapter() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                if (!fromUser) return;
                hsv[1] = 1f - v / 100f;
                update.run();
            }
        });
        list.addView(white, Ui.matchWrap());
    }

    /** A slider whose track is the given strip of colour. */
    private static SeekBar slider(Ui ui, GradientDrawable track, int value, int max, String description) {
        SeekBar s = new SeekBar(ui.ctx);
        track.setCornerRadius(ui.dp(5));
        int side = ui.dp(16);
        s.setPadding(side, 0, side, 0);
        s.setMinimumHeight(ui.dp(44));
        s.setBackground(new InsetDrawable(track, side, ui.dp(17), side, ui.dp(17)));
        s.setProgressDrawable(new ColorDrawable(Color.TRANSPARENT));
        s.setThumbTintList(ColorStateList.valueOf(Color.WHITE));
        s.setMax(max);
        s.setProgress(value);
        s.setContentDescription(description);
        return s;
    }
}
