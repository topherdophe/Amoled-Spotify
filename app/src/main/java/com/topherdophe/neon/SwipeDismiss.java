package com.topherdophe.neon;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * Horizontal swipe removes a row; holding calls its long-click listener; a tap without movement calls
 * the row's click listener
 * (so screen readers, which click instead of swiping, get the same tap behaviour).
 * Vertical drags are left to the surrounding ScrollView.
 */
final class SwipeDismiss implements View.OnTouchListener {
    private final Runnable onDismiss;
    private final int slop;
    private final int longPress = ViewConfiguration.getLongPressTimeout();
    private float downX, downY;
    private boolean swiping, longPressed;
    private View current;
    private final Runnable longPressCheck = () -> {
        if (current != null && !swiping) {
            longPressed = true;
            current.setPressed(false);
            current.performLongClick();
        }
    };

    SwipeDismiss(Context context, Runnable onDismiss) {
        this.onDismiss = onDismiss;
        this.slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @SuppressLint("ClickableViewAccessibility") // performClick() is called for taps below
    @Override public boolean onTouch(View v, MotionEvent e) {
        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                swiping = false;
                longPressed = false;
                current = v;
                v.setPressed(true);
                v.postDelayed(longPressCheck, longPress);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) v.removeCallbacks(longPressCheck);
                if (longPressed) return true;
                if (!swiping && Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) {
                    swiping = true;
                    v.setPressed(false);
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (swiping) {
                    v.setTranslationX(dx);
                    v.setAlpha(Math.max(.15f, 1f - Math.abs(dx) / Math.max(1, v.getWidth())));
                } else if (Math.abs(dy) > slop) {
                    v.setPressed(false);
                }
                return true;
            case MotionEvent.ACTION_UP:
                v.removeCallbacks(longPressCheck);
                v.setPressed(false);
                if (longPressed) return true;
                if (swiping) {
                    if (Math.abs(dx) > v.getWidth() / 3f) {
                        v.animate().translationX(Math.signum(dx) * v.getWidth()).alpha(0f).setDuration(180)
                                .withEndAction(onDismiss).start();
                    } else {
                        v.animate().translationX(0).alpha(1f).setDuration(180).start();
                    }
                } else if (Math.abs(dx) < slop && Math.abs(dy) < slop) {
                    v.performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                v.removeCallbacks(longPressCheck);
                v.setPressed(false);
                v.animate().translationX(0).alpha(1f).setDuration(180).start();
                return true;
            default:
                return false;
        }
    }
}
