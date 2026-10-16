package com.cabletv.player.ui;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * A full-screen "working on it" panel shown while the channel list is being fetched.
 *
 * <p>It exists because the alternative is a black screen. A television that shows nothing for a
 * few seconds is indistinguishable from one that has crashed, and the only reasonable thing a
 * viewer can do about it is pull the plug. Motion is the signal that something is happening even
 * when there is nothing to show yet, so the panel carries an indeterminate spinner and a line of
 * text rather than a static image.
 *
 * <p>It is only ever used when there is genuinely nothing to watch: once the cached playlist is
 * up, or the first channel starts, it goes away and does not come back.
 */
public class LoadingOverlay extends FrameLayout {

    private final ProgressBar mSpinner;
    private final TextView mTitle;
    private final TextView mDetail;
    /** Fades the whole panel, so a slow load does not end with a hard cut to the picture. */
    private ObjectAnimator mFade;

    public LoadingOverlay(Context context) {
        super(context);
        setBackgroundColor(Color.parseColor("#0D1117"));
        setClickable(true);
        setFocusable(false);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);

        mSpinner = new ProgressBar(context);
        LinearLayout.LayoutParams spinnerParams =
                new LinearLayout.LayoutParams(dp2px(52), dp2px(52));
        mSpinner.setLayoutParams(spinnerParams);
        mSpinner.setIndeterminateTintList(
                ColorStateList.valueOf(Color.parseColor("#2EA8FF")));
        column.addView(mSpinner);

        mTitle = new TextView(context);
        mTitle.setTextColor(Color.WHITE);
        mTitle.setTextSize(19);
        mTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp2px(22);
        mTitle.setLayoutParams(titleParams);
        column.addView(mTitle);

        mDetail = new TextView(context);
        mDetail.setTextColor(Color.parseColor("#9AA7B4"));
        mDetail.setTextSize(14);
        mDetail.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp2px(10);
        mDetail.setLayoutParams(detailParams);
        column.addView(mDetail);

        addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        setVisibility(GONE);
    }

    /** Centred, full-screen layout params for adding this to the video container. */
    public FrameLayout.LayoutParams createLayoutParams() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    /**
     * Shows the panel.
     *
     * @param title  what is happening, in the viewer's terms
     * @param detail a second line, or null to leave it out
     */
    public void show(CharSequence title, CharSequence detail) {
        mTitle.setText(title);
        if (detail == null || detail.length() == 0) {
            mDetail.setVisibility(GONE);
        } else {
            mDetail.setVisibility(VISIBLE);
            mDetail.setText(detail);
        }
        cancelFade();
        setAlpha(1f);
        setVisibility(VISIBLE);
        bringToFront();
    }

    /** Updates the second line without disturbing the animation; a no-op while hidden. */
    public void setDetail(CharSequence detail) {
        if (getVisibility() != VISIBLE) {
            return;
        }
        if (detail == null || detail.length() == 0) {
            mDetail.setVisibility(GONE);
        } else {
            mDetail.setVisibility(VISIBLE);
            mDetail.setText(detail);
        }
    }

    /** Fades out and hides. The spinner keeps turning until it is gone, so it never looks stuck. */
    public void hide() {
        if (getVisibility() != VISIBLE) {
            return;
        }
        cancelFade();
        mFade = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0f);
        mFade.setDuration(220);
        mFade.setInterpolator(new LinearInterpolator());
        mFade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                setVisibility(GONE);
                setAlpha(1f);
                mFade = null;
            }
        });
        mFade.start();
    }

    private void cancelFade() {
        if (mFade != null) {
            mFade.cancel();
            mFade = null;
        }
    }

    private int dp2px(int dp) {
        return Math.round(getResources().getDisplayMetrics().density * dp);
    }

    /** Unused drawing hook kept for the panel's own background; the layout does the rest. */
    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
    }

    /** Rounded rectangle helper used by the panel background. */
    static void roundRect(Canvas canvas, Paint paint, float left, float top, float right,
                          float bottom, float radius) {
        canvas.drawRoundRect(new RectF(left, top, right, bottom), radius, radius, paint);
    }
}
