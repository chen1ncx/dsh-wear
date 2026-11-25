package io.dsh.watch;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Three dots that light up in turn. Small, cheap to draw, and it reads better
 * than a spinner on a round watch screen.
 */
final class LoadingDots extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private final float gap;
    private int phase;
    private ValueAnimator animator;

    LoadingDots(Context context, int color, float radiusDp, float gapDp) {
        super(context);
        float density = context.getResources().getDisplayMetrics().density;
        radius = Math.max(1.4f, radiusDp * density);
        gap = gapDp * density;
        paint.setColor(color);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (visibility == View.VISIBLE) start(); else stop();
    }

    void start() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofInt(0, 2);
        animator.setDuration(900);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                phase = (int) animation.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    void stop() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = (int) (radius * 6 + gap * 2);
        int height = (int) (radius * 2);
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float y = getHeight() / 2f;
        float startX = (getWidth() - (radius * 6 + gap * 2)) / 2f + radius;
        for (int i = 0; i < 3; i += 1) {
            float alpha = i == phase ? 1f : (i == (phase + 2) % 3 ? 0.35f : 0.6f);
            paint.setAlpha((int) (255 * alpha));
            canvas.drawCircle(startX + i * (radius * 2 + gap), y, radius, paint);
        }
    }
}
